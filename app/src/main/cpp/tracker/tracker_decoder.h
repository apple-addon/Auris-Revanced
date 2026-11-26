#ifndef BITCHORD_TRACKER_DECODER_H
#define BITCHORD_TRACKER_DECODER_H

#include "libopenmpt/libopenmpt.h"

#include <cstddef>
#include <cstdint>
#include <memory>

namespace bitchord::tracker {

inline constexpr std::int32_t kSampleRate = 48'000;
inline constexpr std::int32_t kChannelCount = 2;
inline constexpr std::int32_t kBytesPerSample = 4;
inline constexpr std::int32_t kBytesPerFrame = kChannelCount * kBytesPerSample;
inline constexpr std::int64_t kTimeUnset = -1;

/**
 * Finite, single-play libopenmpt decoder.
 *
 * Construction may allocate in libopenmpt. read() only renders into the
 * caller's buffer and performs no wrapper-side allocation or sample copying.
 * The input bytes/stream are needed only while the module is being created.
 */
class Decoder final {
public:
    static std::unique_ptr<Decoder> createFromMemory(const void* data, std::size_t size) noexcept;
    static std::unique_ptr<Decoder> createFromCallbacks(
        openmpt_stream_callbacks callbacks, void* stream) noexcept;

    ~Decoder();

    Decoder(const Decoder&) = delete;
    Decoder& operator=(const Decoder&) = delete;
    Decoder(Decoder&&) = delete;
    Decoder& operator=(Decoder&&) = delete;

    double durationSeconds() const noexcept { return duration_seconds_; }
    std::int64_t durationUs() const noexcept;

    /** Seeks and returns libopenmpt's approximate new position in seconds. */
    double seekSeconds(double seconds) noexcept;
    void reset() noexcept;

    /** Writes little-endian Float32 L,R frames and returns the frame count. */
    std::size_t read(float* interleaved, std::size_t frame_count) noexcept;

    std::int64_t positionFrame() const noexcept { return position_frame_; }
    std::int64_t positionUs() const noexcept { return framesToUs(position_frame_); }
    bool ended() const noexcept { return ended_; }

    static std::int64_t framesToUs(std::int64_t frames) noexcept;

private:
    explicit Decoder(openmpt_module* module) noexcept;
    static std::unique_ptr<Decoder> adopt(openmpt_module* module) noexcept;

    openmpt_module* module_;
    double duration_seconds_;
    std::int64_t position_frame_ = 0;
    bool ended_ = false;
};

}  // namespace bitchord::tracker

#endif  // BITCHORD_TRACKER_DECODER_H
