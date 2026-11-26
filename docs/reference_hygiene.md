# Reference hygiene audit: dormant jniLibs `.so` files and `com.hiby` / `com.maxmpz` stubs

Date: 2026-11-26. Initial read-only audit; no code, resources, build files, or git state
were modified during the audit.

> **Remediation applied 2026-11-26 (same day).** The 12 dormant `.so` files were moved
> out of the repository to `/root/Auris-Revanced-reference-libs/` (they are no longer
> packaged into APKs, saving ~21 MB). The `com.hiby` / `com.maxmpz` JNI contract stubs
> were relocated to `tools/reference-stubs/src/` as non-compiled reference material, and
> the dead `ReferenceNativeLibraries.kt` loader was removed. Tracked `libffmpegJNI.so`
> and the app's own `bitchord_*` native libraries are unchanged.

## Verdict

**Dormant and unused by the app runtime.** The six untracked `.so` names and the
`com.hiby` / `com.maxmpz` stubs have **zero call sites** anywhere in the repo. They are
reference/contract material only. Because AGP's default `jniLibs` srcDir is
`app/src/main/jniLibs` (no `sourceSets` override in `app/build.gradle.kts`), all 12
untracked `.so` files (~21 MB across two ABIs) **are packaged into the APK** even though
nothing ever loads them.

## Evidence

### 1. Untracked binaries (`git status --porcelain`: `??`)

`app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/`: `libffmpeg_neon.so`,
`libhibyaudioutils.so`, `libhibyservice.so`, `libpowerampcore.so`, `libsmartusb_jni.so`,
`libusb.so` — 12 files, all real stripped ELF shared objects (`file` verified),
~11.4 MiB (arm64) + ~9.7 MiB (armeabi-v7a).

Note: `libffmpegJNI.so` (arm64/armeabi-v7a/x86_64) is **git-tracked and actively used** —
loaded by the vendored `androidx/media3/decoder/ffmpeg/FfmpegLibrary.java` via
`new LibraryLoader("ffmpegJNI")` (line 44). It is not part of this finding.

### 2. The only loader of those six names is itself never called

`app/src/main/java/com/music/bitchord/playback/audio/ReferenceNativeLibraries.kt`:

- `loadPaws()` → `loadInOrder("ffmpeg_neon", "powerampcore")` (line 6)
- `loadHiBy()` → `loadInOrder("hibyaudioutils", "smartusb_jni", "usb", "hibyservice")` (line 10)
- `System.loadLibrary` inside a lazy block (lines 21–30)

Repo-wide grep for `ReferenceNativeLibraries` / `loadPaws` / `loadHiBy`: **1 match — the
file itself**. No caller in `app/src/main`, `app/src/test`, or any other module.

### 3. Complete `loadLibrary` inventory in `app/src/main`

All runtime loads target libraries built from `app/src/main/cpp` (or the tracked
ffmpegJNI), never the untracked reference `.so`:

| Library | Load site |
|---|---|
| `bitchord_dsd`, `bitchord_dst` | `playback/dsd/DsdDecoders.kt:82,110` |
| `bitchord_audio_outputs` | `playback/audio/AAudioOutputProvider.kt:221` |
| `bitchord_jamesdsp` | `playback/audio/JamesDspProcessor.kt:17` |
| `bitchord_tracker` | `playback/tracker/TrackerDecoder.kt:97` |
| `bitchord_aurisdsp` | `playback/audio/AurisDspNative.kt:10` |
| `ffmpegJNI` | `androidx/media3/decoder/ffmpeg/FfmpegLibrary.java:44,47` (tracked, in use) |
| `ffmpeg_neon`, `powerampcore`, `hibyaudioutils`, `smartusb_jni`, `usb`, `hibyservice` | **only** `ReferenceNativeLibraries.kt` — never invoked |

Grep for the six names across all of `app/src/main` (`.kt`/`.java`/`.cpp`): only comments
(`MsebProcessor.kt:8`, `cpp/aurisdsp/MSEB_DATA_REQUIREMENTS.md:12`) and the dead loader.

### 4. Stub classes are never referenced

`app/src/main/java/com/hiby/` (5 files: `SmartAv`, `util/AudioUtils`, `util/AudioUtilsHiRes`,
`util/DspUtil`, `util/OtHiByTrackUtils`) and `app/src/main/java/com/maxmpz/` (6 files:
`dsp/data/Band`, `equalizer/eq/PeqNativeUtils`, `audioplayer/equalizer/Coefs`,
`audioplayer/plugin/{NativePluginManager,NativePluginInfo,OutputInternalHelper}`,
`audioplayer/processing/Pipeline2`) declare only `native` methods — pure JNI contracts
(e.g. `SmartAv.native_*`, `Coefs.native_calc_coefs`, `Pipeline2.native_create`).

Repo-wide grep for `import com.hiby` / `import com.maxmpz` and every stub class name:
matches occur **only inside the stub packages themselves** (package declarations and one
intra-stub import `Coefs.java:3`). No imports from `com.music.bitchord.**`, no test
references, no reflective `Class.forName` usage.

### 5. Not linked into the app's own native code

`app/src/main/cpp/CMakeLists.txt` contains no reference to hoby/poweramp/smartusb/ffmpeg_neon/
usb targets — the app's C++ never links against the reference `.so` files.

### 6. Packaging config ships them anyway

`app/build.gradle.kts:269-273` sets `packaging.jniLibs.useLegacyPackaging = false`
(uncompressed, 16 KB-aligned — comment cites "the reference engines"), and no
`sourceSets` block relocates `jniLibs`, so the default `src/main/jniLibs` is merged into
every APK/AAB.

### 7. Intent documented as reference-only

`docs/final_handoff_report.md:16` lists "`com/maxmpz/` + `com/hiby/` PAWS/HiBy contract
stubs; bundled `jniLibs` (powerampcore, hibyservice, ffmpeg_neon, etc.)" as reference
material; `tools/aurisdsp/HIBY_MSEB_RECOVERED.md` and
`app/src/main/cpp/aurisdsp/MSEB_DATA_REQUIREMENTS.md` use `libhibyservice.so` purely as a
recovery/disassembly source for reimplementing MSEB in `aurisdsp`.

## Recommendation

- **Remove the 12 untracked `.so` files from `app/src/main/jniLibs/` before packaging**
  (they are untracked, so this is not a git operation). They cost ~21 MB per APK and are
  never `loadLibrary`-ed at runtime. If the team wants them kept, move them out of the
  default jniLibs srcDir (e.g. under `tools/reference-binaries/` or `docs/`) so they stop
  shipping; keeping them in `src/main/jniLibs` is only "reference" in name.
- **Keep the `com.hiby` / `com.maxmpz` stubs only as reference, outside `app/src/main/java`**
  (or delete them): they compile but are dead code with zero call sites. They document the
  original PAWS/HiBy JNI contracts, which has value for the `aurisdsp` reimplementation, so
  prefer relocating over deleting.
- **Do not touch** tracked `libffmpegJNI.so` (used by the media3 FFmpeg decoder) or the
  `bitchord_*` libraries built from `app/src/main/cpp`.
- Optionally delete the dead `ReferenceNativeLibraries.kt` loader once the `.so` files are
  gone, so no future code path can load them; until then it is the only thing keeping the
  binaries "reachable" on paper.
