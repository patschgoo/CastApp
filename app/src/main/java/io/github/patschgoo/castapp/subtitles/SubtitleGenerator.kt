package io.github.patschgoo.castapp.subtitles

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import io.github.patschgoo.castapp.capture.TranscodeInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Writes subtitles for a video or audio file that has none: decodes its sound,
 * runs offline speech recognition (Vosk) over it and turns the words into timed
 * subtitles. Runs much faster than real time on a modern phone.
 */
class SubtitleGenerator(private val context: Context) {

    /**
     * [onProgress] gets the fraction done (0..1) and all subtitles found so far,
     * roughly every few seconds of work.
     */
    suspend fun generate(input: TranscodeInput, lang: VoskModels.Language, onProgress: (Float, List<Cue>) -> Unit): List<Cue> =
        withContext(Dispatchers.Default) {
            if (!VoskModels.isInstalled(context, lang)) throw IOException("The ${lang.label} language isn't downloaded")
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val extractor = input.open(context)
            var decoder: MediaCodec? = null
            val words = ArrayList<Word>()
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

                Model(VoskModels.modelDir(context, lang).absolutePath).use { model ->
                    Recognizer(model, 16_000f).use { recognizer ->
                        recognizer.setWords(true)
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
                                    if (recognizer.acceptWaveForm(mono, mono.size)) words += CueBuilder.wordsFromVosk(recognizer.result)
                                }
                                dec.releaseOutputBuffer(outIdx, false)
                                val now = System.currentTimeMillis()
                                if (now - lastReport > 3000 && durationUs > 0) {
                                    lastReport = now
                                    onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f), CueBuilder.build(words))
                                }
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                            }
                        }
                        words += CueBuilder.wordsFromVosk(recognizer.finalResult)
                    }
                }
            } finally {
                try { decoder?.stop() } catch (_: Exception) {}
                try { decoder?.release() } catch (_: Exception) {}
                extractor.release()
            }
            CueBuilder.build(words).also { onProgress(1f, it) }
        }
}
