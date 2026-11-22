package io.github.patschgoo.castapp.capture

import android.content.Context
import android.media.MediaFormat
import io.github.patschgoo.castapp.service.AudioTrackInfo
import io.github.patschgoo.castapp.service.TextTrackInfo
import io.github.patschgoo.castapp.service.TrackList
import io.github.patschgoo.castapp.stream.MediaSource
import io.github.patschgoo.castapp.subtitles.EmbeddedSubtitles

/** Lists the sound and subtitle tracks of a video file. */
object TrackProbe {
    /**
     * Sound tracks come from Android's MediaExtractor (the same one the converter
     * uses, so the numbering matches); subtitle tracks from Media3's extractors,
     * which also read SRT/ASS/WebVTT/tx3g inside MKV and MP4.
     */
    fun probe(context: Context, input: TranscodeInput, source: MediaSource?): TrackList {
        val audio = try { audioTracks(context, input) } catch (_: Exception) { emptyList() }
        val text: List<TextTrackInfo> = try { source?.let { EmbeddedSubtitles.list(it) }.orEmpty() } catch (_: Exception) { emptyList() }
        return TrackList(audio, text)
    }

    fun audioTracks(context: Context, input: TranscodeInput): List<AudioTrackInfo> {
        val ex = input.open(context)
        try {
            val list = ArrayList<AudioTrackInfo>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                list += AudioTrackInfo(
                    ordinal = list.size,
                    language = if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else null,
                    mimeType = mime,
                    channels = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0,
                )
            }
            return list
        } finally {
            ex.release()
        }
    }
}
