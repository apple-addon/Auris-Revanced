package com.music.bitchord.playback.audio

/**
 * HiBy-style MSEB: ten musical dimensions rather than ten frequencies.
 *
 * ## What this is, honestly
 *
 * HiBy's own MSEB is a proprietary effect inside `libhibyservice.so`; its
 * coefficients are not published and its preset data arrives from HiBy's
 * servers. This is a clean-room implementation of the *control surface* and a
 * documented mapping onto standard shelving and peaking sections. It does not
 * claim L2 curve parity or bit-identical output; that requires reference
 * sweeps and null tests. The UI must not call it "HiBy MSEB" without qualifier.
 *
 * ## The mapping
 *
 * Every dimension is `-100..+100`, neutral at `0`, matching the reference
 * slider's range. Positive values boost the named region. Frequencies are
 * fixed; only gain moves, because that is what the reference UI exposes.
 *
 * | Dimension | Shape | Centre | Max |
 * |---|---|---|---|
 * | Overall temperature | tilt (low + high shelf) | 250 Hz / 3.5 kHz | ±5 dB |
 * | Bass extension | low shelf | 60 Hz | ±9 dB |
 * | Bass texture | peaking | 110 Hz | ±6 dB |
 * | Sub bass | low shelf | 35 Hz | ±9 dB |
 * | Presence | peaking | 2.5 kHz | ±6 dB |
 * | Treble | high shelf | 3.5 kHz | ±9 dB |
 * | Treble extension | high shelf | 9 kHz | ±9 dB |
 * | Air | high shelf | 14 kHz | ±6 dB |
 * | Female overtones | peaking | 3.2 kHz | ±6 dB |
 * | Male overtones | peaking | 180 Hz | ±6 dB |
 *
 * The two overtone bands sit on top of Presence and Bass texture on purpose:
 * the reference surface treats them as separate musical controls even where
 * their regions overlap.
 *
 * ## Stability
 *
 * The bank glides every parameter and clamps Q and frequency, so a slider
 * storm cannot produce a click or a pole outside the unit circle. With every
 * dimension at zero the bank is skipped entirely.
 */
class MsebProcessor(
    private val bank: BiquadBank = BiquadBank(SECTION_COUNT),
) : FloatAudioProcessor {

    /** Neutral values for all ten dimensions. */
    val flatValues: FloatArray
        get() = FloatArray(DIMENSIONS) { 0f }

    val isEnabled: Boolean
        get() = bank.isEnabled

    /**
     * Publishes the ten slider values. Called from the settings thread; the
     * audio thread sees the resulting targets at its next sub-block.
     */
    fun setTuning(enabled: Boolean, values: FloatArray, preampDb: Float = 0f) {
        require(values.size == DIMENSIONS) { "MSEB needs $DIMENSIONS values, got ${values.size}" }
        val specs = ArrayList<BiquadSpec>(SECTION_COUNT)
        val temperature = values[TEMPERATURE].coerceIn(-100f, 100f) / 100f
        if (temperature != 0f) {
            // Warm is +low/-high; cool is the mirror. Two shelves, not one
            // tilt filter, so the neutral point is an exact bypass.
            specs += BiquadSpec(250f, 0.6f, temperature * TEMPERATURE_DB, BiquadKind.LOW_SHELF)
            specs += BiquadSpec(3500f, 0.6f, -temperature * TEMPERATURE_DB, BiquadKind.HIGH_SHELF)
        }
        for (dimension in 0 until DIMENSIONS) {
            if (dimension == TEMPERATURE) continue
            val value = values[dimension].coerceIn(-100f, 100f) / 100f
            if (value == 0f) continue
            val shape = SHAPES[dimension]
            specs += BiquadSpec(shape.frequencyHz, shape.q, value * shape.maxDb, shape.kind)
        }
        // A preamp remains meaningful even when every MSEB dimension is zero.
        bank.setTarget(enabled, specs, preampDb)
    }

    override fun configure(sampleRate: Int, channelCount: Int) = bank.configure(sampleRate, channelCount)

    override fun process(block: AudioBlock) = bank.process(block)

    @Suppress("DEPRECATION")
    override fun flush() = bank.flush()

    override fun reset() = bank.reset()

    private class Shape(
        val frequencyHz: Float,
        val q: Float,
        val maxDb: Float,
        val kind: BiquadKind,
    )

    companion object {
        const val DIMENSIONS = 10

        const val TEMPERATURE = 0
        const val BASS_EXTENSION = 1
        const val BASS_TEXTURE = 2
        const val SUB_BASS = 3
        const val PRESENCE = 4
        const val TREBLE = 5
        const val TREBLE_EXTENSION = 6
        const val AIR = 7
        const val FEMALE_OVERTONES = 8
        const val MALE_OVERTONES = 9

        /** Stable order for storage and UI. */
        val NAMES = arrayOf(
            "Overall temperature",
            "Bass extension",
            "Bass texture",
            "Note thickness",
            "Voice",
            "Female overtones",
            "Sibilance LF",
            "Sibilance HF",
            "Impulse response",
            "Air",
        )

        private const val TEMPERATURE_DB = 5f

        private val SHAPES = arrayOf(
            Shape(250f, 0.6f, 0f, BiquadKind.LOW_SHELF),
            Shape(60f, 0.7f, 9f, BiquadKind.LOW_SHELF),
            Shape(110f, 1.6f, 6f, BiquadKind.PEAKING),
            Shape(35f, 0.7f, 9f, BiquadKind.LOW_SHELF),
            Shape(2500f, 1.0f, 6f, BiquadKind.PEAKING),
            Shape(3500f, 0.7f, 9f, BiquadKind.HIGH_SHELF),
            Shape(9000f, 0.6f, 9f, BiquadKind.HIGH_SHELF),
            Shape(14000f, 0.5f, 6f, BiquadKind.HIGH_SHELF),
            Shape(3200f, 1.4f, 6f, BiquadKind.PEAKING),
            Shape(180f, 1.4f, 6f, BiquadKind.PEAKING),
        )

        const val SECTION_COUNT = 12
    }
}
