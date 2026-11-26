#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <complex>
#include <cstdint>
#include <cstring>
#include <functional>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "AurisDspEngine.h"
#include "Biquad.h"

namespace {

constexpr double kPi = 3.141592653589793238462643383279502884;
constexpr double kSampleRate = 48000.0;

void require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

void requireNear(double actual, double expected, double tolerance, const std::string& message) {
    if (!(std::abs(actual - expected) <= tolerance)) {
        throw std::runtime_error(message + ": actual=" + std::to_string(actual) +
            " expected=" + std::to_string(expected) + " tolerance=" + std::to_string(tolerance));
    }
}

aurisdsp::ParamArray activeParams(aurisdsp::Mode mode) {
    aurisdsp::ParamArray params = aurisdsp::makeDefaultParams();
    params[aurisdsp::PARAM_MODE] = static_cast<float>(mode);
    return params;
}

void publish(aurisdsp::Engine& engine, const aurisdsp::ParamArray& params) {
    engine.setParams(params.data(), params.size());
}

std::vector<float> sine(double frequency, std::size_t count, double amplitude = 0.1) {
    std::vector<float> output(count);
    for (std::size_t index = 0; index < count; ++index) {
        output[index] = static_cast<float>(amplitude *
            std::sin(2.0 * kPi * frequency * static_cast<double>(index) / kSampleRate));
    }
    return output;
}

double rms(const std::vector<float>& samples, std::size_t first = 0) {
    require(first < samples.size(), "RMS range must not be empty");
    double sum = 0.0;
    for (std::size_t index = first; index < samples.size(); ++index) {
        const double value = samples[index];
        sum += value * value;
    }
    return std::sqrt(sum / static_cast<double>(samples.size() - first));
}

double impulseMagnitude(
    aurisdsp::BiquadType type,
    double filterFrequency,
    double testFrequency,
    double gainDb = 0.0,
    double q = 1.0) {
    constexpr std::size_t kImpulseSamples = 32768;
    aurisdsp::Biquad biquad;
    biquad.set(type, kSampleRate, filterFrequency, q, gainDb, true);
    std::complex<double> sum(0.0, 0.0);
    for (std::size_t index = 0; index < kImpulseSamples; ++index) {
        const double input = index == 0 ? 1.0 : 0.0;
        const double output = biquad.process(input, 0);
        require(std::isfinite(output), "Impulse response became non-finite");
        const double phase = -2.0 * kPi * testFrequency * static_cast<double>(index) / kSampleRate;
        sum += output * std::complex<double>(std::cos(phase), std::sin(phase));
    }
    return std::abs(sum);
}

struct ExpectedMsebSection {
    double frequencyHz;
    double q;
    aurisdsp::BiquadType type;
    double gainScale;
    std::size_t dimension;
};

constexpr ExpectedMsebSection kExpectedMsebSections[aurisdsp::kMsebSectionCount] = {
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
};

double msebImpulseMagnitude(std::size_t dimension, double testFrequency) {
    constexpr std::size_t kImpulseSamples = 32768;
    std::array<float, aurisdsp::kMsebDimensionCount> values{};
    values[dimension] = 1.0f;
    std::array<aurisdsp::MsebSectionSpec, aurisdsp::kMsebSectionCount> specs{};
    aurisdsp::buildMsebMapping(values, 100.0f, specs);
    std::array<aurisdsp::Biquad, aurisdsp::kMsebSectionCount> bank{};
    for (std::size_t section = 0; section < specs.size(); ++section) {
        bank[section].set(specs[section].type, kSampleRate, specs[section].frequencyHz,
            specs[section].q, specs[section].gainDb, specs[section].enabled);
    }
    std::complex<double> sum(0.0, 0.0);
    for (std::size_t index = 0; index < kImpulseSamples; ++index) {
        double sample = index == 0 ? 1.0 : 0.0;
        for (auto& biquad : bank) {
            if (biquad.active()) sample = biquad.process(sample, 0);
        }
        require(std::isfinite(sample), "MSEB impulse response became non-finite");
        const double phase = -2.0 * kPi * testFrequency * static_cast<double>(index) / kSampleRate;
        sum += sample * std::complex<double>(std::cos(phase), std::sin(phase));
    }
    return std::abs(sum);
}

bool sameBits(float a, float b) {
    std::uint32_t x = 0;
    std::uint32_t y = 0;
    std::memcpy(&x, &a, sizeof(x));
    std::memcpy(&y, &b, sizeof(y));
    return x == y;
}

struct RbjReference {
    double b0;
    double b1;
    double b2;
    double a1;
    double a2;
};

/**
 * Independent re-derivation of the recovered RBJ formulas from
 * tools/aurisdsp/HIBY_MSEB_RECOVERED.md: A = 10^(gainDb/40),
 * omega = 2*pi*f/fs, alpha = sin(omega)/(2*Q), shelves use sqrt(A), and all
 * coefficients are normalized by a0. The frequency/Q clamps mirror the
 * documented engine behavior (0.45*fs cap, f >= 1 Hz, Q >= 0.01).
 */
RbjReference referenceRbj(
    aurisdsp::BiquadType type,
    double sampleRate,
    double frequencyHz,
    double q,
    double gainDb) {
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
        default:
            throw std::runtime_error("reference covers only the recovered MSEB filter types");
    }
    return RbjReference{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
}

void requireCoeffNear(double actual, double expected, const std::string& label) {
    const double tolerance = 1.0e-13 * std::max(1.0, std::abs(expected));
    if (!(std::abs(actual - expected) <= tolerance)) {
        throw std::runtime_error(label + ": actual=" + std::to_string(actual) +
            " expected=" + std::to_string(expected));
    }
}

double engineImpulseMagnitude(aurisdsp::Engine& engine, double testFrequency) {
    constexpr std::size_t kImpulseSamples = 32768;
    std::vector<float> block(kImpulseSamples, 0.0f);
    block[0] = 1.0f;
    require(engine.process(block.data(), block.size()), "Engine impulse process");
    std::complex<double> sum(0.0, 0.0);
    for (std::size_t index = 0; index < kImpulseSamples; ++index) {
        require(std::isfinite(block[index]), "Engine impulse response became non-finite");
        const double phase = -2.0 * kPi * testFrequency * static_cast<double>(index) / kSampleRate;
        sum += static_cast<double>(block[index]) *
            std::complex<double>(std::cos(phase), std::sin(phase));
    }
    return std::abs(sum);
}

void testRecoveredMsebConstantsAndResponses() {
    static_assert(aurisdsp::kMsebSectionCount == 13, "Recovered MSEB must have 13 sections");
    for (std::size_t activeDimension = 0;
         activeDimension < aurisdsp::kMsebDimensionCount; ++activeDimension) {
        std::array<float, aurisdsp::kMsebDimensionCount> values{};
        values[activeDimension] = 1.0f;
        std::array<aurisdsp::MsebSectionSpec, aurisdsp::kMsebSectionCount> specs{};
        aurisdsp::buildMsebMapping(values, 100.0f, specs);
        for (std::size_t section = 0; section < specs.size(); ++section) {
            const auto& expected = kExpectedMsebSections[section];
            const auto& actual = specs[section];
            require(actual.frequencyHz == expected.frequencyHz && actual.q == expected.q &&
                actual.type == expected.type, "Recovered MSEB frequency/Q/type constant mismatch");
            const bool shouldBeActive = expected.dimension == activeDimension;
            require(actual.enabled == shouldBeActive,
                activeDimension == 0 ? "All four temperature sections must be simultaneous" :
                    "Only the selected non-temperature section may be active");
            const double expectedGain = shouldBeActive ? 100.0 * expected.gainScale : 0.0;
            require(actual.gainDb == expectedGain, "Recovered raw-slider gain scale mismatch");
        }
    }

    {
        std::array<float, aurisdsp::kMsebDimensionCount> values{};
        values[4] = -0.5f;
        std::array<aurisdsp::MsebSectionSpec, aurisdsp::kMsebSectionCount> specs{};
        aurisdsp::buildMsebMapping(values, 25.0f, specs);
        require(specs[7].enabled && specs[7].gainDb == -25.0,
            "Normalized -0.5 must become raw -50 and Voice gain -25 dB at range 25");
    }

    requireNear(msebImpulseMagnitude(0, 0.0), 1.0, 1.0e-8,
        "Four-temperature-section DC response");
    requireNear(msebImpulseMagnitude(0, kSampleRate * 0.5), std::pow(10.0, -24.0 / 20.0), 1.0e-6,
        "Four simultaneous temperature shelves at Nyquist");
    for (std::size_t section = 4; section < aurisdsp::kMsebSectionCount; ++section) {
        const auto& expected = kExpectedMsebSections[section];
        const double testFrequency = expected.type == aurisdsp::BiquadType::LOW_SHELF ? 0.0 :
            (expected.type == aurisdsp::BiquadType::HIGH_SHELF ? kSampleRate * 0.5 : expected.frequencyHz);
        const double expectedGain = std::pow(10.0, 100.0 * expected.gainScale / 20.0);
        requireNear(msebImpulseMagnitude(expected.dimension, testFrequency), expectedGain,
            std::max(0.003, expectedGain * 0.003),
            "Recovered MSEB RBJ expected response for section " + std::to_string(section));
    }
}

void testAllBiquadImpulseResponses() {
    const double boost = std::pow(10.0, 6.0 / 20.0);
    requireNear(impulseMagnitude(aurisdsp::BiquadType::PEAKING, 1000.0, 1000.0, 6.0),
        boost, 0.002, "Peaking center gain");
    requireNear(impulseMagnitude(aurisdsp::BiquadType::LOW_SHELF, 60.0, 20.0, 6.0, 0.7),
        boost, 0.03, "Low-shelf low-frequency gain");
    requireNear(impulseMagnitude(aurisdsp::BiquadType::LOW_SHELF, 60.0, 10000.0, 6.0, 0.7),
        1.0, 0.002, "Low-shelf high-frequency unity gain");
    requireNear(impulseMagnitude(aurisdsp::BiquadType::HIGH_SHELF, 4000.0, 10000.0, 6.0, 0.7),
        boost, 0.03, "High-shelf high-frequency gain");
    requireNear(impulseMagnitude(aurisdsp::BiquadType::HIGH_SHELF, 4000.0, 20.0, 6.0, 0.7),
        1.0, 0.002, "High-shelf low-frequency unity gain");
    require(impulseMagnitude(aurisdsp::BiquadType::NOTCH, 1000.0, 1000.0) < 1.0e-4,
        "Notch must reject its center");
    requireNear(impulseMagnitude(aurisdsp::BiquadType::NOTCH, 1000.0, 100.0),
        1.0, 0.01, "Notch away from center");
    requireNear(impulseMagnitude(aurisdsp::BiquadType::BAND_PASS, 1000.0, 1000.0),
        1.0, 0.002, "Band-pass center gain");
    require(impulseMagnitude(aurisdsp::BiquadType::BAND_PASS, 1000.0, 10.0) < 0.02,
        "Band-pass low-frequency rejection");
    requireNear(impulseMagnitude(aurisdsp::BiquadType::LOW_PASS, 1000.0, 100.0, 0.0, 0.707),
        1.0, 0.002, "Low-pass passband");
    require(impulseMagnitude(aurisdsp::BiquadType::LOW_PASS, 1000.0, 10000.0, 0.0, 0.707) < 0.02,
        "Low-pass stopband");
    requireNear(impulseMagnitude(aurisdsp::BiquadType::HIGH_PASS, 1000.0, 10000.0, 0.0, 0.707),
        1.0, 0.002, "High-pass passband");
    require(impulseMagnitude(aurisdsp::BiquadType::HIGH_PASS, 1000.0, 100.0, 0.0, 0.707) < 0.02,
        "High-pass stopband");
}

float fromBits(std::uint32_t bits) {
    float value;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

void testExactBypassAndNulls() {
    aurisdsp::Engine engine;
    require(engine.configure(48000, 2, 64), "Bypass configuration");
    aurisdsp::ParamArray params = activeParams(aurisdsp::Mode::OFF);
    params[aurisdsp::PARAM_MSEB_ENABLED] = 1.0f;
    params[aurisdsp::PARAM_MSEB_PREAMP_DB] = 45.0f;
    params[aurisdsp::PARAM_PEQ_ENABLED] = 1.0f;
    params[aurisdsp::PARAM_PEQ_PREAMP_DB] = -45.0f;
    params[aurisdsp::PARAM_POWERAMP_EQ_ENABLED] = 1.0f;
    params[aurisdsp::PARAM_POWERAMP_PREAMP_DB] = 12.0f;
    params[aurisdsp::PARAM_LIMITER_ENABLED] = 1.0f;
    params[aurisdsp::PARAM_REVERB_ENABLED] = 1.0f;
    params[aurisdsp::PARAM_REVERB_MIX] = 1.0f;
    params[aurisdsp::PARAM_DVC_ENABLED] = 1.0f;
    params[aurisdsp::PARAM_DVC_GAIN_DB] = 24.0f;
    publish(engine, params);

    const std::array<float, 12> original = {
        0.0f, -0.0f, 1.0f, -1.0f,
        std::numeric_limits<float>::infinity(), -std::numeric_limits<float>::infinity(),
        fromBits(0x7fc00001U), fromBits(0xffc00002U),
        std::numeric_limits<float>::denorm_min(), -std::numeric_limits<float>::denorm_min(),
        0.12345678f, -0.87654321f,
    };
    std::vector<float> samples(original.begin(), original.end());
    require(engine.process(samples.data(), 6), "OFF process");
    require(std::memcmp(samples.data(), original.data(), sizeof(original)) == 0,
        "OFF must preserve every Float32 bit, including NaNs and signed zero");

    params[aurisdsp::PARAM_MODE] = static_cast<float>(aurisdsp::Mode::BIT_PERFECT);
    publish(engine, params);
    require(engine.process(samples.data(), 6), "Bit-perfect process");
    require(std::memcmp(samples.data(), original.data(), sizeof(original)) == 0,
        "BIT_PERFECT must preserve every Float32 bit");
    require(engine.process(nullptr, 0), "Empty process must be a valid null operation");

    params[aurisdsp::PARAM_MODE] = static_cast<float>(aurisdsp::Mode::HYBRID);
    publish(engine, params);
    std::vector<float> zeros(64 * 2, 0.0f);
    require(engine.process(zeros.data(), 64), "Active zero-signal process");
    require(std::all_of(zeros.begin(), zeros.end(), [](float value) { return value == 0.0f; }),
        "Zero input must remain zero through an impulse/null run");
}

void testPeqSineAndPreamp() {
    aurisdsp::Engine engine;
    require(engine.configure(48000, 1, 16384), "PEQ configuration");
    auto params = activeParams(aurisdsp::Mode::HIBY);
    params[aurisdsp::PARAM_PEQ_ENABLED] = 1.0f;
    params[aurisdsp::PARAM_PEQ_PREAMP_DB] = -3.0f;
    const std::size_t band = aurisdsp::peqBandIndex(0);
    params[band] = 1.0f;
    params[band + 1] = static_cast<float>(aurisdsp::BiquadType::PEAKING);
    params[band + 2] = 1000.0f;
    params[band + 3] = 1.0f;
    params[band + 4] = 6.0f;
    publish(engine, params);

    const std::vector<float> input = sine(1000.0, 16384);
    std::vector<float> output = input;
    require(engine.process(output.data(), output.size()), "PEQ sine process");
    const double expected = std::pow(10.0, 3.0 / 20.0);
    requireNear(rms(output, 2048) / rms(input, 2048), expected, 0.003,
        "PEQ +6 dB followed by -3 dB preamp");
}

void testMsebPreampAndRangePreservation() {
    const std::vector<float> input = sine(20.0, 16384);
    aurisdsp::Engine preampEngine;
    require(preampEngine.configure(48000, 1, 16384), "MSEB preamp configuration");
    auto preamp = activeParams(aurisdsp::Mode::HIBY);
    preamp[aurisdsp::PARAM_MSEB_ENABLED] = 1.0f;
    preamp[aurisdsp::PARAM_MSEB_PREAMP_DB] = -6.0f;
    publish(preampEngine, preamp);
    std::vector<float> preampOutput = input;
    require(preampEngine.process(preampOutput.data(), preampOutput.size()), "MSEB preamp process");
    requireNear(rms(preampOutput, 2048) / rms(input, 2048), std::pow(10.0, -6.0 / 20.0), 0.0001,
        "MSEB preamp with neutral dimensions");

    aurisdsp::Engine fullRangeEngine;
    aurisdsp::Engine quarterRangeEngine;
    require(fullRangeEngine.configure(48000, 1, 16384), "Full-range configuration");
    require(quarterRangeEngine.configure(48000, 1, 16384), "Quarter-range configuration");
    auto full = activeParams(aurisdsp::Mode::HIBY);
    full[aurisdsp::PARAM_MSEB_ENABLED] = 1.0f;
    full[aurisdsp::msebValueIndex(1)] = 1.0f;
    full[aurisdsp::PARAM_MSEB_RANGE_PERCENT] = 100.0f;
    auto quarter = full;
    quarter[aurisdsp::PARAM_MSEB_RANGE_PERCENT] = 25.0f;
    publish(fullRangeEngine, full);
    publish(quarterRangeEngine, quarter);
    std::vector<float> fullOutput = input;
    std::vector<float> quarterOutput = input;
    require(fullRangeEngine.process(fullOutput.data(), fullOutput.size()), "Full-range process");
    require(quarterRangeEngine.process(quarterOutput.data(), quarterOutput.size()), "Quarter-range process");
    require(std::memcmp(fullOutput.data(), quarterOutput.data(), fullOutput.size() * sizeof(float)) == 0,
        "Range switches must preserve raw sliders and produce identical output");
}

void testLimiterAndHybridFinalSafety() {
    aurisdsp::Engine poweramp;
    require(poweramp.configure(48000, 1, 4096), "Poweramp limiter configuration");
    auto params = activeParams(aurisdsp::Mode::POWERAMP);
    params[aurisdsp::PARAM_POWERAMP_EQ_ENABLED] = 1.0f;
    params[aurisdsp::PARAM_POWERAMP_PREAMP_DB] = 12.0f;
    params[aurisdsp::PARAM_LIMITER_ENABLED] = 1.0f;
    params[aurisdsp::PARAM_LIMITER_CEILING_DB] = -6.0f;
    publish(poweramp, params);
    std::vector<float> impulse(4096, 0.0f);
    impulse[0] = 1.0f;
    require(poweramp.process(impulse.data(), impulse.size()), "Poweramp limiter impulse");
    const double ceiling = std::pow(10.0, -6.0 / 20.0);
    for (float value : impulse) require(std::abs(value) <= ceiling + 1.0e-6,
        "Zero-attack limiter exceeded its ceiling");
    requireNear(impulse[0], ceiling, 1.0e-6, "Limited impulse amplitude");

    aurisdsp::Engine hybrid;
    require(hybrid.configure(48000, 1, 4096), "Hybrid limiter configuration");
    auto hybridParams = activeParams(aurisdsp::Mode::HYBRID);
    hybridParams[aurisdsp::PARAM_MSEB_ENABLED] = 1.0f;
    hybridParams[aurisdsp::PARAM_MSEB_PREAMP_DB] = 45.0f;
    hybridParams[aurisdsp::PARAM_POWERAMP_EQ_ENABLED] = 1.0f;
    hybridParams[aurisdsp::PARAM_POWERAMP_PREAMP_DB] = 12.0f;
    hybridParams[aurisdsp::PARAM_DVC_ENABLED] = 1.0f;
    hybridParams[aurisdsp::PARAM_DVC_GAIN_DB] = 24.0f;
    hybridParams[aurisdsp::PARAM_LIMITER_ENABLED] = 1.0f;
    hybridParams[aurisdsp::PARAM_LIMITER_CEILING_DB] = -12.0f;
    publish(hybrid, hybridParams);
    std::fill(impulse.begin(), impulse.end(), 0.0f);
    impulse[0] = 1.0f;
    require(hybrid.process(impulse.data(), impulse.size()), "Hybrid final-limiter impulse");
    const double hybridCeiling = std::pow(10.0, -12.0 / 20.0);
    requireNear(impulse[0], hybridCeiling, 1.0e-6, "Hybrid limiter must follow all gain stages");
}

void testReverbImpulseTailAndChunking() {
    aurisdsp::Engine engine;
    require(engine.configure(48000, 2, 64), "Reverb configuration");
    auto params = activeParams(aurisdsp::Mode::POWERAMP);
    params[aurisdsp::PARAM_REVERB_ENABLED] = 1.0f;
    params[aurisdsp::PARAM_REVERB_DAMP] = 0.2f;
    params[aurisdsp::PARAM_REVERB_FILTER] = 0.7f;
    params[aurisdsp::PARAM_REVERB_FADE] = 0.8f;
    params[aurisdsp::PARAM_REVERB_PRE_DELAY] = 0.1f;
    params[aurisdsp::PARAM_REVERB_PRE_DELAY_MIX] = 1.0f;
    params[aurisdsp::PARAM_REVERB_SIZE] = 0.0f;
    params[aurisdsp::PARAM_REVERB_MIX] = 0.5f;
    publish(engine, params);

    std::vector<float> impulse(2048 * 2, 0.0f);
    impulse[0] = 1.0f;
    impulse[1] = 1.0f;
    for (std::size_t first = 0; first < 2048; first += 64) {
        require(engine.process(impulse.data() + first * 2, 64), "Chunked reverb process");
    }
    requireNear(impulse[0], 0.5, 1.0e-6, "Reverb dry component at impulse");
    requireNear(impulse[1], 0.5, 1.0e-6, "Independent right dry component");
    bool tail = false;
    for (std::size_t frame = 231; frame < 2048; ++frame) {
        require(std::isfinite(impulse[frame * 2]) && std::isfinite(impulse[frame * 2 + 1]),
            "Reverb tail must remain finite");
        if (impulse[frame * 2] != 0.0f && impulse[frame * 2 + 1] != 0.0f) tail = true;
    }
    require(tail, "Pre-delayed reverb impulse must produce a tail");
}

void testResetAndEightChannels() {
    aurisdsp::Engine resetEngine;
    require(resetEngine.configure(48000, 1, 4096), "Reset configuration");
    auto params = activeParams(aurisdsp::Mode::HIBY);
    params[aurisdsp::PARAM_PEQ_ENABLED] = 1.0f;
    const std::size_t band = aurisdsp::peqBandIndex(2);
    params[band] = 1.0f;
    params[band + 2] = 700.0f;
    params[band + 3] = 2.0f;
    params[band + 4] = 8.0f;
    publish(resetEngine, params);
    const std::vector<float> original = [] {
        std::vector<float> value(4096, 0.0f);
        value[0] = 0.75f;
        return value;
    }();
    std::vector<float> first = original;
    std::vector<float> second = original;
    require(resetEngine.process(first.data(), first.size()), "First reset impulse");
    resetEngine.reset();
    require(resetEngine.process(second.data(), second.size()), "Second reset impulse");
    require(std::memcmp(first.data(), second.data(), first.size() * sizeof(float)) == 0,
        "Reset must reproduce a fresh impulse response exactly");

    aurisdsp::Engine eightChannel;
    require(eightChannel.configure(48000, 8, 128), "Eight-channel configuration");
    auto multichannel = activeParams(aurisdsp::Mode::HYBRID);
    multichannel[aurisdsp::PARAM_POWERAMP_EQ_ENABLED] = 1.0f;
    multichannel[aurisdsp::powerampBandIndex(4)] = 2.0f;
    multichannel[aurisdsp::PARAM_TONE_ENABLED] = 1.0f;
    multichannel[aurisdsp::PARAM_BASS_DB] = 3.0f;
    multichannel[aurisdsp::PARAM_TREBLE_DB] = -2.0f;
    multichannel[aurisdsp::PARAM_REVERB_ENABLED] = 1.0f;
    multichannel[aurisdsp::PARAM_REVERB_MIX] = 0.1f;
    multichannel[aurisdsp::PARAM_REVERB_FADE] = 0.4f;
    multichannel[aurisdsp::PARAM_REVERB_SIZE] = 0.4f;
    multichannel[aurisdsp::PARAM_LIMITER_ENABLED] = 1.0f;
    publish(eightChannel, multichannel);
    std::vector<float> samples(256 * 8);
    for (std::size_t frame = 0; frame < 256; ++frame) {
        for (std::size_t channel = 0; channel < 8; ++channel) {
            samples[frame * 8 + channel] = static_cast<float>(0.02 * (channel + 1) *
                std::sin(2.0 * kPi * (200.0 + channel * 100.0) * frame / kSampleRate));
        }
    }
    require(eightChannel.process(samples.data(), 128), "First eight-channel block");
    require(eightChannel.process(samples.data() + 128 * 8, 128), "Second eight-channel block");
    require(std::all_of(samples.begin(), samples.end(), [](float value) { return std::isfinite(value); }),
        "Eight-channel processing must stay finite");
}

void testConcurrentSeqlockSnapshots() {
    aurisdsp::Engine engine;
    require(engine.configure(48000, 1, 1), "Seqlock configuration");
    auto first = activeParams(aurisdsp::Mode::POWERAMP);
    first[aurisdsp::PARAM_POWERAMP_EQ_ENABLED] = 1.0f;
    first[aurisdsp::PARAM_POWERAMP_PREAMP_DB] = -6.0f;
    first[aurisdsp::PARAM_DVC_ENABLED] = 1.0f;
    first[aurisdsp::PARAM_DVC_GAIN_DB] = 6.0f;
    auto second = first;
    second[aurisdsp::PARAM_POWERAMP_PREAMP_DB] = 6.0f;
    second[aurisdsp::PARAM_DVC_GAIN_DB] = -6.0f;
    publish(engine, first);

    std::atomic<bool> start{false};
    std::atomic<bool> stop{false};
    std::thread writer([&] {
        while (!start.load(std::memory_order_acquire)) {
        }
        bool toggle = false;
        while (!stop.load(std::memory_order_relaxed)) {
            publish(engine, toggle ? second : first);
            toggle = !toggle;
        }
    });

    start.store(true, std::memory_order_release);
    constexpr float input = 0.1f;
    std::size_t mismatches = 0;
    for (std::size_t iteration = 0; iteration < 20000; ++iteration) {
        float output = input;
        if (!engine.process(&output, 1) || std::abs(output - input) > 2.0e-6) ++mismatches;
    }
    stop.store(true, std::memory_order_relaxed);
    writer.join();
    require(mismatches == 0,
        "A complete snapshot must apply compensating preamp/DVC gains together");
}

void testValidationAndSanitization() {
    aurisdsp::Engine engine;
    require(!engine.configure(48000, 0, 64), "Zero channels must fail");
    require(!engine.configure(48000, 9, 64), "Nine channels must fail");
    require(!engine.configure(48000, 2, 0), "Zero maxFrames must fail");
    require(!engine.configure(1000, 2, 64), "Unsupported sample rate must fail");
    require(engine.configure(48000, 1, 2), "Validation configuration");

    auto params = activeParams(aurisdsp::Mode::POWERAMP);
    params[aurisdsp::PARAM_POWERAMP_EQ_ENABLED] = 1.0f;
    params[aurisdsp::PARAM_POWERAMP_PREAMP_DB] = 12.0f;
    publish(engine, params);
    std::array<float, 3> samples = {0.25f, -0.25f, 0.5f};
    const auto original = samples;
    require(!engine.process(samples.data(), samples.size()), "Oversize block must be rejected");
    require(std::memcmp(samples.data(), original.data(), sizeof(samples)) == 0,
        "Rejected block must remain untouched");
    engine.setParams(nullptr, 0);

    auto nonfinite = activeParams(aurisdsp::Mode::HIBY);
    nonfinite[aurisdsp::PARAM_MSEB_ENABLED] = 1.0f;
    nonfinite[aurisdsp::PARAM_MSEB_PREAMP_DB] = std::numeric_limits<float>::quiet_NaN();
    nonfinite[aurisdsp::msebValueIndex(0)] = std::numeric_limits<float>::infinity();
    publish(engine, nonfinite);
    std::array<float, 2> finiteSamples = {0.25f, -0.25f};
    require(engine.process(finiteSamples.data(), finiteSamples.size()), "Sanitized process");
    requireNear(finiteSamples[0], original[0], 0.0, "NaN preamp must use its neutral default");
    requireNear(finiteSamples[1], original[1], 0.0, "Infinite dimension must clamp without NaN output");
    require(std::strlen(aurisdsp::versionHash()) == 16, "Version hash must be 16 hexadecimal digits");
}

void testRecoveredMsebGoldenTableExactness() {
    static_assert(aurisdsp::kMsebDimensionCount == 10, "Ten MSEB dimensions");
    static_assert(aurisdsp::kMsebSectionCount == 13, "Thirteen recovered sections");
    const float probes[] = {0.0f, 1.0f, -1.0f, 0.37f, -0.83f, 0.01f};
    for (const float probe : probes) {
        const double raw = static_cast<double>(probe) * 100.0;
        for (std::size_t dimension = 0; dimension < aurisdsp::kMsebDimensionCount; ++dimension) {
            std::array<float, aurisdsp::kMsebDimensionCount> values{};
            values[dimension] = probe;
            std::array<aurisdsp::MsebSectionSpec, aurisdsp::kMsebSectionCount> specs{};
            aurisdsp::buildMsebMapping(values, 100.0f, specs);
            for (std::size_t section = 0; section < specs.size(); ++section) {
                const auto& expected = kExpectedMsebSections[section];
                const auto& actual = specs[section];
                const std::string label = "section " + std::to_string(section) +
                    " dim " + std::to_string(dimension);
                require(actual.frequencyHz == expected.frequencyHz, label + " frequencyHz not bit-exact");
                require(actual.q == expected.q, label + " q not bit-exact");
                require(actual.type == expected.type, label + " type mismatch");
                const bool shouldBeActive = expected.dimension == dimension && raw != 0.0;
                require(actual.enabled == shouldBeActive, label + " enabled mismatch");
                const double expectedGain = shouldBeActive ? raw * expected.gainScale : 0.0;
                require(actual.gainDb == expectedGain, label + " gainDb not bit-exact");
            }
        }
    }
    // Temperature drives all four shelves with one identical gain.
    std::array<float, aurisdsp::kMsebDimensionCount> values{};
    values[0] = 0.5f;
    std::array<aurisdsp::MsebSectionSpec, aurisdsp::kMsebSectionCount> specs{};
    aurisdsp::buildMsebMapping(values, 100.0f, specs);
    for (std::size_t section = 1; section < 4; ++section) {
        require(specs[section].gainDb == specs[0].gainDb && specs[section].enabled,
            "All four temperature shelves must share one gain");
    }
    require(specs[0].gainDb == (static_cast<double>(0.5f) * 100.0) * -0.06,
        "Temperature gain must be raw slider times -0.06 exactly");
}

void testMsebRangeIsMetadataOnly() {
    std::array<float, aurisdsp::kMsebDimensionCount> values{};
    values[0] = 0.5f;
    values[3] = -0.25f;
    values[9] = 1.0f;
    std::array<aurisdsp::MsebSectionSpec, aurisdsp::kMsebSectionCount> reference{};
    aurisdsp::buildMsebMapping(values, 100.0f, reference);
    for (const float range : {20.0f, 25.0f, 40.0f, 50.0f, 75.0f, 100.0f}) {
        std::array<aurisdsp::MsebSectionSpec, aurisdsp::kMsebSectionCount> specs{};
        aurisdsp::buildMsebMapping(values, range, specs);
        for (std::size_t section = 0; section < specs.size(); ++section) {
            require(specs[section].gainDb == reference[section].gainDb &&
                specs[section].enabled == reference[section].enabled &&
                specs[section].frequencyHz == reference[section].frequencyHz &&
                specs[section].q == reference[section].q &&
                specs[section].type == reference[section].type,
                "Range metadata must never rewrite raw slider gains");
        }
    }

    const std::vector<float> input = sine(20.0, 4096);
    std::vector<float> outputs[3];
    const float ranges[] = {25.0f, 50.0f, 100.0f};
    for (std::size_t index = 0; index < 3; ++index) {
        aurisdsp::Engine engine;
        require(engine.configure(48000, 1, 4096), "Range engine configuration");
        auto params = activeParams(aurisdsp::Mode::HIBY);
        params[aurisdsp::PARAM_MSEB_ENABLED] = 1.0f;
        params[aurisdsp::msebValueIndex(1)] = 1.0f;
        params[aurisdsp::msebValueIndex(4)] = -0.5f;
        params[aurisdsp::PARAM_MSEB_RANGE_PERCENT] = ranges[index];
        publish(engine, params);
        outputs[index] = input;
        require(engine.process(outputs[index].data(), outputs[index].size()), "Range process");
    }
    require(std::memcmp(outputs[0].data(), outputs[1].data(), input.size() * sizeof(float)) == 0,
        "Range 25 vs 50 must produce identical PCM");
    require(std::memcmp(outputs[0].data(), outputs[2].data(), input.size() * sizeof(float)) == 0,
        "Range 25 vs 100 must produce identical PCM");
}

void testRbjCoefficientsMatchRecoveredFormulas() {
    const float probes[] = {1.0f, -1.0f, 0.37f};
    const double sampleRates[] = {8000.0, 44100.0, 48000.0, 96000.0, 192000.0};
    for (const float probe : probes) {
        for (std::size_t dimension = 0; dimension < aurisdsp::kMsebDimensionCount; ++dimension) {
            std::array<float, aurisdsp::kMsebDimensionCount> values{};
            values[dimension] = probe;
            std::array<aurisdsp::MsebSectionSpec, aurisdsp::kMsebSectionCount> specs{};
            aurisdsp::buildMsebMapping(values, 100.0f, specs);
            for (std::size_t section = 0; section < specs.size(); ++section) {
                for (const double sampleRate : sampleRates) {
                    aurisdsp::Biquad biquad;
                    biquad.set(specs[section].type, sampleRate, specs[section].frequencyHz,
                        specs[section].q, specs[section].gainDb, true);
                    require(biquad.active(), "Recovered section must stay active");
                    const RbjReference expected = referenceRbj(specs[section].type, sampleRate,
                        specs[section].frequencyHz, specs[section].q, specs[section].gainDb);
                    const auto actual = biquad.coefficients();
                    const std::string label = "RBJ section " + std::to_string(section) +
                        " dim " + std::to_string(dimension) + " fs " +
                        std::to_string(static_cast<long long>(sampleRate));
                    requireCoeffNear(actual.b0, expected.b0, label + " b0");
                    requireCoeffNear(actual.b1, expected.b1, label + " b1");
                    requireCoeffNear(actual.b2, expected.b2, label + " b2");
                    requireCoeffNear(actual.a1, expected.a1, label + " a1");
                    requireCoeffNear(actual.a2, expected.a2, label + " a2");
                }
            }
        }
    }

    // Explicit spot check of the peaking block exactly as written in the doc.
    {
        aurisdsp::Biquad biquad;
        biquad.set(aurisdsp::BiquadType::PEAKING, 48000.0, 1000.0, 1.0, 6.0, true);
        const double omega = 2.0 * kPi * 1000.0 / 48000.0;
        const double alpha = std::sin(omega) / 2.0;
        const double a = std::pow(10.0, 6.0 / 40.0);
        const double cosine = std::cos(omega);
        const double a0 = 1.0 + alpha / a;
        const auto c = biquad.coefficients();
        requireCoeffNear(c.b0, (1.0 + alpha * a) / a0, "doc peaking b0");
        requireCoeffNear(c.b1, (-2.0 * cosine) / a0, "doc peaking b1");
        requireCoeffNear(c.b2, (1.0 - alpha * a) / a0, "doc peaking b2");
        requireCoeffNear(c.a1, (-2.0 * cosine) / a0, "doc peaking a1");
        requireCoeffNear(c.a2, (1.0 - alpha / a) / a0, "doc peaking a2");
    }

    // At 8 kHz the 11319 Hz temperature shelf must compute at 0.45 * sampleRate.
    {
        std::array<float, aurisdsp::kMsebDimensionCount> values{};
        values[0] = 1.0f;
        std::array<aurisdsp::MsebSectionSpec, aurisdsp::kMsebSectionCount> specs{};
        aurisdsp::buildMsebMapping(values, 100.0f, specs);
        aurisdsp::Biquad clamped;
        clamped.set(specs[3].type, 8000.0, specs[3].frequencyHz, specs[3].q, specs[3].gainDb, true);
        aurisdsp::Biquad explicitClamp;
        explicitClamp.set(specs[3].type, 8000.0, 8000.0 * 0.45, specs[3].q, specs[3].gainDb, true);
        const auto x = clamped.coefficients();
        const auto y = explicitClamp.coefficients();
        require(x.b0 == y.b0 && x.b1 == y.b1 && x.b2 == y.b2 && x.a1 == y.a1 && x.a2 == y.a2,
            "Frequency above 0.45*sampleRate must clamp to 0.45*sampleRate");
    }
}

void testEngineMsebThroughFloat32Abi() {
    for (std::size_t dimension = 0; dimension < aurisdsp::kMsebDimensionCount; ++dimension) {
        aurisdsp::Engine engine;
        require(engine.configure(48000, 1, 32768), "ABI engine configuration");
        auto params = activeParams(aurisdsp::Mode::HIBY);
        params[aurisdsp::PARAM_MSEB_ENABLED] = 1.0f;
        params[aurisdsp::msebValueIndex(dimension)] = 1.0f;  // normalized +1 == raw +100
        publish(engine, params);
        if (dimension == 0) {
            requireNear(engineImpulseMagnitude(engine, kSampleRate * 0.5),
                std::pow(10.0, -24.0 / 20.0), 1.0e-4,
                "ABI temperature: four shelves at Nyquist must give -24 dB");
            continue;
        }
        const auto& expected = kExpectedMsebSections[dimension + 3];
        const double testFrequency = expected.type == aurisdsp::BiquadType::LOW_SHELF ? 0.0 :
            (expected.type == aurisdsp::BiquadType::HIGH_SHELF ? kSampleRate * 0.5
                                                                : expected.frequencyHz);
        const double expectedGain = std::pow(10.0, 100.0 * expected.gainScale / 20.0);
        requireNear(engineImpulseMagnitude(engine, testFrequency), expectedGain,
            std::max(1.0e-6, expectedGain * 0.005),
            "ABI dimension " + std::to_string(dimension) + " response at its recovered centre");
    }

    // All dimensions at +1: DC gain comes only from the 70 Hz low shelf (+25 dB).
    {
        aurisdsp::Engine engine;
        require(engine.configure(48000, 1, 32768), "ABI all-dims configuration");
        auto params = activeParams(aurisdsp::Mode::HIBY);
        params[aurisdsp::PARAM_MSEB_ENABLED] = 1.0f;
        for (std::size_t dimension = 0; dimension < aurisdsp::kMsebDimensionCount; ++dimension) {
            params[aurisdsp::msebValueIndex(dimension)] = 1.0f;
        }
        publish(engine, params);
        requireNear(engineImpulseMagnitude(engine, 0.0), std::pow(10.0, 25.0 / 20.0), 0.05,
            "All-dims DC gain must equal the bass-extension shelf only");
    }

    // Normalized -1 must reach the mapping as raw -100 (Voice: -50 dB at 650 Hz).
    {
        aurisdsp::Engine engine;
        require(engine.configure(48000, 1, 32768), "ABI negative configuration");
        auto params = activeParams(aurisdsp::Mode::HIBY);
        params[aurisdsp::PARAM_MSEB_ENABLED] = 1.0f;
        params[aurisdsp::msebValueIndex(4)] = -1.0f;
        publish(engine, params);
        requireNear(engineImpulseMagnitude(engine, 650.0), std::pow(10.0, -50.0 / 20.0), 1.0e-5,
            "Normalized -1 must become raw -100 (Voice -50 dB at 650 Hz)");
    }
}

void testPreampExactnessAndClamping() {
    const std::array<float, 8> input = {
        0.25f, -0.25f, 0.5f, -0.75f, 1.0e-20f, -3.7e-5f, 0.1f, -0.9f,
    };
    auto run = [&](float msebPreamp, bool msebOn, float peqPreamp, bool peqOn) {
        aurisdsp::Engine engine;
        require(engine.configure(48000, 1, input.size()), "Preamp engine configuration");
        auto params = activeParams(aurisdsp::Mode::HIBY);
        params[aurisdsp::PARAM_MSEB_ENABLED] = msebOn ? 1.0f : 0.0f;
        params[aurisdsp::PARAM_MSEB_PREAMP_DB] = msebPreamp;
        params[aurisdsp::PARAM_PEQ_ENABLED] = peqOn ? 1.0f : 0.0f;
        params[aurisdsp::PARAM_PEQ_PREAMP_DB] = peqPreamp;
        // A gain-zero peaking band stays an exact bypass even when switched on.
        const std::size_t band = aurisdsp::peqBandIndex(0);
        params[band] = 1.0f;
        params[band + 1] = static_cast<float>(aurisdsp::BiquadType::PEAKING);
        params[band + 2] = 1000.0f;
        params[band + 3] = 1.0f;
        params[band + 4] = 0.0f;
        publish(engine, params);
        std::vector<float> out(input.begin(), input.end());
        require(engine.process(out.data(), out.size()), "Preamp process");
        return out;
    };
    auto expectBits = [&](const std::vector<float>& out, double msebGain, double peqGain) {
        for (std::size_t index = 0; index < input.size(); ++index) {
            double value = static_cast<double>(input[index]);
            value *= msebGain;
            value *= peqGain;
            const float expected = static_cast<float>(value);
            require(sameBits(out[index], expected),
                "Preamp must apply exactly pow(10, dB/20) in double then round once");
        }
    };

    expectBits(run(-6.0f, true, 0.0f, false), std::pow(10.0, -6.0 / 20.0), 1.0);
    expectBits(run(0.0f, false, 4.5f, true), 1.0, std::pow(10.0, 4.5 / 20.0));
    expectBits(run(-3.0f, true, -3.0f, true),
        std::pow(10.0, -3.0 / 20.0), std::pow(10.0, -3.0 / 20.0));
    expectBits(run(100.0f, true, -100.0f, true),
        std::pow(10.0, 45.0 / 20.0), std::pow(10.0, -45.0 / 20.0));
    expectBits(run(-12.0f, false, 0.0f, false), 1.0, 1.0);
}

void testNeutralActivePathIsBitExact() {
    aurisdsp::Engine engine;
    require(engine.configure(48000, 1, 8), "Neutral engine configuration");
    auto params = activeParams(aurisdsp::Mode::HIBY);
    params[aurisdsp::PARAM_MSEB_ENABLED] = 1.0f;  // all dimensions stay 0
    params[aurisdsp::PARAM_PEQ_ENABLED] = 1.0f;    // every band on but gain 0
    for (std::size_t band = 0; band < aurisdsp::kPeqBandCount; ++band) {
        const std::size_t base = aurisdsp::peqBandIndex(band);
        params[base] = 1.0f;
        params[base + 1] = static_cast<float>(aurisdsp::BiquadType::PEAKING);
        params[base + 4] = 0.0f;
    }
    publish(engine, params);

    const std::array<float, 8> original = {
        0.0f, -0.0f,
        std::numeric_limits<float>::denorm_min(), -std::numeric_limits<float>::denorm_min(),
        0.12345678f, -0.87654321f, 1.0f, -1.0f,
    };
    std::vector<float> samples(original.begin(), original.end());
    require(engine.process(samples.data(), samples.size()), "Neutral active process");
    require(std::memcmp(samples.data(), original.data(), sizeof(original)) == 0,
        "An active but neutral HiBy path must preserve every finite Float32 bit");

    const std::array<float, 3> poisoned = {
        fromBits(0x7fc00001U), std::numeric_limits<float>::infinity(),
        -std::numeric_limits<float>::infinity(),
    };
    std::vector<float> poisonedOut(poisoned.begin(), poisoned.end());
    require(engine.process(poisonedOut.data(), poisonedOut.size()), "Poisoned process");
    for (float value : poisonedOut) {
        require(sameBits(value, 0.0f),
            "Non-finite input must canonicalize to +0.0f in active modes");
    }
}

void testPeqBandFieldsThroughFloat32Abi() {
    struct Case {
        aurisdsp::BiquadType type;
        float frequencyHz;
        float q;
        float gainDb;
        double testFrequency;
        double expectedGain;
    };
    const std::array<Case, 3> cases = {{
        {aurisdsp::BiquadType::PEAKING, 1000.0f, 2.0f, 9.0f, 1000.0, std::pow(10.0, 9.0 / 20.0)},
        {aurisdsp::BiquadType::LOW_SHELF, 100.0f, 0.7f, 6.0f, 0.0, std::pow(10.0, 6.0 / 20.0)},
        {aurisdsp::BiquadType::HIGH_SHELF, 8000.0f, 0.9f, -6.0f, kSampleRate * 0.5,
            std::pow(10.0, -6.0 / 20.0)},
    }};
    for (std::size_t index = 0; index < cases.size(); ++index) {
        aurisdsp::Engine engine;
        require(engine.configure(48000, 1, 32768), "PEQ ABI configuration");
        auto params = activeParams(aurisdsp::Mode::HIBY);
        params[aurisdsp::PARAM_PEQ_ENABLED] = 1.0f;
        const std::size_t base = aurisdsp::peqBandIndex(3);
        params[base] = 1.0f;
        params[base + 1] = static_cast<float>(cases[index].type);
        params[base + 2] = cases[index].frequencyHz;
        params[base + 3] = cases[index].q;
        params[base + 4] = cases[index].gainDb;
        publish(engine, params);
        requireNear(engineImpulseMagnitude(engine, cases[index].testFrequency),
            cases[index].expectedGain, std::max(1.0e-5, cases[index].expectedGain * 0.005),
            "PEQ band " + std::to_string(index) + " through the ABI");
    }

    // A band switched off must leave PCM bit-untouched even with nonzero gain.
    {
        aurisdsp::Engine engine;
        require(engine.configure(48000, 1, 8), "PEQ off-band configuration");
        auto params = activeParams(aurisdsp::Mode::HIBY);
        params[aurisdsp::PARAM_PEQ_ENABLED] = 1.0f;
        const std::size_t base = aurisdsp::peqBandIndex(0);
        params[base] = 0.0f;  // off
        params[base + 1] = static_cast<float>(aurisdsp::BiquadType::PEAKING);
        params[base + 2] = 1000.0f;
        params[base + 3] = 1.0f;
        params[base + 4] = 12.0f;  // ignored while off
        publish(engine, params);
        const std::array<float, 4> original = {0.3f, -0.7f, 0.0f, -0.0f};
        std::vector<float> samples(original.begin(), original.end());
        require(engine.process(samples.data(), samples.size()), "PEQ off-band process");
        require(std::memcmp(samples.data(), original.data(), sizeof(original)) == 0,
            "An off PEQ band must leave PCM bit-untouched even with nonzero gain");
    }
}

}  // namespace

int main() {
    const std::vector<std::pair<std::string, std::function<void()>>> tests = {
        {"recovered MSEB constants and responses", testRecoveredMsebConstantsAndResponses},
        {"biquad impulse responses", testAllBiquadImpulseResponses},
        {"exact bypass and nulls", testExactBypassAndNulls},
        {"PEQ sine and preamp", testPeqSineAndPreamp},
        {"MSEB preamp and raw range preservation", testMsebPreampAndRangePreservation},
        {"limiter and Hybrid final safety", testLimiterAndHybridFinalSafety},
        {"reverb impulse tail and chunking", testReverbImpulseTailAndChunking},
        {"reset and eight channels", testResetAndEightChannels},
        {"concurrent seqlock snapshots", testConcurrentSeqlockSnapshots},
        {"validation and sanitization", testValidationAndSanitization},
        {"recovered MSEB golden table exactness", testRecoveredMsebGoldenTableExactness},
        {"MSEB range is metadata only", testMsebRangeIsMetadataOnly},
        {"RBJ coefficients match recovered formulas", testRbjCoefficientsMatchRecoveredFormulas},
        {"engine MSEB mapping through the Float32 ABI", testEngineMsebThroughFloat32Abi},
        {"preamp exactness and clamping", testPreampExactnessAndClamping},
        {"neutral active path is bit-exact", testNeutralActivePathIsBitExact},
        {"PEQ band fields through the Float32 ABI", testPeqBandFieldsThroughFloat32Abi},
    };

    std::size_t failures = 0;
    for (const auto& test : tests) {
        try {
            test.second();
            std::cout << "PASS: " << test.first << '\n';
        } catch (const std::exception& error) {
            ++failures;
            std::cerr << "FAIL: " << test.first << ": " << error.what() << '\n';
        }
    }
    if (failures != 0) {
        std::cerr << failures << " test group(s) failed\n";
        return 1;
    }
    std::cout << "All host DSP tests passed\n";
    return 0;
}
