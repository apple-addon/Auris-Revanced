package com.music.bitchord.ui.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePresetFilterTest {
    private val presets = listOf(
        AudioPreset("flat", "Flat", setOf(AudioPresetTag.BUILT_IN, AudioPresetTag.GRAPHIC)),
        AudioPreset(
            "hd650",
            "Sennheiser HD650",
            setOf(AudioPresetTag.AUTO_EQ, AudioPresetTag.PARAMETRIC, AudioPresetTag.DEVICE),
            "AutoEq over-ear",
            deviceName = "Sennheiser HD650",
            previewGainsDb = listOf(-2f, 1f, 3f),
        ),
        AudioPreset(
            "mine",
            "Night bus",
            setOf(AudioPresetTag.USER, AudioPresetTag.GRAPHIC, AudioPresetTag.ASSIGNED),
            previewGainsDb = listOf(0f, 2f, 1f),
        ),
        AudioPreset("other", "Studio", setOf(AudioPresetTag.USER, AudioPresetTag.PARAMETRIC)),
    )

    @Test
    fun chipsExactlyMatchRunbookOrder() {
        assertEquals(
            listOf("User", "Built-in", "AutoEq", "Graphic", "Parametric", "Assigned", "Device", "Reset"),
            PowerampPresetFilter.entries.map { it.label },
        )
    }

    @Test
    fun sourceAndTypeFiltersUseIndependentTags() {
        assertIds(PowerampPresetFilter.USER, "mine", "other")
        assertIds(PowerampPresetFilter.BUILT_IN, "flat")
        assertIds(PowerampPresetFilter.AUTO_EQ, "hd650")
        assertIds(PowerampPresetFilter.GRAPHIC, "flat", "mine")
        assertIds(PowerampPresetFilter.PARAMETRIC, "hd650", "other")
    }

    @Test
    fun assignedDeviceAndNeutralResetFiltersWork() {
        assertIds(PowerampPresetFilter.ASSIGNED, "mine")
        assertIds(PowerampPresetFilter.DEVICE, "hd650")
        assertIds(PowerampPresetFilter.RESET, "flat", "hd650", "mine", "other")
    }

    @Test
    fun queryTrimsAndSearchesNameSubtitleAndDevice() {
        assertEquals(
            listOf("hd650"),
            filterAudioPresets(presets, "  OVER-EAR ", PowerampPresetFilter.AUTO_EQ).map { it.id },
        )
        assertEquals(
            listOf("hd650"),
            filterAudioPresets(presets, "sennheiser", PowerampPresetFilter.DEVICE).map { it.id },
        )
        assertEquals(
            listOf("mine"),
            filterAudioPresets(presets, "night", PowerampPresetFilter.USER).map { it.id },
        )
    }

    @Test
    fun resetChipClearsSearchFilterAndLongPressSelection() {
        val state = PowerampEqUiState(
            presetQuery = "night",
            presetFilter = PowerampPresetFilter.DEVICE,
            contextPresetId = "mine",
        ).resetPresetFilters()
        assertEquals("", state.presetQuery)
        assertEquals(PowerampPresetFilter.USER, state.presetFilter)
        assertEquals(null, state.contextPresetId)
    }

    @Test
    fun previewsStayGroupedBySourceAfterTypeFiltering() {
        val filtered = filterAudioPresets(presets, "", PowerampPresetFilter.GRAPHIC)
        val groups = groupAudioPresets(filtered)
        assertEquals(listOf("User", "Built-in"), groups.map { it.title })
        assertEquals(listOf("mine"), groups[0].presets.map { it.id })
        assertEquals(listOf("flat"), groups[1].presets.map { it.id })
        assertEquals(listOf(0f, 2f, 1f), groups[0].presets.single().previewGainsDb)
    }

    @Test
    fun emptyResultIsValidAndDoesNotMutateSource() {
        assertTrue(filterAudioPresets(presets, "missing", PowerampPresetFilter.USER).isEmpty())
        assertEquals(4, presets.size)
    }

    private fun assertIds(filter: PowerampPresetFilter, vararg ids: String) {
        assertEquals(ids.toList(), filterAudioPresets(presets, "", filter).map { it.id })
    }
}
