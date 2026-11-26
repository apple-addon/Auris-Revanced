#ifndef AURIS_DSP_PARAMS_H
#define AURIS_DSP_PARAMS_H

#include <cstddef>
#include <cstdint>

namespace aurisdsp {

/** Hard channel limit for every filter and delay-line state. */
constexpr std::size_t kMaxChannels = 8;
constexpr std::size_t kMsebDimensionCount = 10;
constexpr std::size_t kPeqBandCount = 10;
constexpr std::size_t kPowerampBandCount = 10;
constexpr std::size_t kPeqBandFieldCount = 5;

/** Values are carried as exact small integers in the Float32 parameter array. */
enum class Mode : std::int32_t {
    OFF = 0,
    POWERAMP = 1,
    HIBY = 2,
    HYBRID = 3,
    BIT_PERFECT = 4,
};

/** RBJ Audio EQ Cookbook filter types used by the TDF-II sections. */
enum class BiquadType : std::int32_t {
    PEAKING = 0,
    LOW_SHELF = 1,
    HIGH_SHELF = 2,
    LOW_PASS = 3,
    HIGH_PASS = 4,
    NOTCH = 5,
    BAND_PASS = 6,
};

/**
 * Stable Float32 ABI consumed by nativeSetParams.
 *
 * Kotlin must allocate exactly kParamCount floats and fill every entry. There
 * are no variable-length or partial updates: one call atomically publishes one
 * complete snapshot. Boolean values are 0.0f (false) or 1.0f (true); native
 * code accepts >= 0.5f as true. Enum entries are their exact integer values.
 * Non-finite values are replaced by defaults and finite values are clamped to
 * the ranges below. Units are dB, Hz, milliseconds, or normalized 0..1 as
 * indicated. Native code never reads Kotlin objects or player settings.
 *
 * Index  Field                         Range / meaning                Default
 *   0    MODE                          Mode enum; OFF and BIT_PERFECT   OFF
 *                                      are exact, byte-untouched bypass
 *   1    MSEB_ENABLED                  boolean                         0
 *   2    MSEB_PREAMP_DB                -45..+45 dB                     0
 *   3    MSEB_RANGE_PERCENT            25..100; 25/50/100 are the UI   100
 *                                      preset values. Range is preserved as
 *                                      metadata and never rescales raw sliders.
 *   4+i  MSEB_VALUE[i], i=0..9         normalized -1..+1               0
 *        Reference raw slider = normalized value * 100. Order: overall
 *        temperature, bass extension, bass texture, note thickness, voice,
 *        female overtones, sibilance LF, sibilance HF, impulse response, air.
 *        Temperature drives four shelves; every other dimension drives one.
 *  14    PEQ_ENABLED                   boolean                         0
 *  15    PEQ_PREAMP_DB                 -45..+45 dB                     0
 *  16+5*i PEQ band i, i=0..9:
 *    +0   ENABLED                      boolean                         0
 *    +1   TYPE                         BiquadType enum                 PEAKING
 *    +2   FREQUENCY_HZ                 20..20000 (also Nyquist-clamped) metadata
 *    +3   Q                            0.1..10                         0.2
 *    +4   GAIN_DB                      -15..+15; ignored by pass/notch  0
 *  66    POWERAMP_EQ_ENABLED           boolean                         0
 *  67    POWERAMP_PREAMP_DB            -12..+12 dB                     0
 *  68+i  POWERAMP_BAND_DB[i], i=0..9   -12..+12 dB                     0
 *        Centres are 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz;
 *        Q=1.0. The end sections are low/high shelves, the rest peak.
 *  78    TONE_ENABLED                  boolean                         0
 *  79    BASS_DB                       -12..+12 dB, 250 Hz low shelf   0
 *  80    TREBLE_DB                     -12..+12 dB, 4 kHz high shelf   0
 *  81    LIMITER_ENABLED               boolean                         0
 *  82    LIMITER_CEILING_DB            -24..0 dBFS                     -0.3
 *  83    LIMITER_RELEASE_MS            10..2000 ms                     100
 *  84    REVERB_ENABLED                boolean                         0
 *  85    REVERB_DAMP                   0..1 feedback damping           0
 *  86    REVERB_FILTER                 0..1 wet low-pass, 700 Hz..16k  0.5
 *  87    REVERB_FADE                   0..1 decay feedback             0
 *  88    REVERB_PRE_DELAY              0..1 maps to 0..100 ms          0
 *  89    REVERB_PRE_DELAY_MIX          0..1 immediate/delayed wet input 0
 *  90    REVERB_SIZE                   0..1 comb delay scale            0
 *  91    REVERB_MIX                    0..1 dry/wet mix                 0
 *  92    DVC_ENABLED                   boolean                         0
 *  93    DVC_GAIN_DB                   -24..+24 dB output trim         0
 *
 * DVC is represented as a PCM output trim because this library deliberately
 * has no Android player, audio-device, or platform-volume dependencies.
 *
 * MSEB and PEQ have independent enable/preamp stages. Poweramp preamp belongs
 * to its graphic EQ stage and is bypassed with that EQ. The limiter is a
 * channel-linked, zero-attack peak limiter with exponential release.
 *
 * Filter frequencies are additionally limited to 0.45 * sampleRate at
 * coefficient calculation, so imported 20 kHz bands remain stable at low rates.
 */
enum ParamIndex : std::size_t {
    PARAM_MODE = 0,
    PARAM_MSEB_ENABLED = 1,
    PARAM_MSEB_PREAMP_DB = 2,
    PARAM_MSEB_RANGE_PERCENT = 3,
    PARAM_MSEB_VALUES = 4,
    PARAM_PEQ_ENABLED = 14,
    PARAM_PEQ_PREAMP_DB = 15,
    PARAM_PEQ_BANDS = 16,
    PARAM_POWERAMP_EQ_ENABLED = 66,
    PARAM_POWERAMP_PREAMP_DB = 67,
    PARAM_POWERAMP_BANDS = 68,
    PARAM_TONE_ENABLED = 78,
    PARAM_BASS_DB = 79,
    PARAM_TREBLE_DB = 80,
    PARAM_LIMITER_ENABLED = 81,
    PARAM_LIMITER_CEILING_DB = 82,
    PARAM_LIMITER_RELEASE_MS = 83,
    PARAM_REVERB_ENABLED = 84,
    PARAM_REVERB_DAMP = 85,
    PARAM_REVERB_FILTER = 86,
    PARAM_REVERB_FADE = 87,
    PARAM_REVERB_PRE_DELAY = 88,
    PARAM_REVERB_PRE_DELAY_MIX = 89,
    PARAM_REVERB_SIZE = 90,
    PARAM_REVERB_MIX = 91,
    PARAM_DVC_ENABLED = 92,
    PARAM_DVC_GAIN_DB = 93,
    PARAM_COUNT = 94,
};

constexpr std::size_t kParamCount = static_cast<std::size_t>(PARAM_COUNT);

constexpr std::size_t msebValueIndex(std::size_t dimension) {
    return PARAM_MSEB_VALUES + dimension;
}

constexpr std::size_t peqBandIndex(std::size_t band) {
    return PARAM_PEQ_BANDS + band * kPeqBandFieldCount;
}

constexpr std::size_t powerampBandIndex(std::size_t band) {
    return PARAM_POWERAMP_BANDS + band;
}

/** Compile-time defaults suitable for the host engine and Kotlin mappers. */
constexpr float kDefaultPeqFrequenciesHz[kPeqBandCount] = {
    31.0f, 62.0f, 125.0f, 250.0f, 500.0f,
    1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f,
};

}  // namespace aurisdsp

#endif  // AURIS_DSP_PARAMS_H
