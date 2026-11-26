#ifndef AURIS_DSP_ENGINE_H
#define AURIS_DSP_ENGINE_H

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

#include "AurisDspParams.h"
#include "Biquad.h"
#include "MsebMapping.h"

namespace aurisdsp {

using ParamArray = std::array<float, kParamCount>;

/** Return the neutral OFF snapshot used by nativeSetParams for invalid entries. */
ParamArray makeDefaultParams() noexcept;

/** Stable FNV-1a identifier for this engine ABI and algorithm revision. */
const char* versionHash() noexcept;

/**
 * Self-contained interleaved Float32 / internal Float64 PCM engine.
 *
 * configure() and setParams() are control-thread operations. process() and
 * reset() are audio-thread operations and allocate no memory. Do not destroy or
 * reconfigure an engine concurrently with process()/reset(). setParams() may
 * run concurrently with process(): a lock-free seqlock snapshot prevents torn
 * parameter blocks; its writer mutex is never acquired by the audio thread.
 */
class Engine final {
public:
    Engine() noexcept;
    ~Engine() = default;

    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    bool configure(int sampleRate, int channels, int maxFrames) noexcept;
    void setParams(const float* params, std::size_t count) noexcept;
    bool process(float* interleaved, std::size_t frames) noexcept;
    void reset() noexcept;

    int sampleRate() const noexcept { return sampleRate_; }
    int channels() const noexcept { return channels_; }
    int maxFrames() const noexcept { return maxFrames_; }

private:
    struct DelayLine {
        std::size_t offset = 0;
        std::size_t maximumLength = 1;
        std::size_t length = 1;
        std::size_t write = 0;
        double dampingState = 0.0;
    };

    class Reverb final {
    public:
        static constexpr std::size_t kCombCount = 4;
        static constexpr std::size_t kAllpassCount = 2;
        static constexpr std::size_t kLinesPerChannel = 1 + kCombCount + kAllpassCount;

        bool configure(int sampleRate, int channels);
        void tune(const ParamArray& params) noexcept;
        void reset() noexcept;
        void process(double* samples, std::size_t frames, int channels) noexcept;

    private:
        std::size_t maximumLineLength(std::size_t line) const noexcept;
        std::size_t tunedLineLength(std::size_t line) const noexcept;

        int sampleRate_ = 0;
        int channels_ = 0;
        double damp_ = 0.0;
        double wetFilterAlpha_ = 1.0;
        double feedback_ = 0.6;
        double preDelay_ = 0.0;
        double preDelayMix_ = 0.0;
        double sizeScale_ = 0.35;
        double mix_ = 0.0;
        std::array<double, kMaxChannels> wetFilterState_{};
        std::array<DelayLine, kMaxChannels * kLinesPerChannel> lines_{};
        std::vector<double> delayStorage_;
    };

    void loadSnapshot(ParamArray& output) const noexcept;
    void applySnapshot(const ParamArray& params) noexcept;
    void rebuildFilters() noexcept;
    void resetState() noexcept;

    template <std::size_t N>
    static void processBank(
        std::array<Biquad, N>& bank,
        double* samples,
        std::size_t frames,
        int channels) noexcept {
        for (std::size_t band = 0; band < N; ++band) {
            Biquad& biquad = bank[band];
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

    static void applyGain(double* samples, std::size_t count, double gain) noexcept;
    void processLimiter(double* samples, std::size_t frames) noexcept;
    void processPowerampBeforeReverb(double* samples, std::size_t frames, bool earlyLimiter) noexcept;
    void processHiby(double* samples, std::size_t frames) noexcept;

    int sampleRate_ = 0;
    int channels_ = 0;
    int maxFrames_ = 0;

    std::array<Biquad, kMsebSectionCount> msebBank_{};
    std::array<Biquad, kPeqBandCount> peqBank_{};
    std::array<Biquad, kPowerampBandCount> powerampBank_{};
    std::array<Biquad, 2> toneBank_{};
    Reverb reverb_;
    std::vector<double> workBuffer_;

    double limiterGain_ = 1.0;
    double limiterReleaseCoefficient_ = 0.0;
    double limiterCeiling_ = 1.0;
    bool reverbRunning_ = false;
    bool limiterRunning_ = false;

    ParamArray appliedParams_{};
    Mode appliedMode_ = Mode::OFF;
    bool appliedValid_ = false;

    // Seqlock payload uses atomic bits, not concurrently accessed plain floats;
    // this avoids a C++ data race as well as logical snapshot tearing.
    std::atomic<std::uint32_t> sequence_{0};
    std::array<std::atomic<std::uint32_t>, kParamCount> publishedBits_{};
    std::mutex writerMutex_;
};

}  // namespace aurisdsp

#endif  // AURIS_DSP_ENGINE_H
