#ifndef AURIS_NATIVE_OUTPUT_H
#define AURIS_NATIVE_OUTPUT_H

#include <cstdint>
#include <string>

namespace auris {

constexpr int kErrorInvalidArgument = -12001;
constexpr int kErrorInvalidBuffer = -12004;
constexpr int64_t kUnknown = -1;

struct OutputSpec {
    int32_t sample_rate = 0;
    int32_t channels = 0;
    int32_t audio_session_id = 0;
    int32_t requested_buffer_frames = 0;

    int32_t frame_bytes() const { return channels * static_cast<int32_t>(sizeof(float)); }
    bool valid() const {
        return sample_rate > 0 && sample_rate <= 768000 && channels > 0 && channels <= 32 &&
                requested_buffer_frames >= 0;
    }
};

struct OutputSnapshot {
    int64_t sample_rate = kUnknown;
    int64_t channels = kUnknown;
    int64_t bits_per_sample = kUnknown;
    int64_t buffer_size_frames = kUnknown;
    int64_t buffer_capacity_frames = kUnknown;
    int64_t xruns = kUnknown;
    int64_t output_frames = kUnknown;
    int64_t position_us = kUnknown;
    int64_t sharing = 0; // 0 unknown, 1 shared, 2 exclusive.
    int64_t audio_session_id = kUnknown;
    std::string failure;
};

inline int64_t frames_to_us(int64_t frames, int32_t sample_rate) {
    if (frames < 0 || sample_rate <= 0) return kUnknown;
    const int64_t seconds = frames / sample_rate;
    const int64_t remainder_us = (frames % sample_rate) * 1000000 / sample_rate;
    if (seconds > (INT64_MAX - remainder_us) / 1000000) return INT64_MAX;
    return seconds * 1000000 + remainder_us;
}

class NativeOutput {
public:
    virtual ~NativeOutput() = default;

    virtual int play() = 0;
    virtual int pause() = 0;
    /** Consumes whole interleaved float frames and returns a frame count or error. */
    virtual int write(const uint8_t *data, int32_t byte_count) = 0;
    virtual int flush() = 0;
    virtual int stop() = 0;
    virtual void set_volume(float volume) = 0;
    virtual int64_t xrun_count() = 0;
    virtual OutputSnapshot snapshot() = 0;
    virtual int32_t frame_bytes() const = 0;
};

int opensl_error(uint32_t result);

} // namespace auris

#endif
