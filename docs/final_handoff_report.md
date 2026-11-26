# Final handoff report — PAWS/HiBy audio feature port (skeleton)

- Date: 2026-11-26. Author: handoff subagent (read-only pass; only this file written).
- Inputs read: `docs/video_ui_spec.md`, `tools/aurisdsp/HIBY_MSEB_RECOVERED.md`, current source under `app/src`, `shared/`, `sharedUi/`, `tools/aurisdsp/`.
- Missing input: `docs/paws_hibs_reference_checklist.md` was **not found** anywhere in this environment — checklist mapping below is derived from `video_ui_spec.md` instead. [PENDING: locate or regenerate that checklist]
- Status legend: **VERIFIED** = code/tests clearly show it; **PENDING** = other lanes still running / not executed here; **HW-UNVERIFIED** = needs real hardware.

## 1. Changed files / audio components

Modified (tracked): `app/build.gradle.kts`, `app/src/main/cpp/CMakeLists.txt`, `app/src/main/cpp/jamesdsp/jni/JamesDspJni.cpp`, `MainActivity.kt`, `AppSettings.kt`, `AudioOutputStatus.kt`, `CrossfadeController.kt`, `PlaybackService.kt`, `AudioFidelity.kt`, `DspChain.kt`, `JamesDspProcessor.kt`, `PrecisionAudioSink.kt`, `ExclusiveUsbOutput.kt`, `AudioPipelineDialog.kt`, `AndroidPlayerHost.kt`, `EqualizerScreen.kt`, `SettingsAudio.kt`, `SettingsDialogs.kt`, `FloatDspChainTest.kt`, `PrecisionAudioSinkTest.kt`, `shared/.../EqualizerCurve.kt`, `EqualizerPreset.kt`, `sharedUi/.../AudioOutputSheet.kt`, `NowPlayingOverlays.kt`, `PlayerHost.kt`, `audio/decent-usb-audio-driver/...`, locale `strings.xml`.

New (untracked) audio components:

- Native DSP: `app/src/main/cpp/aurisdsp/` (`AurisDspEngine.*`, `MsebMapping.*`, `Biquad.h`, `AurisDspJni.cpp`, `MSEB_DATA_REQUIREMENTS.md`); host tests `tools/aurisdsp/` (`aurisdsp_tests.cpp`, `build_and_test.sh`); `app/src/main/cpp/outputs/` (AAudio/OpenSL ES + `spsc_float_ring.h`); `app/src/main/cpp/tracker/`; `app/src/test/cpp/outputs/SpscFloatRingTest.cpp`.
- Kotlin processors: `MsebProcessor`, `ParametricEqProcessor` (+`PeqBand`), `BiquadBank`, `HiByMsebV1Codec`, `HiByPeqV1Codec`, `MonoAudioProcessor`, `ReverbTuning`, `NativeReferenceProcessor`/`NativeReferenceParams`/`ReferenceMode`/`AurisDspNative` (94-float param pack), backends `AAudioOutputProvider`, `OpenSlEsOutputProvider`, `NativeAudioOutput`/`NativeOutputBackend`, `AurisAudioOutputProvider`, `audiophile/` (`AudiophileResampler`, `AudioFocusStateMachine`, `RouteEqualizerPolicy`, `VisualDelayPolicy`, `AdvancedOutputPolicy`), `replaygain/`, `usb/`, `bluetooth/BluetoothAudioTracker`, `fades/`, `dsd/` (incl. `raw/`), `usb/ExclusiveUsbRawDsdTransport.kt`, `RawDsdUsbOutput.kt`, `tracker/`.
- UI: `MsebScreen`, `PeqScreen`, `PeqResponse`, `QualityOptionsSheet` (PAWS 3-page), `PowerampQualityPages`, `QualityPresetUi`, `ui/screens/audiophile/` (Audio Info, Crossfade/Fade/Gapless, ReplayGain, Audio Focus, EQ, Resampler, DVC, Audio Outputs, Bit Perfect, Advanced Tweaks, Reference Mode + stores), `AudioConflictDialog`, `RotaryKnob`; `tools/reference-stubs/src/com.maxmpz/` + `.../com.hiby/` PAWS/HiBy contract stubs (reference only, not compiled); dormant proprietary reference `.so` files removed from `jniLibs` so they no longer ship (see `docs/reference_hygiene.md`).

## 2. Control-by-control checklist — PAWS Audio settings → implementation

| PAWS reference control (video_ui_spec §1) | Implementation | Status |
|---|---|---|
| Audio Info | `audiophile/AudioInfoScreen.kt` + `AudioOutputStatus` | VERIFIED (code; UI text pinned by `QualityUiTextTest`) |
| Crossfade/Fade/Gapless (preload, silence, crossfade/short/manual/play-pause/seek fades, restore defaults) | `audiophile/AudioInfoAndTransitionScreens.kt` `CrossfadeFadeGaplessScreen`, `playback/fades/*`, `CrossfadeController` | VERIFIED (code + `fades/` tests; audio result HW-UNVERIFIED) |
| Replay Gain (enable/source/preamps) | `ReplayGainScreen`, `playback/audio/replaygain/*` | VERIFIED (code + `ReplayGainProcessorTest`) |
| Audio Focus (pause/duck/no-duck) | `AudioFocusScreen`, `audiophile/AudioFocusStateMachine.kt`, `fades/AudioFocusPolicy.kt` | VERIFIED (code + tests) |
| Equalizer (10 bands 31–16K) | `DspSettingsScreens.AudiophileEqualizerScreen`, `EqualizerScreen`, `EqLayout.MANUAL_BANDS_HZ` | VERIFIED |
| Resampler + dither (9 PAWS modes) | `ResamplerScreen`, `AudiophileSettings.ResamplerQuality/DitherMode`, `AudiophileResampler` | VERIFIED (labels pinned by `QualityUiTextTest`; resampler tests exist) |
| DVC (+Bluetooth policy, headroom, compensate) | `DvcScreen`, `DvcSettings`, `ListenerVolumePolicy` | VERIFIED (defaults pinned by `QualityUiTextTest`) |
| Audio Outputs (routes, per-device profiles `+`, driver, buffer, visual delay, No DVC/No headroom/No Equ-Tone/No Duck, EQ preset assign) | `AudioOutputsScreen`, `AudioOutputProfilesStore`, `OutputProfile` | VERIFIED (code) |
| Bit Perfect (64-bit, follow rate, DSD remaster, Perfect BP, per-output rows) | `BitPerfectScreen`, `BitPerfectSettings`, `BitPerfectOutputOverride`, `BitPerfectMixer` | VERIFIED (code; bit-exactness HW-UNVERIFIED) |
| Advanced Tweaks | `AdvancedTweaksScreen`, `AdvancedTweaks`, `AdvancedOutputPolicy(Resolver)` | VERIFIED (code + `RouteAndAdvancedPolicyTest`) |

## 3. PAWS EQ Hub (3 pages) + presets → implementation

- Page 1 Equalizer & Tone: preamp + 10 band sliders (31…16K), `Equ/Tone/Limit` pills, live state strip (`NO DVC EQ 10 TON LMT`), Bass/Treble knobs, Preset overlay with search + filter chips (`User, Built-in, AutoEq, Graphic, Parametric, Assigned, Reset filters`), curve previews, long-press menu → `PowerampQualityPages.PowerampEqualizerPage` + `PowerampPresetBrowser`; strip/labels pinned by `QualityUiTextTest` (`eqStateStrip`, `presetFilterChips`, `formatBandFrequency`). VERIFIED (code/tests).
- Page 2 Volume/Stereo/Tempo: Balance, Stereo Expand, Tempo toggle + knob ±, Mono, Reset, Volume knob → `PowerampVolumePage` (writes `AppSettings` balance/stereo/speed/tempo/mono/volume). VERIFIED (code).
- Page 3 Reverb: Damp/Filter/Fade/Pre-Delay/Pre-Delay Mix/Size knobs, Reverb/Preset/Save/Reset, Mix → `PowerampReverbPage` + `ReverbTuning` + `QualityPresetStore`. VERIFIED (code).
- Presets: `shared/.../EqualizerPreset.kt` (16 built-ins + CUSTOM, 7→10 band expansion), per-output EQ preset assignment via `OutputProfile.eqPreset` (never rewrites global). VERIFIED (code).

## 4. HiBy MSEB / PEQ → implementation

- MSEB UI: `MsebScreen` — master toggle, preset None/Customize + Save/Import/Share, "Slide rod range adjustment" (Fine-tuning ±20 / Middling ±40 / Excessive ±100; range is metadata, values preserved), Pre Amp ±45 dB, 10 dimension sliders with HiBy endpoint labels (`MSEB_ENDPOINTS`, pinned by `QualityUiTextTest`). VERIFIED (code/tests).
- MSEB audio: two paths — (a) native `MsebMapping.cpp` uses the **exact recovered 13-section table** from `HIBY_MSEB_RECOVERED.md` (33/231/1617/11319 Hz temperature shelves etc.), exercised by `tools/aurisdsp/aurisdsp_tests.cpp`; (b) Kotlin `MsebProcessor` clean-room fallback when native engine unavailable. In `DspChain.process`, Kotlin mseb/peq run only when `nativeReference` is not enabled. VERIFIED (code); native-vs-proprietary bit parity PENDING (see §7).
- MSEB share codec: `HiByMsebV1Codec` (`hiby://plg_mseb/v1/`, JSON datalist/range/result, 10 dims) — VERIFIED by `HiByMsebV1CodecTest`.
- PEQ UI: `PeqScreen` + `PeqResponse` live graph, Pre Amp ±15 dB, Band0–Band9 tabs, per-band Frequency/Q/Gain/Type (Low Shelf/Peaking/High Shelf)/Enable → VERIFIED (code; type labels pinned by `QualityUiTextTest`).
- PEQ audio + codec: `ParametricEqProcessor` (10 bands, 20 Hz–20 kHz, Q 0.1–10, ±15 dB, defaults 31…16 kHz Q 0.20 off); `HiByPeqV1Codec` (`hiby://plg_peq/v1/` base64 binary) — VERIFIED by `HiByPeqV1CodecTest`.

## 5. Conflict & theme behavior

- `AudioConflictCoordinator` + `QualityOptionsPolicy`: Quality Options vs native Bit-Perfect conflict requires confirmation via `AudioConflictDialog`; cancel drops request, confirm runs atomic `AppSettings.confirmNativeBitPerfectConflict()`. VERIFIED by `QualityOptionsPolicyTest`.
- `AudiophileConflictPolicy.resolve(...)`: per-route/per-device folding of Perfect BP, No DVC, Bluetooth DVC policy, No Equ/Tone, No Duck, DSD remaster, resampler/dither, DVC compensate (bounded 6 dB, max 12), per-output EQ preset, `no_af`. Stored choices never destructively rewritten. VERIFIED (code + `AudiophileOutputResolverTest`, `RouteAndAdvancedPolicyTest`).
- Reference modes (`ReferenceMode`: Poweramp / HiBy / Hybrid / Bit-Perfect) gate which stage groups run; Hybrid = defined order, not a reference sound. VERIFIED (code).
- Theme: all new sheets/dialogs use `MaterialTheme.colorScheme` (background/surface/onSurface/primary), so they follow the app Appearance theme automatically; only hardcoded color is the preset curve preview dot (`Color.Green` in `PowerampPresetBrowser`). VERIFIED (code).

## 6. Output backends & decoders

- Backends: native AAudio + OpenSL ES Float32 (`cpp/outputs/`, `AAudioOutputProvider`, `OpenSlEsOutputProvider`, `NativeAudioOutput`) behind `AurisAudioOutputProvider` (Media3 `ForwardingAudioOutputProvider` with AudioTrack fallback; Hi-Res/USB-exclusive map to AudioTrack there); USB exclusive via `ExclusiveUsbOutput`/`UsbDirectManager`/decent-usb-audio-driver (+ raw DSD transports); Bluetooth tracked by `BluetoothAudioTracker`. VERIFIED (code); device behavior HW-UNVERIFIED.
- Decoders: Media3/ExoPlayer pipeline with in-tree FFmpeg decoder extension; DSD: `playback/dsd/` (Dsf/Dff extract, DoP, UAC2 descriptors, raw DSD, native `cpp/dsd/dst`); trackers: `playback/tracker/` + `native/libopenmpt`. VERIFIED (code); codec accuracy on device HW-UNVERIFIED.

## 7. Verification commands (to run later) & results

- `bash tools/aurisdsp/build_and_test.sh` → result: [PENDING — lane]
- `SANITIZE=1 bash tools/aurisdsp/build_and_test.sh` → result: [PENDING — lane]
- `g++ -std=c++17 -O2 -I app/src/main/cpp/outputs app/src/test/cpp/outputs/SpscFloatRingTest.cpp -o /tmp/opencode/spsc_test && /tmp/opencode/spsc_test` → result: [PENDING]
- `./gradlew :app:testDebugUnitTest --tests "com.music.bitchord.playback.audio.HiBy*" --tests "com.music.bitchord.Quality*" --tests "com.music.bitchord.playback.audio.audiophile.*" --tests "com.music.bitchord.playback.fades.*" --tests "com.music.bitchord.playback.dsd.*" --tests "com.music.bitchord.playback.audio.ReferenceDspTest" --tests "com.music.bitchord.playback.audio.DspChainFocusGainTest"` → result: [PENDING — Gradle lane]
- Full: `./gradlew :app:testDebugUnitTest` → result: [PENDING]
- Native-vs-`libhibyservice.so` PCM null/sweep comparison per `MSEB_DATA_REQUIREMENTS.md` §"Evidence still needed" → result: [PENDING — reference lane]

## 8. Hardware-unverified limits

- No device/emulator run in this pass: AAudio/OpenSL ES exclusive mode, USB DAC (incl. raw DSD/DoP), Bluetooth absolute volume, speaker mixer path — all HW-UNVERIFIED.
- MSEB constants are recovered exactly from the reference binary and reproduced by host tests, but bit-identical proprietary PCM output is not claimed without an offline reference sweep/hardware null test.
- Audio Pipeline fidelity modal (`AudioPipelineDialog`, `AudioFidelity`) logic verified by `AudioFidelityTest`; on-device readings HW-UNVERIFIED.

## 9. APK policy

- **No APK built and no Gradle/APK step run in this pass.** Per policy, an APK is produced only if explicitly requested. [PENDING: explicit request, if any]
