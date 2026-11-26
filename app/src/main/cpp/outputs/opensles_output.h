#ifndef BITCHORD_OPENSLES_OUTPUT_H_
#define BITCHORD_OPENSLES_OUTPUT_H_

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "output_common.h"
#include "spsc_float_ring.h"

namespace bitchord::audio {

class OpenSlEsOutput {
 public:
  static constexpr size_t kQueueSlotCount = 4;

  explicit OpenSlEsOutput(const OutputConfig& config);
  ~OpenSlEsOutput();

  OpenSlEsOutput(const OpenSlEsOutput&) = delete;
  OpenSlEsOutput& operator=(const OpenSlEsOutput&) = delete;

  bool open();
  bool start();
  bool pause();
  bool flush();
  size_t write(const void* source, size_t frame_count) noexcept;
  size_t frameBytes() const noexcept { return ring_.frameBytes(); }
  int64_t positionFrames() const noexcept;
  NativeTelemetry telemetry();
  std::string lastError() const;
  bool isOpen() const noexcept;
  bool isPlaying() const;

 private:
  struct QueueSlot {
    std::vector<float> samples;
    uint32_t audio_frames = 0;
  };

  static void SLAPIENTRY bufferQueueCallback(
      SLAndroidSimpleBufferQueueItf caller, void* context);
  void onBufferQueue(SLAndroidSimpleBufferQueueItf caller) noexcept;
  bool enqueueOne(SLAndroidSimpleBufferQueueItf queue, bool count_underrun) noexcept;
  bool primeQueueLocked();
  void closeObjectsLocked();
  bool resetQueueLocked();

  const OutputConfig config_;
  const int32_t buffer_frames_;
  SpscFloatRing ring_;
  std::array<QueueSlot, kQueueSlotCount> slots_;

  mutable std::mutex control_mutex_;
  SLObjectItf engine_object_ = nullptr;
  SLEngineItf engine_ = nullptr;
  SLObjectItf output_mix_object_ = nullptr;
  SLObjectItf player_object_ = nullptr;
  SLPlayItf player_ = nullptr;
  SLAndroidSimpleBufferQueueItf buffer_queue_ = nullptr;

  std::atomic<bool> open_{false};
  std::atomic<bool> playing_requested_{false};
  std::atomic<bool> released_{false};
  std::atomic<int64_t> frames_written_{0};
  std::atomic<int64_t> frames_completed_{0};
  std::atomic<int64_t> underrun_count_{0};
  std::atomic<int32_t> last_error_code_{SL_RESULT_SUCCESS};
  std::atomic<int32_t> queued_buffers_{0};
  std::atomic<int64_t> queued_audio_frames_{0};
  std::atomic<uint32_t> callback_active_{0};

  // Queue ownership is FIFO. Control enqueues while stopped and the callback
  // enqueues while running; resetQueueLocked() establishes the stopped state.
  uint32_t next_enqueue_ = 0;
  uint32_t next_completion_ = 0;
};

}  // namespace bitchord::audio

#endif  // BITCHORD_OPENSLES_OUTPUT_H_
