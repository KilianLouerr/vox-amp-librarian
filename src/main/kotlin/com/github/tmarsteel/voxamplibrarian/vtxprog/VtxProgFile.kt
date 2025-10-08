package com.github.tmarsteel.voxamplibrarian.vtxprog

import com.github.tmarsteel.voxamplibrarian.BinaryInput
import com.github.tmarsteel.voxamplibrarian.BinaryOutput
import com.github.tmarsteel.voxamplibrarian.hex
import com.github.tmarsteel.voxamplibrarian.protocol.*
import com.github.tmarsteel.voxamplibrarian.protocol.message.MessageParseException

data class VtxProgFile(
    val programs: List<Program>
) {
    fun writeToInVtxProgFormat(output: BinaryOutput) {
        output.write(PREFIX)
        programs.forEach { it.writeToInVtxProgFormat(output) }
    }

    private fun Program.writeToInVtxProgFormat(output: BinaryOutput) {
        var flags = 0x00
        if (pedal1Enabled) {
            flags = flags or FLAG_PEDAL_1_ENABLED
        }
        if (pedal2Enabled) {
            flags = flags or FLAG_PEDAL_2_ENABLED
        }
        if (reverbPedalEnabled) {
            flags = flags or FLAG_REVERB_PEDAL_ENABLED
        }

        output.write(programName.encoded)
        output.write(noiseReductionSensitivity)
        output.write(flags.toByte())
        output.write(ampModel)
        output.write(gain)
        output.write(treble)
        output.write(middle)
        output.write(bass)
        output.write(volume)
        output.write(presence)
        output.write(resonance)

        // write booleans canonically as 0/1
        output.writeBool01(brightCap)
        output.writeBool01(lowCut)
        output.writeBool01(midBoost)

        output.write(tubeBias)
        output.write(ampClass)

        // pedal 1
        output.write(pedal1Type.protocolValue)
        output.writeUShortBE(pedal1Dial1.semanticValue.toInt())
        output.writeByte(pedal1Dial2.toInt())
        output.writeByte(pedal1Dial3.toInt())
        output.writeByte(pedal1Dial4.toInt())
        output.writeByte(pedal1Dial5.toInt())
        output.writeByte(pedal1Dial6.toInt())

        // pedal 2
        output.write(pedal2Type.protocolValue)
        output.writeUShortBE(pedal2Dial1.semanticValue.toInt())
        output.writeByte(pedal2Dial2.toInt())
        output.writeByte(pedal2Dial3.toInt())
        output.writeByte(pedal2Dial4.toInt())
        output.writeByte(pedal2Dial5.toInt())
        output.writeByte(pedal2Dial6.toInt())

        output.write(byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        ))
        output.write(reverbPedalType.protocolValue)
        output.write(reverbPedalDial1)
        output.write(reverbPedalDial2)
        output.write(reverbPedalDial3)
        output.write(reverbPedalDial4)
        output.write(reverbPedalDial5)

        output.write(0x00.toByte())
    }

    private fun BinaryOutput.writeBool01(value: Boolean) {
        write((if (value) 0x01 else 0x00).toByte())
    }
    private fun BinaryOutput.writeUShortBE(value: Int) {
        require(value in 0..0xFFFF) { "UShort out of range: $value" }
        write(((value ushr 8) and 0xFF).toByte())
        write((value and 0xFF).toByte())
    }
    private fun BinaryOutput.writeByte(v: Int) {
        write((v and 0xFF).toByte())
    }

    companion object {
        private val PREFIX = byteArrayOf(
            0x56, 0x54, 0x58, 0x50, 0x52, 0x4F, 0x47, 0x31, 0x30, 0x30, 0x30, 0x20, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        private val FLAG_PEDAL_1_ENABLED = 0b0000_0010
        private val FLAG_PEDAL_2_ENABLED = 0b0000_0100
        private val FLAG_REVERB_PEDAL_ENABLED = 0b0001_0000

        fun readFromInVtxProgFormat(input: BinaryInput): VtxProgFile {
            val prefixFromFile = ByteArray(PREFIX.size)
            input.nextBytes(prefixFromFile)
            if (!prefixFromFile.contentEquals(PREFIX)) {
                throw IllegalArgumentException("Incorrect prefix")
            }

            val programs = mutableListOf<Program>()
            while (input.bytesRemaining > 0) {
                if (input.bytesRemaining < 0x3E) {
                    throw MessageParseException.InvalidMessage("The input file has an incorrect length, programs are always 0x3E bytes long (at offset ${input.position.hex()})")
                }

                programs.add(readProgramInVtxProgFormat(input))
            }

            return VtxProgFile(programs)
        }

        private fun readProgramInVtxProgFormat(input: BinaryInput): Program {
            val encodedProgramName = ByteArray(0x10)
            input.nextBytes(encodedProgramName)
            val programName = ProgramName.decode(encodedProgramName)

            val nrSens = ZeroToTenDial.readFrom(input)
            val flags = input.nextByte().toInt()
            val ampModel = AmpModel.readFrom(input)
            val gain = ZeroToTenDial.readFrom(input)
            val treble = ZeroToTenDial.readFrom(input)
            val middle = ZeroToTenDial.readFrom(input)
            val bass = ZeroToTenDial.readFrom(input)
            val volume = ZeroToTenDial.readFrom(input)
            val presence = ZeroToTenDial.readFrom(input)
            val resonance = ZeroToTenDial.readFrom(input)

            // Amp-level booleans: accept 0x20 as true for legacy
            val brightCap = readBool01LenientAmp(input)
            val lowCut = readBool01LenientAmp(input)
            val midBoost = readBool01LenientAmp(input)

            val tubeBias = TubeBias.readFrom(input)
            val ampClass = AmpClass.readFrom(input)
            val pedal1Type = Slot1PedalType.ofProtocolValue(input.nextByte())
            val pedal1Dial1 = TwoByteDial(input.nextUShort())
            val pedal1Dial2 = readByteLenientDial(input)
            val pedal1Dial3 = readByteLenientDial(input)
            val pedal1Dial4 = readByteLenientDial(input)
            val pedal1Dial5 = readByteLenientDial(input)
            val pedal1Dial6 = readByteLenientDial(input)

            val pedal2Type = Slot2PedalType.ofProtocolValue(input.nextByte())
            val pedal2Dial1 = TwoByteDial(input.nextUShort())
            val pedal2Dial2 = readByteLenientDial(input)
            val pedal2Dial3 = readByteLenientDial(input)
            val pedal2Dial4 = readByteLenientDial(input)
            val pedal2Dial5 = readByteLenientDial(input)
            val pedal2Dial6 = readByteLenientDial(input)

            input.skip(0x08)
            val reverbPedalType = ReverbPedalType.ofProtocolValue(input.nextByte())
            val reverbPedalDial1 = ZeroToTenDial.readFrom(input)
            val reverbPedalDial2 = ZeroToTenDial.readFrom(input)
            val reverbPedalDial3 = readByteLenientDial(input)
            val reverbPedalDial4 = ZeroToTenDial.readFrom(input)
            val reverbPedalDial5 = ZeroToTenDial.readFrom(input)
            input.skip(1)

            return ProgramImpl(
                programName = programName,
                noiseReductionSensitivity = nrSens,
                ampModel = ampModel,
                gain = gain,
                treble = treble,
                middle = middle,
                bass = bass,
                volume = volume,
                presence = presence,
                resonance = resonance,
                brightCap = brightCap,
                lowCut = lowCut,
                midBoost = midBoost,
                tubeBias = tubeBias,
                ampClass = ampClass,
                pedal1Enabled = flags and FLAG_PEDAL_1_ENABLED > 0,
                pedal1Type = pedal1Type,
                pedal1Dial1 = pedal1Dial1,
                pedal1Dial2 = pedal1Dial2,
                pedal1Dial3 = pedal1Dial3,
                pedal1Dial4 = pedal1Dial4,
                pedal1Dial5 = pedal1Dial5,
                pedal1Dial6 = pedal1Dial6,
                pedal2Enabled = flags and FLAG_PEDAL_2_ENABLED > 0,
                pedal2Type = pedal2Type,
                pedal2Dial1 = pedal2Dial1,
                pedal2Dial2 = pedal2Dial2,
                pedal2Dial3 = pedal2Dial3,
                pedal2Dial4 = pedal2Dial4,
                pedal2Dial5 = pedal2Dial5,
                pedal2Dial6 = pedal2Dial6,
                reverbPedalEnabled = flags and FLAG_REVERB_PEDAL_ENABLED > 0,
                reverbPedalType = reverbPedalType,
                reverbPedalDial1 = reverbPedalDial1,
                reverbPedalDial2 = reverbPedalDial2,
                reverbPedalDial3 = reverbPedalDial3,
                reverbPedalDial4 = reverbPedalDial4,
                reverbPedalDial5 = reverbPedalDial5,
            )
        }

        // --- Minimal leniency helpers ---

        // Amp-level booleans: 0=false, 1=true, 0x20 (space) -> false
        private fun readBool01LenientAmp(input: BinaryInput): Boolean {
            val raw = input.nextByte().toInt() and 0xFF
            return when (raw) {
                0x00 -> false
                0x01 -> true
                0x20 -> false
                else -> throw MessageParseException.InvalidMessage("Expected boolean (0 or 1), got $raw")
            }
        }

        // Single-byte pedal/reverb dials: keep numeric, but treat 0x20 as 0x01 (true) for legacy boolean-style dials
        private fun readByteLenientDial(input: BinaryInput): Byte {
            val raw = input.nextByte().toInt() and 0xFF
            return when (raw) {
                0x20 -> 0x01.toByte()
                else -> raw.toByte()
            }
        }
    }
}