#ifndef BITCHORD_AAUDIO_OUTPUT_H_
#define BITCHORD_AAUDIO_OUTPUT_H_

#include <aaudio/AAudio.h>

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

#include "output_common.h"
#include "spsc_float_ring.h"

namespace bitchord::audio {

class AAudioOutput {
 public:
  explicit AAudioOutput(const OutputConfig& config);
  ~AAudioOutput();

  AAudioOutput(const AAudioOutput&) = delete;
  AAudioOutput& operator=(const AAudioOutput&) = delete;

  bool open();
  bool start();
  bool pause();
  bool flush();
  size_t write(const void* source, size_t frame_count) noexcept;
  size_t frameBytes() const noexcept { return ring_.frameBytes(); }
  int64_t positionFrames();
  NativeTelemetry telemetry();
  std::string lastError() const;
  bool isOpen() const noexcept;
  bool isPlaying() const;

 private:
  enum RecoveryReason : int32_t {
    kRecoveryNone = 0,
    kRecoveryXrun = 1,
    kRecoveryError = 2,
  };

  static aaudio_data_callback_result_t dataCallback(
      AAudioStream* stream, void* user_data, void* audio_data,
      int32_t frame_count);
  static void errorCallback(AAudioStream* stream, void* user_data,
                            aaudio_result_t error);

  aaudio_data_callback_result_t onData(AAudioStream* stream,
                                       void* audio_data,
                                       int32_t frame_count) noexcept;
  void onError(AAudioStream* stream, aaudio_result_t error) noexcept;
  void requestRecovery(RecoveryReason reason) noexcept;

  bool openWithExclusiveFallbackLocked();
  bool openAttemptLocked(aaudio_sharing_mode_t sharing_mode,
                         AAudioStream** opened_stream);
  void closeStreamLocked();
  bool pauseLocked();
  bool flushStreamLocked();
  bool waitForStateLocked(AAudioStream* stream,
                          aaudio_stream_state_t current_state,
                          aaudio_stream_state_t target_state);
  bool recover(RecoveryReason reason);
  void recoveryLoop() noexcept;
  int64_t positionFramesLocked();
  void preservePositionForNewStreamLocked();

  const OutputConfig config_;
  SpscFloatRing ring_;

  mutable std::mutex control_mutex_;
  std::atomic<AAudioStream*> stream_{nullptr};
  std::atomic<bool> open_{false};
  std::atomic<bool> playing_requested_{false};
  std::atomic<bool> released_{false};

  std::atomic<int64_t> frames_written_{0};
  std::atomic<int64_t> last_position_{-1};
  std::atomic<int64_t> underrun_count_{0};
  std::atomic<int64_t> xrun_count_{0};
  std::atomic<int64_t> recovery_count_{0};
  std::atomic<int64_t> disconnect_count_{0};
  std::atomic<int32_t> last_stream_xrun_count_{0};
  std::atomic<int32_t> last_error_code_{AAUDIO_OK};
  std::atomic<ClockSource> clock_source_{ClockSource::kNone};
  std::atomic<int32_t> recovery_reason_{kRecoveryNone};
  std::atomic<uint32_t> callback_active_{0};

  // Protected by control_mutex_. AAudio timestamps restart at zero when a
  // disconnected stream is replaced; this base keeps the public clock stable.
  int64_t timestamp_base_ = 0;

  std::thread recovery_thread_;
  std::mutex recovery_mutex_;
  std::condition_variable recovery_cv_;
  std::atomic<bool> recovery_stop_{false};
};

}  // namespace bitchord::audio

#endif  // BITCHORD_AAUDIO_OUTPUT_H_
