package io.github.patschgoo.castapp.stream

/**
 * ADTS framing for raw AAC frames: the format of internet radio streams, which
 * every Cast speaker and TV plays as a live "audio/aac" stream.
 */
object Adts {
    private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** Builds the 7-byte ADTS header for an AAC frame of [payloadLength] bytes. */
    fun header(config: AudioConfig, payloadLength: Int): ByteArray {
        // Object type from the AudioSpecificConfig (AAC-LC = 2); ADTS stores it minus one.
        val objectType = if (config.asc.isNotEmpty()) (config.asc[0].toInt() and 0xFF) ushr 3 else 2
        val profile = (objectType - 1).coerceIn(0, 3)
        val freqIndex = SAMPLE_RATES.indexOf(config.sampleRate).let { if (it < 0) 3 else it }
        val channels = config.channels.coerceIn(1, 7)
        val frameLength = payloadLength + 7
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 0, no CRC
            ((profile shl 6) or (freqIndex shl 2) or (channels ushr 2)).toByte(),
            (((channels and 3) shl 6) or (frameLength ushr 11)).toByte(),
            ((frameLength ushr 3) and 0xFF).toByte(),
            (((frameLength and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(), // buffer fullness 0x7FF (VBR), one raw data block
        )
    }

    fun frame(config: AudioConfig, aac: ByteArray): ByteArray = header(config, aac.size) + aac
}
