# Self-contained native output target. The parent may add this directory with
# add_subdirectory(native_outputs); standalone Android builds also work.
cmake_minimum_required(VERSION 3.22.1)
project(auris_native_outputs LANGUAGES CXX)

add_library(auris_native_outputs SHARED
    native_outputs_jni.cpp
    aaudio_output.cpp
    opensl_output.cpp
)

target_include_directories(auris_native_outputs PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)
target_compile_features(auris_native_outputs PRIVATE cxx_std_17)
target_compile_options(auris_native_outputs PRIVATE
    -O3
    -fno-fast-math
    -ffp-contract=off
    -fvisibility=hidden
    -Wall
    -Wextra
    -Wpedantic
)

# Android 15+/Play 16 KB ELF LOAD alignment; also valid on 4 KB systems.
target_link_options(auris_native_outputs PRIVATE "-Wl,-z,max-page-size=16384")

find_library(auris-log-lib log)
find_library(auris-aaudio-lib aaudio)
find_library(auris-opensl-lib OpenSLES)
find_library(auris-android-lib android)
target_link_libraries(auris_native_outputs PRIVATE
    ${auris-log-lib}
    ${auris-aaudio-lib}
    ${auris-opensl-lib}
    ${auris-android-lib}
    dl
)
