package io.github.patschgoo.castapp.capture

import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.service.TrackNames
import java.io.IOException

/** Why the phone can't convert a file. */
sealed class ConvertProblem {
    /** Android can't open the container (e.g. AVI on phones without a vendor AVI reader). [fileType]: "AVI", or null. */
    data class UnreadableFileType(val fileType: String?) : ConvertProblem()

    /** The file itself couldn't be read (network share gone, file removed...). */
    data object ReadFailed : ConvertProblem()

    /** The file has no picture track. */
    data object NoVideoTrack : ConvertProblem()

    /** No decoder on this phone for the picture: [codec] is a readable name such as "MPEG-2" or "H.264 10-bit". */
    data class NoVideoDecoder(val mime: String, val codec: String) : ConvertProblem()

    /** The reason as a short phrase for "This phone can't convert … (<reason>)". */
    fun describe(): String = when (this) {
        is UnreadableFileType -> if (fileType != null) {
            Txt.sOr(R.string.convert_reason_file_type, "it can't read %1\$s files", fileType)
        } else {
            Txt.sOr(R.string.convert_reason_this_type, "it can't read this file type")
        }
        ReadFailed -> Txt.sOr(R.string.convert_reason_unreadable, "couldn't read the file")
        NoVideoTrack -> Txt.sOr(R.string.convert_reason_no_video, "the file has no picture")
        is NoVideoDecoder -> Txt.sOr(R.string.convert_reason_decoder, "no decoder for %1\$s video", codec)
    }
}

/** The converter can't work on this file: thrown by [Transcoder.start] before anything is sent to the TV. */
class ConvertException(val problem: ConvertProblem) : IOException(problem.describe())

/**
 * What the phone's converter can do with a file.
 * [problem]: why it can't convert at all (null: it can). [droppedAudioMime]: the chosen sound track
 * has no decoder here, so the converted video has no sound. [video]: the picture track, for whether
 * it's copied as it is ([copiesPicture]).
 */
data class ConvertCheck(
    val problem: ConvertProblem?,
    val durationMs: Long = 0,
    val droppedAudioMime: String? = null,
    val video: Remux.VideoFacts? = null,
) {
    /** The converter copies the picture as it is at quality [maxHeight] ([Remux.videoRoute]); [allowed]: not refused by this TV. */
    fun copiesPicture(maxHeight: Int, allowed: Boolean = true): Boolean =
        problem == null && Remux.videoRoute(video, maxHeight, allowed) == Remux.VideoRoute.Copy
}

/** The decisions behind [Transcoder.start] and [Transcoder.probe], without Android in the way (unit tests). */
object ConvertChecks {
    /** One track of the file as MediaExtractor reports it. */
    data class TrackFacts(val mime: String?, val durationUs: Long = 0)

    /** The tracks the converter uses: the first picture track and sound track [audioOrdinal] (or the first one). -1: none. */
    fun pickTracks(tracks: List<TrackFacts>, audioOrdinal: Int): Pair<Int, Int> {
        var video = -1
        var audio = -1
        var audioSeen = 0
        tracks.forEachIndexed { i, t ->
            val mime = t.mime ?: return@forEachIndexed
            if (mime.startsWith("video/") && video < 0) video = i
            if (mime.startsWith("audio/")) {
                // The chosen sound track, or the first one if the file has fewer.
                if (audio < 0 || audioSeen == audioOrdinal) audio = i
                audioSeen++
            }
        }
        return video to audio
    }

    /**
     * [tracks]: null when the container can't be opened; [readable]: whether its bytes could be read
     * at all then (a network problem rather than an unknown file type). [videoDecoder]/[audioDecoder]:
     * whether this phone has a decoder for the track at that index. [videoCodec]: a readable name of it.
     * [droppedVideo]: asked when no picture track came out: the picture codec the file declares all the
     * same (an AVI's DivX 3 stream, which the reader drops), null if it has none.
     */
    fun decide(
        tracks: List<TrackFacts>?,
        audioOrdinal: Int,
        readable: Boolean = true,
        fileType: String? = null,
        videoDecoder: (Int) -> Boolean,
        audioDecoder: (Int) -> Boolean,
        videoCodec: (Int) -> String = { i -> codecName(tracks?.getOrNull(i)?.mime) },
        droppedVideo: () -> AviCodecs.Codec? = { null },
    ): ConvertCheck {
        if (tracks == null) return ConvertCheck(openFailed(fileType) { readable })
        val durationMs = (tracks.maxOfOrNull { it.durationUs } ?: 0L) / 1000
        val (video, audio) = pickTracks(tracks, audioOrdinal)
        if (video < 0) {
            // A picture the reader couldn't hand out: "no decoder for DivX 3", not "no picture".
            val declared = droppedVideo()
            val problem = if (declared != null) ConvertProblem.NoVideoDecoder(declared.mime.orEmpty(), declared.name) else ConvertProblem.NoVideoTrack
            return ConvertCheck(problem, durationMs)
        }
        if (!videoDecoder(video)) {
            return ConvertCheck(ConvertProblem.NoVideoDecoder(tracks[video].mime.orEmpty(), videoCodec(video)), durationMs)
        }
        val dropped = if (audio >= 0 && !audioDecoder(audio)) tracks[audio].mime else null
        return ConvertCheck(null, durationMs, dropped)
    }

    /**
     * File types Android's MediaExtractor opens on every phone. When one of them can't be opened, the
     * file couldn't be read (a share or server gone for a moment), whatever its first bytes were.
     */
    private val ALWAYS_OPENED = setOf("MKV", "MK3D", "WEBM", "MP4", "M4V", "MOV", "3GP", "3G2", "TS", "MPG", "MPEG")

    /**
     * Why a file MediaExtractor couldn't open can't be converted: its type ([fileType], "AVI") only where
     * the phone may lack a reader for it and [readable] (which reads its first byte) shows the bytes are
     * there; a reading problem otherwise, which is asked again next time.
     */
    fun openFailed(fileType: String?, readable: () -> Boolean): ConvertProblem =
        if (fileType?.uppercase() !in ALWAYS_OPENED && readable()) ConvertProblem.UnreadableFileType(fileType) else ConvertProblem.ReadFailed

    /**
     * [open], and once more if that fails: a share or server connection that had gone stale is replaced
     * by then. An interrupted thread isn't asked twice.
     */
    fun <T> openTwice(open: () -> T): T = try {
        open()
    } catch (e: Exception) {
        if (e is InterruptedException || e is java.io.InterruptedIOException || Thread.currentThread().isInterrupted) throw e
        open()
    }

    fun codecName(mime: String?): String = mime?.let { TrackNames.codec(it) } ?: mime.orEmpty()

    /** "H.264 10-bit" for a Hi10P track, "MPEG-2" for a plain one. */
    fun videoCodecName(mime: String?, profile: Int?): String =
        listOfNotNull(codecName(mime), profile?.let { deepProfile(mime.orEmpty(), it) }).joinToString(" ")

    /**
     * Profiles with more than 8 bits or finer colour than 4:2:0, which a phone's decoder often lacks
     * although it plays the codec ([android.media.MediaCodecInfo.CodecProfileLevel] values): a short
     * label for them, null for the ordinary ones.
     */
    fun deepProfile(mime: String, profile: Int): String? = when (mime.lowercase()) {
        "video/avc" -> when (profile) {
            0x10 -> tenBit() // AVCProfileHigh10
            0x20 -> "4:2:2" // AVCProfileHigh422
            0x40 -> "4:4:4" // AVCProfileHigh444
            else -> null
        }
        // HEVCProfileMain10, Main10HDR10, Main10HDR10Plus
        "video/hevc" -> if (profile == 0x2 || profile == 0x1000 || profile == 0x2000) tenBit() else null
        // VP9Profile2/3 and their HDR variants
        "video/x-vnd.on2.vp9" -> if (profile in setOf(0x4, 0x8, 0x1000, 0x2000, 0x4000, 0x8000)) tenBit() else null
        // AV1ProfileMain10, Main10HDR10, Main10HDR10Plus
        "video/av01" -> if (profile == 0x2 || profile == 0x1000 || profile == 0x2000) tenBit() else null
        else -> null
    }

    /** "10-bit" in the phone's language ("10-Bit"); English under unit test. */
    private fun tenBit(): String = Txt.sOr(R.string.convert_codec_10bit, "10-bit")

    /**
     * The profile to ask the phone's codec list about, or null to ask about the codec alone. HDR variants
     * are asked as their plain 10-bit profile: a decoder for HEVC Main 10 converts an HDR10 file too,
     * even where it doesn't list HDR10 (no HDR screen).
     */
    fun profileToCheck(mime: String, profile: Int): Int? {
        if (deepProfile(mime, profile) == null) return null
        return when (mime.lowercase()) {
            "video/hevc", "video/av01" -> 0x2 // Main 10
            "video/x-vnd.on2.vp9" -> if (profile == 0x8 || profile == 0x2000 || profile == 0x8000) 0x8 else 0x4 // Profile 3 / 2
            else -> profile
        }
    }

    /** "AVI" for ".../Drawn Together 01.avi"; null when the name has no telling extension. */
    fun fileType(name: String): String? {
        val ext = name.substringBefore('?').substringAfterLast('/').substringAfterLast("%2F").substringAfterLast('.', "")
        return ext.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }?.uppercase()
    }
}
