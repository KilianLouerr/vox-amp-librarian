package com.github.tmarsteel.voxamplibrarian

/*
This file is supposed to completely contain/abstract the web/w3c/browser part of doing midi,
so the rest of the app can stay agnostic and work on other APIs (android, java, ...), too.
 */

import com.github.tmarsteel.voxamplibrarian.logging.LoggerFactory
import com.github.tmarsteel.voxamplibrarian.protocol.MidiDevice
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Date
import kotlin.js.Promise

private class WebMidiPermissionDeniedException(override val cause: Throwable) : RuntimeException(cause)

private val logger = LoggerFactory["webmidi"]

private sealed class MidiState {
    object Initializing : MidiState()
    object NotSupported : MidiState()
    class PermissionDenied(val cause: Throwable) : MidiState()
    data class Available(
        val inputs: Map<String, MidiInput>,
        val outputs: Map<String, MidiOutput>,
    ) : MidiState() {
        val connectionStates: Map<String, Boolean> = (inputs.values + outputs.values).associate { it.id to (it.state == "connected") }
    }
}

private val midiState: Flow<MidiState> = flow {
    logger.trace("root webmidi flow is initializing")
    val dynamicNavigator = window.navigator.asDynamic()
    if (dynamicNavigator.requestMIDIAccess === undefined) {
        emit(MidiState.NotSupported)
        return@flow
    }

    val midiAccessPromise: Promise<MidiAccess> = dynamicNavigator.requestMIDIAccess(object : MidiOptions {
        override var sysex = true
    })
    val midiAccess = try {
        suspendCancellableCoroutine<MidiAccess> { cont ->
            midiAccessPromise.then(
                onFulfilled = { cont.resume(it) },
                onRejected = { cont.resumeWithException(WebMidiPermissionDeniedException(it)) }
            )
        }
    }
    catch (e: WebMidiPermissionDeniedException) {
        emit(MidiState.PermissionDenied(e.cause))
        return@flow
    }

    val stateChangeEvents: Channel<MidiConnectionEvent> = Channel(Channel.BUFFERED, onBufferOverflow = BufferOverflow.SUSPEND)
    midiAccess.addStateChangedListener { event ->
        GlobalScope.launch {
            stateChangeEvents.send(event)
        }
    }

    val initialState = MidiState.Available(midiAccess.inputs.toKotlinMap(), midiAccess.outputs.toKotlinMap())
    emit(initialState)
    stateChangeEvents.consumeAsFlow()
        .runningFold(MidiStateFilterCarry(initialState, true)) { filterState, event ->
            val currentState = MidiState.Available(midiAccess.inputs.toKotlinMap(), midiAccess.outputs.toKotlinMap())
            val previousPortConnectionState = filterState.previousState.connectionStates[event.port.id]
            val currentPortConnectionState = event.port.state == "connected"
            val actualChanges = previousPortConnectionState != currentPortConnectionState
            logger.trace("state change event for port ${event.port.id}: previous: $previousPortConnectionState, now: $currentPortConnectionState, changed = $actualChanges")
            MidiStateFilterCarry(currentState, actualChanges)
        }
        .filter { it.actualChanges }
        .map { it.previousState }
        .collect(this@flow::emit)
}.shareIn(GlobalScope, SharingStarted.Lazily, 1)

private class MidiStateFilterCarry(
    val previousState: MidiState.Available,
    val actualChanges: Boolean,
)

private val webMidiVoxAmpDevice: Flow<MidiDevice?> = midiState
    .runningFold<MidiState, WebMidiVoxVtxDevice?>(null) { currentDevice, currentMidiState ->
        when (currentMidiState) {
            is MidiState.Initializing -> return@runningFold null
            is MidiState.PermissionDenied,
            MidiState.NotSupported -> {
                currentDevice?.close()
                return@runningFold null
            }
            is MidiState.Available -> {
                if (currentDevice != null) {
                    if (!currentDevice.isConnected) {
                        currentDevice.close()
                        return@runningFold null
                    }

                    return@runningFold currentDevice
                }

                WebMidiVoxVtxDevice.tryBuildFrom(currentMidiState)
            }
        }
    }
    .shareIn(GlobalScope, SharingStarted.Lazily, 1)

private val directBleDevice = MutableStateFlow<MidiDevice?>(null)

val DIRECT_BLE_MIDI_AVAILABLE: Boolean
    get() = window.navigator.asDynamic().bluetooth !== undefined

val VOX_AMP_MIDI_DEVICE: Flow<MidiDevice?> = combine(directBleDevice, webMidiVoxAmpDevice) { bleDevice, midiDevice ->
    bleDevice ?: midiDevice
}.distinctUntilChanged().shareIn(GlobalScope, SharingStarted.Lazily, 1)

suspend fun requestDirectBleMidiConnection() {
    if (!DIRECT_BLE_MIDI_AVAILABLE) {
        logger.warn("Direct BLE requested, but Web Bluetooth is not available")
        return
    }

    (directBleDevice.value as? WebBluetoothBleMidiDevice)?.close()
    directBleDevice.value = null

    val bleDevice = WebBluetoothBleMidiDevice.requestAndConnect()
    directBleDevice.value = bleDevice
}

private class WebMidiVoxVtxDevice private constructor(val input: MidiInput, val output: MidiOutput) : MidiDevice {
    private val incomingSysExBuffer = mutableListOf<Byte>()

    override suspend fun sendSysExMessage(manufacturerId: Byte, writer: (BinaryOutput) -> Unit) {
        val binaryOutput = BufferedBinaryOutput()
        binaryOutput.write(0xf0.toByte())
        binaryOutput.write(manufacturerId)
        writer(binaryOutput)
        binaryOutput.write(0xf7.toByte())
        val rawData = binaryOutput.contentAsArrayOfUnsignedInts()
        logger.debug("> ${rawData.hex()}")
        output.send(rawData)
    }

    override lateinit var incomingSysExMessageHandler: (manufacturerId: Byte, payload: BinaryInput) -> Unit

    init {
        input.onmidimessage = onmidimessage@{ messageEvent ->
            val bytes = messageEvent.data.toByteArray()
            logger.debug("< ${bytes.hex()}")

            if (!this::incomingSysExMessageHandler.isInitialized) {
                console.warn("Dropping incoming message because no handler is registered.", messageEvent)
                return@onmidimessage
            }

            if (bytes.isEmpty()) {
                return@onmidimessage
            }

            for (byte in bytes) {
                val byteAsInt = byte.toInt() and 0xFF

                if (incomingSysExBuffer.isEmpty()) {
                    if (byteAsInt != 0xF0) {
                        continue
                    }
                } else if (byteAsInt == 0xF0) {
                    logger.warn("Restarting buffered WebMIDI SysEx after nested start byte", bytes.hex())
                    incomingSysExBuffer.clear()
                }

                incomingSysExBuffer.add(byte)

                if (byteAsInt != 0xF7) {
                    continue
                }

                if (incomingSysExBuffer.size < 3) {
                    incomingSysExBuffer.clear()
                    return@onmidimessage
                }

                val fullMessage = incomingSysExBuffer.toByteArray()
                incomingSysExBuffer.clear()

                val manufacturerId = fullMessage[1]
                this.incomingSysExMessageHandler(
                    manufacturerId,
                    ByteArrayBinaryInput(fullMessage, 2, fullMessage.lastIndex - 1)
                )
            }
        }
    }

    val isConnected: Boolean
        get() = input.state == "connected" && output.state == "connected"

    suspend fun open() {
        logger.info("Opening MIDI ports", input.name, input.connection, output.name, output.connection)
        await(input.open())
        await(output.open())
        logger.info("Opened MIDI ports", input.name, input.connection, output.name, output.connection)
    }

    suspend fun close() {
        await(input.close())
        await(output.close())
    }

    companion object {
        private val logger = LoggerFactory["vtx-midi"]

        fun tryBuildFrom(midiState: MidiState.Available): WebMidiVoxVtxDevice? {
            val input = midiState.inputs.entries.singleOrNull { (_, input) -> input.isVtxAmp }
                ?: return null

            val output = midiState.outputs.entries.singleOrNull { (_, output) -> output.isVtxAmp }
                ?: return null

            return WebMidiVoxVtxDevice(input.value, output.value).also { device ->
                GlobalScope.launch {
                    device.open()
                }
            }
        }

        private val MidiPort.isVtxAmp: Boolean
            get() = isVtxAmpOnWindows || isVtxAmpOnLinuxAlsa || isMyEsp32

        private val MidiPort.isVtxAmpOnWindows: Boolean
            get() = manufacturer?.lowercase() == "korg, inc." && name == "Valvetronix X"

        private val MidiPort.isVtxAmpOnLinuxAlsa: Boolean
            get() = manufacturer?.lowercase() == "vox amplification ltd." && (name?.contains("valvetronix x", ignoreCase = true) == true)

        private val MidiPort.isMyEsp32: Boolean
            get() = name?.contains("ESP32_Vox_Bridge", ignoreCase = true) == true
    }
}

private class WebBluetoothBleMidiDevice private constructor(
    private val device: BluetoothDevice,
    private val server: BluetoothRemoteGATTServer,
    private val characteristic: BluetoothRemoteGATTCharacteristic,
) : MidiDevice {
    private val incomingSysExBuffer = mutableListOf<Byte>()

    override lateinit var incomingSysExMessageHandler: (manufacturerId: Byte, payload: BinaryInput) -> Unit

    override suspend fun sendSysExMessage(manufacturerId: Byte, writer: (BinaryOutput) -> Unit) {
        val binaryOutput = BufferedBinaryOutput()
        binaryOutput.write(0xf0.toByte())
        binaryOutput.write(manufacturerId)
        writer(binaryOutput)
        binaryOutput.write(0xf7.toByte())

        val rawData = binaryOutput.copyToInput().let { input ->
            ByteArray(input.bytesRemaining) { input.nextByte() }
        }

        logger.debug("BLE direct > ${rawData.hex()}")

        var offset = 0
        while (offset < rawData.size) {
            val payloadLength = minOf(BLE_MIDI_CHUNK_SIZE, rawData.size - offset)
            val packet = makeBleMidiPacket(rawData.copyOfRange(offset, offset + payloadLength))
            writePacket(packet)
            offset += payloadLength
        }
    }

    private suspend fun writePacket(packet: ByteArray) {
        val value = packet.toUint8Array()
        val noResponseWriter = characteristic.writeValueWithoutResponse
        if (noResponseWriter != null) {
            await(noResponseWriter.asDynamic().call(characteristic, value).unsafeCast<Promise<Unit>>())
        } else {
            await(characteristic.writeValue(value))
        }
    }

    private fun handleNotification(event: Event) {
        val target = event.target.unsafeCast<BluetoothRemoteGATTCharacteristic>()
        val value = target.value ?: return
        val bytes = value.toByteArray()
        logger.debug("BLE direct < ${bytes.hex()}")

        if (!this::incomingSysExMessageHandler.isInitialized || bytes.isEmpty()) {
            return
        }

        val payload = extractBleMidiPayload(bytes)
        for (byte in payload) {
            val byteAsInt = byte.toInt() and 0xFF

            if (incomingSysExBuffer.isEmpty()) {
                if (byteAsInt != 0xF0) {
                    continue
                }
            } else if (byteAsInt == 0xF0) {
                logger.warn("Restarting buffered BLE SysEx after nested start byte", bytes.hex())
                incomingSysExBuffer.clear()
            }

            incomingSysExBuffer.add(byte)

            if (byteAsInt != 0xF7) {
                continue
            }

            if (incomingSysExBuffer.size >= 3) {
                val fullMessage = incomingSysExBuffer.toByteArray()
                val manufacturerId = fullMessage[1]
                incomingSysExMessageHandler(
                    manufacturerId,
                    ByteArrayBinaryInput(fullMessage, 2, fullMessage.lastIndex - 1)
                )
            }

            incomingSysExBuffer.clear()
        }
    }

    suspend fun close() {
        runCatching { await(characteristic.stopNotifications()) }
        if (server.connected) {
            runCatching { server.disconnect() }
        }
    }

    companion object {
        private val logger = LoggerFactory["ble-direct"]
        private const val MIDI_SERVICE_UUID = "03b80e5a-ede8-4b33-a751-6ce34ec4c700"
        private const val MIDI_CHARACTERISTIC_UUID = "7772e5db-3868-4112-a1a9-f2669d106bf3"
        private const val BLE_MIDI_CHUNK_SIZE = 18

        suspend fun requestAndConnect(): WebBluetoothBleMidiDevice {
            val bluetooth = window.navigator.asDynamic().bluetooth.unsafeCast<Bluetooth>()
            val requestOptions = js("({})")
            requestOptions.filters = arrayOf(js("({ services: ['$MIDI_SERVICE_UUID'] })"))
            requestOptions.optionalServices = arrayOf(MIDI_SERVICE_UUID)

            val device = await(bluetooth.requestDevice(requestOptions))
            val gattServer = device.gatt ?: error("Selected BLE device does not expose a GATT server")
            val server = await(gattServer.connect())
            val service = await(server.getPrimaryService(MIDI_SERVICE_UUID))
            val characteristic = await(service.getCharacteristic(MIDI_CHARACTERISTIC_UUID))

            val result = WebBluetoothBleMidiDevice(device, server, characteristic)
            characteristic.addEventListener("characteristicvaluechanged", result::handleNotification.unsafeCast<(Event) -> Unit>())
            await(characteristic.startNotifications())

            device.addEventListener("gattserverdisconnected", {
                logger.info("Direct BLE MIDI disconnected", device.name)
                GlobalScope.launch {
                    result.close()
                }
                directBleDevice.value = null
            }.unsafeCast<(Event) -> Unit>())

            logger.info("Direct BLE MIDI connected", device.name)
            return result
        }

        private fun makeBleMidiPacket(payload: ByteArray): ByteArray {
            val timestamp = Date.now().toLong() and 0x1FFF
            val packet = ByteArray(payload.size + 2)
            packet[0] = (0x80 or ((timestamp shr 7) and 0x3F).toInt()).toByte()
            packet[1] = (0x80 or (timestamp and 0x7F).toInt()).toByte()
            payload.copyInto(packet, destinationOffset = 2)
            return packet
        }

        private fun extractBleMidiPayload(packet: ByteArray): ByteArray {
            if (packet.isEmpty()) {
                return packet
            }

            val payloadOffset = when {
                packet.size >= 2 && (packet[0].toInt() and 0x80) != 0 && (packet[1].toInt() and 0x80) != 0 -> 2
                (packet[0].toInt() and 0x80) != 0 -> 1
                else -> 0
            }

            return if (payloadOffset >= packet.size) byteArrayOf() else packet.copyOfRange(payloadOffset, packet.size)
        }
    }
}

private fun BufferedBinaryOutput.contentAsArrayOfUnsignedInts(): Array<Int> {
    val asInput = copyToInput()
    return Array(asInput.bytesRemaining) { asInput.nextByte().toUByte().toInt() }
}

private fun <K, V> JsMap<K, V>.toKotlinMap(): Map<K, V> {
    val map = HashMap<K, V>(size)
    forEach { value, key ->
        map[key] = value
    }

    return map
}

private fun Uint8Array.toByteArray(): ByteArray {
    return ByteArray(length) { this[it].toByte() }
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val result = Uint8Array(size)
    forEachIndexed { index, byte ->
        result.asDynamic()[index] = byte.toInt() and 0xFF
    }
    return result
}

// ------ TYPINGS FROM @types/webmidi:2.0.6, adapted and fixed -------
private external interface JsMap<K, V> {
    val size: Int
    fun forEach(callback: (V, K) -> Unit)
}

private external interface MidiOptions {
    var sysex: Boolean
}

private typealias MidiInputMap = JsMap<String, MidiInput>
private typealias MidiOutputMap = JsMap<String, MidiOutput>

@Suppress("INTERFACE_WITH_SUPERCLASS")
private external interface MidiAccess : EventTarget {
    var inputs: MidiInputMap
    var outputs: MidiOutputMap
    fun onstatechange(e: MidiConnectionEvent)
    var sysexEnabled: Boolean
}

private fun MidiAccess.addStateChangedListener(listener: (MidiConnectionEvent) -> Unit) {
    addEventListener("statechange", listener.unsafeCast<(Event) -> Unit>())
}

@Suppress("INTERFACE_WITH_SUPERCLASS")
private external interface MidiPort : EventTarget {
    var id: String
    var manufacturer: String?
        get() = definedExternally
        set(value) = definedExternally
    var name: String?
        get() = definedExternally
        set(value) = definedExternally
    var type: String
    var version: String?
        get() = definedExternally
        set(value) = definedExternally
    var state: String
    var connection: String
    fun open(): Promise<MidiPort>
    fun close(): Promise<MidiPort>
}

private external interface MidiInput : MidiPort {
    var onmidimessage: ((MidiMessageEvent) -> Unit)?
    override var type: String
}

private external interface MidiOutput : MidiPort {
    override var type: String
    fun send(data: Array<Number>, timestamp: Number = definedExternally)
    fun send(data: Array<Number>)
    fun send(data: Array<Int>, timestamp: Number = definedExternally)
    fun send(data: Array<Int>)
    fun clear()
}

@Suppress("INTERFACE_WITH_SUPERCLASS")
private external interface MidiMessageEvent : Event {
    var receivedTime: Number
    var data: Uint8Array
}

@Suppress("INTERFACE_WITH_SUPERCLASS")
private external interface MidiConnectionEvent : Event {
    var port: MidiPort
}

// ------ minimal Web Bluetooth typings -------
private external interface Bluetooth {
    fun requestDevice(options: dynamic): Promise<BluetoothDevice>
}

@Suppress("INTERFACE_WITH_SUPERCLASS")
private external interface BluetoothDevice : EventTarget {
    var id: String
    var name: String?
    var gatt: BluetoothRemoteGATTServer?
}

private external interface BluetoothRemoteGATTServer {
    val connected: Boolean
    fun connect(): Promise<BluetoothRemoteGATTServer>
    fun disconnect()
    fun getPrimaryService(service: String): Promise<BluetoothRemoteGATTService>
}

private external interface BluetoothRemoteGATTService {
    fun getCharacteristic(characteristic: String): Promise<BluetoothRemoteGATTCharacteristic>
}

@Suppress("INTERFACE_WITH_SUPERCLASS")
private external interface BluetoothRemoteGATTCharacteristic : EventTarget {
    var value: DataView?
    val writeValueWithoutResponse: ((Uint8Array) -> Promise<Unit>)?
    fun startNotifications(): Promise<BluetoothRemoteGATTCharacteristic>
    fun stopNotifications(): Promise<BluetoothRemoteGATTCharacteristic>
    fun writeValue(value: Uint8Array): Promise<Unit>
}

private external interface DataView {
    val byteLength: Int
    val byteOffset: Int
    val buffer: ArrayBuffer
}

private fun DataView.toByteArray(): ByteArray {
    val bytes = Uint8Array(buffer, byteOffset, byteLength)
    return ByteArray(byteLength) { bytes[it].toByte() }
}



