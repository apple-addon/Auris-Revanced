#ifndef BITCHORD_SPSC_FLOAT_RING_H_
#define BITCHORD_SPSC_FLOAT_RING_H_

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

#include "output_common.h"

namespace bitchord::audio {

// One renderer-thread producer and one audio-callback consumer. Construction
// allocates and zeroes the complete store; read(), write() and available()
// never allocate, throw, lock or access a JNI/Java object.
class SpscFloatRing {
 public:
  SpscFloatRing(int32_t requested_capacity_frames, int32_t channel_count)
      : capacity_frames_(RoundUpPowerOfTwo(requested_capacity_frames)),
        mask_(static_cast<uint64_t>(capacity_frames_ - 1)),
        frame_bytes_(static_cast<size_t>(channel_count) * kFloatBytes),
        samples_(static_cast<size_t>(capacity_frames_) * channel_count, 0.0f) {}

  SpscFloatRing(const SpscFloatRing&) = delete;
  SpscFloatRing& operator=(const SpscFloatRing&) = delete;

  size_t write(const void* source, size_t frame_count) noexcept {
    const uint64_t tail = tail_.load(std::memory_order_relaxed);
    const uint64_t head = head_.load(std::memory_order_acquire);
    const uint64_t used = tail - head;
    const size_t free_frames = used >= static_cast<uint64_t>(capacity_frames_)
                                   ? 0
                                   : capacity_frames_ - static_cast<size_t>(used);
    const size_t accepted = std::min(frame_count, free_frames);
    if (accepted == 0) return 0;

    const auto* input = static_cast<const uint8_t*>(source);
    const size_t offset = static_cast<size_t>(tail & mask_);
    const size_t first = std::min(accepted, capacity_frames_ - offset);
    copyFrames(input, offset, first);
    if (first < accepted) {
      copyFrames(input + first * frame_bytes_, 0, accepted - first);
    }
    tail_.store(tail + accepted, std::memory_order_release);
    return accepted;
  }

  size_t read(void* destination, size_t frame_count) noexcept {
    const uint64_t head = head_.load(std::memory_order_relaxed);
    const uint64_t tail = tail_.load(std::memory_order_acquire);
    const size_t available = tail - head >= static_cast<uint64_t>(capacity_frames_)
                                 ? capacity_frames_
                                 : static_cast<size_t>(tail - head);
    const size_t consumed = std::min(frame_count, available);
    if (consumed == 0) return 0;

    auto* output = static_cast<uint8_t*>(destination);
    const size_t offset = static_cast<size_t>(head & mask_);
    const size_t first = std::min(consumed, capacity_frames_ - offset);
    copyTo(output, offset, first);
    if (first < consumed) {
      copyTo(output + first * frame_bytes_, 0, consumed - first);
    }
    head_.store(head + consumed, std::memory_order_release);
    return consumed;
  }

  size_t available() const noexcept {
    const uint64_t head = head_.load(std::memory_order_acquire);
    const uint64_t tail = tail_.load(std::memory_order_acquire);
    const uint64_t used = tail - head;
    return used >= static_cast<uint64_t>(capacity_frames_)
               ? static_cast<size_t>(capacity_frames_)
               : static_cast<size_t>(used);
  }

  size_t capacityFrames() const noexcept {
    return static_cast<size_t>(capacity_frames_);
  }

  size_t frameBytes() const noexcept { return frame_bytes_; }

  // Only call while the producer and callback consumer are both quiescent.
  void resetQuiescent() noexcept {
    head_.store(0, std::memory_order_relaxed);
    tail_.store(0, std::memory_order_relaxed);
  }

 private:
  void copyFrames(const uint8_t* source, size_t frame_offset,
                  size_t frame_count) noexcept {
    std::memcpy(reinterpret_cast<uint8_t*>(samples_.data()) +
                    frame_offset * frame_bytes_,
                source, frame_count * frame_bytes_);
  }

  void copyTo(uint8_t* destination, size_t frame_offset,
              size_t frame_count) const noexcept {
    std::memcpy(destination,
                reinterpret_cast<const uint8_t*>(samples_.data()) +
                    frame_offset * frame_bytes_,
                frame_count * frame_bytes_);
  }

  const int32_t capacity_frames_;
  const uint64_t mask_;
  const size_t frame_bytes_;
  std::vector<float> samples_;
  alignas(64) std::atomic<uint64_t> head_{0};
  alignas(64) std::atomic<uint64_t> tail_{0};
};

}  // namespace bitchord::audio

#endif  // BITCHORD_SPSC_FLOAT_RING_H_
