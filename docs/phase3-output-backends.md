# Phase 3 — output backends and exclusive settings

Status: implemented and host/static verified. Hardware behaviour that only a
real Android device and DAC can prove remains explicitly device-only below.

## Backends

| Driver | Real path | Honest failure/fallback |
|---|---|---|
| AudioTrack | Media3 AudioTrack provider | Shared mixer; stock behaviour |
| Hi-Res | AudioTrack direct-profile probe through `getDirectPlaybackSupport` / `getDirectProfilesForAttributes`; direct flag requested only for an exact format match | Shared fallback is labelled shared/unverified |
| USB Exclusive | Userspace `usbdevfs` through the decent USB driver | Opens only when permission, alt setting and DAC clock readback succeed; PCM may fall back to AudioTrack, packed DSD/DoP fails closed |
| USB System Bit-Perfect | API 34+ `AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT` | Unsupported on older API levels or when the framework refuses the exact format |
| AAudio | Native Float32 AAudio; device-id routing; exclusive low-latency request with shared fallback | Actual sharing mode, rate, buffer and XRuns come from AAudio; a different opened device fails |
| OpenSL ES | Native Float32 `SLAndroidSimpleBufferQueue`; four ~10 ms preallocated buffers; SPSC ring | Always reported shared; queue/transport errors fail closed |

HiBy/Hybrid AudioTrack output advertises PCM16 only, rejects offload/tunnelling,
and uses native `nowConsumed` evidence for position/activity. Written frames are
never substituted for played frames.

## Exclusive settings

- One canonical `OutputDriver` scalar controls every local output row.
- Local route selection is one-hot and uses radio/check semantics.
- Chromecast remains an independent Cast route, not an `OutputDriver`.
- The USB route exposes both userspace USB Exclusive and Android System
  Bit-Perfect without conflating them.
- A newly selected route is never shown as live `Active`; runtime device and
  backend evidence must promote it.
- USB System Bit-Perfect participates in the direct-path conflict policy, so
  enabling Quality Options first retires the direct output and vice versa.
- Cancelling a conflict restores the already committed route/backend.

## Verification

Commands used (offline, 12 GiB Gradle heap, 4 workers, in-process Kotlin):

```sh
./gradlew --offline --no-daemon --max-workers=4 \
  -Dorg.gradle.jvmargs='-Xmx12g -XX:MaxMetaspaceSize=2g -Dfile.encoding=UTF-8' \
  :app:externalNativeBuildDevDebug

./gradlew --offline --no-daemon --max-workers=4 \
  -Dorg.gradle.jvmargs='-Xmx12g -XX:MaxMetaspaceSize=2g -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDevDebugUnitTest :app:assembleDevDebug
```

Results:

- Full JVM suite: 1,814 tests, 0 failures, 0 errors, 1 skipped.
- Native host output-core test: passed.
- ARM64 packaging audit: 23 shared objects, all `PT_LOAD` aligned to `0x4000`,
  RELRO + BIND_NOW, no stale `libstdc++.so`, no unresolved `DT_NEEDED`, no
  legacy Poweramp payload.
- Debug APK:
  `app/build/outputs/apk/dev/debug/app-dev-arm64-v8a-debug.apk`
- SHA-256:
  `b7f89e2c6f3ac0df3eb7f66e1dc552e1f78a32b0343e7ef78a28368624097070`

## Device-only validation

These cannot be proven on the x86_64 build host:

- whether a particular HAL grants AAudio exclusive/MMAP and which actual device
  AAudio opens;
- whether an OEM AudioTrack builder accepts the direct flag for a named profile;
- USB DAC permission, UAC2 raw-DSD alt availability, feedback cadence, volume
  control readback and mid-track clock behaviour;
- Android 14+ mixer bit-perfect acceptance for a specific DAC;
- actual HiBy `OtHiByTrackUtils.nowConsumed` cadence on hardware.

All of these paths fail closed or report the unverified/failure state rather than
substituting a different driver silently.
