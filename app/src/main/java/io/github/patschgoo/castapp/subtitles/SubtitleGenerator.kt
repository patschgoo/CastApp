package io.github.patschgoo.castapp.subtitles

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import io.github.patschgoo.castapp.capture.TranscodeInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import java.io.IOException
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Writes subtitles for a video or audio file that has none: decodes its sound,
 * runs offline speech recognition over it (the fast Vosk or the accurate Whisper
 * engine, see [SpeechEngine]) and turns the words into timed subtitles.
 */
class SubtitleGenerator(private val context: Context) {

    /** The fast engine in [lang] (as before there were several engines). */
    suspend fun generate(input: TranscodeInput, lang: VoskModels.Language, onProgress: (Float, List<Cue>) -> Unit): List<Cue> =
        generate(input, SpeechSetup.Vosk(lang), onProgress)

    /**
     * [onProgress] gets the fraction done (0..1) and all subtitles found so far,
     * roughly every few seconds of work.
     */
    suspend fun generate(input: TranscodeInput, setup: SpeechSetup, onProgress: (Float, List<Cue>) -> Unit): List<Cue> =
        withContext(Dispatchers.Default) {
            if (setup is SpeechSetup.Vosk) LibVosk.setLogLevel(LogLevel.WARNINGS)
            val engine = setup.open(context)
            // The engine works in long native calls: stop it from outside when the job is cancelled.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    engine.cancel()
                }
            }
            val extractor = try {
                input.open(context)
            } catch (e: Exception) {
                watcher.cancel()
                engine.close()
                throw e
            }
            var decoder: MediaCodec? = null
            try {
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw IOException("This file has no sound")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                dec.configure(format, null, null, 0)
                dec.start()
                decoder = dec

                var converter: PcmConverter? = null
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var isFloat = false
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var lastReport = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val inIdx = dec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val size = extractor.readSampleData(dec.getInputBuffer(inIdx)!!, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = dec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        converter = null
                    } else if (outIdx >= 0) {
                        val buf = dec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val b = buf.slice().order(ByteOrder.nativeOrder())
                            val samples = info.size / if (isFloat) 4 else 2
                            val pcm = FloatArray(samples) { i -> if (isFloat) b.getFloat(i * 4) else b.getShort(i * 2) / 32768f }
                            val conv = converter ?: PcmConverter(rate, channels).also { converter = it }
                            val mono = conv.convert(pcm, samples / channels)
                            engine.accept(mono, mono.size)
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 3000 && durationUs > 0) {
                            lastReport = now
                            onProgress((engine.processedSeconds * 1_000_000 / durationUs).toFloat().coerceIn(0f, 1f), engine.cues())
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                engine.finish()
            } finally {
                try { decoder?.stop() } catch (_: Exception) {}
                try { decoder?.release() } catch (_: Exception) {}
                extractor.release()
                watcher.cancel()
                engine.close()
            }
            engine.cues().also { onProgress(1f, it) }
        }
}
