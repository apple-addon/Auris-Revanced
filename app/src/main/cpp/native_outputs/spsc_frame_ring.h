#ifndef AURIS_SPSC_FRAME_RING_H
#define AURIS_SPSC_FRAME_RING_H

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>

namespace auris {

/**
 * One producer / one consumer, interleaved-float frame ring. The producer and
 * consumer may be different threads; clear() requires both sides quiesced.
 * Monotonic cursors avoid the empty/full ambiguity and index wraparound bugs.
 */
class SpscFrameRing {
    static_assert(std::atomic<uint64_t>::is_always_lock_free,
            "The audio callback requires lock-free 64-bit cursors");

public:
    SpscFrameRing(size_t capacity_frames, size_t channels)
            : capacity_frames_(capacity_frames),
              channels_(channels),
              samples_(capacity_frames > 0 && channels > 0
                      ? new float[capacity_frames * channels]
                      : nullptr) {
    }

    bool valid() const { return samples_ != nullptr; }
    size_t capacity_frames() const { return capacity_frames_; }

    size_t readable_frames() const {
        const uint64_t write = write_cursor_.load(std::memory_order_acquire);
        const uint64_t read = read_cursor_.load(std::memory_order_acquire);
        return static_cast<size_t>(write - read);
    }

    size_t writable_frames() const {
        return capacity_frames_ - readable_frames();
    }

    size_t write(const float *input, size_t frames, float gain) {
        if (!input || frames == 0 || !valid()) return 0;
        const uint64_t write = write_cursor_.load(std::memory_order_relaxed);
        const uint64_t read = read_cursor_.load(std::memory_order_acquire);
        const size_t available = capacity_frames_ - static_cast<size_t>(write - read);
        const size_t count = std::min(frames, available);
        if (count == 0) return 0;

        const size_t offset = static_cast<size_t>(write % capacity_frames_);
        const size_t first = std::min(count, capacity_frames_ - offset);
        copy(input, samples_.get() + offset * channels_, first, gain);
        copy(input + first * channels_, samples_.get(), count - first, gain);
        write_cursor_.store(write + count, std::memory_order_release);
        return count;
    }

    size_t read(float *output, size_t frames) {
        if (!output || frames == 0 || !valid()) return 0;
        const uint64_t read = read_cursor_.load(std::memory_order_relaxed);
        const uint64_t write = write_cursor_.load(std::memory_order_acquire);
        const size_t available = static_cast<size_t>(write - read);
        const size_t count = std::min(frames, available);
        if (count == 0) return 0;

        const size_t offset = static_cast<size_t>(read % capacity_frames_);
        const size_t first = std::min(count, capacity_frames_ - offset);
        std::memcpy(output, samples_.get() + offset * channels_, first * channels_ * sizeof(float));
        std::memcpy(output + first * channels_, samples_.get(),
                (count - first) * channels_ * sizeof(float));
        read_cursor_.store(read + count, std::memory_order_release);
        return count;
    }

    void clear() {
        read_cursor_.store(0, std::memory_order_relaxed);
        write_cursor_.store(0, std::memory_order_relaxed);
    }

private:
    void copy(const float *input, float *output, size_t frames, float gain) {
        const size_t sample_count = frames * channels_;
        if (sample_count == 0) return;
        if (gain == 1.0f) {
            std::memcpy(output, input, sample_count * sizeof(float));
            return;
        }
        for (size_t i = 0; i < sample_count; ++i) output[i] = input[i] * gain;
    }

    const size_t capacity_frames_;
    const size_t channels_;
    std::unique_ptr<float[]> samples_;
    alignas(64) std::atomic<uint64_t> read_cursor_{0};
    alignas(64) std::atomic<uint64_t> write_cursor_{0};
};

} // namespace auris

#endif
