package io.github.patschgoo.castapp.tv

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.service.TrackNames
import org.json.JSONObject

/**
 * Why CastApp TV can't play what it was sent (or plays it without sound), for sender apps:
 * `GET /castapp/status` and `CurrentTransportStatus` = `ERROR_OCCURRED`. [code] is one of
 * [PlaybackFailure]'s codes, [message] an English sentence (it goes to other devices), [format] the
 * format it is about when known ("10-bit H.264 (High 10)", "DTS", "WMV"), [httpStatus] the server's
 * answer for a [PlaybackFailure.NETWORK] failure.
 */
data class TvError(val code: String, val message: String, val format: String? = null, val httpStatus: Int? = null) {
    fun toJson(): JSONObject = JSONObject().put("code", code).put("message", message).apply {
        format?.let { put("format", it) }
        httpStatus?.let { put("http_status", it) }
    }

    companion object {
        /** The `error` (or `warning`) object of `/castapp/status`; null when there is none. */
        fun fromJson(o: JSONObject?): TvError? {
            val code = o?.optString("code")?.takeIf { it.isNotBlank() } ?: return null
            return TvError(
                code = code,
                message = o.optString("message"),
                format = o.optString("format").takeIf { o.has("format") && it.isNotBlank() },
                httpStatus = if (o.has("http_status")) o.optInt("http_status") else null,
            )
        }
    }
}

/** The reasons CastApp TV gives ([TvError]) and how they are found. */
object PlaybackFailure {
    /** The picture's format has no decoder on this TV (10-bit H.264 on a Shield, VC-1 ...). */
    const val NO_VIDEO_DECODER = "no_video_decoder"

    /** The sound's format has no decoder (a failure for music; for a video only a warning: it plays without sound). */
    const val NO_AUDIO_DECODER = "no_audio_decoder"

    /** Sound, but the file's picture can't be read at all (DivX 3 in an AVI). */
    const val NO_PICTURE = "no_picture"

    /** The file type (container or stream manifest) can't be opened, or the file is damaged. */
    const val UNSUPPORTED_FORMAT = "unsupported_format"

    /** The file could not be loaded: no connection, a time-out, an HTTP error. */
    const val NETWORK = "network"

    /** Anything else the player stopped with. */
    const val PLAYER_ERROR = "player_error"

    fun noVideoDecoder(format: String?) = TvError(
        NO_VIDEO_DECODER,
        if (format != null) "No decoder for $format video on this TV" else "No decoder for the picture of this video on this TV",
        format,
    )

    fun noAudioDecoder(format: String?) = TvError(
        NO_AUDIO_DECODER,
        if (format != null) "No decoder for $format sound on this TV" else "No decoder for the sound of this file on this TV",
        format,
    )

    /** A video that plays on without sound: a warning, not a failure. */
    fun playsWithoutSound(format: String?) = noAudioDecoder(format).let { it.copy(message = it.message + ": it plays without sound") }

    fun noPicture(container: String?) = TvError(
        NO_PICTURE,
        "Only the sound plays: this TV can't read the picture of this ${container?.let { "$it " }.orEmpty()}file",
        container,
    )

    fun unsupportedFormat(container: String?) = TvError(
        UNSUPPORTED_FORMAT,
        if (container != null) "This TV can't open $container files" else "This TV can't open this file type",
        container,
    )

    fun damaged(container: String?) = TvError(
        UNSUPPORTED_FORMAT,
        "This TV could not read the file: it seems damaged${container?.let { ", or it isn't a real $it file" }.orEmpty()}",
        container,
    )

    fun network(httpStatus: Int? = null, errorCode: Int = PlaybackException.ERROR_CODE_IO_UNSPECIFIED, errorCodeName: String? = null) = TvError(
        NETWORK,
        when {
            httpStatus != null -> "The TV could not load the file: the server answered HTTP $httpStatus"
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "The TV could not connect to the server of the file"
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The TV's connection to the server of the file timed out"
            errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "The TV may not load this file over plain HTTP"
            errorCodeName != null -> "The TV could not load the file ($errorCodeName)"
            else -> "The TV could not load the file"
        },
        httpStatus = httpStatus,
    )

    fun playerError(errorCodeName: String, format: String? = null) = TvError(
        PLAYER_ERROR,
        "The TV's player stopped with an error ($errorCodeName${format?.let { ", $it" }.orEmpty()})",
        format,
    )

    /**
     * A Media3 player error as a reason. [trackType]: the kind of track whose renderer failed
     * ([C.TRACK_TYPE_VIDEO], [C.TRACK_TYPE_AUDIO], else [C.TRACK_TYPE_UNKNOWN]); [claimed]: that renderer
     * said it handles the format (a decoder that doesn't, like an H.264 decoder given High 10, is "no decoder");
     * [format]: its name ([formatName]); [container]: the file type ([containerName]).
     */
    fun fromPlayerError(
        errorCode: Int,
        errorCodeName: String,
        trackType: Int = C.TRACK_TYPE_UNKNOWN,
        claimed: Boolean = true,
        format: String? = null,
        httpStatus: Int? = null,
        container: String? = null,
    ): TvError {
        val video = trackType == C.TRACK_TYPE_VIDEO
        val audio = trackType == C.TRACK_TYPE_AUDIO
        return when (errorCode) {
            in IO_ERRORS -> network(httpStatus, errorCode, errorCodeName)
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            -> unsupportedFormat(container)
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            -> damaged(container)
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> {
                val noDecoder = !claimed ||
                    errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                    errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                when {
                    video && noDecoder -> noVideoDecoder(format)
                    audio && noDecoder -> noAudioDecoder(format)
                    (video || audio) && errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED -> TvError(
                        PLAYER_ERROR,
                        "Decoding the ${format?.let { "$it " }.orEmpty()}${if (video) "video" else "sound"} failed on this TV (a damaged file?)",
                        format,
                    )
                    else -> playerError(errorCodeName, format)
                }
            }
            in AUDIO_OUTPUT_ERRORS -> TvError(PLAYER_ERROR, "The TV's sound output failed ($errorCodeName)", format)
            in DRM_ERRORS -> TvError(PLAYER_ERROR, "The file is copy-protected (DRM) and can't be played here ($errorCodeName)")
            else -> playerError(errorCodeName, format)
        }
    }

    private val IO_ERRORS = PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
    private val AUDIO_OUTPUT_ERRORS = PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED..PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED
    private val DRM_ERRORS = PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED

    /** [fromPlayerError] for what ExoPlayer reported while playing [media]. */
    @OptIn(UnstableApi::class)
    fun of(error: PlaybackException, media: TvMedia): TvError {
        val renderer = (error as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
        val format = renderer?.rendererFormat
        return fromPlayerError(
            errorCode = error.errorCode,
            errorCodeName = error.errorCodeName,
            trackType = format?.let { MimeTypes.getTrackType(it.sampleMimeType) } ?: C.TRACK_TYPE_UNKNOWN,
            claimed = renderer == null || renderer.rendererFormatSupport == C.FORMAT_HANDLED,
            format = format?.let { formatName(it.sampleMimeType, it.codecs) },
            httpStatus = httpStatusOf(error),
            container = containerName(media.uri, media.mimeType),
        )
    }

    private fun httpStatusOf(error: Throwable): Int? {
        var cause: Throwable? = error
        repeat(8) {
            (cause as? HttpDataSource.InvalidResponseCodeException)?.let { return it.responseCode }
            cause = cause?.cause ?: return null
        }
        return null
    }

    /**
     * A name for a track's format: "10-bit H.264 (High 10)", "10-bit HEVC (Main 10)", "DTS" ... [codecs] is
     * the RFC 6381 string Media3 reads from the file ("avc1.6E0033"); without it just the codec's name.
     */
    fun formatName(sampleMime: String?, codecs: String?): String? {
        val mime = sampleMime?.lowercase()
        val codec = codecs?.split(',')?.map { it.trim().lowercase() }.orEmpty()
        fun part(vararg prefixes: String) = codec.firstOrNull { c -> prefixes.any { c.startsWith(it) } }
        when (mime) {
            MimeTypes.VIDEO_H264, null -> part("avc1.", "avc3.")?.let { c ->
                return when (c.substring(5).take(2).toIntOrNull(16)) {
                    110 -> "10-bit H.264 (High 10)"
                    122 -> "H.264 High 4:2:2"
                    244 -> "H.264 High 4:4:4"
                    else -> "H.264"
                }
            }
            MimeTypes.VIDEO_H265 -> part("hvc1.", "hev1.")?.let { c ->
                // hvc1.<profile space letter><profile>.<compatibility>...: 2 = Main 10, 4 = range extensions (4:2:2, 12-bit).
                return when (c.split('.').getOrNull(1)?.trimStart('a', 'b', 'c')?.toIntOrNull()) {
                    2 -> "10-bit HEVC (Main 10)"
                    4 -> "HEVC Range Extensions"
                    else -> "HEVC"
                }
            }
            MimeTypes.VIDEO_AV1 -> part("av01.")?.let { c ->
                // av01.<profile>.<level><tier>.<bit depth>
                val bits = c.split('.').getOrNull(3)?.toIntOrNull()
                return if (bits != null && bits > 8) "$bits-bit AV1" else "AV1"
            }
            VP9 -> part("vp09.")?.let { c ->
                // vp09.<profile>.<level>.<bit depth>
                val bits = c.split('.').getOrNull(3)?.toIntOrNull()
                return if (bits != null && bits > 8) "$bits-bit VP9" else "VP9"
            }
        }
        return mime?.let { TrackNames.codec(it) }
    }

    /** Media3's MimeTypes.VIDEO_VP9 (marked unstable there). */
    private const val VP9 = "video/x-vnd.on2.vp9"

    /** The file type, for messages: the address's extension ("MKV"), else a name for its MIME type. */
    fun containerName(uri: String, mimeType: String?): String? {
        val ext = uri.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) return ext.uppercase()
        return when (mimeType?.lowercase()?.substringBefore(';')?.trim()) {
            null, "" -> null
            "video/mp4", "audio/mp4" -> "MP4"
            "video/x-matroska", "audio/x-matroska" -> "MKV"
            "video/webm", "audio/webm" -> "WebM"
            "video/mp2t" -> "MPEG-TS"
            "video/mpeg" -> "MPEG"
            "video/x-msvideo", "video/avi" -> "AVI"
            "video/x-flv" -> "FLV"
            "video/quicktime" -> "MOV"
            "video/x-ms-wmv" -> "WMV"
            "video/x-ms-asf" -> "ASF"
            "video/ogg", "audio/ogg" -> "Ogg"
            "application/x-mpegurl", "application/vnd.apple.mpegurl" -> "HLS"
            "application/dash+xml" -> "DASH"
            else -> mimeType.substringAfter('/').substringAfter("x-").substringBefore(';').uppercase().takeIf { it.isNotBlank() }
        }
    }

    /** [error] for the phone's own messages, in the phone's language (the TV's [TvError.message] is English). */
    fun describe(error: TvError): String {
        val f = error.format
        return when (error.code) {
            NO_VIDEO_DECODER -> if (f != null) {
                Txt.sOr(R.string.tv_reason_no_video_decoder, "The TV has no decoder for %1\$s video", f)
            } else {
                Txt.sOr(R.string.tv_reason_no_video_decoder_any, "The TV has no decoder for the picture of this video")
            }
            NO_AUDIO_DECODER -> if (f != null) {
                Txt.sOr(R.string.tv_reason_no_audio_decoder, "The TV has no decoder for %1\$s sound", f)
            } else {
                Txt.sOr(R.string.tv_reason_no_audio_decoder_any, "The TV has no decoder for the sound of this file")
            }
            NO_PICTURE -> Txt.sOr(R.string.tv_reason_no_picture, "The TV can only play the sound: it can't read the picture of this file")
            UNSUPPORTED_FORMAT -> if (f != null) {
                Txt.sOr(R.string.tv_reason_unsupported_format, "The TV can't open this %1\$s file", f)
            } else {
                Txt.sOr(R.string.tv_reason_unsupported_format_any, "The TV can't open this file type")
            }
            NETWORK -> if (error.httpStatus != null) {
                Txt.sOr(R.string.tv_reason_network_http, "The TV couldn't load the file (HTTP %1\$d)", error.httpStatus)
            } else {
                Txt.sOr(R.string.tv_reason_network, "The TV couldn't load the file")
            }
            // The English sentence (with Media3's error name) goes to the debug log; here only what happened.
            else -> Txt.sOr(R.string.tv_reason_player_error, "The TV's player stopped with an error")
        }
    }
}
