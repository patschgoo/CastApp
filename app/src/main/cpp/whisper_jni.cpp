// JNI bridge between io.github.patschgoo.castapp.subtitles.WhisperNative and whisper.cpp.
//
// transcribe() returns one line per segment: "<start ms>\t<end ms>\t<no-speech probability>\t<text>"
// (tabs and line breaks inside the text are replaced by spaces), or null when it failed or was aborted.

#include <jni.h>

#include <atomic>
#include <cstdio>
#include <string>

#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "CastWhisper", __VA_ARGS__)
#else
#define LOGW(...) std::fprintf(stderr, __VA_ARGS__)
#endif

namespace {

struct Handle {
    whisper_context * ctx = nullptr;
    std::atomic<bool> aborted{false};
};

struct ProgressSink {
    JNIEnv * env;
    jobject listener;
    jmethodID method;
};

void log_callback(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR) LOGW("%s", text);
}

void on_progress(whisper_context *, whisper_state *, int progress, void * user_data) {
    auto * sink = static_cast<ProgressSink *>(user_data);
    if (sink->listener == nullptr) return;
    sink->env->CallVoidMethod(sink->listener, sink->method, (jint) progress);
    if (sink->env->ExceptionCheck()) sink->env->ExceptionClear();
}

bool on_abort(void * user_data) {
    return static_cast<Handle *>(user_data)->aborted.load();
}

bool on_encoder_begin(whisper_context *, whisper_state *, void * user_data) {
    return !static_cast<Handle *>(user_data)->aborted.load();
}

void clean(std::string & s) {
    for (auto & c : s) if (c == '\t' || c == '\n' || c == '\r') c = ' ';
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_patschgoo_castapp_subtitles_WhisperNative_open(JNIEnv * env, jclass, jstring model_path) {
    whisper_log_set(log_callback, nullptr);
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context * ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);
    if (ctx == nullptr) return 0;
    auto * handle = new Handle();
    handle->ctx = ctx;
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_io_github_patschgoo_castapp_subtitles_WhisperNative_close(JNIEnv *, jclass, jlong ptr) {
    auto * handle = reinterpret_cast<Handle *>(ptr);
    if (handle == nullptr) return;
    whisper_free(handle->ctx);
    delete handle;
}

JNIEXPORT void JNICALL
Java_io_github_patschgoo_castapp_subtitles_WhisperNative_abort(JNIEnv *, jclass, jlong ptr) {
    auto * handle = reinterpret_cast<Handle *>(ptr);
    if (handle != nullptr) handle->aborted.store(true);
}

JNIEXPORT jstring JNICALL
Java_io_github_patschgoo_castapp_subtitles_WhisperNative_transcribe(
        JNIEnv * env, jclass, jlong ptr, jfloatArray pcm, jint count, jstring language,
        jboolean translate, jint threads, jobject listener) {
    auto * handle = reinterpret_cast<Handle *>(ptr);
    if (handle == nullptr || handle->aborted.load()) return nullptr;
    if (count <= 0 || count > env->GetArrayLength(pcm)) return env->NewStringUTF("");

    const char * lang_chars = env->GetStringUTFChars(language, nullptr);
    std::string lang = lang_chars;
    env->ReleaseStringUTFChars(language, lang_chars);

    ProgressSink sink{env, listener, nullptr};
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        sink.method = env->GetMethodID(cls, "onProgress", "(I)V");
        env->DeleteLocalRef(cls);
        if (sink.method == nullptr) {
            env->ExceptionClear();
            sink.listener = nullptr;
        }
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.translate = translate == JNI_TRUE;
    params.language = lang.empty() ? "auto" : lang.c_str();
    params.detect_language = false;
    params.no_context = true;          // every chunk on its own: no repeated text across chunks
    params.suppress_nst = true;        // no "[music]" / "(applause)" style tokens
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.progress_callback = on_progress;
    params.progress_callback_user_data = &sink;
    params.abort_callback = on_abort;
    params.abort_callback_user_data = handle;
    params.encoder_begin_callback = on_encoder_begin;
    params.encoder_begin_callback_user_data = handle;

    jfloat * samples = env->GetFloatArrayElements(pcm, nullptr);
    int rc = whisper_full(handle->ctx, params, samples, count);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (rc != 0 || handle->aborted.load()) return nullptr;

    std::string out;
    const int n = whisper_full_n_segments(handle->ctx);
    char times[96];
    for (int i = 0; i < n; i++) {
        std::string text = whisper_full_get_segment_text(handle->ctx, i);
        clean(text);
        const long long t0 = (long long) whisper_full_get_segment_t0(handle->ctx, i) * 10;
        const long long t1 = (long long) whisper_full_get_segment_t1(handle->ctx, i) * 10;
        std::snprintf(times, sizeof(times), "%lld\t%lld\t%.3f\t", t0, t1,
                      whisper_full_get_segment_no_speech_prob(handle->ctx, i));
        out += times;
        out += text;
        out += '\n';
    }
    // NewStringUTF wants modified UTF-8; go through a byte array so any UTF-8 text survives.
    jbyteArray bytes = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = (jstring) env->NewObject(string_class, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_class);
    return result;
}

}  // extern "C"
