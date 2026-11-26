# PAWS / HiBy reference control checklist — Auris ReVanced

Scope: HiBy reference limited to **MSEB and PEQ** (per `docs/video_ui_spec.md` §3).
References inspected read-only on 2026-11-26:

- `/root/Apks/paws_auris`, `/root/Apks/paws_auris_v2` — rebranded Poweramp (PAWS) sources
- `/root/Apks/hibs_rebranded` — rebranded HiBy (AurAudio) sources, incl. `lib/arm64-v8a/libhibyservice.so`

Status legend:

- **VERIFIED** — control exists in Auris-Revanced source with matching label/behavior, pinned by code and/or unit tests.
- **PENDING** — implemented in code but parity/evidence not yet demonstrated (offline sweep or reference-lane test outstanding).
- **HW-UNVERIFIED** — cannot be confirmed without on-device/hardware measurement; no bit-parity claim made.

## Entry points (HiBy main-menu toggles → Auris-Revanced)

| Reference control | Reference location | Auris-Revanced path | Status |
|---|---|---|---|
| Main menu: Equalizer / PEQ / MSEB enable toggles | `hibs_rebranded` main menu; `smali_classes3/com/auraudio/music/broadcast/MsebAndEqStateBroadCastReceiver.smali` | `sharedUi/.../player/AudioOutputSheet.kt` Quality rows "MSEB" (L133) and "PEQ" (L139); state in `app/.../data/settings/AppSettings.kt` (`msebEnabled` L938, `peqEnabled` L944) | VERIFIED |
| MSEB dialog from player overlay | HiBy `MsebSettingActivity` + `Presenter/MsebSettingActivityPresenter*` | `sharedUi/.../player/NowPlayingOverlays.kt` (L138-139) → `PlayerHost.MsebDialog` → `app/.../ui/screens/MsebScreen.kt` | VERIFIED |
| PEQ dialog from player overlay | HiBy `PeqActivity` + `Presenter/PeqActivityPresenter*` | `NowPlayingOverlays.kt` (L142) → `PlayerHost.PeqDialog` → `app/.../ui/screens/PeqScreen.kt` | VERIFIED |

## MSEB screen controls

| # | Reference control (HiBy string) | Auris-Revanced implementation | Status |
|---|---|---|---|
| 1 | Top bar: back, `MSEB`, Reset, master enable toggle | `MsebScreen.kt` `QualityScreenHeader(title = "MSEB", enabled, onReset = ::resetMseb)` (L78-91) | VERIFIED |
| 2 | Preset selector `None >` / `Customize` + Save/Import/Share | `QualityPresetSelector` + `QualityPresetActions` (L93-105); presets via `QualityPresetStore` (`saveMseb`/`loadMseb`) | VERIFIED |
| 3 | `Slide rod range adjustment`, observed `Excessive(+100)` | `MSEB_RANGES = [20, 40, 100]`, labels `Excessive(±100)` / `Middling (±40)` / `Fine-tuning(±20)` (L356-365); pinned by `QualityUiTextTest` L230-234 | VERIFIED |
| 4 | Ten sliders with `-`/`+` fine buttons, bipolar labels, defaults `0` | `MsebDimensionControl` (L227-299) with `AurisSlider` + Add/Remove `IconButton`s; `MSEB_ENDPOINTS` (L368-379) pinned by `QualityUiTextTest` L241-251 | VERIFIED |
| 5 | Overall temperature — Cool/Bright ↔ Warm/Dark | `MsebProcessor.NAMES[0]`; endpoint pair `MSEB_ENDPOINTS[0]` | VERIFIED |
| 6 | Bass extension — Light ↔ Deep | `NAMES[1]` / `MSEB_ENDPOINTS[1]` | VERIFIED |
| 7 | Bass texture — Fast ↔ Thumpy | `NAMES[2]` / `MSEB_ENDPOINTS[2]` | VERIFIED |
| 8 | Note thickness — Crisp ↔ Thick | `NAMES[3]` / `MSEB_ENDPOINTS[3]` | VERIFIED |
| 9 | Voice — Recessed/Crisp ↔ Forward/Radio Effect | `NAMES[4]` / `MSEB_ENDPOINTS[4]` ("Radio Edit" wording normalized) | VERIFIED |
| 10 | Female overtones — Detoxed ↔ Intoxicating | `NAMES[5]` / `MSEB_ENDPOINTS[5]` | VERIFIED |
| 11 | Sibilance LF — Soft ↔ Crisp | `NAMES[6]` / `MSEB_ENDPOINTS[6]` | VERIFIED |
| 12 | Sibilance HF — Soft ↔ Crisp | `NAMES[7]` / `MSEB_ENDPOINTS[7]` | VERIFIED |
| 13 | Impulse response — Slow/Musical ↔ Fast/Hard | `NAMES[8]` / `MSEB_ENDPOINTS[8]` | VERIFIED |
| 14 | Air — Soft ↔ Crisp | `NAMES[9]` / `MSEB_ENDPOINTS[9]` | VERIFIED |
| 15 | MSEB share/import codec | `HiByMsebV1Codec.kt` — `hiby://plg_mseb/v1/` JSON; `HiByMsebV1CodecTest.kt` | VERIFIED |
| 16 | MSEB audio path — Kotlin clean-room | `MsebProcessor.kt` (10 biquad shapes), runs in `DspChain` before PAWS-shaped stages; gated by `ReferenceMode.HIBY/HYBRID` | VERIFIED |
| 17 | MSEB audio path — native mapping | `app/src/main/cpp/aurisdsp/MsebMapping.cpp` (13 sections recovered from `libhibyservice.so`, see `tools/aurisdsp/HIBY_MSEB_RECOVERED.md`); exercised by `tools/aurisdsp/aurisdsp_tests.cpp` (static_assert 13 sections, RBJ response checks) | VERIFIED (code/tests) |
| 18 | Native-vs-proprietary PCM parity | `app/src/main/cpp/aurisdsp/MSEB_DATA_REQUIREMENTS.md` §"Evidence still needed": requires Float32 in/out pairs vs reference build | PENDING |
| 19 | On-device MSEB output equivalence vs HiBy hardware/app | No hardware null/sweep test performed in this environment | HW-UNVERIFIED |

## PEQ screen controls

| # | Reference control (HiBy string) | Auris-Revanced implementation | Status |
|---|---|---|---|
| 1 | Title `Parametric equalizer`, Reset + master enable | `PeqScreen.kt` header `title = "Parametric equalizer"` (L90), `QualityScreenHeader` reset/toggle | VERIFIED |
| 2 | Preset selector `None >` / `Customize` + Save/Import/Share | `QualityPresetSelector`/`QualityPresetActions` (L104-120); `QualityPresetStore.savePeq/loadPeq` | VERIFIED |
| 3 | Live frequency-response graph, log axis `100 / 1k / 10k`, dB scale | `PeqResponse.kt` `PeqResponseGraph`; markers `100f→"100", 1_000f→"1k", 10_000f→"10k"` (L272-274), log10 magnitude (L261) | VERIFIED |
| 4 | `Pre Amp: 0.00dB` slider | `QualitySlider("Pre Amp", -15..15 dB)` (L133-141); format `%.2fdB` pinned by `QualityUiTextTest` L264-268 | VERIFIED |
| 5 | Scrollable tabs `Band0`…`Band9` | `LazyRow` of `Band$index` `FilterChip`s (L144-153) | VERIFIED |
| 6 | Per-band `Frequency` (default 31.0 Hz … 16000.0 Hz) | `PeqLogSlider("Frequency", MIN_HZ=20 .. MAX_HZ=20000)` (L227-240); `DEFAULT_BANDS = 31,62,125,250,500,1k,2k,4k,8k,16k` in `ParametricEqProcessor.kt` L80 | VERIFIED |
| 7 | Per-band `Q` (observed `0.20`) | `PeqLogSlider("Q", MIN_Q=0.1 .. MAX_Q=10)` (L241-254) | VERIFIED |
| 8 | Per-band `Gain` (`0.00dB`, ±15 dB) | `QualitySlider("Gain", -MAX_GAIN_DB..+MAX_GAIN_DB, MAX_GAIN_DB=15)` (L255-266) | VERIFIED |
| 9 | Per-band `Type` dropdown (`Low Shelf`, Peaking, `High Shelf`) | `Type` `OutlinedButton` + `DropdownMenu` over `BiquadKind.entries` (L191-224); labels pinned by `QualityUiTextTest` L258-262 | VERIFIED |
| 10 | Per-band `Enable` toggle | `Switch` bound to `band.enabled` (L178-188) | VERIFIED |
| 11 | PEQ share/import codec | `HiByPeqV1Codec.kt` — `hiby://plg_peq/v1/` base64 binary, 10 bands × 14 bytes; `HiByPeqV1CodecTest.kt` | VERIFIED |
| 12 | PEQ audio path — Kotlin | `ParametricEqProcessor.kt` + `BiquadBank.kt`; off-band = bit-untouched (test in `aurisdsp_tests.cpp` L911-925); in `DspChain` after MSEB | VERIFIED |
| 13 | PEQ through native Float32 ABI | `AurisDspEngine` PEQ params (`PARAM_PEQ_*`) in `app/src/main/cpp/aurisdsp/`; ABI band-field test in `aurisdsp_tests.cpp` L893-925 | VERIFIED (code/tests) |
| 14 | Native-vs-proprietary PEQ PCM parity | Same evidence gap as MSEB item 18 (`MSEB_DATA_REQUIREMENTS.md`) | PENDING |
| 15 | On-device PEQ output equivalence vs HiBy hardware/app | No hardware measurement performed | HW-UNVERIFIED |

## Cross-cutting

| Item | Path | Status |
|---|---|---|
| Conflict gating (MSEB/PEQ vs Poweramp-shaped EQ) | `ReferenceMode.kt` (HIBY runs MSEB+PEQ only; POWERAMP/HYBRID/BIT_PERFECT semantics), `AudioConflictCoordinator.requestQualityOptions`, `DspChain.process` gating | VERIFIED |
| Settings persistence | `AppSettings.kt` L938-946, L2054-2060 (`KEY_MSEB_*`, `KEY_PEQ_*`) | VERIFIED |
| Native param pack (94-float) incl. MSEB/PEQ | `NativeReferenceParams.kt`, `AurisDspNative.kt`, `NativeReferenceProcessor.kt`; normalization test `NativeReferenceParamsTest.kt` | VERIFIED |
| End-to-end bit-identity vs reference builds | Explicitly not claimed; see `MSEB_DATA_REQUIREMENTS.md` §"Evidence still needed" and `ReferenceMode` doc ("L2/L3 parity is not claimed until measured") | PENDING / HW-UNVERIFIED |

Sources: `docs/video_ui_spec.md` §3; `docs/final_handoff_report.md` §4; reference strings in `hibs_rebranded/res/values/strings.xml` (`tv_total_temperature`, `tv_bass_dive`, `peq_pre_amp`, `peq_low_shelf`, …).
