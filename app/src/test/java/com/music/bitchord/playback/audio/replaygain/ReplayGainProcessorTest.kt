package com.music.bitchord.playback.audio.replaygain

import com.music.bitchord.playback.audio.AudioBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGainProcessorTest {
    @Test
    fun `track gain and RG preamp are applied in dB`() {
        val processor = ReplayGainProcessor()
        processor.update(
            enabled = true,
            metadata = ReplayGainMetadata(trackGainDb = -6.0),
            source = ReplayGainSource.TRACK,
            rgPreampDb = 2f,
            nonRgPreampDb = -20f,
        )

        val output = process(processor, 0.5f, -0.25f)

        assertSamples(dbToLinear(-4.0) * 0.5f, dbToLinear(-4.0) * -0.25f, actual = output)
        assertEquals(ReplayGainSource.TRACK, processor.snapshot.selectedSource)
    }

    @Test
    fun `album gain is applied when album source is selected`() {
        val processor = ReplayGainProcessor()
        processor.update(
            enabled = true,
            metadata = ReplayGainMetadata(trackGainDb = -2.0, albumGainDb = -9.0),
            source = ReplayGainSource.ALBUM,
        )

        val output = process(processor, 0.5f)

        assertSamples(0.5f * dbToLinear(-9.0), actual = output)
        assertEquals(ReplayGainSource.ALBUM, processor.snapshot.selectedSource)
    }

    @Test
    fun `non-RG preamp applies only when both gains are absent`() {
        val processor = ReplayGainProcessor()
        processor.update(
            enabled = true,
            metadata = ReplayGainMetadata(trackPeak = 0.5),
            rgPreampDb = 12f,
            nonRgPreampDb = -6f,
        )

        assertSamples(0.5f * dbToLinear(-6.0), actual = process(processor, 0.5f))
        assertNull(processor.snapshot.selectedSource)
    }

    @Test
    fun `zero dB tag is ReplayGain and does not select non-RG preamp`() {
        val processor = ReplayGainProcessor()
        processor.update(
            enabled = true,
            metadata = ReplayGainMetadata(trackGainDb = 0),
            rgPreampDb = 6f,
            nonRgPreampDb = -24f,
        )

        assertSamples(0.25f * dbToLinear(6.0), actual = process(processor, 0.25f))
    }

    @Test
    fun `track and album modes fall back to the other gain and its peak`() {
        val processor = ReplayGainProcessor()
        processor.update(
            enabled = true,
            metadata = ReplayGainMetadata(albumGainDb = 20, albumPeak = 0.25, trackPeak = 0.001),
            source = ReplayGainSource.TRACK,
        )

        assertEquals(ReplayGainSource.ALBUM, processor.snapshot.selectedSource)
        assertEquals(4f, processor.snapshot.linearGain, 0f)

        processor.update(
            enabled = true,
            metadata = ReplayGainMetadata(trackGainDb = -12),
            source = ReplayGainSource.ALBUM,
        )
        assertEquals(ReplayGainSource.TRACK, processor.snapshot.selectedSource)
        assertSamples(0.5f * dbToLinear(-12.0), actual = process(processor, 0.5f))
    }

    @Test
    fun `smart source follows album context and falls back both ways`() {
        val processor = ReplayGainProcessor()
        val both = ReplayGainMetadata(trackGainDb = -3, albumGainDb = -9)
        processor.update(enabled = true, metadata = both, source = ReplayGainSource.SMART)

        processor.setMetadata(both, albumContext = true)
        assertEquals(ReplayGainSource.ALBUM, processor.snapshot.selectedSource)
        processor.setMetadata(both, albumContext = false)
        assertEquals(ReplayGainSource.TRACK, processor.snapshot.selectedSource)

        processor.setMetadata(ReplayGainMetadata(trackGainDb = -3), albumContext = true)
        assertEquals(ReplayGainSource.TRACK, processor.snapshot.selectedSource)
        processor.setMetadata(ReplayGainMetadata(albumGainDb = -9), albumContext = false)
        assertEquals(ReplayGainSource.ALBUM, processor.snapshot.selectedSource)
    }

    @Test
    fun `metadata peak caps boost at full scale`() {
        val processor = ReplayGainProcessor()
        processor.update(
            enabled = true,
            metadata = ReplayGainMetadata(trackGainDb = 20, trackPeak = 0.25),
        )

        assertEquals(4f, processor.snapshot.linearGain, 0f)
        assertSamples(0.8f, actual = process(processor, 0.2f))
    }

    @Test
    fun `attenuation and safe peaks are not raised by clipping prevention`() {
        val processor = ReplayGainProcessor()
        processor.update(
            enabled = true,
            metadata = ReplayGainMetadata(trackGainDb = -6, trackPeak = 0.5),
        )

        assertEquals(dbToLinear(-6.0), processor.snapshot.linearGain, 0f)
    }

    @Test
    fun `clipping prevention can be disabled`() {
        val processor = ReplayGainProcessor()
        processor.update(
            enabled = true,
            metadata = ReplayGainMetadata(trackGainDb = 20, trackPeak = 0.25),
            preventClipping = false,
        )

        assertSamples(2f, actual = process(processor, 0.2f))
    }

    @Test
    fun `disabled and unity states are exact bypasses`() {
        val processor = ReplayGainProcessor()
        val input = floatArrayOf(0f, -0f, 0.25f, -3f, Float.NaN, Float.POSITIVE_INFINITY)
        val expectedBits = input.rawBits()

        processor.update(
            enabled = false,
            metadata = ReplayGainMetadata(trackGainDb = 20, trackPeak = 1),
        )
        assertTrue(processor.isBypassed)
        assertTrue(rawBitsAfterProcessing(processor, input).contentEquals(expectedBits))

        processor.update(enabled = true, metadata = ReplayGainMetadata.EMPTY)
        assertTrue(processor.isBypassed)
        assertTrue(rawBitsAfterProcessing(processor, input).contentEquals(expectedBits))

        processor.update(enabled = true, metadata = ReplayGainMetadata(trackGainDb = 0))
        assertTrue(processor.isBypassed)
        assertTrue(rawBitsAfterProcessing(processor, input).contentEquals(expectedBits))
    }

    @Test
    fun `tag parser accepts dB gains linear and dB peaks and mixed numeric types`() {
        val metadata = ReplayGainMetadata.fromMetadata(
            mapOf(
                "replaygain_track_gain" to " -6.5 dB ",
                "REPLAYGAIN_ALBUM_GAIN" to -3.0,
                "REPLAYGAIN_TRACK_PEAK" to "0.5",
                "REPLAYGAIN_ALBUM_PEAK" to "-6.0206 dB",
            ),
        )

        assertEquals(-6.5f, metadata.trackGainDb!!, 0f)
        assertEquals(-3f, metadata.albumGainDb!!, 0f)
        assertEquals(0.5f, metadata.trackPeak!!, 0f)
        assertEquals(0.5f, metadata.albumPeak!!, 0.00001f)
    }

    @Test
    fun `non-finite and malformed tags are absent rather than dangerous gains`() {
        val parsed = ReplayGainMetadata.fromTags(
            mapOf(
                ReplayGainMetadata.TRACK_GAIN_KEY to "NaN",
                ReplayGainMetadata.ALBUM_GAIN_KEY to "Infinity",
                ReplayGainMetadata.TRACK_PEAK_KEY to "not a number",
                ReplayGainMetadata.ALBUM_PEAK_KEY to Float.NEGATIVE_INFINITY,
            ),
        )
        assertTrue(parsed.isEmpty)

        val direct = ReplayGainMetadata(
            trackGainDb = Float.NaN,
            albumGainDb = Double.POSITIVE_INFINITY,
            trackPeak = Float.NaN,
            albumPeak = -1,
        )
        assertTrue(direct.isEmpty)

        val processor = ReplayGainProcessor()
        processor.update(
            enabled = true,
            metadata = direct,
            rgPreampDb = Float.POSITIVE_INFINITY,
            nonRgPreampDb = Float.NaN,
        )
        assertTrue(processor.isBypassed)
        assertTrue(processor.snapshot.linearGain.isFinite())
    }

    @Test
    fun `finite metadata and preamps are clamped to safe bounds`() {
        val metadata = ReplayGainMetadata(
            trackGainDb = 10000,
            albumGainDb = -10000,
            trackPeak = Double.MAX_VALUE,
            albumPeak = 0,
        )
        assertEquals(ReplayGainMetadata.MAX_GAIN_DB, metadata.trackGainDb!!, 0f)
        assertEquals(ReplayGainMetadata.MIN_GAIN_DB, metadata.albumGainDb!!, 0f)
        assertEquals(ReplayGainMetadata.MAX_PEAK, metadata.trackPeak!!, 0f)
        assertNull(metadata.albumPeak)

        val processor = ReplayGainProcessor()
        processor.update(enabled = true, metadata = metadata, rgPreampDb = 10000f)
        assertEquals(ReplayGainSnapshot.MAX_PREAMP_DB, processor.snapshot.rgPreampDb, 0f)
        assertEquals(84f, processor.snapshot.targetGainDb, 0f)
        assertTrue(processor.snapshot.linearGain.isFinite())

        val hugeDbPeak = ReplayGainMetadata.fromMetadata(
            mapOf(ReplayGainMetadata.TRACK_PEAK_KEY to "100000 dB"),
        )
        assertEquals(ReplayGainMetadata.MAX_PEAK, hugeDbPeak.trackPeak!!, 0f)
    }

    @Test
    fun `published snapshots remain immutable across updates`() {
        val processor = ReplayGainProcessor()
        processor.update(
            enabled = true,
            metadata = ReplayGainMetadata(trackGainDb = -3),
            source = ReplayGainSource.TRACK,
        )
        val previous = processor.snapshot

        processor.update(
            enabled = true,
            metadata = ReplayGainMetadata(albumGainDb = -12),
            source = ReplayGainSource.ALBUM,
            albumContext = true,
            rgPreampDb = 3f,
        )

        assertNotSame(previous, processor.snapshot)
        assertEquals(ReplayGainSource.TRACK, previous.selectedSource)
        assertEquals(-3f, previous.targetGainDb, 0f)
        assertFalse(previous.albumContext)
        assertEquals(ReplayGainSource.ALBUM, processor.snapshot.selectedSource)
        assertEquals(-9f, processor.snapshot.targetGainDb, 0f)
        assertTrue(processor.snapshot.albumContext)
    }

    private fun process(processor: ReplayGainProcessor, vararg samples: Float): FloatArray {
        val block = AudioBlock(channelCount = 1, capacityFrames = samples.size)
        samples.copyInto(block.samples)
        block.reset(samples.size)
        processor.process(block)
        return block.samples.copyOf(samples.size)
    }

    private fun rawBitsAfterProcessing(processor: ReplayGainProcessor, input: FloatArray): IntArray {
        val block = AudioBlock(channelCount = 1, capacityFrames = input.size)
        input.copyInto(block.samples)
        block.reset(input.size)
        processor.process(block)
        return block.samples.copyOf(input.size).rawBits()
    }

    private fun FloatArray.rawBits(): IntArray = IntArray(size) { this[it].toRawBits() }

    private fun assertSamples(vararg expected: Float, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (index in expected.indices) {
            assertEquals("sample $index", expected[index], actual[index], 0.000001f)
        }
    }

    private fun dbToLinear(db: Double): Float = Math.pow(10.0, db / 20.0).toFloat()
}
