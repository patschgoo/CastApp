package io.github.patschgoo.castapp.stream

/**
 * Codec configuration for the video track: H.264 (SPS + PPS) or HEVC (VPS + SPS + PPS).
 * Parameter sets are NAL units without start codes.
 */
class VideoConfig(
    val width: Int,
    val height: Int,
    val sps: ByteArray,
    val pps: ByteArray,
    val codec: VideoCodec = VideoCodec.H264,
    /** HEVC only. */
    val vps: ByteArray? = null,
) {
    init {
        require(codec != VideoCodec.HEVC || vps != null) { "HEVC needs a VPS" }
    }

    /** RFC 6381 codec string for MSE and HLS, e.g. "avc1.64001F" or "hvc1.1.6.L120.90". */
    val codecString: String
        get() = if (codec == VideoCodec.HEVC) HevcUtils.codecString(sps) else AvcUtils.codecString(sps)
}

/** Codec configuration for the AAC audio track. */
class AudioConfig(
    val sampleRate: Int,
    val channels: Int,
    /** AudioSpecificConfig (csd-0 from MediaCodec). */
    val asc: ByteArray,
    val bitrate: Int,
)

/**
 * One sample ready to be written into a fragment. Duration is in track timescale units, and so is
 * [compositionOffset]: how much later than its decoding time the frame is shown (B-frames of a copied
 * H.264 picture; never negative). 0 for everything the phone encodes itself.
 */
class FragmentSample(val data: ByteArray, val duration: Int, val isSync: Boolean, val compositionOffset: Int = 0)

/**
 * Minimal fragmented MP4 (ISO BMFF) writer: an init segment (ftyp + moov) followed
 * by any number of moof + mdat fragments. This is the format both Chrome's
 * progressive player and MSE-based HLS players (used by Cast receivers) understand.
 */
object FragmentedMp4 {
    const val VIDEO_TRACK_ID = 1
    const val AUDIO_TRACK_ID = 2
    const val VIDEO_TIMESCALE = 90_000

    private val MATRIX = intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)

    fun initSegment(video: VideoConfig, audio: AudioConfig?): ByteArray {
        val w = BoxWriter(1024)
        w.box("ftyp") {
            fourcc("isom")
            u32(0x200)
            fourcc("isom"); fourcc("iso6"); fourcc("iso5")
            if (video.codec == VideoCodec.H264) fourcc("avc1")
            fourcc("mp41")
        }
        w.box("moov") {
            fullBox("mvhd", 0, 0) {
                u32(0); u32(0) // creation / modification time
                u32(1000) // timescale
                u32(0) // duration (unknown, live)
                u32(0x00010000) // rate 1.0
                u16(0x0100) // volume 1.0
                zeros(10)
                MATRIX.forEach { u32(it) }
                zeros(24) // pre_defined
                u32(if (audio != null) 3 else 2) // next_track_ID
            }
            videoTrak(video)
            if (audio != null) audioTrak(audio)
            box("mvex") {
                trex(VIDEO_TRACK_ID)
                if (audio != null) trex(AUDIO_TRACK_ID)
            }
        }
        return w.toByteArray()
    }

    private fun BoxWriter.trex(trackId: Int) = fullBox("trex", 0, 0) {
        u32(trackId)
        u32(1) // default_sample_description_index
        u32(0); u32(0); u32(0)
    }

    private fun BoxWriter.tkhd(trackId: Int, isAudio: Boolean, width: Int, height: Int) =
        fullBox("tkhd", 0, 0x3) {
            u32(0); u32(0)
            u32(trackId)
            u32(0) // reserved
            u32(0) // duration
            zeros(8)
            u16(0) // layer
            u16(0) // alternate_group
            u16(if (isAudio) 0x0100 else 0) // volume
            u16(0)
            MATRIX.forEach { u32(it) }
            u32(width shl 16)
            u32(height shl 16)
        }

    private fun BoxWriter.mdhd(timescale: Int) = fullBox("mdhd", 0, 0) {
        u32(0); u32(0)
        u32(timescale)
        u32(0)
        u16(0x55C4) // language "und"
        u16(0)
    }

    private fun BoxWriter.hdlr(type: String, name: String) = fullBox("hdlr", 0, 0) {
        u32(0)
        fourcc(type)
        zeros(12)
        bytes(name.toByteArray(Charsets.US_ASCII))
        u8(0)
    }

    private fun BoxWriter.dinf() = box("dinf") {
        fullBox("dref", 0, 0) {
            u32(1)
            fullBox("url ", 0, 1) {}
        }
    }

    private fun BoxWriter.emptySampleTables() {
        fullBox("stts", 0, 0) { u32(0) }
        fullBox("stsc", 0, 0) { u32(0) }
        fullBox("stsz", 0, 0) { u32(0); u32(0) }
        fullBox("stco", 0, 0) { u32(0) }
    }

    private fun BoxWriter.videoTrak(v: VideoConfig) = box("trak") {
        tkhd(VIDEO_TRACK_ID, false, v.width, v.height)
        box("mdia") {
            mdhd(VIDEO_TIMESCALE)
            hdlr("vide", "VideoHandler")
            box("minf") {
                fullBox("vmhd", 0, 1) { u16(0); u16(0); u16(0); u16(0) }
                dinf()
                box("stbl") {
                    fullBox("stsd", 0, 0) {
                        u32(1)
                        box(if (v.codec == VideoCodec.HEVC) "hvc1" else "avc1") {
                            zeros(6)
                            u16(1) // data_reference_index
                            zeros(16) // pre_defined + reserved
                            u16(v.width)
                            u16(v.height)
                            u32(0x00480000) // 72 dpi
                            u32(0x00480000)
                            u32(0)
                            u16(1) // frame_count
                            zeros(32) // compressorname
                            u16(0x0018) // depth
                            u16(0xFFFF) // pre_defined = -1
                            if (v.codec == VideoCodec.HEVC) hvcC(v) else avcC(v)
                        }
                    }
                    emptySampleTables()
                }
            }
        }
    }

    private fun BoxWriter.hvcC(v: VideoConfig) = box("hvcC") {
        bytes(HevcUtils.decoderConfigurationRecord(v.vps!!, v.sps, v.pps))
    }

    private fun BoxWriter.avcC(v: VideoConfig) = box("avcC") {
        val sps = v.sps
        val profile = if (sps.size > 1) sps[1].toInt() and 0xFF else 66
        u8(1)
        u8(profile)
        u8(if (sps.size > 2) sps[2].toInt() and 0xFF else 0)
        u8(if (sps.size > 3) sps[3].toInt() and 0xFF else 31)
        u8(0xFF) // 4-byte NAL lengths
        u8(0xE1) // one SPS
        u16(sps.size)
        bytes(sps)
        u8(1) // one PPS
        u16(v.pps.size)
        bytes(v.pps)
        if (profile == 100 || profile == 110 || profile == 122 || profile == 144) {
            u8(0xFC or 1) // chroma_format_idc = 4:2:0
            u8(0xF8) // bit_depth_luma_minus8 = 0
            u8(0xF8) // bit_depth_chroma_minus8 = 0
            u8(0) // no SPS extensions
        }
    }

    private fun BoxWriter.audioTrak(a: AudioConfig) = box("trak") {
        tkhd(AUDIO_TRACK_ID, true, 0, 0)
        box("mdia") {
            mdhd(a.sampleRate)
            hdlr("soun", "SoundHandler")
            box("minf") {
                fullBox("smhd", 0, 0) { u16(0); u16(0) }
                dinf()
                box("stbl") {
                    fullBox("stsd", 0, 0) {
                        u32(1)
                        box("mp4a") {
                            zeros(6)
                            u16(1)
                            zeros(8)
                            u16(a.channels)
                            u16(16)
                            u16(0)
                            u16(0)
                            u32(a.sampleRate shl 16)
                            esds(a)
                        }
                    }
                    emptySampleTables()
                }
            }
        }
    }

    private fun BoxWriter.esds(a: AudioConfig) = fullBox("esds", 0, 0) {
        val dsi = a.asc
        val decConfigLen = 13 + 2 + dsi.size
        val esLen = 3 + 2 + decConfigLen + 3
        descriptor(0x03, esLen)
        u16(0) // ES_ID
        u8(0) // flags
        descriptor(0x04, decConfigLen)
        u8(0x40) // MPEG-4 Audio
        u8(0x15) // AudioStream, upstream=0, reserved=1
        u24(6144) // bufferSizeDB
        u32(a.bitrate) // maxBitrate
        u32(a.bitrate) // avgBitrate
        descriptor(0x05, dsi.size)
        bytes(dsi)
        descriptor(0x06, 1)
        u8(0x02)
    }

    private fun BoxWriter.descriptor(tag: Int, length: Int) {
        u8(tag)
        require(length < 0x80) { "descriptor too long" }
        u8(length)
    }

    /**
     * Builds one moof + mdat pair for [samples] of a single track.
     * [baseDecodeTime] is in the track's timescale.
     */
    fun fragment(sequenceNumber: Int, trackId: Int, baseDecodeTime: Long, samples: List<FragmentSample>): ByteArray {
        val payload = samples.sumOf { it.data.size }
        val w = BoxWriter(256 + samples.size * 12 + payload)
        val moofStart = w.size
        var dataOffsetPos = 0
        w.box("moof") {
            fullBox("mfhd", 0, 0) { u32(sequenceNumber) }
            box("traf") {
                fullBox("tfhd", 0, 0x020000) { u32(trackId) } // default-base-is-moof
                fullBox("tfdt", 1, 0) { u64(baseDecodeTime) }
                // data-offset, sample-duration, sample-size, sample-flags present (and composition
                // time offsets when a frame is shown later than it's decoded: B-frames)
                val offsets = samples.any { it.compositionOffset != 0 }
                fullBox("trun", 0, 0x000001 or 0x000100 or 0x000200 or 0x000400 or if (offsets) 0x000800 else 0) {
                    u32(samples.size)
                    dataOffsetPos = size
                    u32(0) // patched below
                    for (s in samples) {
                        u32(s.duration)
                        u32(s.data.size)
                        u32(if (s.isSync) 0x02000000 else 0x01010000)
                        if (offsets) u32(s.compositionOffset.coerceAtLeast(0))
                    }
                }
            }
        }
        val moofSize = w.size - moofStart
        w.patchU32(dataOffsetPos, moofSize + 8)
        w.u32(8 + payload)
        w.fourcc("mdat")
        for (s in samples) w.bytes(s.data)
        return w.toByteArray()
    }
}

/** Tiny big-endian byte writer with nested box support. */
class BoxWriter(initialCapacity: Int = 256) {
    private var buf = ByteArray(initialCapacity.coerceAtLeast(16))
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) {
            var n = buf.size * 2
            while (n < size + extra) n *= 2
            buf = buf.copyOf(n)
        }
    }

    fun u8(v: Int) { ensure(1); buf[size++] = v.toByte() }
    fun u16(v: Int) { ensure(2); buf[size++] = (v ushr 8).toByte(); buf[size++] = v.toByte() }
    fun u24(v: Int) { ensure(3); buf[size++] = (v ushr 16).toByte(); buf[size++] = (v ushr 8).toByte(); buf[size++] = v.toByte() }
    fun u32(v: Int) {
        ensure(4)
        buf[size++] = (v ushr 24).toByte(); buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 8).toByte(); buf[size++] = v.toByte()
    }
    fun u64(v: Long) { u32((v ushr 32).toInt()); u32(v.toInt()) }
    fun zeros(n: Int) { ensure(n); size += n } // array is zero-initialised and never reused
    fun bytes(b: ByteArray) { ensure(b.size); System.arraycopy(b, 0, buf, size, b.size); size += b.size }
    fun fourcc(s: String) { require(s.length == 4); s.forEach { u8(it.code) } }

    fun patchU32(pos: Int, v: Int) {
        buf[pos] = (v ushr 24).toByte(); buf[pos + 1] = (v ushr 16).toByte()
        buf[pos + 2] = (v ushr 8).toByte(); buf[pos + 3] = v.toByte()
    }

    inline fun box(type: String, body: BoxWriter.() -> Unit) {
        val start = size
        u32(0)
        fourcc(type)
        body()
        patchU32(start, size - start)
    }

    inline fun fullBox(type: String, version: Int, flags: Int, body: BoxWriter.() -> Unit) = box(type) {
        u8(version)
        u24(flags)
        body()
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
