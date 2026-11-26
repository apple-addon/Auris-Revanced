# Native analysis front end for Automix.
#
# Phase 1: whole-track DSP analysis (tempo/key/energy/structure via
# autocorrelation, no ML model). Phase 2 adds the log-mel front end the
# Beat This! ONNX beat/downbeat model reads (mel_spectrogram.cpp / mel_jni.cpp).
# Phase 3 adds the linear-frequency STFT the open-unmix vocal-separation model
# reads (vocal_spectrogram.cpp / vocal_jni.cpp). Both models themselves run
# through ONNX Runtime on the Kotlin side, not here. The shared analyzer
# sources live under the repo's root `native/` tree rather than here, so a
# later phase adding a Rubber Band-based transition renderer can add sources
# to that same tree without duplicating this module.

cmake_minimum_required(VERSION 3.22.1)
project(bitchord_analysis LANGUAGES C CXX)

get_filename_component(BITCHORD_NATIVE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native"
    ABSOLUTE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(bitchord_analysis SHARED
    ${BITCHORD_NATIVE_DIR}/analyzer/audio_analysis.cpp
    ${BITCHORD_NATIVE_DIR}/analyzer/tempo_analysis.cpp
    ${BITCHORD_NATIVE_DIR}/analyzer/resampler.cpp
    ${BITCHORD_NATIVE_DIR}/analyzer/mel_spectrogram.cpp
    ${BITCHORD_NATIVE_DIR}/analyzer/vocal_spectrogram.cpp
    jni/analysis_jni.cpp
    jni/mel_jni.cpp
    jni/vocal_jni.cpp
)

target_include_directories(bitchord_analysis PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${BITCHORD_NATIVE_DIR}
)

# -ffast-math is deliberately NOT enabled: the analyzer's log/mel-adjacent
# arithmetic (see audio_analysis.cpp) is tuned against exact IEEE behaviour.
target_compile_options(bitchord_analysis PRIVATE -O3 -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(bitchord_analysis ${log-lib})

# 16KB page-size support (Android 15+ 16KB kernels, Play requirement): align
# LOAD segments to 16KB. A superset of 4KB alignment, so 4KB devices are
# unaffected. APK-side alignment of uncompressed .so files is handled by AGP.
target_link_options(bitchord_analysis PRIVATE "-Wl,-z,max-page-size=16384")

# DSD playback (DSF/DFF). The decimator turns 1-bit DSD into float PCM for
# DsdExtractor; DST is the lossless compression SACD rips use inside DFF.
# DST is LGPL (see dsd/dst/NOTICE.md), so it is a library of its own that can
# be rebuilt or swapped without touching the rest.
add_library(bitchord_dsd SHARED dsd/dsd_decimator.cpp)
target_compile_options(bitchord_dsd PRIVATE -O3 -fno-fast-math -ffp-contract=off -fvisibility=hidden)
target_link_options(bitchord_dsd PRIVATE "-Wl,-z,max-page-size=16384")

add_library(bitchord_dst SHARED dsd/dst/decoder.c dsd/dst/jni.cpp)
target_compile_options(bitchord_dst PRIVATE -O3 -fno-strict-aliasing -fvisibility=hidden)
target_link_options(bitchord_dst PRIVATE "-Wl,-z,max-page-size=16384")

# Tracker decoding through the vendored libopenmpt 0.8.10 sources.
# These directories and four C API implementation files exactly match
# native/libopenmpt/build/android_ndk/Android.mk. In particular, do not sweep in
# libopenmpt/in_openmpt (Windows-only) or libopenmpt/libopenmpt_test.
file(GLOB OPENMPT_COMMON_SOURCES CONFIGURE_DEPENDS
    "${BITCHORD_NATIVE_DIR}/libopenmpt/common/*.cpp"
)
file(GLOB OPENMPT_SOUNDLIB_SOURCES CONFIGURE_DEPENDS
    "${BITCHORD_NATIVE_DIR}/libopenmpt/soundlib/*.cpp"
    "${BITCHORD_NATIVE_DIR}/libopenmpt/soundlib/plugins/*.cpp"
    "${BITCHORD_NATIVE_DIR}/libopenmpt/soundlib/plugins/dmo/*.cpp"
)
file(GLOB OPENMPT_SOUNDDSP_SOURCES CONFIGURE_DEPENDS
    "${BITCHORD_NATIVE_DIR}/libopenmpt/sounddsp/*.cpp"
)
set(OPENMPT_SOURCES
    ${OPENMPT_COMMON_SOURCES}
    ${OPENMPT_SOUNDLIB_SOURCES}
    ${OPENMPT_SOUNDDSP_SOURCES}
    "${BITCHORD_NATIVE_DIR}/libopenmpt/libopenmpt/libopenmpt_c.cpp"
    "${BITCHORD_NATIVE_DIR}/libopenmpt/libopenmpt/libopenmpt_cxx.cpp"
    "${BITCHORD_NATIVE_DIR}/libopenmpt/libopenmpt/libopenmpt_impl.cpp"
    "${BITCHORD_NATIVE_DIR}/libopenmpt/libopenmpt/libopenmpt_ext_impl.cpp"
)
add_library(bitchord_tracker SHARED
    tracker/tracker_decoder.cpp
    tracker/tracker_jni.cpp
    ${OPENMPT_SOURCES}
)
target_compile_features(bitchord_tracker PRIVATE cxx_std_17)
target_compile_definitions(bitchord_tracker PRIVATE LIBOPENMPT_BUILD MPT_WITH_ZLIB)
target_include_directories(bitchord_tracker PRIVATE
    "${BITCHORD_NATIVE_DIR}/libopenmpt"
    "${BITCHORD_NATIVE_DIR}/libopenmpt/src"
    "${BITCHORD_NATIVE_DIR}/libopenmpt/common"
    "${BITCHORD_NATIVE_DIR}/libopenmpt/include"
    "${BITCHORD_NATIVE_DIR}/libopenmpt/build"
)
target_compile_options(bitchord_tracker PRIVATE -O3 -fvisibility=hidden -fexceptions -frtti)
target_link_options(bitchord_tracker PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(bitchord_tracker z ${log-lib})

# Native AAudio and OpenSL ES Float32 outputs, each with a lock-free ring.
add_library(bitchord_audio_outputs SHARED
    outputs/aaudio_output.cpp
    outputs/opensles_output.cpp
    outputs/outputs_jni.cpp
)
target_compile_features(bitchord_audio_outputs PRIVATE cxx_std_17)
target_compile_options(bitchord_audio_outputs PRIVATE -O3 -fno-fast-math -fvisibility=hidden)
target_link_options(bitchord_audio_outputs PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(bitchord_audio_outputs aaudio OpenSLES ${log-lib})

# Self-contained 64-bit reference DSP, fed directly by ExoPlayer buffers.
add_library(bitchord_aurisdsp SHARED
    aurisdsp/AurisDspEngine.cpp
    aurisdsp/AurisDspJni.cpp
    aurisdsp/MsebMapping.cpp
)
target_compile_features(bitchord_aurisdsp PRIVATE cxx_std_17)
target_compile_options(bitchord_aurisdsp PRIVATE -O3 -fno-fast-math -ffp-contract=off -fvisibility=hidden)
target_link_options(bitchord_aurisdsp PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(bitchord_aurisdsp ${log-lib})

# JamesDSP's native engine, running in-process on BitChord's own Float32
# playback stream. See jamesdsp/CMakeLists.txt and JamesDspProcessor.kt.
add_subdirectory(jamesdsp)
