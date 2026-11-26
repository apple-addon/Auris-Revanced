#ifndef BITCHORD_OUTPUT_COMMON_H_
#define BITCHORD_OUTPUT_COMMON_H_

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace bitchord::audio {

constexpr int kFloatBytes = 4;
constexpr int kMaxChannels = 8;
constexpr int32_t kMinSampleRate = 4000;
constexpr int32_t kMaxSampleRate = 768000;
constexpr int32_t kMaxRingCapacityFrames = 1 << 22;
constexpr int kTelemetryValueCount = 19;

// Keep this order in sync with the Kotlin telemetry decoder.
enum TelemetryIndex {
  kOpen = 0,
  kPlaying = 1,
  kSampleRate = 2,
  kChannelCount = 3,
  kRingCapacityFrames = 4,
  kBufferCapacityFrames = 5,
  kFramesPerBuffer = 6,
  kSharingMode = 7,
  kLowLatency = 8,
  kPositionFrames = 9,
  kClockSource = 10,
  kFramesWritten = 11,
  kUnderrunCount = 12,
  kXrunCount = 13,
  kRecoveryCount = 14,
  kDisconnectCount = 15,
  kBufferedFrames = 16,
  kNativeState = 17,
  kLastErrorCode = 18,
};

enum class ClockSource : int64_t {
  kNone = 0,
  kAAudioTimestamp = 1,
  kAAudioFramesRead = 2,
  kOpenSlQueueCompletion = 3,
};

class ScopedCallbackActivity {
 public:
  explicit ScopedCallbackActivity(std::atomic<uint32_t>& active) noexcept
      : active_(active) {
    active_.fetch_add(1, std::memory_order_acq_rel);
  }
  ~ScopedCallbackActivity() {
    active_.fetch_sub(1, std::memory_order_acq_rel);
  }

  ScopedCallbackActivity(const ScopedCallbackActivity&) = delete;
  ScopedCallbackActivity& operator=(const ScopedCallbackActivity&) = delete;

 private:
  std::atomic<uint32_t>& active_;
};

struct OutputConfig {
  int32_t sample_rate;
  int32_t channel_count;
  int32_t ring_capacity_frames;
  int32_t buffer_size_frames;  // Zero lets the backend choose.
  int32_t device_id;           // Zero is AAUDIO_UNSPECIFIED.
};

struct NativeTelemetry {
  bool is_open = false;
  bool is_playing = false;
  int32_t sample_rate = 0;
  int32_t channel_count = 0;
  int64_t ring_capacity_frames = 0;
  int64_t buffer_capacity_frames = 0;
  int64_t frames_per_buffer = 0;
  int32_t sharing_mode = -1;
  bool low_latency = false;
  int64_t position_frames = -1;
  ClockSource clock_source = ClockSource::kNone;
  int64_t frames_written = 0;
  int64_t underrun_count = 0;
  int64_t xrun_count = 0;
  int64_t recovery_count = 0;
  int64_t disconnect_count = 0;
  int64_t buffered_frames = 0;
  int32_t native_state = 0;
  int32_t last_error_code = 0;
};

inline bool IsValidConfig(const OutputConfig& config) {
  if (config.sample_rate < kMinSampleRate ||
      config.sample_rate > kMaxSampleRate ||
      config.channel_count <= 0 || config.channel_count > kMaxChannels ||
      config.ring_capacity_frames <= 0 ||
      config.ring_capacity_frames > kMaxRingCapacityFrames ||
      config.buffer_size_frames < 0 ||
      config.buffer_size_frames > config.ring_capacity_frames ||
      config.device_id < 0) {
    return false;
  }
  const int64_t frame_bytes =
      static_cast<int64_t>(config.channel_count) * kFloatBytes;
  const int64_t sample_bytes =
      static_cast<int64_t>(config.ring_capacity_frames) * frame_bytes;
  return sample_bytes <= std::numeric_limits<int32_t>::max();
}

// The ring uses a mask rather than a modulo in its real-time operations.
constexpr int32_t RoundUpPowerOfTwo(int32_t value) {
  if (value <= 1) return 1;
  return 1 << (32 - __builtin_clz(static_cast<uint32_t>(value - 1)));
}

// Extrapolates an AAudio timestamp without overflowing int64_t. The upper
// bound is the number of application frames written, so padded silence can
// never move the media position beyond content actually supplied by the app.
constexpr int64_t ExtrapolateTimestampFrames(
    int64_t frame_position,
    int64_t timestamp_nanoseconds,
    int64_t now_nanoseconds,
    int32_t sample_rate,
    int64_t upper_bound) {
  if (frame_position < 0 || timestamp_nanoseconds < 0 ||
      now_nanoseconds < timestamp_nanoseconds || sample_rate <= 0 ||
      upper_bound < 0) {
    return -1;
  }
  if (frame_position >= upper_bound) return upper_bound;
  const int64_t elapsed = now_nanoseconds - timestamp_nanoseconds;
  const int64_t seconds = elapsed / 1000000000LL;
  const int64_t remainder = elapsed % 1000000000LL;
  const int64_t remaining_capacity = upper_bound - frame_position;

  // Only arithmetic at or below remaining_capacity needs to be represented.
  // This avoids both 128-bit integers (unsupported on 32-bit Android) and
  // overflow when a caller supplies a timestamp from the distant past.
  if (seconds > remaining_capacity / sample_rate) return upper_bound;
  const int64_t whole_seconds = seconds * sample_rate;
  const int64_t remainder_frames =
      (remainder * static_cast<int64_t>(sample_rate)) / 1000000000LL;
  if (remainder_frames > remaining_capacity - whole_seconds) return upper_bound;
  return frame_position + whole_seconds + remainder_frames;
}

inline void WriteTelemetry(const NativeTelemetry& telemetry,
                           int64_t* values) {
  values[kOpen] = telemetry.is_open ? 1 : 0;
  values[kPlaying] = telemetry.is_playing ? 1 : 0;
  values[kSampleRate] = telemetry.sample_rate;
  values[kChannelCount] = telemetry.channel_count;
  values[kRingCapacityFrames] = telemetry.ring_capacity_frames;
  values[kBufferCapacityFrames] = telemetry.buffer_capacity_frames;
  values[kFramesPerBuffer] = telemetry.frames_per_buffer;
  values[kSharingMode] = telemetry.sharing_mode;
  values[kLowLatency] = telemetry.low_latency ? 1 : 0;
  values[kPositionFrames] = telemetry.position_frames;
  values[kClockSource] = static_cast<int64_t>(telemetry.clock_source);
  values[kFramesWritten] = telemetry.frames_written;
  values[kUnderrunCount] = telemetry.underrun_count;
  values[kXrunCount] = telemetry.xrun_count;
  values[kRecoveryCount] = telemetry.recovery_count;
  values[kDisconnectCount] = telemetry.disconnect_count;
  values[kBufferedFrames] = telemetry.buffered_frames;
  values[kNativeState] = telemetry.native_state;
  values[kLastErrorCode] = telemetry.last_error_code;
}

}  // namespace bitchord::audio

#endif  // BITCHORD_OUTPUT_COMMON_H_
