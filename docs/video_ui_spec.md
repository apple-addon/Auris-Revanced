# Video UI spec — Auris ReVanced reference extraction

Scope note: from the HiBy reference, only **MSEB** and **PEQ** are in scope. HiBy’s own playback engine, USB/DSD output pipeline, volume lock, and other HiBy features are reference-only and are not ported; all processing remains in Auris ReVanced’s ExoPlayer/Media3 pipeline and our own native bridge.

Inspected in this environment on 2026-11-25:

- `/tmp/9cd90745-1e42-4e71-8e06-c2b2234e8570.mp4` — PAWS/Poweramp Audio settings walkthrough, 13.37 s, 1220x2712.
- `/tmp/db19bcfc-f20e-49c0-97c0-179273831828.mp4` — PAWS/Poweramp 3-page EQ Hub and presets walkthrough, 13.98 s.
- `/tmp/74d9f6fc-cd4a-4e50-8a3f-27c724d79ce1.mp4` — HiBy MSEB and PEQ walkthrough, 15.60 s.
- `/tmp/370b0ad6-9e7c-43bf-8086-da0c3f3cf188.jpg` — existing Audio Pipeline indicator/modal reference.

Extraction method: ffprobe stream/duration checks, 1 fps frame extraction, 4x8 contact sheets, full-frame OCR with Tesseract, and visual inspection of the contact sheets/reference image. The earlier UUID-named paths from the first prompt were absent; these are the replacement files supplied later.

## 1. PAWS/Poweramp Audio settings video

Top-level Audio list observed:

- Audio Info — “Detailed info about the audio processing. Also available by long press on small meta info on the Main and Equalizer screens”.
- Crossfade, Fade, and Gapless — crossfade options, fade type/length, gapless.
- Replay Gain (RG) — enable RG, source, preamp values.
- Audio Focus — pause/resume/duck volume on calls/notifications/start.
- Equalizer — band count, frequencies.
- Resampler — resampling/dither settings.
- Direct Volume Control (DVC) — DVC options, DVC for Bluetooth.
- Audio Outputs — Wired Headset, Bluetooth, USB, Chromecast, other outputs.
- Bit Perfect — 64-bit audio pipeline, Perfect Bit Perfect.
- Advanced Tweaks — volume levels, MusicFX, equalizer/reverb preset reset.

Crossfade/Fade/Gapless screen observed:

- Note: “Tracks with different sample rates may not be stitched together gaplessly.”
- Preload Gapless Tracks — 0 ms slider; presets None/Normal/More visible in the walkthrough.
- Silence Between Tracks — 0 ms; note says applied to all tracks excluding CUE tracks and elapsed time may go beyond track duration.
- Crossfade Length — default 5000 ms.
- Short Manual Crossfade Length — default 400 ms.
- Play/Pause/Stop Fade Length — default 400 ms.
- Seek Fade Length — default 100 ms.
- Restore Defaults button.

Audio Outputs screen observed:

- Header note: Poweramp may use different output drivers depending on connected output device.
- Named Bluetooth/USB devices can have a separate profile created with `+`.
- Rows: Wired Headset/AUX, Speaker, Bluetooth, USB DAC, Chromecast, Other Output Devices, Other, Advanced Tweaks, Restore Defaults.
- Speaker row showed live status: “Active (48 kHz 32 bit)”.
- USB DAC detail screen observed rows including:
  - Output Device / driver summary and live Status/Sample Rate/Sample Format rows.
  - Equalizer Preset assignment.
  - Float32 Sample Format.
  - Buffer Size — “Preset: Normal, 50 ms”.
  - Visualization/Lyrics Delay — 0 ms.
  - No DVC — note: disabling DVC may be required for Hi-Res/advanced output; global status row “Enabled - Direct Volume Control (DVC)”.
  - No Headroom Gain Reduction — don’t reduce output gain when DVC disabled; may distort high EQ/tone gains.
  - No Equ/Tone.
  - No Duck — disable ducking for this output/device, e.g. notifications; temporarily pause player instead.
  - Restore Defaults.

Bit Perfect screen observed:

- Show More disclosure.
- 64-bit Audio Pipeline.
- Follow Track Sample Rate — match supported device rate or upsample to next higher supported rate.
- DSD Remastering — remaster PCM to DSD; note allows any format including DSD with DSP in PCM domain remastered to DSD for output.
- Perfect Bit Perfect — direct decoder-to-output path; enables DSD output for DSD tracks; no EQ, no DSP, no software volume; intended for DAP/USB DAC hardware volume; honest note that unsupported rates may still convert/resample.
- Per-output compatibility rows for Wired Headset/AUX and USB DAC.
- Restore Defaults.

## 2. PAWS/Poweramp EQ Hub video

Player top strip observed: `40 AUDIOTRACK OUTPUT 32 BIT 48 KHZ`.

Page 1 — Equalizer & Tone:

- Vertical Preamp slider at left, default `0.0`.
- Horizontal scrollable band sliders labeled `31 62 125 250 500 1K 2K 4K 8K 16K`, all default `0.0`.
- Live compact state strip: `NO DVC EQ 10 TON LMT` / `NO DVC EQ 10 TON` variants.
- Left pills: `Equ`, `Tone`, `Limit`; `Equ` dims sliders when off.
- Center `Preset` pill and right overflow menu.
- Rotary knobs `Bass` and `Treble`, default `0%`.
- Preset overlay:
  - Search bar with clear button.
  - Filter chips: `User`, `Built-in`, `AutoEq`, `Graphic`, `Parametric`, `Assigned`, `Reset filters`.
  - Groups: User with `Graphic Default Preset`, `Parametric Default Preset`; Built-in list including Bass, Bass & Treble, Clarity, Classical, Dance, Flat, Live, Middle, Pop, Punchy Bass, Rock, Soft, Soft Bass, etc., each with a curve preview.
  - Hint: “Long press any item for the selection menu”.

Page 2 — Volume, Stereo & Tempo:

- Rotary knobs `Balance` default `0.00`, `Stereo Expand` default `0%`.
- `Tempo` toggle pill, tempo knob `1.00x`/`1.84` shown while adjusted, with `+`/`-` step buttons.
- `Mono` toggle pill.
- `Reset` button.
- Large `Volume` rotary knob, shown `20%`, range 0–100%.

Page 3 — Reverb:

- Six rotary knobs in 2 rows of 3, default `0.00`: `Damp`, `Filter`, `Fade`, `Pre-Delay`, `Pre-Delay Mix`, `Size`.
- Action row: `Reverb` toggle pill, `Preset`, `Save`, `Reset`.
- Bottom large `Mix` knob, default `0.00`.

## 3. HiBy MSEB / PEQ video

HiBy main menu observed toggles: Equalizer Disabled, PEQ Disabled, MSEB Disabled, plus Plugins, Sleep timer, Settings, About.

MSEB screen observed:

- Top bar: back, `MSEB`, Reset and master enable toggle.
- Action row: preset selector `None >` / `Customize`, plus Save/Import/Share-style actions in the reference app chrome.
- `Slide rod range adjustment` selector, observed `Excessive(+100)`.
- Ten sliders with `-`/`+` fine buttons and bipolar labels:
  1. Overall temperature — Cool / Bright ↔ Warm / Dark.
  2. Bass extension — Light ↔ Deep.
  3. Bass texture — Fast ↔ Thumpy.
  4. Note thickness — Crisp ↔ Thick.
  5. Voice — Recessed / Crisp ↔ Forward / Radio Effect.
  6. Female overtones — Detoxed ↔ Intoxicating.
  7. Sibilance LF — Soft ↔ Crisp.
  8. Sibilance HF — Soft ↔ Crisp.
  9. Impulse response — Slow / Musical ↔ Fast / Hard.
  10. Air — Soft ↔ Crisp.
- All defaults observed at `0`.

PEQ screen observed:

- Title `Parametric equalizer`, Reset + master enable toggle.
- Preset selector `None >` and `Customize`.
- Live yellow frequency-response graph over logarithmic horizontal axis with `100 / 1k / 10k` markers and vertical dB scale.
- `Pre Amp: 0.00dB` slider.
- Scrollable tabs `Band0`…`Band9`.
- Per-band rows: `Frequency`, `Q` (observed `0.20`), `Gain` (`0.00dB`), `Type` dropdown (`Low Shelf`, etc.), `Enable` toggle.
- Observed default frequencies include 31.0 Hz, 62.0 Hz, 125 Hz, 250 Hz, 500 Hz, up to 16000.0 Hz.

## 4. Audio Pipeline reference image

Preserve exactly:

- Entry row below “Spatial audio”: title `Audio Pipeline`, subtitle `16-bit fallback · 48 kHz`, chevron.
- Modal header: `Audio Pipeline`, subtitle `Live signal path, decoder to output`.
- Fidelity card: circular `/100` arc gauge, `FIDELITY`, status title such as `Mixed path`, pills such as `DSP bypassed` and `2 deductions`, deduction rows (`Built-in speaker path -32`, `System mixer in the path -12`).
- Numbered vertical timeline: `01 TRACK INFO` with Source, Format, Bit Depth, Sample Rate, Bitrate, PCM Data Rate, Channels; `02 DECODER` with Decoder Name and Format; downstream stages; bottom `Done`.
