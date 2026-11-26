# Standalone Auris DSP engine

This directory contains a self-contained C++17 PCM engine. It has no host
player, Android framework, Media3, JamesDSP, or external DSP-library dependency.
The only Android-specific source is the thin JNI bridge in `AurisDspJni.cpp`.

## Sources for the parent integration

Add these sources to the parent's chosen native target; this change deliberately
does not modify an existing CMake or Gradle file:

- `AurisDspEngine.cpp`
- `MsebMapping.cpp`
- `AurisDspJni.cpp` (Android/JNI target only)

Public headers are `AurisDspEngine.h`, `AurisDspParams.h`, `Biquad.h`, and
`MsebMapping.h`. `AurisDspParams.h` is the stable 94-float Kotlin ABI, including
all indices, defaults, units, ranges, PEQ strides, and enum numeric values.

The expected JNI owner is `com.music.bitchord.playback.audio.AurisDspNative`:

```kotlin
external fun nativeCreate(): Long
external fun nativeConfigure(handle: Long, sampleRate: Int, channels: Int, maxFrames: Int): Boolean
external fun nativeProcess(handle: Long, directFloatBuffer: ByteBuffer, frames: Int)
external fun nativeSetParams(handle: Long, params: FloatArray)
external fun nativeReset(handle: Long)
external fun nativeDestroy(handle: Long)
external fun nativeGetVersionHash(): String
```

Pass a zero-position direct Float32 buffer view with native sample layout,
interleaved as configured, with capacity for `frames * channels` floats measured
from the direct buffer address. JNI uses the address directly and does not alter
position/limit. `frames` must not exceed `maxFrames`; an invalid call is a safe
no-op. `nativeSetParams` requires exactly `PARAM_COUNT` entries. Destruction and
reconfiguration must not race processing. Parameter publication may race
processing and uses a lock-free atomic seqlock snapshot.

## Processing contract

- One through eight interleaved channels; Float32 input/output.
- Conversion to Float64 happens once per active input block and back once at
  output. Filter arithmetic, coefficients, delay lines, gains, and limiter
  states are all double precision.
- The work buffer and every reverb line are allocated by `nativeConfigure`.
  Processing does no heap allocation, mutex locking, array copying through JNI,
  or thread attach/detach.
- All seven biquads use RBJ cookbook coefficients and transposed direct form II.
  Frequencies are clamped to `0.45 * sampleRate` for stable imported presets.
- `OFF` and `BIT_PERFECT` return before reading or writing any PCM sample. This
  preserves signed zero, NaN payload bits, infinities, and denormals exactly.
- The limiter is channel-linked, with zero attack and exponential release.
- Reverb is an independent-per-channel pre-delay, four-comb feedback network,
  wet low-pass filter, and two all-pass diffusers. All seven exposed controls
  affect the network.

Mode order is:

```text
POWERAMP:  graphic EQ/preamp -> bass/treble -> limiter -> reverb -> DVC trim
HIBY:      MSEB/preamp -> PEQ/preamp
HYBRID:    MSEB/preamp -> PEQ/preamp
           -> graphic EQ/preamp -> bass/treble -> reverb -> DVC trim
           -> one final limiter
OFF / BIT_PERFECT: exact untouched bypass
```

DVC is a PCM trim here, not an Android device-volume operation. Poweramp-shaped
stages use standard clean-room curves. MSEB uses the recovered 13-section
initialization metadata and verified standard RBJ formulas documented below;
analytic host tests alone are not a full-library bit-identity demonstration.

## Replaceable MSEB boundary

`buildMsebMapping` in `MsebMapping.cpp` is the only function containing MSEB
section frequencies, Qs, gains, and dimension-to-section mapping. It contains
the recovered thirteen filters: four simultaneous temperature shelves and nine
independent dimension sections. Normalized JNI values are converted to raw
`-100..+100` sliders there; range changes preserve those raw values. The shared
`Biquad` implements the verified RBJ formulas. Keep future reference revisions
in that one function. See `tools/aurisdsp/HIBY_MSEB_RECOVERED.md` for the source
table and `MSEB_DATA_REQUIREMENTS.md` for the remaining direct native PCM
validation boundary.

## Host-only verification

From the repository root:

```sh
bash tools/aurisdsp/build_and_test.sh
SANITIZE=1 bash tools/aurisdsp/build_and_test.sh
```

The script invokes host `g++`, not Gradle or an APK build. Tests cover recovered
MSEB constants and analytic impulse responses, all seven generic filter types,
PEQ sine/RMS behavior, preamps, raw-slider range preservation, exact bypass/null
behavior, limiter ceilings, Hybrid final limiting, chunked reverb tails, reset
determinism, parameter sanitization, concurrent seqlock snapshots, invalid sizes,
and eight-channel processing.
