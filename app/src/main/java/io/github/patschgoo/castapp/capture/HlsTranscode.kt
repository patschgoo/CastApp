package io.github.patschgoo.castapp.capture

import android.content.Context
import android.util.Log
import io.github.patschgoo.castapp.stream.FragmentedMp4
import io.github.patschgoo.castapp.stream.HlsConversion
import io.github.patschgoo.castapp.stream.InterleavingQueue
import io.github.patschgoo.castapp.stream.VodSegmenter

/**
 * Converts [input] from [startMs] into the segments of [conversion] (HLS for Apple
 * TV and Roku). Returns how to stop it. The converter pauses by itself while it is
 * far ahead of what the TV has fetched.
 */
fun startHlsTranscode(
    context: Context,
    input: TranscodeInput,
    startMs: Long,
    maxHeight: Int,
    audioTrack: Int,
    conversion: HlsConversion,
): () -> Unit {
    val queue = InterleavingQueue(setOf(FragmentedMp4.VIDEO_TRACK_ID, FragmentedMp4.AUDIO_TRACK_ID))
    val transcoder = Transcoder(context, input, startMs * 1000, queue, maxHeight = maxHeight, audioTrackOrdinal = audioTrack)
    transcoder.hub.subscribe(queue)
    transcoder.start()
    val store = conversion.store
    val pump = Thread({
        try {
            val init = transcoder.hub.awaitInitSegment(30_000)
            if (init == null) {
                store.finish(error = true)
                return@Thread
            }
            store.setInit(init)
            val segmenter = VodSegmenter(
                transcoder.hub.publishedAudioRate,
                requestKeyFrame = { transcoder.hub.requestKeyFrame() },
            ) { durationUs, data -> store.add(durationUs, data) }
            while (!conversion.stopped && !queue.isFinished) {
                if (!store.awaitRoom(500)) continue
                val sample = queue.take(500) ?: continue
                segmenter.add(sample)
            }
            if (!conversion.stopped) {
                segmenter.finish()
                store.finish(error = transcoder.error != null && store.count == 0)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w("HlsTranscode", "HLS conversion failed", e)
            store.finish(error = true)
        }
    }, "hls-convert").apply {
        isDaemon = true
        start()
    }
    return {
        transcoder.stop()
        pump.interrupt()
    }
}
