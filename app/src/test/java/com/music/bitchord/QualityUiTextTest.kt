package com.music.bitchord

import android.media.AudioFormat
import com.music.bitchord.playback.EqBandLayout
import com.music.bitchord.playback.EqLayout
import com.music.bitchord.playback.audio.BiquadKind
import com.music.bitchord.ui.screens.peqLabel
import com.music.bitchord.playback.fades.FadeSettings
import com.music.bitchord.playback.fades.GaplessPreloadPreset
import com.music.bitchord.ui.screens.CUSTOM_PRESET_LABEL
import com.music.bitchord.ui.screens.NONE_PRESET_LABEL
import com.music.bitchord.ui.screens.eqStateStrip
import com.music.bitchord.ui.screens.formatBandFrequency
import com.music.bitchord.ui.screens.formatPeqDb
import com.music.bitchord.ui.screens.formatPeqFrequencyHz
import com.music.bitchord.ui.screens.msebRangeLabel
import com.music.bitchord.ui.screens.MSEB_ENDPOINTS
import com.music.bitchord.ui.screens.MSEB_RANGES
import com.music.bitchord.ui.screens.presetFilterChips
import com.music.bitchord.ui.screens.audiophile.AudioOutputRoute
import com.music.bitchord.ui.screens.audiophile.AudiophileSection
import com.music.bitchord.ui.screens.audiophile.chipLabel
import com.music.bitchord.ui.screens.audiophile.DitherMode
import com.music.bitchord.ui.screens.audiophile.DvcBluetoothPolicy
import com.music.bitchord.ui.screens.audiophile.DvcSettings
import com.music.bitchord.ui.screens.audiophile.formatRgDb
import com.music.bitchord.ui.screens.audiophile.liveOutputReadout
import com.music.bitchord.ui.screens.audiophile.ReplayGainType
import com.music.bitchord.ui.screens.audiophile.replayGainType
import com.music.bitchord.ui.screens.audiophile.ResamplerSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visible strings, ranges and defaults the quality surfaces promise, as
 * extracted from the reference PAWS/HiBy sources (res/xml + strings). These
 * pin the labels so a refactor cannot silently drift away from them.
 */
class QualityUiTextTest {

    // ── Top-level Audio section labels (PAWS res/xml/audio.xml) ──────────

    @Test
    fun `audio section labels match the reference list`() {
        val titles = AudiophileSection.entries.take(10).map { it.title }
        assertEquals(
            listOf(
                "Audio Info",
                "Crossfade, Fade, and Gapless",
                "Replay Gain (RG)",
                "Audio Focus",
                "Equalizer",
                "Resampler",
                "Direct Volume Control (DVC)",
                "Audio Outputs",
                "Bit Perfect",
                "Advanced Tweaks",
            ),
            titles,
        )
    }

    // ── Audio Outputs rows (audio_outputs2.xml) ──────────────────────────

    @Test
    fun `output route labels match the reference rows`() {
        assertEquals("Speaker", AudioOutputRoute.PHONE.label)
        assertEquals("Wired Headset/AUX", AudioOutputRoute.WIRED.label)
        assertEquals("Bluetooth", AudioOutputRoute.BLUETOOTH.label)
        assertEquals("USB DAC", AudioOutputRoute.USB.label)
        assertEquals("Other Output Devices", AudioOutputRoute.OTHER.label)
    }

    @Test
    fun `speaker live readout reads active rate and bit depth`() {
        assertEquals("Active (48 kHz 32 bit)", liveOutputReadout(48_000, AudioFormat.ENCODING_PCM_32BIT))
        assertEquals("Active (44 kHz 16 bit)", liveOutputReadout(44_100, AudioFormat.ENCODING_PCM_16BIT))
        assertEquals("Active (48 kHz 24 bit)", liveOutputReadout(48_000, AudioFormat.ENCODING_PCM_24BIT_PACKED))
        assertEquals("Active (48 kHz 32 bit)", liveOutputReadout(48_000, AudioFormat.ENCODING_PCM_FLOAT))
        assertEquals("Active (48 kHz)", liveOutputReadout(48_000, null))
        assertEquals("Active", liveOutputReadout(null, null))
    }

    // ── Fade/gapless (fade.xml) ───────────────────────────────────────────

    @Test
    fun `gapless preload preset chips are None Normal More`() {
        assertEquals("None", GaplessPreloadPreset.NONE.chipLabel())
        assertEquals("Normal", GaplessPreloadPreset.NORMAL.chipLabel())
        assertEquals("More", GaplessPreloadPreset.MORE.chipLabel())
        assertEquals(0L, GaplessPreloadPreset.NONE.durationMs)
        assertEquals(2_500L, GaplessPreloadPreset.NORMAL.durationMs)
        assertEquals(5_000L, GaplessPreloadPreset.MORE.durationMs)
    }

    // ── Replay Gain (rg.xml) ──────────────────────────────────────────────

    @Test
    fun `rg type options map onto the persisted enabled and preventClipping pair`() {
        assertEquals(ReplayGainType.NONE, replayGainType(enabled = false, preventClipping = false))
        assertEquals(ReplayGainType.NONE, replayGainType(enabled = false, preventClipping = true))
        assertEquals(ReplayGainType.APPLY, replayGainType(enabled = true, preventClipping = false))
        assertEquals(ReplayGainType.APPLY_PEAK, replayGainType(enabled = true, preventClipping = true))
        assertEquals("\"Don't apply\"", ReplayGainType.NONE.label)
        assertEquals("Apply Gain", ReplayGainType.APPLY.label)
        assertEquals("Apply Gain/prevent clipping according to Peak", ReplayGainType.APPLY_PEAK.label)
    }

    @Test
    fun `rg preamp uses the reference d_db format`() {
        assertEquals("0.0dB", formatRgDb(0f))
        assertEquals("-6.0dB", formatRgDb(-6f))
        assertEquals("3.5dB", formatRgDb(3.5f))
    }

    // ── Resampler (audio_resampler.xml) ───────────────────────────────────

    @Test
    fun `resampler cutoff default and range mirror the reference 970 of 800 to 990`() {
        assertEquals(97, ResamplerSettings.DEFAULTS.cutoffPercent)
        assertEquals(80, ResamplerSettings.DEFAULTS.copy(cutoffPercent = 70).normalized().cutoffPercent)
        assertEquals(99, ResamplerSettings.DEFAULTS.copy(cutoffPercent = 100).normalized().cutoffPercent)
    }

    @Test
    fun `dither labels are the exact reference entries for all nine shapes in array order`() {
        // paws res/values/arrays.xml pref_dither_entries, in order.
        assertEquals(
            listOf(
                "None (fastest)",
                "Rectangular (fast)",
                "Triangular (fast)",
                "Triangular with high pass (fast)",
                "F-weighted noise shaping (slow)",
                "Modified-e-weighted noise shaping (slow)",
                "Improved-e-weighted noise shaping (slow)",
                "Shibata noise shaping (slow)",
                "Low shibata noise shaping (slow)",
            ),
            DitherMode.entries.map { it.label },
        )
        // Default stays None; reference numeric values 4..8 resolve to the new modes.
        assertEquals(DitherMode.NONE, ResamplerSettings.DEFAULTS.dither)
        assertEquals(DitherMode.NONE, DitherMode.fromStored(null))
        assertEquals(DitherMode.NONE, DitherMode.fromStored("0"))
        assertEquals(DitherMode.F_WEIGHTED, DitherMode.fromStored("4"))
        assertEquals(DitherMode.MODIFIED_E_WEIGHTED, DitherMode.fromStored("5"))
        assertEquals(DitherMode.IMPROVED_E_WEIGHTED, DitherMode.fromStored("6"))
        assertEquals(DitherMode.SHIBATA, DitherMode.fromStored("7"))
        assertEquals(DitherMode.LOW_SHIBATA, DitherMode.fromStored("8"))
        assertEquals(DitherMode.SHIBATA, DitherMode.fromStored("shibata"))
        assertTrue(DitherMode.SHIBATA.isNoiseShaping)
        assertTrue(DitherMode.LOW_SHIBATA.isNoiseShaping)
        assertFalse(DitherMode.HIGH_PASS.isNoiseShaping)
    }

    // ── Graphic EQ band counts (peq_equ_bands.xml) ─────────────────────────

    @Test
    fun `band count entries are exactly the reference list with default 10`() {
        assertEquals(
            listOf("5", "10 (ISO)", "12", "15 (ISO)", "16", "24", "31 (ISO)", "32"),
            EqBandLayout.BAND_COUNTS.map(EqBandLayout::labelFor),
        )
        assertEquals(intArrayOf(5, 10, 12, 15, 16, 24, 31, 32).toList(), EqBandLayout.BAND_COUNTS.toList())
        assertEquals(10, EqBandLayout.DEFAULT_COUNT)
        assertEquals(10, EqLayout.manualCount)
        assertEquals(10, EqBandLayout.frequenciesFor(10).size)
        assertArrayEquals(EqLayout.MANUAL_BANDS_HZ, EqBandLayout.frequenciesFor(10), 0f)
        assertEquals(31, EqBandLayout.frequenciesFor(31).size)
        assertEquals(20f, EqBandLayout.frequenciesFor(31).first())
        assertEquals(20_000f, EqBandLayout.frequenciesFor(31).last())
        assertEquals(32, EqBandLayout.frequenciesFor(32).size)
        assertEquals(5, EqBandLayout.frequenciesFor(5).size)
        // Custom layout: 5..32 bands log-spaced between first and last frequency.
        val custom = EqBandLayout.customFrequencies(12, 20f, 16_000f)
        assertEquals(12, custom.size)
        assertEquals(20f, custom.first())
        assertEquals(16_000f, custom.last())
    }

    @Test
    fun `eq state strip gains the EQ PARAM form and band count`() {
        assertEquals("NO DVC EQ 10 TON LMT", eqStateStrip(bitPerfect = true, equalizer = true, tone = true, limiter = true))
        assertEquals("NO DVC EQ PARAM 1 TON LMT", eqStateStrip(bitPerfect = true, equalizer = true, tone = true, limiter = true, bandCount = 1, parametric = true))
        assertEquals("DVC EQ 24", eqStateStrip(bitPerfect = false, equalizer = true, tone = false, limiter = false, bandCount = 24))
    }

    // ── Fade switches (fade.xml) ───────────────────────────────────────────

    @Test
    fun `fade switch defaults match fade xml and stay backward compatible`() {
        assertEquals(true, FadeSettings.DEFAULTS.fadePlayPause)
        assertEquals(true, FadeSettings.DEFAULTS.fadeSeek)
        assertEquals(true, FadeSettings.DEFAULTS.crossfadeAutoAdvance)
        assertEquals(true, FadeSettings.DEFAULTS.fadeManualAdvance)
    }

    // ── DVC (audio_dvc.xml) ───────────────────────────────────────────────

    @Test
    fun `dvc defaults mirror the reference dvc_enabled and no_dvc_bt_absvol`() {
        assertEquals(true, DvcSettings.DEFAULTS.enabled)
        assertEquals(DvcBluetoothPolicy.FORCE_OFF, DvcSettings.DEFAULTS.bluetoothPolicy)
        assertEquals("No DVC for Bluetooth Absolute Volume", DvcBluetoothPolicy.FORCE_OFF.label)
        assertEquals(-6f, DvcSettings.DEFAULTS.headroomDb)
        assertEquals(-10f, DvcSettings.DEFAULTS.copy(headroomDb = -99f).normalized().headroomDb)
        // compensate_dvc_vol: reference default false.
        assertEquals(false, DvcSettings.DEFAULTS.compensateVolume)
    }

    // ── Player volume fresh default (EQ Hub knob) ──────────────────────────

    @Test
    fun `fresh player volume default is 20 percent and stored values are preserved`() {
        assertEquals(0.20f, com.music.bitchord.data.settings.AppSettings.DEFAULT_PLAYER_VOLUME)
        assertEquals(0.20f, com.music.bitchord.data.settings.AppSettings.resolvePlayerVolume(null))
        assertEquals(0.7f, com.music.bitchord.data.settings.AppSettings.resolvePlayerVolume(0.7f))
        assertEquals(1f, com.music.bitchord.data.settings.AppSettings.resolvePlayerVolume(1f))
        assertEquals(0f, com.music.bitchord.data.settings.AppSettings.resolvePlayerVolume(-3f))
        assertEquals(0.20f, com.music.bitchord.data.settings.AppSettings.playerVolume.value)
    }

    // ── MSEB (HiBy strings) ───────────────────────────────────────────────

    @Test
    fun `slide rod range adjustment offers Fine-tuning Middling Excessive`() {
        assertEquals(intArrayOf(20, 40, 100).toList(), MSEB_RANGES.toList())
        assertEquals("Fine-tuning(±20)", msebRangeLabel(20))
        assertEquals("Middling (±40)", msebRangeLabel(40))
        assertEquals("Excessive(±100)", msebRangeLabel(100))
        // Legacy ±25/±50 values stay readable until the user picks a range.
        assertEquals("Custom(±25)", msebRangeLabel(25))
    }

    @Test
    fun `mseb endpoint labels match the HiBy strings in dimension order`() {
        assertEquals(10, MSEB_ENDPOINTS.size)
        assertEquals("Cool / Bright" to "Warm / Dark", MSEB_ENDPOINTS[0])
        assertEquals("Light" to "Deep", MSEB_ENDPOINTS[1])
        assertEquals("Fast" to "Thumpy", MSEB_ENDPOINTS[2])
        assertEquals("Crisp" to "Thick", MSEB_ENDPOINTS[3])
        assertEquals("Recessed / Crisp" to "Forward / Radio Edit", MSEB_ENDPOINTS[4])
        assertEquals("Detoxed" to "Intoxicating", MSEB_ENDPOINTS[5])
        assertEquals("Soft" to "Crisp", MSEB_ENDPOINTS[6])
        assertEquals("Soft" to "Crisp", MSEB_ENDPOINTS[7])
        assertEquals("Slow / Musical" to "Fast / Hard", MSEB_ENDPOINTS[8])
        assertEquals("Soft" to "Crisp", MSEB_ENDPOINTS[9])
    }

    // ── PEQ (HiBy strings) ────────────────────────────────────────────────

    @Test
    fun `peq type labels are Low Shelf Peaking High Shelf`() {
        assertEquals("Low Shelf", BiquadKind.LOW_SHELF.peqLabel())
        assertEquals("Peaking", BiquadKind.PEAKING.peqLabel())
        assertEquals("High Shelf", BiquadKind.HIGH_SHELF.peqLabel())
    }

    @Test
    fun `peq value formats match the reference format strings`() {
        assertEquals("0.00dB", formatPeqDb(0f))
        assertEquals("-3.00dB", formatPeqDb(-3f))
        assertEquals("31.0Hz", formatPeqFrequencyHz(31f))
        assertEquals("16000.0Hz", formatPeqFrequencyHz(16_000f))
    }

    // ── EQ Hub (PAWS player pages) ────────────────────────────────────────

    @Test
    fun `eq state strip matches the reference variants`() {
        assertEquals("NO DVC EQ 10 TON LMT", eqStateStrip(bitPerfect = true, equalizer = true, tone = true, limiter = true))
        assertEquals("NO DVC EQ 10 TON", eqStateStrip(bitPerfect = true, equalizer = true, tone = true, limiter = false))
        assertEquals("DVC EQ 10", eqStateStrip(bitPerfect = false, equalizer = true, tone = false, limiter = false))
        assertEquals("DVC", eqStateStrip(bitPerfect = false, equalizer = false, tone = false, limiter = false))
    }

    @Test
    fun `band slider labels are 31 through 16K`() {
        assertEquals("31", formatBandFrequency(31f))
        assertEquals("62", formatBandFrequency(62f))
        assertEquals("125", formatBandFrequency(125f))
        assertEquals("1K", formatBandFrequency(1_000f))
        assertEquals("16K", formatBandFrequency(16_000f))
    }

    @Test
    fun `preset overlay filter chips match the reference list`() {
        assertEquals(listOf("User", "Built-in", "AutoEq", "Graphic", "Parametric", "Assigned"), presetFilterChips)
    }

    @Test
    fun `preset selector labels are None and Customize`() {
        assertEquals("None", NONE_PRESET_LABEL)
        assertEquals("Customize", CUSTOM_PRESET_LABEL)
    }
}
