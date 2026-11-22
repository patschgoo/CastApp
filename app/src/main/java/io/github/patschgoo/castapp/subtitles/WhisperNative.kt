package io.github.patschgoo.castapp.subtitles

import java.io.IOException

/** The JNI bridge to whisper.cpp (app/src/main/cpp/whisper_jni.cpp, built into libcastwhisper.so). */
object WhisperNative {
    /** Called from native code while a piece of audio is transcribed (0..100). */
    fun interface Progress {
        fun onProgress(percent: Int)
    }

    @Volatile private var loaded: Boolean? = null

    /** Loads the native library once; throws a readable error when this phone can't run it. */
    @Synchronized
    fun load() {
        if (loaded == true) return
        try {
            System.loadLibrary("castwhisper")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            loaded = false
            throw IOException("The accurate speech engine doesn't run on this phone", e)
        }
    }

    /** A handle for [transcribe], or 0 when the model can't be read. */
    @JvmStatic external fun open(modelPath: String): Long

    @JvmStatic external fun close(handle: Long)

    /** Makes a running (and every later) [transcribe] on [handle] stop as soon as possible. */
    @JvmStatic external fun abort(handle: Long)

    /**
     * Transcribes [count] samples of 16 kHz mono audio (-1..1). [language] is a code like
     * "en" or "auto"; [translate] writes English whatever is spoken. Returns segment lines
     * (see [WhisperSegments.parse]), or null when it failed or was aborted.
     */
    @JvmStatic external fun transcribe(
        handle: Long,
        pcm: FloatArray,
        count: Int,
        language: String,
        translate: Boolean,
        threads: Int,
        progress: Progress?,
    ): String?
}
