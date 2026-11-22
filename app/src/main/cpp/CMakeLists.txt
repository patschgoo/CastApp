# Native part of the optional "Accurate (Whisper)" speech engine.
#
# whisper.cpp (MIT) is vendored under whisper.cpp/ (pruned to the CPU backend; see VERSION and
# update-whisper.sh), so the build needs no network. Only the CPU backend is built, as static
# libraries linked into one small JNI library, libcastwhisper.so (about 1.8 MB arm64 / 1.3 MB armv7
# unpacked, 0.75 + 0.64 MB compressed in the APK). The speech models themselves are downloaded
# in the app (WhisperModels.kt), never bundled.
#
# Desktop check: cmake -S app/src/main/cpp -B /tmp/whisper-host -G Ninja && cmake --build /tmp/whisper-host
cmake_minimum_required(VERSION 3.22.1)
project(castwhisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ggml / whisper options: CPU only, static, no tools.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)        # never tune for the build machine
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)        # ggml's own thread pool; no libomp to ship
if (ANDROID_ABI STREQUAL "armeabi-v7a")
    # llamafile's matrix code needs half-precision NEON, which 32-bit ARM builds don't have.
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
else ()
    set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
endif ()
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
# Baseline instruction sets only (armv8-a / armv7-a NEON), so it runs on every phone that
# installs the app; newer CPU features are not required.
set(GGML_CPU_ARM_ARCH "" CACHE STRING "" FORCE)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
add_subdirectory(${WHISPER_DIR}/ggml ggml EXCLUDE_FROM_ALL)

add_library(castwhisper SHARED
    whisper_jni.cpp
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp
)
target_include_directories(castwhisper PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/src)
file(STRINGS ${WHISPER_DIR}/VERSION WHISPER_VERSION_LINE LIMIT_COUNT 1)
string(REGEX MATCH "^v?([0-9.]+)" _ "${WHISPER_VERSION_LINE}")
target_compile_definitions(castwhisper PRIVATE WHISPER_VERSION="${CMAKE_MATCH_1}")

# Optimised in every build type: an unoptimised Whisper is far too slow to try out even in debug builds.
target_compile_options(castwhisper PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden
                                           -ffunction-sections -fdata-sections)
foreach (t ggml ggml-base ggml-cpu)
    target_compile_options(${t} PRIVATE -O3 -ffunction-sections -fdata-sections)
endforeach ()
target_link_options(castwhisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

if (ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(castwhisper PRIVATE ggml ${LOG_LIB})
    # 16 KB page sizes (Android 15+ devices).
    target_link_options(castwhisper PRIVATE -Wl,-z,max-page-size=16384)
else ()
    # Desktop test build (see SpeechEngine tests): needs the JDK's jni.h.
    find_package(JNI REQUIRED)
    target_include_directories(castwhisper PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(castwhisper PRIVATE ggml)
endif ()
