# Network shares (smbj): its event bus (mbassador) finds handler methods by reflection.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
# Optional parts of those libraries that Android doesn't have (Kerberos log-in, EL filters, logging backend).
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**

# Offline speech recognition (Vosk) binds its native library through JNA, which works by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**

# Whisper speech engine: JNI functions are looked up by class and method name, and the
# native code calls back into WhisperNative.Progress.onProgress.
-keep class io.github.patschgoo.castapp.subtitles.WhisperNative { native <methods>; }
-keep interface io.github.patschgoo.castapp.subtitles.WhisperNative$Progress { void onProgress(int); }
