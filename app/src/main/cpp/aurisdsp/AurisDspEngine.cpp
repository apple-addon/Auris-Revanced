#include "AurisDspEngine.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <new>
#include <utility>

namespace aurisdsp {
namespace {

constexpr double kPi = 3.141592653589793238462643383279502884;
constexpr double kCombMilliseconds[] = {29.7, 37.1, 41.1, 43.7};
constexpr double kAllpassMilliseconds[] = {5.0, 1.7};

static_assert(std::atomic<std::uint32_t>::is_always_lock_free,
    "The audio seqlock requires lock-free 32-bit atomics");
static_assert(sizeof(float) == sizeof(std::uint32_t),
    "The parameter ABI requires 32-bit Float32");

float clampValue(float value, float minimum, float maximum) noexcept {
    return std::max(minimum, std::min(maximum, value));
}

bool enabled(float value) noexcept {
    return value >= 0.5f;
}

double dbToGain(double db) noexcept {
    return std::pow(10.0, db / 20.0);
}

std::uint32_t floatBits(float value) noexcept {
    std::uint32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    return bits;
}

float bitsFloat(std::uint32_t bits) noexcept {
    float value = 0.0f;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

bool gainDependent(BiquadType type) noexcept {
    return type == BiquadType::PEAKING || type == BiquadType::LOW_SHELF ||
        type == BiquadType::HIGH_SHELF;
}

BiquadType sanitizedType(float value) noexcept {
    if (!(value >= 0.0f) || value > 6.0f) return BiquadType::PEAKING;
    return static_cast<BiquadType>(static_cast<std::int32_t>(value));
}

Mode sanitizedMode(float value) noexcept {
    if (!(value >= 0.0f) || value > 4.0f) return Mode::OFF;
    return static_cast<Mode>(static_cast<std::int32_t>(value));
}

void normalizeBoolean(ParamArray& params, std::size_t index) noexcept {
    params[index] = enabled(params[index]) ? 1.0f : 0.0f;
}

constexpr std::uint64_t fnv1a(const char* text) noexcept {
    std::uint64_t hash = 14695981039346656037ULL;
    while (*text != '\0') {
        hash ^= static_cast<unsigned char>(*text++);
        hash *= 1099511628211ULL;
    }
    return hash;
}

struct HexVersion {
    char text[17];

    constexpr explicit HexVersion(std::uint64_t value) : text{} {
        constexpr char digits[] = "0123456789abcdef";
        for (std::size_t index = 0; index < 16; ++index) {
            const unsigned shift = static_cast<unsigned>((15 - index) * 4);
            text[index] = digits[(value >> shift) & 0x0fU];
        }
        text[16] = '\0';
    }
};

constexpr HexVersion kVersionText(fnv1a(
    "aurisdsp-v1|params=94|float32-double64|rbj-tdf2|mseb13-recovered-controls|pa-10|peq-10"));

}  // namespace

const char* versionHash() noexcept {
    return kVersionText.text;
}

ParamArray makeDefaultParams() noexcept {
    ParamArray params{};
    params[PARAM_MODE] = static_cast<float>(Mode::OFF);
    params[PARAM_MSEB_RANGE_PERCENT] = 100.0f;
    params[PARAM_LIMITER_CEILING_DB] = -0.3f;
    params[PARAM_LIMITER_RELEASE_MS] = 100.0f;
    params[PARAM_REVERB_FILTER] = 0.5f;
    for (std::size_t band = 0; band < kPeqBandCount; ++band) {
        const std::size_t base = peqBandIndex(band);
        params[base + 1] = static_cast<float>(BiquadType::PEAKING);
        params[base + 2] = kDefaultPeqFrequenciesHz[band];
        params[base + 3] = 0.2f;
    }
    return params;
}

Engine::Engine() noexcept {
    const ParamArray defaults = makeDefaultParams();
    appliedParams_ = defaults;
    for (std::size_t index = 0; index < kParamCount; ++index) {
        publishedBits_[index].store(floatBits(defaults[index]), std::memory_order_relaxed);
    }
}

void Engine::setParams(const float* input, std::size_t count) noexcept {
    if (input == nullptr || count != kParamCount) return;
    const ParamArray defaults = makeDefaultParams();
    ParamArray next{};
    std::copy(input, input + kParamCount, next.begin());
    for (std::size_t index = 0; index < kParamCount; ++index) {
        if (!std::isfinite(next[index])) next[index] = defaults[index];
    }

    next[PARAM_MODE] = static_cast<float>(sanitizedMode(next[PARAM_MODE]));
    normalizeBoolean(next, PARAM_MSEB_ENABLED);
    next[PARAM_MSEB_PREAMP_DB] = clampValue(next[PARAM_MSEB_PREAMP_DB], -45.0f, 45.0f);
    next[PARAM_MSEB_RANGE_PERCENT] = clampValue(next[PARAM_MSEB_RANGE_PERCENT], 25.0f, 100.0f);
    for (std::size_t dimension = 0; dimension < kMsebDimensionCount; ++dimension) {
        const std::size_t index = msebValueIndex(dimension);
        next[index] = clampValue(next[index], -1.0f, 1.0f);
    }

    normalizeBoolean(next, PARAM_PEQ_ENABLED);
    next[PARAM_PEQ_PREAMP_DB] = clampValue(next[PARAM_PEQ_PREAMP_DB], -45.0f, 45.0f);
    for (std::size_t band = 0; band < kPeqBandCount; ++band) {
        const std::size_t base = peqBandIndex(band);
        normalizeBoolean(next, base);
        next[base + 1] = static_cast<float>(sanitizedType(next[base + 1]));
        next[base + 2] = clampValue(next[base + 2], 20.0f, 20000.0f);
        next[base + 3] = clampValue(next[base + 3], 0.1f, 10.0f);
        next[base + 4] = clampValue(next[base + 4], -15.0f, 15.0f);
    }

    normalizeBoolean(next, PARAM_POWERAMP_EQ_ENABLED);
    next[PARAM_POWERAMP_PREAMP_DB] = clampValue(next[PARAM_POWERAMP_PREAMP_DB], -12.0f, 12.0f);
    for (std::size_t band = 0; band < kPowerampBandCount; ++band) {
        const std::size_t index = powerampBandIndex(band);
        next[index] = clampValue(next[index], -12.0f, 12.0f);
    }
    normalizeBoolean(next, PARAM_TONE_ENABLED);
    next[PARAM_BASS_DB] = clampValue(next[PARAM_BASS_DB], -12.0f, 12.0f);
    next[PARAM_TREBLE_DB] = clampValue(next[PARAM_TREBLE_DB], -12.0f, 12.0f);

    normalizeBoolean(next, PARAM_LIMITER_ENABLED);
    next[PARAM_LIMITER_CEILING_DB] = clampValue(next[PARAM_LIMITER_CEILING_DB], -24.0f, 0.0f);
    next[PARAM_LIMITER_RELEASE_MS] = clampValue(next[PARAM_LIMITER_RELEASE_MS], 10.0f, 2000.0f);
    normalizeBoolean(next, PARAM_REVERB_ENABLED);
    for (std::size_t index = PARAM_REVERB_DAMP; index <= PARAM_REVERB_MIX; ++index) {
        next[index] = clampValue(next[index], 0.0f, 1.0f);
    }
    normalizeBoolean(next, PARAM_DVC_ENABLED);
    next[PARAM_DVC_GAIN_DB] = clampValue(next[PARAM_DVC_GAIN_DB], -24.0f, 24.0f);

    // Only settings threads take this mutex. The audio path never touches it.
    std::lock_guard<std::mutex> lock(writerMutex_);
    sequence_.fetch_add(1U, std::memory_order_acq_rel);
    for (std::size_t index = 0; index < kParamCount; ++index) {
        publishedBits_[index].store(floatBits(next[index]), std::memory_order_relaxed);
    }
    sequence_.fetch_add(1U, std::memory_order_release);
}

void Engine::loadSnapshot(ParamArray& output) const noexcept {
    for (;;) {
        const std::uint32_t before = sequence_.load(std::memory_order_acquire);
        if ((before & 1U) != 0U) continue;
        for (std::size_t index = 0; index < kParamCount; ++index) {
            output[index] = bitsFloat(publishedBits_[index].load(std::memory_order_relaxed));
        }
        const std::uint32_t after = sequence_.load(std::memory_order_acquire);
        if (before == after) return;
    }
}

bool Engine::configure(int sampleRate, int channels, int maxFrames) noexcept {
    if (sampleRate < 8000 || sampleRate > 768000 || channels < 1 ||
        channels > static_cast<int>(kMaxChannels) || maxFrames < 1) {
        return false;
    }
    const std::size_t frames = static_cast<std::size_t>(maxFrames);
    const std::size_t channelCount = static_cast<std::size_t>(channels);
    if (frames > std::numeric_limits<std::size_t>::max() / channelCount) return false;
    const std::size_t sampleCount = frames * channelCount;

    sampleRate_ = 0;
    channels_ = 0;
    maxFrames_ = 0;
    try {
        workBuffer_.assign(sampleCount, 0.0);
        if (!reverb_.configure(sampleRate, channels)) return false;
    } catch (const std::bad_alloc&) {
        workBuffer_.clear();
        return false;
    } catch (...) {
        workBuffer_.clear();
        return false;
    }

    sampleRate_ = sampleRate;
    channels_ = channels;
    maxFrames_ = maxFrames;
    appliedMode_ = Mode::OFF;
    appliedValid_ = false;
    resetState();
    reverbRunning_ = false;
    limiterRunning_ = false;
    return true;
}

void Engine::resetState() noexcept {
    for (auto& biquad : msebBank_) biquad.reset();
    for (auto& biquad : peqBank_) biquad.reset();
    for (auto& biquad : powerampBank_) biquad.reset();
    for (auto& biquad : toneBank_) biquad.reset();
    reverb_.reset();
    limiterGain_ = 1.0;
}

void Engine::reset() noexcept {
    resetState();
    reverbRunning_ = false;
    limiterRunning_ = false;
    appliedValid_ = false;
    appliedMode_ = Mode::OFF;
}

void Engine::rebuildFilters() noexcept {
    const bool hibyActive = appliedMode_ == Mode::HIBY || appliedMode_ == Mode::HYBRID;
    const bool powerampActive = appliedMode_ == Mode::POWERAMP || appliedMode_ == Mode::HYBRID;

    std::array<float, kMsebDimensionCount> msebValues{};
    for (std::size_t dimension = 0; dimension < kMsebDimensionCount; ++dimension) {
        msebValues[dimension] = appliedParams_[msebValueIndex(dimension)];
    }
    std::array<MsebSectionSpec, kMsebSectionCount> msebSpecs{};
    buildMsebMapping(msebValues, appliedParams_[PARAM_MSEB_RANGE_PERCENT], msebSpecs);
    const bool msebEnabled = hibyActive && enabled(appliedParams_[PARAM_MSEB_ENABLED]);
    for (std::size_t section = 0; section < kMsebSectionCount; ++section) {
        const MsebSectionSpec& spec = msebSpecs[section];
        msebBank_[section].set(spec.type, sampleRate_, spec.frequencyHz, spec.q,
            spec.gainDb, msebEnabled && spec.enabled);
    }

    const bool peqEnabled = hibyActive && enabled(appliedParams_[PARAM_PEQ_ENABLED]);
    for (std::size_t band = 0; band < kPeqBandCount; ++band) {
        const std::size_t base = peqBandIndex(band);
        const BiquadType type = sanitizedType(appliedParams_[base + 1]);
        const double gain = appliedParams_[base + 4];
        const bool bandEnabled = peqEnabled && enabled(appliedParams_[base]) &&
            (!gainDependent(type) || gain != 0.0);
        peqBank_[band].set(type, sampleRate_, appliedParams_[base + 2],
            appliedParams_[base + 3], gain, bandEnabled);
    }

    constexpr double frequencies[kPowerampBandCount] = {
        31.0, 62.0, 125.0, 250.0, 500.0,
        1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
    };
    const bool powerampEqEnabled = powerampActive && enabled(appliedParams_[PARAM_POWERAMP_EQ_ENABLED]);
    for (std::size_t band = 0; band < kPowerampBandCount; ++band) {
        const BiquadType type = band == 0 ? BiquadType::LOW_SHELF :
            (band == kPowerampBandCount - 1 ? BiquadType::HIGH_SHELF : BiquadType::PEAKING);
        const double gain = appliedParams_[powerampBandIndex(band)];
        powerampBank_[band].set(type, sampleRate_, frequencies[band], 1.0, gain,
            powerampEqEnabled && gain != 0.0);
    }

    const bool toneEnabled = powerampActive && enabled(appliedParams_[PARAM_TONE_ENABLED]);
    const double bass = appliedParams_[PARAM_BASS_DB];
    const double treble = appliedParams_[PARAM_TREBLE_DB];
    toneBank_[0].set(BiquadType::LOW_SHELF, sampleRate_, 250.0, 0.707, bass,
        toneEnabled && bass != 0.0);
    toneBank_[1].set(BiquadType::HIGH_SHELF, sampleRate_, 4000.0, 0.707, treble,
        toneEnabled && treble != 0.0);

    limiterCeiling_ = dbToGain(appliedParams_[PARAM_LIMITER_CEILING_DB]);
    const double releaseSeconds = static_cast<double>(appliedParams_[PARAM_LIMITER_RELEASE_MS]) * 0.001;
    limiterReleaseCoefficient_ = std::exp(-1.0 / (releaseSeconds * sampleRate_));
    const bool nextLimiterRunning = powerampActive && enabled(appliedParams_[PARAM_LIMITER_ENABLED]);
    if (nextLimiterRunning != limiterRunning_) limiterGain_ = 1.0;
    limiterRunning_ = nextLimiterRunning;

    reverb_.tune(appliedParams_);
    const bool nextReverbRunning = powerampActive && enabled(appliedParams_[PARAM_REVERB_ENABLED]) &&
        appliedParams_[PARAM_REVERB_MIX] > 0.0f;
    if (nextReverbRunning != reverbRunning_) reverb_.reset();
    reverbRunning_ = nextReverbRunning;
}

void Engine::applySnapshot(const ParamArray& params) noexcept {
    const Mode nextMode = sanitizedMode(params[PARAM_MODE]);
    if (!appliedValid_ || nextMode != appliedMode_) {
        resetState();
        reverbRunning_ = false;
        limiterRunning_ = false;
    }
    appliedParams_ = params;
    appliedMode_ = nextMode;
    appliedValid_ = true;
    rebuildFilters();
}

void Engine::applyGain(double* samples, std::size_t count, double gain) noexcept {
    if (gain == 1.0) return;
    for (std::size_t index = 0; index < count; ++index) samples[index] *= gain;
}

void Engine::processLimiter(double* samples, std::size_t frames) noexcept {
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
            limiterGain_ = target;  // Zero attack: no overshoot at the configured ceiling.
        } else {
            limiterGain_ = target + (limiterGain_ - target) * limiterReleaseCoefficient_;
        }
        limiterGain_ = std::max(0.0, std::min(1.0, limiterGain_));
        for (std::size_t channel = 0; channel < channelCount; ++channel) {
            samples[base + channel] *= limiterGain_;
        }
    }
}

void Engine::processHiby(double* samples, std::size_t frames) noexcept {
    const std::size_t count = frames * static_cast<std::size_t>(channels_);
    if (enabled(appliedParams_[PARAM_MSEB_ENABLED])) {
        processBank(msebBank_, samples, frames, channels_);
        applyGain(samples, count, dbToGain(appliedParams_[PARAM_MSEB_PREAMP_DB]));
    }
    if (enabled(appliedParams_[PARAM_PEQ_ENABLED])) {
        processBank(peqBank_, samples, frames, channels_);
        applyGain(samples, count, dbToGain(appliedParams_[PARAM_PEQ_PREAMP_DB]));
    }
}

void Engine::processPowerampBeforeReverb(
    double* samples,
    std::size_t frames,
    bool earlyLimiter) noexcept {
    const std::size_t count = frames * static_cast<std::size_t>(channels_);
    if (enabled(appliedParams_[PARAM_POWERAMP_EQ_ENABLED])) {
        processBank(powerampBank_, samples, frames, channels_);
        applyGain(samples, count, dbToGain(appliedParams_[PARAM_POWERAMP_PREAMP_DB]));
    }
    if (enabled(appliedParams_[PARAM_TONE_ENABLED])) {
        processBank(toneBank_, samples, frames, channels_);
    }
    if (earlyLimiter && limiterRunning_) processLimiter(samples, frames);
}

bool Engine::process(float* interleaved, std::size_t frames) noexcept {
    if (frames == 0) return true;
    if (interleaved == nullptr || sampleRate_ <= 0 || channels_ <= 0 ||
        frames > static_cast<std::size_t>(maxFrames_)) {
        return false;
    }

    ParamArray snapshot{};
    loadSnapshot(snapshot);
    const Mode mode = sanitizedMode(snapshot[PARAM_MODE]);
    if (mode == Mode::OFF || mode == Mode::BIT_PERFECT) {
        if (appliedValid_ || appliedMode_ != mode) resetState();
        appliedMode_ = mode;
        appliedValid_ = false;
        reverbRunning_ = false;
        limiterRunning_ = false;
        return true;  // No Float32 load, store, arithmetic, or canonicalization.
    }

    if (!appliedValid_ || mode != appliedMode_ || snapshot != appliedParams_) {
        applySnapshot(snapshot);
    }

    const std::size_t channelCount = static_cast<std::size_t>(channels_);
    const std::size_t count = frames * channelCount;
    for (std::size_t index = 0; index < count; ++index) {
        const double sample = static_cast<double>(interleaved[index]);
        workBuffer_[index] = std::isfinite(sample) ? sample : 0.0;
    }
    double* samples = workBuffer_.data();

    if (mode == Mode::HIBY) {
        processHiby(samples, frames);
    } else if (mode == Mode::POWERAMP) {
        // Canonical Poweramp-shaped order: EQ -> tone -> limiter -> reverb -> DVC.
        processPowerampBeforeReverb(samples, frames, true);
        if (reverbRunning_) reverb_.process(samples, frames, channels_);
        if (enabled(appliedParams_[PARAM_DVC_ENABLED])) {
            applyGain(samples, count, dbToGain(appliedParams_[PARAM_DVC_GAIN_DB]));
        }
    } else if (mode == Mode::HYBRID) {
        // HiBy -> Poweramp-shaped stages -> DVC -> one final limiter.
        processHiby(samples, frames);
        processPowerampBeforeReverb(samples, frames, false);
        if (reverbRunning_) reverb_.process(samples, frames, channels_);
        if (enabled(appliedParams_[PARAM_DVC_ENABLED])) {
            applyGain(samples, count, dbToGain(appliedParams_[PARAM_DVC_GAIN_DB]));
        }
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
    return true;
}

std::size_t Engine::Reverb::maximumLineLength(std::size_t line) const noexcept {
    const double rate = static_cast<double>(sampleRate_);
    double milliseconds = 100.0;
    if (line >= 1 && line <= kCombCount) {
        milliseconds = kCombMilliseconds[line - 1] * 2.0;
    } else if (line > kCombCount) {
        milliseconds = kAllpassMilliseconds[line - 1 - kCombCount];
    }
    return static_cast<std::size_t>(std::ceil(milliseconds * 0.001 * rate)) + 4U;
}

std::size_t Engine::Reverb::tunedLineLength(std::size_t line) const noexcept {
    double milliseconds = preDelay_ * 100.0;
    if (line >= 1 && line <= kCombCount) {
        milliseconds = kCombMilliseconds[line - 1] * sizeScale_;
    } else if (line > kCombCount) {
        milliseconds = kAllpassMilliseconds[line - 1 - kCombCount];
    }
    const std::size_t desired = static_cast<std::size_t>(milliseconds * 0.001 * sampleRate_) + 1U;
    return std::max<std::size_t>(1U, desired);
}

bool Engine::Reverb::configure(int sampleRate, int channels) {
    sampleRate_ = sampleRate;
    channels_ = channels;
    std::size_t total = 0;
    for (int channel = 0; channel < channels; ++channel) {
        for (std::size_t lineIndex = 0; lineIndex < kLinesPerChannel; ++lineIndex) {
            DelayLine& line = lines_[static_cast<std::size_t>(channel) * kLinesPerChannel + lineIndex];
            line = DelayLine{};
            line.offset = total;
            line.maximumLength = maximumLineLength(lineIndex);
            line.length = line.maximumLength;
            total += line.maximumLength;
        }
    }
    delayStorage_.assign(total, 0.0);
    reset();
    return true;
}

void Engine::Reverb::tune(const ParamArray& params) noexcept {
    damp_ = 0.95 * params[PARAM_REVERB_DAMP];
    const double filter = params[PARAM_REVERB_FILTER];
    const double unclampedCutoff = 700.0 * std::pow(16000.0 / 700.0, filter);
    const double cutoff = std::min(unclampedCutoff, sampleRate_ * 0.45);
    wetFilterAlpha_ = 1.0 - std::exp(-2.0 * kPi * cutoff / sampleRate_);
    feedback_ = 0.60 + 0.38 * params[PARAM_REVERB_FADE];
    preDelay_ = params[PARAM_REVERB_PRE_DELAY];
    preDelayMix_ = params[PARAM_REVERB_PRE_DELAY_MIX];
    sizeScale_ = 0.35 + 1.65 * params[PARAM_REVERB_SIZE];
    mix_ = params[PARAM_REVERB_MIX];

    for (int channel = 0; channel < channels_; ++channel) {
        for (std::size_t lineIndex = 0; lineIndex < kLinesPerChannel; ++lineIndex) {
            DelayLine& line = lines_[static_cast<std::size_t>(channel) * kLinesPerChannel + lineIndex];
            line.length = std::max<std::size_t>(1U,
                std::min(line.maximumLength, tunedLineLength(lineIndex)));
            line.write %= line.length;
        }
    }
}

void Engine::Reverb::reset() noexcept {
    std::fill(delayStorage_.begin(), delayStorage_.end(), 0.0);
    wetFilterState_.fill(0.0);
    for (auto& line : lines_) {
        line.write = 0;
        line.dampingState = 0.0;
    }
}

void Engine::Reverb::process(double* samples, std::size_t frames, int channels) noexcept {
    const std::size_t channelCount = static_cast<std::size_t>(channels);
    for (std::size_t frame = 0; frame < frames; ++frame) {
        const std::size_t base = frame * channelCount;
        for (int channel = 0; channel < channels; ++channel) {
            const double dry = samples[base + static_cast<std::size_t>(channel)];
            DelayLine* channelLines = lines_.data() + static_cast<std::size_t>(channel) * kLinesPerChannel;

            DelayLine& preDelay = channelLines[0];
            const double delayed = delayStorage_[preDelay.offset + preDelay.write];
            delayStorage_[preDelay.offset + preDelay.write] = dry;
            preDelay.write = (preDelay.write + 1U) % preDelay.length;
            const double wetInput = dry * (1.0 - preDelayMix_) + delayed * preDelayMix_;

            double combSum = 0.0;
            for (std::size_t comb = 0; comb < kCombCount; ++comb) {
                DelayLine& line = channelLines[1 + comb];
                const std::size_t address = line.offset + line.write;
                const double output = delayStorage_[address];
                const double damped = output * (1.0 - damp_) + line.dampingState * damp_;
                line.dampingState = damped;
                delayStorage_[address] = wetInput + damped * feedback_;
                line.write = (line.write + 1U) % line.length;
                combSum += output;
            }
            combSum *= 1.0 / static_cast<double>(kCombCount);

            double& filterState = wetFilterState_[static_cast<std::size_t>(channel)];
            filterState += wetFilterAlpha_ * (combSum - filterState);
            if (std::abs(filterState) < 1.0e-30) filterState = 0.0;
            double wet = filterState;

            for (std::size_t allpass = 0; allpass < kAllpassCount; ++allpass) {
                DelayLine& line = channelLines[1 + kCombCount + allpass];
                const std::size_t address = line.offset + line.write;
                const double buffered = delayStorage_[address];
                const double output = buffered - wet;
                delayStorage_[address] = wet + buffered * 0.5;
                line.write = (line.write + 1U) % line.length;
                wet = output;
            }
            samples[base + static_cast<std::size_t>(channel)] = dry * (1.0 - mix_) + wet * mix_;
        }
    }
}

}  // namespace aurisdsp
