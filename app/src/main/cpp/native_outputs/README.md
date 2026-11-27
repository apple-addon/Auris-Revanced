# Phase 3 native outputs

Real, independent PCM-float outputs. This module does **not** modify or replace
`AudioTrack`, Media3's provider, playback settings, or UI.

## Targets

- `AAudioOutput`: blocking `AAudioStream_write` with `AAUDIO_FORMAT_PCM_FLOAT`,
  media usage/content, low-latency performance request, exclusive-first open and
  one shared-open fallback. A non-zero app-selected `AudioDeviceInfo` id is
  requested on the builder and verified on the opened stream; a different
  physical device fails instead of silently using the default. The error
  callback covers disconnect/transport errors and only sets atomic failure
  state (AAudio forbids close/write/pause in that callback). Position prefers
  `AAudioStream_getTimestamp(CLOCK_MONOTONIC)` and falls back to
  `getFramesRead`; XRuns and actual sharing/rate/buffer sizes are queried.
- `OpenSlEsOutput`: `SLAndroidSimpleBufferQueueItf`, four preallocated buffers
  of approximately 10 ms, `SL_ANDROID_DATAFORMAT_PCM_EX` + float representation,
  media stream type, one producer/one consumer lock-free float ring,
  short-buffer/underrun counts, and a submitted-output-frame position clock.
  OpenSL output is always reported shared; no exclusive claim is made.

All short OpenSL buffers are zero-filled. Priming those buffers before playback
is startup silence, not counted as an underrun; short buffers after playback
starts are. Native callback paths allocate no memory, take no mutex, and make
no JNI calls. Volume is applied once while PCM is copied into native storage. A
flush resets position but preserves lifetime XRun/underrun diagnostics.
Format/rate/channel mismatches, queue failures and AAudio disconnects fail
closed; no runtime driver or `AudioTrack` substitution is hidden here.

## Parent CMake integration

From `app/src/main/cpp/CMakeLists.txt`:

```cmake
add_subdirectory(native_outputs)
```

That defines `libauris_native_outputs.so` and its `aaudio`, `OpenSLES`, `log`,
`android`, and `dl` links. The fragment already includes the required 16 KB ELF
link option, so it must not be dropped when copying the target instead:

```cmake
target_link_options(auris_native_outputs PRIVATE "-Wl,-z,max-page-size=16384")
```

APK ZIP alignment remains AGP's responsibility. Minimum Android API is 26.
AAudio's API-28 session-ID entry points are resolved with `dlsym`, not called
unconditionally on API 26/27.

## Kotlin construction

```kotlin
val aaudio = NativeAudioOutput.openAAudio(
    spec,
    requestExclusive = true,
    audioDeviceId = selectedAudioDeviceInfo.id,
)
val opensl = NativeAudioOutput.openOpenSlEs(spec)
```

Both return a Media3 1.11 `AudioOutput`. `openAAudio` retries shared **only when
exclusive open/validation fails**. Any exception must be handled by the future
provider integration; this package deliberately contains no provider or policy
selection. Call `telemetry()` for actual facts and `release()` exactly at the
Media3 lifecycle endpoint.

`NativeOutputSpec.fromMedia3` accepts only non-offloaded, non-tunneled
`ENCODING_PCM_FLOAT` with `usePlaybackParameters=false` (Media3 must apply speed
and pitch upstream). PCM writes consume `ByteBuffer.remaining()`; Media3's integer
write argument is an encoded access-unit count, not a PCM byte count. OpenSL
cannot expose a reliable Android audio session ID,
so that fact is null in telemetry. Position is a stream clock, not a claim about
DAC sample rate or hardware bit depth.

## Standalone native build and core test

An Android build can use this directory directly with the NDK CMake toolchain,
`ANDROID_PLATFORM=android-26`, and the normal `ANDROID_ABI`; no parent edits are
needed to validate compilation.

`tests/native_core_test.cpp` is host-independent and covers ring wraparound,
concurrent SPSC ordering, gain, underrun/output-frame accounting and time
conversion. On a normal C++17 host:

```sh
c++ -std=c++17 -O2 -pthread -Iapp/src/main/cpp/native_outputs \
  app/src/main/cpp/native_outputs/tests/native_core_test.cpp \
  -o native_core_test && ./native_core_test
```

Android-driver behavior itself requires an on-device test; host tests cannot
prove exclusive-mode grants, route disconnects, hardware timestamps, or actual
low latency.
