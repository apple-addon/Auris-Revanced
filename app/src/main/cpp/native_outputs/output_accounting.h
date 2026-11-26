#ifndef AURIS_OUTPUT_ACCOUNTING_H
#define AURIS_OUTPUT_ACCOUNTING_H

#include <atomic>
#include <cstdint>

namespace auris {

/** Callback-side counters; no allocation, locks, or clock calls. */
class OutputAccounting {
public:
    void record_enqueued(uint32_t buffer_frames, uint32_t pcm_frames) {
        output_frames_.fetch_add(buffer_frames, std::memory_order_relaxed);
        position_frames_.fetch_add(buffer_frames, std::memory_order_relaxed);
        if (pcm_frames < buffer_frames) {
            underruns_.fetch_add(1, std::memory_order_relaxed);
            missing_frames_.fetch_add(buffer_frames - pcm_frames, std::memory_order_relaxed);
        }
    }

    uint64_t output_frames() const { return output_frames_.load(std::memory_order_relaxed); }
    uint64_t position_frames() const { return position_frames_.load(std::memory_order_relaxed); }
    uint64_t underruns() const { return underruns_.load(std::memory_order_relaxed); }
    uint64_t missing_frames() const { return missing_frames_.load(std::memory_order_relaxed); }

    /** Seeks reset the position clock, not lifetime output/underrun diagnostics. */
    void reset_position() { position_frames_.store(0, std::memory_order_relaxed); }

private:
    std::atomic<uint64_t> output_frames_{0};
    std::atomic<uint64_t> position_frames_{0};
    std::atomic<uint64_t> underruns_{0};
    std::atomic<uint64_t> missing_frames_{0};
};

} // namespace auris

#endif
