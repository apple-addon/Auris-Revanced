#ifndef AURIS_PARITY_REFERENCE_MODEL_H
#define AURIS_PARITY_REFERENCE_MODEL_H

// Independent host-side reference model used by the parity harness.
//
// This file is written from documentation and statically recovered constants
// only:
//   * tools/aurisdsp/HIBY_MSEB_RECOVERED.md (frequencies, Q, types, gain
//     scales recovered statically from the ARM64 libhibyservice.so);
//   * the public RBJ Audio EQ Cookbook equations;
//   * the documented parameter semantics and stage order in
//     app/src/main/cpp/aurisdsp/AurisDspParams.h.
//
// It does NOT include, link, load, or execute any reference native library.
// The two biquad topologies are deliberate:
//   * Tdf2Biquad mirrors the documented transposed-direct-form-II recurrence
//     so a correct engine is expected to match bit-for-bit under IEEE double;
//   * Df1Biquad is an independent direct-form-I recurrence used only for a
//     tolerance cross-check, so a shared coding mistake cannot hide.
//
// Exactness vocabulary used by the harness/report:
//   * HW_CONSTANT  : data statically extracted from the reference binary.
//   * ANALYTICAL   : host-side reference math only; no ARM execution (no qemu
//                    on this host, and running untrusted ARM binaries is out
//                    of scope by policy).
//   * OWN_DESIGN   : BitChord's own clean-room stage; no reference parity is
//                    claimed beyond "same control surface".

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <vector>

#include "AurisDspParams.h"

namespace parity {

using ParamArray = std::array<float, aurisdsp::kParamCount>;

constexpr double kPi = 3.141592653589793238462643383279502884;

struct Coefficients {
    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a1 = 0.0;
    double a2 = 0.0;
};

// Same documented clamps as the engine: f in [1 Hz, 0.45*fs], Q >= 0.01.
// All coefficients normalized by a0.
inline Coefficients rbjCoefficients(
    aurisdsp::BiquadType type,
    double sampleRate,
    double frequencyHz,
    double q,
    double gainDb) noexcept {
    const double frequency = std::max(1.0, std::min(frequencyHz, sampleRate * 0.45));
    const double safeQ = std::max(0.01, q);
    const double omega = 2.0 * kPi * frequency / sampleRate;
    const double cosine = std::cos(omega);
    const double sine = std::sin(omega);
    const double alpha = sine / (2.0 * safeQ);
    const double a = std::pow(10.0, gainDb / 40.0);

    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a0 = 1.0;
    double a1 = 0.0;
    double a2 = 0.0;

    switch (type) {
        case aurisdsp::BiquadType::PEAKING:
            b0 = 1.0 + alpha * a;
            b1 = -2.0 * cosine;
            b2 = 1.0 - alpha * a;
            a0 = 1.0 + alpha / a;
            a1 = -2.0 * cosine;
            a2 = 1.0 - alpha / a;
            break;
        case aurisdsp::BiquadType::LOW_SHELF: {
            const double twoSqrtAAlpha = 2.0 * std::sqrt(a) * alpha;
            b0 = a * ((a + 1.0) - (a - 1.0) * cosine + twoSqrtAAlpha);
            b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosine);
            b2 = a * ((a + 1.0) - (a - 1.0) * cosine - twoSqrtAAlpha);
            a0 = (a + 1.0) + (a - 1.0) * cosine + twoSqrtAAlpha;
            a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosine);
            a2 = (a + 1.0) + (a - 1.0) * cosine - twoSqrtAAlpha;
            break;
        }
        case aurisdsp::BiquadType::HIGH_SHELF: {
            const double twoSqrtAAlpha = 2.0 * std::sqrt(a) * alpha;
            b0 = a * ((a + 1.0) + (a - 1.0) * cosine + twoSqrtAAlpha);
            b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosine);
            b2 = a * ((a + 1.0) + (a - 1.0) * cosine - twoSqrtAAlpha);
            a0 = (a + 1.0) - (a - 1.0) * cosine + twoSqrtAAlpha;
            a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosine);
            a2 = (a + 1.0) - (a - 1.0) * cosine - twoSqrtAAlpha;
            break;
        }
        case aurisdsp::BiquadType::LOW_PASS:
            b0 = (1.0 - cosine) * 0.5;
            b1 = 1.0 - cosine;
            b2 = b0;
            a0 = 1.0 + alpha;
            a1 = -2.0 * cosine;
            a2 = 1.0 - alpha;
            break;
        case aurisdsp::BiquadType::HIGH_PASS:
            b0 = (1.0 + cosine) * 0.5;
            b1 = -(1.0 + cosine);
            b2 = b0;
            a0 = 1.0 + alpha;
            a1 = -2.0 * cosine;
            a2 = 1.0 - alpha;
            break;
        case aurisdsp::BiquadType::NOTCH:
            b0 = 1.0;
            b1 = -2.0 * cosine;
            b2 = 1.0;
            a0 = 1.0 + alpha;
            a1 = -2.0 * cosine;
            a2 = 1.0 - alpha;
            break;
        case aurisdsp::BiquadType::BAND_PASS:
            b0 = alpha;
            b1 = 0.0;
            b2 = -alpha;
            a0 = 1.0 + alpha;
            a1 = -2.0 * cosine;
            a2 = 1.0 - alpha;
            break;
    }

    if (!std::isfinite(a0) || std::abs(a0) < 1.0e-30) {
        return Coefficients{};
    }
    Coefficients out;
    out.b0 = b0 / a0;
    out.b1 = b1 / a0;
    out.b2 = b2 / a0;
    out.a1 = a1 / a0;
    out.a2 = a2 / a0;
    return out;
}

// Transposed direct form II, double state per channel. Mirrors the
// documented engine recurrence including the tiny-state flush.
class Tdf2Biquad {
public:
    void set(
        aurisdsp::BiquadType type,
        double sampleRate,
        double frequencyHz,
        double q,
        double gainDb,
        bool enabled = true) noexcept {
        active_ = enabled && sampleRate > 0.0;
        if (!active_) {
            reset();
            return;
        }
        coefficients_ = rbjCoefficients(type, sampleRate, frequencyHz, q, gainDb);
        active_ = std::isfinite(coefficients_.b0) && std::isfinite(coefficients_.b1) &&
            std::isfinite(coefficients_.b2) && std::isfinite(coefficients_.a1) &&
            std::isfinite(coefficients_.a2);
        if (!active_) reset();
    }

    inline double process(double input, std::size_t channel) noexcept {
        const double output = coefficients_.b0 * input + z1_[channel];
        z1_[channel] = coefficients_.b1 * input - coefficients_.a1 * output + z2_[channel];
        z2_[channel] = coefficients_.b2 * input - coefficients_.a2 * output;
        if (std::abs(z1_[channel]) < 1.0e-30) z1_[channel] = 0.0;
        if (std::abs(z2_[channel]) < 1.0e-30) z2_[channel] = 0.0;
        return output;
    }

    void reset() noexcept {
        z1_.fill(0.0);
        z2_.fill(0.0);
    }

    bool active() const noexcept { return active_; }
    Coefficients coefficients() const noexcept { return coefficients_; }

private:
    Coefficients coefficients_{};
    bool active_ = false;
    std::array<double, aurisdsp::kMaxChannels> z1_{};
    std::array<double, aurisdsp::kMaxChannels> z2_{};
};

// Independent direct form I cross-check (same normalized coefficients,
// different recurrence). Not expected to be bit-identical to TDF-II.
class Df1Biquad {
public:
    void set(
        aurisdsp::BiquadType type,
        double sampleRate,
        double frequencyHz,
        double q,
        double gainDb,
        bool enabled = true) noexcept {
        active_ = enabled && sampleRate > 0.0;
        if (!active_) {
            reset();
            return;
        }
        coefficients_ = rbjCoefficients(type, sampleRate, frequencyHz, q, gainDb);
        active_ = std::isfinite(coefficients_.b0) && std::isfinite(coefficients_.b1) &&
            std::isfinite(coefficients_.b2) && std::isfinite(coefficients_.a1) &&
            std::isfinite(coefficients_.a2);
        if (!active_) reset();
    }

    inline double process(double input, std::size_t channel) noexcept {
        const double output = coefficients_.b0 * input + coefficients_.b1 * x1_[channel] +
            coefficients_.b2 * x2_[channel] - coefficients_.a1 * y1_[channel] -
            coefficients_.a2 * y2_[channel];
        x2_[channel] = x1_[channel];
        x1_[channel] = input;
        y2_[channel] = y1_[channel];
        y1_[channel] = output;
        return output;
    }

    void reset() noexcept {
        x1_.fill(0.0);
        x2_.fill(0.0);
        y1_.fill(0.0);
        y2_.fill(0.0);
    }

    bool active() const noexcept { return active_; }

private:
    Coefficients coefficients_{};
    bool active_ = false;
    std::array<double, aurisdsp::kMaxChannels> x1_{};
    std::array<double, aurisdsp::kMaxChannels> x2_{};
    std::array<double, aurisdsp::kMaxChannels> y1_{};
    std::array<double, aurisdsp::kMaxChannels> y2_{};
};

// ---------------------------------------------------------------------------
// Statically recovered HiBy MSEB table.
//
// Recovered from libhibyservice.so (arm64), initialization 0x1820c0 and
// setters 0x183bec/0x183d48..0x183d90. The double pairs live contiguously at
// file offsets 0x182208..0x1822a0; tools/parity/REFERENCE_STATIC_EXTRACTION.md
// records the re-verification. Temperature is one control driving sections
// 0..3 simultaneously; sections 4..12 are one control each.
// ---------------------------------------------------------------------------
struct MsebSection {
    double frequencyHz;
    double q;
    aurisdsp::BiquadType type;
    double gainDbPerRawSliderUnit;
    std::size_t dimension;
};

constexpr std::array<MsebSection, 13> kRecoveredMseb = {{
    {33.0, 0.4424999952316284, aurisdsp::BiquadType::HIGH_SHELF, -0.06, 0},
    {231.0, 0.4424999952316284, aurisdsp::BiquadType::HIGH_SHELF, -0.06, 0},
    {1617.0, 0.4424999952316284, aurisdsp::BiquadType::HIGH_SHELF, -0.06, 0},
    {11319.0, 0.4424999952316284, aurisdsp::BiquadType::HIGH_SHELF, -0.06, 0},
    {70.0, 0.7289000153541565, aurisdsp::BiquadType::LOW_SHELF, 0.25, 1},
    {100.0, 0.8571000099182129, aurisdsp::BiquadType::PEAKING, 0.20, 2},
    {200.0, 0.666700005531311, aurisdsp::BiquadType::PEAKING, 0.12, 3},
    {650.0, 0.4041000008583069, aurisdsp::BiquadType::PEAKING, 0.50, 4},
    {3000.0, 1.4140000343322754, aurisdsp::BiquadType::PEAKING, 0.10, 5},
    {5800.0, 0.9991000294685364, aurisdsp::BiquadType::PEAKING, 0.10, 6},
    {9200.0, 0.4041000008583069, aurisdsp::BiquadType::PEAKING, 0.10, 7},
    {7500.0, 0.7289000153541565, aurisdsp::BiquadType::PEAKING, 0.10, 8},
    {10000.0, 0.8571000099182129, aurisdsp::BiquadType::HIGH_SHELF, 0.25, 9},
}};

struct MsebSpec {
    aurisdsp::BiquadType type;
    double frequencyHz;
    double q;
    double gainDb;
    bool active;
};

inline std::array<MsebSpec, 13> msebReference(
    const std::array<float, aurisdsp::kMsebDimensionCount>& normalizedValues) noexcept {
    std::array<MsebSpec, 13> specs{};
    for (std::size_t index = 0; index < specs.size(); ++index) {
        const MsebSection& section = kRecoveredMseb[index];
        const double rawSlider = static_cast<double>(normalizedValues[section.dimension]) * 100.0;
        specs[index].type = section.type;
        specs[index].frequencyHz = section.frequencyHz;
        specs[index].q = section.q;
        specs[index].gainDb = rawSlider * section.gainDbPerRawSliderUnit;
        specs[index].active = rawSlider != 0.0;
    }
    return specs;
}

// ---------------------------------------------------------------------------
// Documented ABI sanitization, mirrored from AurisDspParams.h. The harness
// applies this before handing a parameter block to both the engine and this
// model, so clamp behaviour is compared rather than assumed.
// ---------------------------------------------------------------------------
inline float clampValue(float value, float minimum, float maximum) noexcept {
    return std::max(minimum, std::min(maximum, value));
}

inline ParamArray defaultParams() noexcept {
    ParamArray params{};
    params[aurisdsp::PARAM_MODE] = static_cast<float>(aurisdsp::Mode::OFF);
    params[aurisdsp::PARAM_MSEB_RANGE_PERCENT] = 100.0f;
    params[aurisdsp::PARAM_LIMITER_CEILING_DB] = -0.3f;
    params[aurisdsp::PARAM_LIMITER_RELEASE_MS] = 100.0f;
    params[aurisdsp::PARAM_REVERB_FILTER] = 0.5f;
    for (std::size_t band = 0; band < aurisdsp::kPeqBandCount; ++band) {
        const std::size_t base = aurisdsp::peqBandIndex(band);
        params[base + 1] = static_cast<float>(aurisdsp::BiquadType::PEAKING);
        params[base + 2] = aurisdsp::kDefaultPeqFrequenciesHz[band];
        params[base + 3] = 0.2f;
    }
    return params;
}

inline bool enabled(float value) noexcept {
    return value >= 0.5f;
}

inline aurisdsp::BiquadType sanitizedType(float value) noexcept {
    if (!(value >= 0.0f) || value > 6.0f) return aurisdsp::BiquadType::PEAKING;
    return static_cast<aurisdsp::BiquadType>(static_cast<std::int32_t>(value));
}

inline aurisdsp::Mode sanitizedMode(float value) noexcept {
    if (!(value >= 0.0f) || value > 4.0f) return aurisdsp::Mode::OFF;
    return static_cast<aurisdsp::Mode>(static_cast<std::int32_t>(value));
}

inline ParamArray sanitize(ParamArray params) noexcept {
    const ParamArray defaults = defaultParams();
    for (std::size_t index = 0; index < aurisdsp::kParamCount; ++index) {
        if (!std::isfinite(params[index])) params[index] = defaults[index];
    }
    params[aurisdsp::PARAM_MODE] = static_cast<float>(sanitizedMode(params[aurisdsp::PARAM_MODE]));
    params[aurisdsp::PARAM_MSEB_ENABLED] = enabled(params[aurisdsp::PARAM_MSEB_ENABLED]) ? 1.0f : 0.0f;
    params[aurisdsp::PARAM_MSEB_PREAMP_DB] =
        clampValue(params[aurisdsp::PARAM_MSEB_PREAMP_DB], -45.0f, 45.0f);
    params[aurisdsp::PARAM_MSEB_RANGE_PERCENT] =
        clampValue(params[aurisdsp::PARAM_MSEB_RANGE_PERCENT], 25.0f, 100.0f);
    for (std::size_t dimension = 0; dimension < aurisdsp::kMsebDimensionCount; ++dimension) {
        const std::size_t index = aurisdsp::msebValueIndex(dimension);
        params[index] = clampValue(params[index], -1.0f, 1.0f);
    }
    params[aurisdsp::PARAM_PEQ_ENABLED] = enabled(params[aurisdsp::PARAM_PEQ_ENABLED]) ? 1.0f : 0.0f;
    params[aurisdsp::PARAM_PEQ_PREAMP_DB] =
        clampValue(params[aurisdsp::PARAM_PEQ_PREAMP_DB], -45.0f, 45.0f);
    for (std::size_t band = 0; band < aurisdsp::kPeqBandCount; ++band) {
        const std::size_t base = aurisdsp::peqBandIndex(band);
        params[base] = enabled(params[base]) ? 1.0f : 0.0f;
        params[base + 1] = static_cast<float>(sanitizedType(params[base + 1]));
        params[base + 2] = clampValue(params[base + 2], 20.0f, 20000.0f);
        params[base + 3] = clampValue(params[base + 3], 0.1f, 10.0f);
        params[base + 4] = clampValue(params[base + 4], -15.0f, 15.0f);
    }
    params[aurisdsp::PARAM_POWERAMP_EQ_ENABLED] =
        enabled(params[aurisdsp::PARAM_POWERAMP_EQ_ENABLED]) ? 1.0f : 0.0f;
    params[aurisdsp::PARAM_POWERAMP_PREAMP_DB] =
        clampValue(params[aurisdsp::PARAM_POWERAMP_PREAMP_DB], -12.0f, 12.0f);
    for (std::size_t band = 0; band < aurisdsp::kPowerampBandCount; ++band) {
        const std::size_t index = aurisdsp::powerampBandIndex(band);
        params[index] = clampValue(params[index], -12.0f, 12.0f);
    }
    params[aurisdsp::PARAM_TONE_ENABLED] = enabled(params[aurisdsp::PARAM_TONE_ENABLED]) ? 1.0f : 0.0f;
    params[aurisdsp::PARAM_BASS_DB] = clampValue(params[aurisdsp::PARAM_BASS_DB], -12.0f, 12.0f);
    params[aurisdsp::PARAM_TREBLE_DB] = clampValue(params[aurisdsp::PARAM_TREBLE_DB], -12.0f, 12.0f);
    params[aurisdsp::PARAM_LIMITER_ENABLED] =
        enabled(params[aurisdsp::PARAM_LIMITER_ENABLED]) ? 1.0f : 0.0f;
    params[aurisdsp::PARAM_LIMITER_CEILING_DB] =
        clampValue(params[aurisdsp::PARAM_LIMITER_CEILING_DB], -24.0f, 0.0f);
    params[aurisdsp::PARAM_LIMITER_RELEASE_MS] =
        clampValue(params[aurisdsp::PARAM_LIMITER_RELEASE_MS], 10.0f, 2000.0f);
    params[aurisdsp::PARAM_REVERB_ENABLED] =
        enabled(params[aurisdsp::PARAM_REVERB_ENABLED]) ? 1.0f : 0.0f;
    for (std::size_t index = aurisdsp::PARAM_REVERB_DAMP;
         index <= aurisdsp::PARAM_REVERB_MIX; ++index) {
        params[index] = clampValue(params[index], 0.0f, 1.0f);
    }
    params[aurisdsp::PARAM_DVC_ENABLED] = enabled(params[aurisdsp::PARAM_DVC_ENABLED]) ? 1.0f : 0.0f;
    params[aurisdsp::PARAM_DVC_GAIN_DB] = clampValue(params[aurisdsp::PARAM_DVC_GAIN_DB], -24.0f, 24.0f);
    return params;
}

inline double dbToGain(double db) noexcept {
    return std::pow(10.0, db / 20.0);
}

// ---------------------------------------------------------------------------
// Reference reverb network (BitChord's own design; documented controls in
// AurisDspParams.h). Re-stated independently from the engine source so a
// block/state bookkeeping bug shows up as a mismatch.
// ---------------------------------------------------------------------------
constexpr double kCombMilliseconds[4] = {29.7, 37.1, 41.1, 43.7};
constexpr double kAllpassMilliseconds[2] = {5.0, 1.7};
constexpr std::size_t kCombCount = 4;
constexpr std::size_t kAllpassCount = 2;
constexpr std::size_t kLinesPerChannel = 1 + kCombCount + kAllpassCount;

class ReferenceReverb {
public:
    void configure(int sampleRate, int channels) {
        sampleRate_ = sampleRate;
        channels_ = channels;
        std::size_t total = 0;
        for (int channel = 0; channel < channels_; ++channel) {
            for (std::size_t lineIndex = 0; lineIndex < kLinesPerChannel; ++lineIndex) {
                Line& line = lines_[static_cast<std::size_t>(channel) * kLinesPerChannel + lineIndex];
                line = Line{};
                line.offset = total;
                line.maximumLength = maximumLength(lineIndex);
                line.length = line.maximumLength;
                total += line.maximumLength;
            }
        }
        storage_.assign(total, 0.0);
        reset();
    }

    void tune(const ParamArray& params) {
        damp_ = 0.95 * params[aurisdsp::PARAM_REVERB_DAMP];
        const double filter = params[aurisdsp::PARAM_REVERB_FILTER];
        const double unclampedCutoff = 700.0 * std::pow(16000.0 / 700.0, filter);
        const double cutoff = std::min(unclampedCutoff, sampleRate_ * 0.45);
        wetFilterAlpha_ = 1.0 - std::exp(-2.0 * kPi * cutoff / sampleRate_);
        feedback_ = 0.60 + 0.38 * params[aurisdsp::PARAM_REVERB_FADE];
        preDelay_ = params[aurisdsp::PARAM_REVERB_PRE_DELAY];
        preDelayMix_ = params[aurisdsp::PARAM_REVERB_PRE_DELAY_MIX];
        sizeScale_ = 0.35 + 1.65 * params[aurisdsp::PARAM_REVERB_SIZE];
        mix_ = params[aurisdsp::PARAM_REVERB_MIX];
        for (int channel = 0; channel < channels_; ++channel) {
            for (std::size_t lineIndex = 0; lineIndex < kLinesPerChannel; ++lineIndex) {
                Line& line = lines_[static_cast<std::size_t>(channel) * kLinesPerChannel + lineIndex];
                line.length = std::max<std::size_t>(1U, std::min(line.maximumLength, tunedLength(lineIndex)));
                line.write %= line.length;
            }
        }
    }

    void reset() {
        std::fill(storage_.begin(), storage_.end(), 0.0);
        wetFilterState_.fill(0.0);
        for (auto& line : lines_) {
            line.write = 0;
            line.dampingState = 0.0;
        }
    }

    void process(double* interleaved, std::size_t frames, int channels) {
        const std::size_t channelCount = static_cast<std::size_t>(channels);
        for (std::size_t frame = 0; frame < frames; ++frame) {
            const std::size_t base = frame * channelCount;
            for (int channel = 0; channel < channels; ++channel) {
                const double dry = interleaved[base + static_cast<std::size_t>(channel)];
                Line* channelLines = lines_.data() + static_cast<std::size_t>(channel) * kLinesPerChannel;

                Line& preDelay = channelLines[0];
                const double delayed = storage_[preDelay.offset + preDelay.write];
                storage_[preDelay.offset + preDelay.write] = dry;
                preDelay.write = (preDelay.write + 1U) % preDelay.length;
                const double wetInput = dry * (1.0 - preDelayMix_) + delayed * preDelayMix_;

                double combSum = 0.0;
                for (std::size_t comb = 0; comb < kCombCount; ++comb) {
                    Line& line = channelLines[1 + comb];
                    const std::size_t address = line.offset + line.write;
                    const double output = storage_[address];
                    const double damped = output * (1.0 - damp_) + line.dampingState * damp_;
                    line.dampingState = damped;
                    storage_[address] = wetInput + damped * feedback_;
                    line.write = (line.write + 1U) % line.length;
                    combSum += output;
                }
                combSum *= 1.0 / static_cast<double>(kCombCount);

                double& filterState = wetFilterState_[static_cast<std::size_t>(channel)];
                filterState += wetFilterAlpha_ * (combSum - filterState);
                if (std::abs(filterState) < 1.0e-30) filterState = 0.0;
                double wet = filterState;

                for (std::size_t allpass = 0; allpass < kAllpassCount; ++allpass) {
                    Line& line = channelLines[1 + kCombCount + allpass];
                    const std::size_t address = line.offset + line.write;
                    const double buffered = storage_[address];
                    const double output = buffered - wet;
                    storage_[address] = wet + buffered * 0.5;
                    line.write = (line.write + 1U) % line.length;
                    wet = output;
                }
                interleaved[base + static_cast<std::size_t>(channel)] = dry * (1.0 - mix_) + wet * mix_;
            }
        }
    }

private:
    struct Line {
        std::size_t offset = 0;
        std::size_t maximumLength = 1;
        std::size_t length = 1;
        std::size_t write = 0;
        double dampingState = 0.0;
    };

    std::size_t maximumLength(std::size_t line) const {
        const double rate = static_cast<double>(sampleRate_);
        double milliseconds = 100.0;
        if (line >= 1 && line <= kCombCount) {
            milliseconds = kCombMilliseconds[line - 1] * 2.0;
        } else if (line > kCombCount) {
            milliseconds = kAllpassMilliseconds[line - 1 - kCombCount];
        }
        return static_cast<std::size_t>(std::ceil(milliseconds * 0.001 * rate)) + 4U;
    }

    std::size_t tunedLength(std::size_t line) const {
        double milliseconds = preDelay_ * 100.0;
        if (line >= 1 && line <= kCombCount) {
            milliseconds = kCombMilliseconds[line - 1] * sizeScale_;
        } else if (line > kCombCount) {
            milliseconds = kAllpassMilliseconds[line - 1 - kCombCount];
        }
        const std::size_t desired = static_cast<std::size_t>(milliseconds * 0.001 * sampleRate_) + 1U;
        return std::max<std::size_t>(1U, desired);
    }

    int sampleRate_ = 0;
    int channels_ = 0;
    double damp_ = 0.0;
    double wetFilterAlpha_ = 1.0;
    double feedback_ = 0.6;
    double preDelay_ = 0.0;
    double preDelayMix_ = 0.0;
    double sizeScale_ = 0.35;
    double mix_ = 0.0;
    std::array<double, aurisdsp::kMaxChannels> wetFilterState_{};
    std::array<Line, aurisdsp::kMaxChannels * kLinesPerChannel> lines_{};
    std::vector<double> storage_;
};

// ---------------------------------------------------------------------------
// Complete reference chain. Mirrors the documented stage order only:
//   HIBY     : MSEB -> MSEB preamp -> PEQ -> PEQ preamp
//   POWERAMP : PA EQ -> PA preamp -> tone -> limiter -> reverb -> DVC
//   HYBRID   : HIBY stages -> PA EQ/preamp/tone -> reverb -> DVC -> limiter
//   OFF / BIT_PERFECT: untouched
// ---------------------------------------------------------------------------
class ReferenceChain {
public:
    bool configure(int sampleRate, int channels) {
        sampleRate_ = sampleRate;
        channels_ = channels;
        reverb_.configure(sampleRate, channels);
        return true;
    }

    void apply(const ParamArray& clamped) {
        params_ = clamped;
        mode_ = parity::sanitizedMode(params_[aurisdsp::PARAM_MODE]);
        const bool hibyActive = mode_ == aurisdsp::Mode::HIBY || mode_ == aurisdsp::Mode::HYBRID;
        const bool powerampActive = mode_ == aurisdsp::Mode::POWERAMP || mode_ == aurisdsp::Mode::HYBRID;

        std::array<float, aurisdsp::kMsebDimensionCount> msebValues{};
        for (std::size_t dimension = 0; dimension < aurisdsp::kMsebDimensionCount; ++dimension) {
            msebValues[dimension] = params_[aurisdsp::msebValueIndex(dimension)];
        }
        const auto specs = msebReference(msebValues);
        const bool msebEnabled = hibyActive && parity::enabled(params_[aurisdsp::PARAM_MSEB_ENABLED]);
        for (std::size_t section = 0; section < specs.size(); ++section) {
            mseb_[section].set(specs[section].type, sampleRate_, specs[section].frequencyHz,
                specs[section].q, specs[section].gainDb, msebEnabled && specs[section].active);
        }

        const bool peqEnabled = hibyActive && parity::enabled(params_[aurisdsp::PARAM_PEQ_ENABLED]);
        for (std::size_t band = 0; band < aurisdsp::kPeqBandCount; ++band) {
            const std::size_t base = aurisdsp::peqBandIndex(band);
            const aurisdsp::BiquadType type = parity::sanitizedType(params_[base + 1]);
            const double gain = params_[base + 4];
            const bool gainDependent = type == aurisdsp::BiquadType::PEAKING ||
                type == aurisdsp::BiquadType::LOW_SHELF || type == aurisdsp::BiquadType::HIGH_SHELF;
            const bool bandEnabled = peqEnabled && parity::enabled(params_[base]) &&
                (!gainDependent || gain != 0.0);
            peq_[band].set(type, sampleRate_, params_[base + 2], params_[base + 3], gain, bandEnabled);
        }

        constexpr double frequencies[aurisdsp::kPowerampBandCount] = {
            31.0, 62.0, 125.0, 250.0, 500.0,
            1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
        };
        const bool powerampEqEnabled = powerampActive &&
            parity::enabled(params_[aurisdsp::PARAM_POWERAMP_EQ_ENABLED]);
        for (std::size_t band = 0; band < aurisdsp::kPowerampBandCount; ++band) {
            const aurisdsp::BiquadType type = band == 0 ? aurisdsp::BiquadType::LOW_SHELF :
                (band == aurisdsp::kPowerampBandCount - 1 ? aurisdsp::BiquadType::HIGH_SHELF :
                    aurisdsp::BiquadType::PEAKING);
            const double gain = params_[aurisdsp::powerampBandIndex(band)];
            poweramp_[band].set(type, sampleRate_, frequencies[band], 1.0, gain,
                powerampEqEnabled && gain != 0.0);
        }

        const bool toneEnabled = powerampActive && parity::enabled(params_[aurisdsp::PARAM_TONE_ENABLED]);
        const double bass = params_[aurisdsp::PARAM_BASS_DB];
        const double treble = params_[aurisdsp::PARAM_TREBLE_DB];
        tone_[0].set(aurisdsp::BiquadType::LOW_SHELF, sampleRate_, 250.0, 0.707, bass,
            toneEnabled && bass != 0.0);
        tone_[1].set(aurisdsp::BiquadType::HIGH_SHELF, sampleRate_, 4000.0, 0.707, treble,
            toneEnabled && treble != 0.0);

        limiterCeiling_ = parity::dbToGain(params_[aurisdsp::PARAM_LIMITER_CEILING_DB]);
        const double releaseSeconds = static_cast<double>(params_[aurisdsp::PARAM_LIMITER_RELEASE_MS]) * 0.001;
        limiterRelease_ = std::exp(-1.0 / (releaseSeconds * sampleRate_));
        limiterRunning_ = powerampActive && parity::enabled(params_[aurisdsp::PARAM_LIMITER_ENABLED]);
        limiterGain_ = 1.0;

        reverb_.tune(params_);
        reverbRunning_ = powerampActive && parity::enabled(params_[aurisdsp::PARAM_REVERB_ENABLED]) &&
            params_[aurisdsp::PARAM_REVERB_MIX] > 0.0f;

        msebOn_ = parity::enabled(params_[aurisdsp::PARAM_MSEB_ENABLED]);
        peqOn_ = parity::enabled(params_[aurisdsp::PARAM_PEQ_ENABLED]);
        powerampEqOn_ = parity::enabled(params_[aurisdsp::PARAM_POWERAMP_EQ_ENABLED]);
        toneOn_ = parity::enabled(params_[aurisdsp::PARAM_TONE_ENABLED]);
        dvcOn_ = parity::enabled(params_[aurisdsp::PARAM_DVC_ENABLED]);
    }

    void process(float* interleaved, std::size_t frames) {
        if (frames == 0) return;
        if (mode_ == aurisdsp::Mode::OFF || mode_ == aurisdsp::Mode::BIT_PERFECT) return;
        const std::size_t channelCount = static_cast<std::size_t>(channels_);
        const std::size_t count = frames * channelCount;
        scratch_.resize(count);
        for (std::size_t index = 0; index < count; ++index) {
            const double sample = static_cast<double>(interleaved[index]);
            scratch_[index] = std::isfinite(sample) ? sample : 0.0;
        }
        double* samples = scratch_.data();

        if (mode_ == aurisdsp::Mode::HIBY) {
            processHiby(samples, frames);
        } else if (mode_ == aurisdsp::Mode::POWERAMP) {
            processPoweramp(samples, frames, true);
            if (reverbRunning_) reverb_.process(samples, frames, channels_);
            applyDvc(samples, count);
        } else if (mode_ == aurisdsp::Mode::HYBRID) {
            processHiby(samples, frames);
            processPoweramp(samples, frames, false);
            if (reverbRunning_) reverb_.process(samples, frames, channels_);
            applyDvc(samples, count);
            if (limiterRunning_) processLimiter(samples, frames);
        }

        for (std::size_t index = 0; index < count; ++index) {
            const double sample = samples[index];
            if (!std::isfinite(sample)) {
                interleaved[index] = 0.0f;
                continue;
            }
            const float output = static_cast<float>(sample);
            interleaved[index] = std::isfinite(output) ? output : 0.0f;
        }
    }

    // Cross-check variant used for a tolerance comparison only: same filters,
    // direct-form-I topology, no limiter/reverb state (those are compared
    // against the TDF-II model instead).
    void processFiltersDf1(float* interleaved, std::size_t frames) {
        if (frames == 0) return;
        if (mode_ == aurisdsp::Mode::OFF || mode_ == aurisdsp::Mode::BIT_PERFECT) return;
        const std::size_t channelCount = static_cast<std::size_t>(channels_);
        const std::size_t count = frames * channelCount;
        df1Scratch_.resize(count);
        for (std::size_t index = 0; index < count; ++index) {
            const double sample = static_cast<double>(interleaved[index]);
            df1Scratch_[index] = std::isfinite(sample) ? sample : 0.0;
        }
        double* samples = df1Scratch_.data();
        const bool hibyActive = mode_ == aurisdsp::Mode::HIBY || mode_ == aurisdsp::Mode::HYBRID;
        const bool powerampActive = mode_ == aurisdsp::Mode::POWERAMP || mode_ == aurisdsp::Mode::HYBRID;
        if (hibyActive) {
            if (msebOn_) {
                processBankDf1(msebDf1_, samples, frames);
                applyGain(samples, count, parity::dbToGain(params_[aurisdsp::PARAM_MSEB_PREAMP_DB]));
            }
            if (peqOn_) {
                processBankDf1(peqDf1_, samples, frames);
                applyGain(samples, count, parity::dbToGain(params_[aurisdsp::PARAM_PEQ_PREAMP_DB]));
            }
        }
        if (powerampActive) {
            if (powerampEqOn_) {
                processBankDf1(powerampDf1_, samples, frames);
                applyGain(samples, count, parity::dbToGain(params_[aurisdsp::PARAM_POWERAMP_PREAMP_DB]));
            }
            if (toneOn_) processBankDf1(toneDf1_, samples, frames);
        }
        for (std::size_t index = 0; index < count; ++index) {
            const double sample = samples[index];
            const float output = std::isfinite(sample) ? static_cast<float>(sample) : 0.0f;
            interleaved[index] = std::isfinite(output) ? output : 0.0f;
        }
    }

    // Builds the same DF-I banks for processFiltersDf1().
    void applyDf1() {
        std::array<float, aurisdsp::kMsebDimensionCount> msebValues{};
        for (std::size_t dimension = 0; dimension < aurisdsp::kMsebDimensionCount; ++dimension) {
            msebValues[dimension] = params_[aurisdsp::msebValueIndex(dimension)];
        }
        const auto specs = msebReference(msebValues);
        const bool hibyActive = mode_ == aurisdsp::Mode::HIBY || mode_ == aurisdsp::Mode::HYBRID;
        const bool powerampActive = mode_ == aurisdsp::Mode::POWERAMP || mode_ == aurisdsp::Mode::HYBRID;
        const bool msebEnabled = hibyActive && parity::enabled(params_[aurisdsp::PARAM_MSEB_ENABLED]);
        for (std::size_t section = 0; section < specs.size(); ++section) {
            msebDf1_[section].set(specs[section].type, sampleRate_, specs[section].frequencyHz,
                specs[section].q, specs[section].gainDb, msebEnabled && specs[section].active);
        }
        const bool peqEnabled = hibyActive && parity::enabled(params_[aurisdsp::PARAM_PEQ_ENABLED]);
        for (std::size_t band = 0; band < aurisdsp::kPeqBandCount; ++band) {
            const std::size_t base = aurisdsp::peqBandIndex(band);
            const aurisdsp::BiquadType type = parity::sanitizedType(params_[base + 1]);
            const double gain = params_[base + 4];
            const bool gainDependent = type == aurisdsp::BiquadType::PEAKING ||
                type == aurisdsp::BiquadType::LOW_SHELF || type == aurisdsp::BiquadType::HIGH_SHELF;
            const bool bandEnabled = peqEnabled && parity::enabled(params_[base]) &&
                (!gainDependent || gain != 0.0);
            peqDf1_[band].set(type, sampleRate_, params_[base + 2], params_[base + 3], gain, bandEnabled);
        }
        constexpr double frequencies[aurisdsp::kPowerampBandCount] = {
            31.0, 62.0, 125.0, 250.0, 500.0,
            1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
        };
        const bool powerampEqEnabled = powerampActive &&
            parity::enabled(params_[aurisdsp::PARAM_POWERAMP_EQ_ENABLED]);
        for (std::size_t band = 0; band < aurisdsp::kPowerampBandCount; ++band) {
            const aurisdsp::BiquadType type = band == 0 ? aurisdsp::BiquadType::LOW_SHELF :
                (band == aurisdsp::kPowerampBandCount - 1 ? aurisdsp::BiquadType::HIGH_SHELF :
                    aurisdsp::BiquadType::PEAKING);
            const double gain = params_[aurisdsp::powerampBandIndex(band)];
            powerampDf1_[band].set(type, sampleRate_, frequencies[band], 1.0, gain,
                powerampEqEnabled && gain != 0.0);
        }
        const bool toneEnabled = powerampActive && parity::enabled(params_[aurisdsp::PARAM_TONE_ENABLED]);
        toneDf1_[0].set(aurisdsp::BiquadType::LOW_SHELF, sampleRate_, 250.0, 0.707,
            params_[aurisdsp::PARAM_BASS_DB], toneEnabled && params_[aurisdsp::PARAM_BASS_DB] != 0.0);
        toneDf1_[1].set(aurisdsp::BiquadType::HIGH_SHELF, sampleRate_, 4000.0, 0.707,
            params_[aurisdsp::PARAM_TREBLE_DB], toneEnabled && params_[aurisdsp::PARAM_TREBLE_DB] != 0.0);
    }

private:
    template <std::size_t N, typename BiquadT>
    static void processBank(
        std::array<BiquadT, N>& bank,
        double* samples,
        std::size_t frames,
        int channels) {
        for (std::size_t band = 0; band < N; ++band) {
            BiquadT& biquad = bank[band];
            if (!biquad.active()) continue;
            for (std::size_t frame = 0; frame < frames; ++frame) {
                const std::size_t base = frame * static_cast<std::size_t>(channels);
                for (int channel = 0; channel < channels; ++channel) {
                    const std::size_t index = base + static_cast<std::size_t>(channel);
                    samples[index] = biquad.process(samples[index], static_cast<std::size_t>(channel));
                }
            }
        }
    }

    template <std::size_t N>
    static void processBankDf1(
        std::array<Df1Biquad, N>& bank,
        double* samples,
        std::size_t frames) {
        for (std::size_t band = 0; band < bank.size(); ++band) {
            if (!bank[band].active()) continue;
            for (std::size_t frame = 0; frame < frames; ++frame) {
                const std::size_t base = frame * 2;
                for (std::size_t channel = 0; channel < 2; ++channel) {
                    const std::size_t index = base + channel;
                    samples[index] = bank[band].process(samples[index], channel);
                }
            }
        }
    }

    static void applyGain(double* samples, std::size_t count, double gain) {
        if (gain == 1.0) return;
        for (std::size_t index = 0; index < count; ++index) samples[index] *= gain;
    }

    void processHiby(double* samples, std::size_t frames) {
        const std::size_t count = frames * static_cast<std::size_t>(channels_);
        if (msebOn_) {
            processBank(mseb_, samples, frames, channels_);
            applyGain(samples, count, parity::dbToGain(params_[aurisdsp::PARAM_MSEB_PREAMP_DB]));
        }
        if (peqOn_) {
            processBank(peq_, samples, frames, channels_);
            applyGain(samples, count, parity::dbToGain(params_[aurisdsp::PARAM_PEQ_PREAMP_DB]));
        }
    }

    void processPoweramp(double* samples, std::size_t frames, bool earlyLimiter) {
        const std::size_t count = frames * static_cast<std::size_t>(channels_);
        if (powerampEqOn_) {
            processBank(poweramp_, samples, frames, channels_);
            applyGain(samples, count, parity::dbToGain(params_[aurisdsp::PARAM_POWERAMP_PREAMP_DB]));
        }
        if (toneOn_) processBank(tone_, samples, frames, channels_);
        if (earlyLimiter && limiterRunning_) processLimiter(samples, frames);
    }

    void applyDvc(double* samples, std::size_t count) {
        if (dvcOn_) applyGain(samples, count, parity::dbToGain(params_[aurisdsp::PARAM_DVC_GAIN_DB]));
    }

    void processLimiter(double* samples, std::size_t frames) {
        const std::size_t channelCount = static_cast<std::size_t>(channels_);
        for (std::size_t frame = 0; frame < frames; ++frame) {
            const std::size_t base = frame * channelCount;
            double peak = 0.0;
            for (std::size_t channel = 0; channel < channelCount; ++channel) {
                const double magnitude = std::abs(samples[base + channel]);
                if (std::isfinite(magnitude) && magnitude > peak) peak = magnitude;
            }
            const double target = peak > limiterCeiling_ && peak > 0.0 ? limiterCeiling_ / peak : 1.0;
            if (target < limiterGain_) {
                limiterGain_ = target;
            } else {
                limiterGain_ = target + (limiterGain_ - target) * limiterRelease_;
            }
            limiterGain_ = std::max(0.0, std::min(1.0, limiterGain_));
            for (std::size_t channel = 0; channel < channelCount; ++channel) {
                samples[base + channel] *= limiterGain_;
            }
        }
    }

    int sampleRate_ = 0;
    int channels_ = 0;
    aurisdsp::Mode mode_ = aurisdsp::Mode::OFF;
    ParamArray params_{};

    std::array<Tdf2Biquad, 13> mseb_{};
    std::array<Tdf2Biquad, aurisdsp::kPeqBandCount> peq_{};
    std::array<Tdf2Biquad, aurisdsp::kPowerampBandCount> poweramp_{};
    std::array<Tdf2Biquad, 2> tone_{};

    std::array<Df1Biquad, 13> msebDf1_{};
    std::array<Df1Biquad, aurisdsp::kPeqBandCount> peqDf1_{};
    std::array<Df1Biquad, aurisdsp::kPowerampBandCount> powerampDf1_{};
    std::array<Df1Biquad, 2> toneDf1_{};

    ReferenceReverb reverb_{};
    std::vector<double> scratch_{};
    std::vector<double> df1Scratch_{};

    double limiterGain_ = 1.0;
    double limiterRelease_ = 0.0;
    double limiterCeiling_ = 1.0;
    bool limiterRunning_ = false;
    bool reverbRunning_ = false;
    bool msebOn_ = false;
    bool peqOn_ = false;
    bool powerampEqOn_ = false;
    bool toneOn_ = false;
    bool dvcOn_ = false;
};

}  // namespace parity

#endif  // AURIS_PARITY_REFERENCE_MODEL_H
