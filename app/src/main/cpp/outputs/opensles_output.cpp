#include "opensles_output.h"

#include <algorithm>
#include <cstring>
#include <thread>

namespace bitchord::audio {
namespace {

void waitForCallbacks(const std::atomic<uint32_t>& active) {
  while (active.load(std::memory_order_acquire) != 0) {
    std::this_thread::yield();
  }
}

const char* resultName(SLresult result) {
  switch (result) {
    case SL_RESULT_SUCCESS: return "SL_RESULT_SUCCESS";
    case SL_RESULT_PRECONDITIONS_VIOLATED: return "SL_RESULT_PRECONDITIONS_VIOLATED";
    case SL_RESULT_PARAMETER_INVALID: return "SL_RESULT_PARAMETER_INVALID";
    case SL_RESULT_MEMORY_FAILURE: return "SL_RESULT_MEMORY_FAILURE";
    case SL_RESULT_RESOURCE_ERROR: return "SL_RESULT_RESOURCE_ERROR";
    case SL_RESULT_RESOURCE_LOST: return "SL_RESULT_RESOURCE_LOST";
    case SL_RESULT_IO_ERROR: return "SL_RESULT_IO_ERROR";
    case SL_RESULT_BUFFER_INSUFFICIENT: return "SL_RESULT_BUFFER_INSUFFICIENT";
    case SL_RESULT_CONTENT_CORRUPTED: return "SL_RESULT_CONTENT_CORRUPTED";
    case SL_RESULT_CONTENT_UNSUPPORTED: return "SL_RESULT_CONTENT_UNSUPPORTED";
    case SL_RESULT_CONTENT_NOT_FOUND: return "SL_RESULT_CONTENT_NOT_FOUND";
    case SL_RESULT_PERMISSION_DENIED: return "SL_RESULT_PERMISSION_DENIED";
    case SL_RESULT_FEATURE_UNSUPPORTED: return "SL_RESULT_FEATURE_UNSUPPORTED";
    case SL_RESULT_INTERNAL_ERROR: return "SL_RESULT_INTERNAL_ERROR";
    case SL_RESULT_UNKNOWN_ERROR: return "SL_RESULT_UNKNOWN_ERROR";
    case SL_RESULT_OPERATION_ABORTED: return "SL_RESULT_OPERATION_ABORTED";
    case SL_RESULT_CONTROL_LOST: return "SL_RESULT_CONTROL_LOST";
    default: return nullptr;
  }
}

}  // namespace

OpenSlEsOutput::OpenSlEsOutput(const OutputConfig& config)
    : config_(config),
      buffer_frames_(std::min<int32_t>(
          config.buffer_size_frames > 0
              ? config.buffer_size_frames
              : std::clamp<int32_t>(config.sample_rate / 100, 64, 1024),
          RoundUpPowerOfTwo(config.ring_capacity_frames))),
      ring_(config.ring_capacity_frames, config.channel_count) {
  for (QueueSlot& slot : slots_) {
    slot.samples.resize(static_cast<size_t>(buffer_frames_) * config.channel_count,
                        0.0f);
  }
}

OpenSlEsOutput::~OpenSlEsOutput() {
  released_.store(true, std::memory_order_release);
  playing_requested_.store(false, std::memory_order_release);
  std::lock_guard<std::mutex> lock(control_mutex_);
  closeObjectsLocked();
}

bool OpenSlEsOutput::open() {
  std::lock_guard<std::mutex> lock(control_mutex_);
  if (released_.load(std::memory_order_acquire)) return false;
  closeObjectsLocked();
  ring_.resetQuiescent();
  frames_written_.store(0, std::memory_order_release);
  frames_completed_.store(0, std::memory_order_release);
  underrun_count_.store(0, std::memory_order_release);
  queued_audio_frames_.store(0, std::memory_order_release);
  next_enqueue_ = 0;
  next_completion_ = 0;
  last_error_code_.store(SL_RESULT_SUCCESS, std::memory_order_release);

  const SLEngineOption engine_options[] = {
      {SL_ENGINEOPTION_THREADSAFE, SL_BOOLEAN_TRUE},
  };
  SLresult result = slCreateEngine(&engine_object_, 1, engine_options, 0,
                                   nullptr, nullptr);
  if (result == SL_RESULT_SUCCESS) {
    result = (*engine_object_)
                 ->Realize(engine_object_, SL_BOOLEAN_FALSE);
  }
  if (result == SL_RESULT_SUCCESS) {
    result = (*engine_object_)
                 ->GetInterface(engine_object_, SL_IID_ENGINE, &engine_);
  }
  if (result == SL_RESULT_SUCCESS) {
    result = (*engine_)
                 ->CreateOutputMix(engine_, &output_mix_object_, 0, nullptr,
                                   nullptr);
  }
  if (result == SL_RESULT_SUCCESS) {
    result = (*output_mix_object_)
                 ->Realize(output_mix_object_, SL_BOOLEAN_FALSE);
  }

  if (result == SL_RESULT_SUCCESS) {
    SLDataLocator_AndroidSimpleBufferQueue source_locator = {
        SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE,
        static_cast<SLuint32>(kQueueSlotCount),
    };
    SLAndroidDataFormat_PCM_EX source_format = {
        SL_ANDROID_DATAFORMAT_PCM_EX,
        static_cast<SLuint32>(config_.channel_count),
        static_cast<SLuint32>(config_.sample_rate) * 1000,
        SL_PCMSAMPLEFORMAT_FIXED_32,
        SL_PCMSAMPLEFORMAT_FIXED_32,
        SL_ANDROID_SPEAKER_USE_DEFAULT,
        SL_BYTEORDER_LITTLEENDIAN,
        SL_ANDROID_PCM_REPRESENTATION_FLOAT,
    };
    SLDataSource source = {&source_locator, &source_format};
    SLDataLocator_OutputMix sink_locator = {
        SL_DATALOCATOR_OUTPUTMIX,
        output_mix_object_,
    };
    SLDataSink sink = {&sink_locator, nullptr};
    const SLInterfaceID interface_ids[] = {
        SL_IID_ANDROIDSIMPLEBUFFERQUEUE,
        SL_IID_PLAY,
    };
    const SLboolean required[] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
    result = (*engine_)
                 ->CreateAudioPlayer(engine_, &player_object_, &source, &sink,
                                     2, interface_ids, required);
  }
  if (result == SL_RESULT_SUCCESS) {
    result = (*player_object_)
                 ->Realize(player_object_, SL_BOOLEAN_FALSE);
  }
  if (result == SL_RESULT_SUCCESS) {
    result = (*player_object_)
                 ->GetInterface(player_object_, SL_IID_PLAY, &player_);
  }
  if (result == SL_RESULT_SUCCESS) {
    result = (*player_object_)
                 ->GetInterface(player_object_, SL_IID_ANDROIDSIMPLEBUFFERQUEUE,
                                &buffer_queue_);
  }
  if (result == SL_RESULT_SUCCESS) {
    result = (*buffer_queue_)
                 ->RegisterCallback(buffer_queue_, &OpenSlEsOutput::bufferQueueCallback,
                                    this);
  }
  if (result != SL_RESULT_SUCCESS) {
    last_error_code_.store(static_cast<int32_t>(result), std::memory_order_release);
    closeObjectsLocked();
    return false;
  }
  open_.store(true, std::memory_order_release);
  return true;
}

void OpenSlEsOutput::closeObjectsLocked() {
  open_.store(false, std::memory_order_release);
  playing_requested_.store(false, std::memory_order_release);
  if (player_object_ != nullptr) {
    if (player_ != nullptr) {
      (*player_)->SetPlayState(player_, SL_PLAYSTATE_STOPPED);
    }
    if (buffer_queue_ != nullptr) (*buffer_queue_)->Clear(buffer_queue_);
    waitForCallbacks(callback_active_);
    (*player_object_)->Destroy(player_object_);
    player_object_ = nullptr;
  }
  player_ = nullptr;
  buffer_queue_ = nullptr;
  if (output_mix_object_ != nullptr) {
    (*output_mix_object_)->Destroy(output_mix_object_);
    output_mix_object_ = nullptr;
  }
  if (engine_object_ != nullptr) {
    (*engine_object_)->Destroy(engine_object_);
    engine_object_ = nullptr;
  }
  engine_ = nullptr;
  queued_buffers_.store(0, std::memory_order_release);
  queued_audio_frames_.store(0, std::memory_order_release);
}

bool OpenSlEsOutput::enqueueOne(SLAndroidSimpleBufferQueueItf queue,
                                bool count_underrun) noexcept {
  if (queue == nullptr ||
      queued_buffers_.load(std::memory_order_relaxed) >=
          static_cast<int32_t>(kQueueSlotCount)) {
    return true;
  }
  QueueSlot& slot = slots_[next_enqueue_];
  const size_t frames_read = ring_.read(slot.samples.data(), buffer_frames_);
  if (frames_read < static_cast<size_t>(buffer_frames_)) {
    std::memset(slot.samples.data() + frames_read * config_.channel_count, 0,
                (static_cast<size_t>(buffer_frames_) - frames_read) *
                    ring_.frameBytes());
    if (count_underrun) {
      underrun_count_.fetch_add(1, std::memory_order_acq_rel);
    }
  }

  // Metadata is complete before Enqueue transfers this slot's ownership to the
  // player. The callback reads it only after that buffer completes.
  slot.audio_frames = static_cast<uint32_t>(frames_read);
  const SLresult result = (*queue)->Enqueue(
      queue, slot.samples.data(),
      static_cast<SLuint32>(static_cast<size_t>(buffer_frames_) * ring_.frameBytes()));
  if (result != SL_RESULT_SUCCESS) {
    slot.audio_frames = 0;
    last_error_code_.store(static_cast<int32_t>(result), std::memory_order_release);
    playing_requested_.store(false, std::memory_order_release);
    return false;
  }
  next_enqueue_ = (next_enqueue_ + 1) % kQueueSlotCount;
  queued_buffers_.fetch_add(1, std::memory_order_acq_rel);
  queued_audio_frames_.fetch_add(static_cast<int64_t>(frames_read),
                                 std::memory_order_acq_rel);
  return true;
}

bool OpenSlEsOutput::primeQueueLocked() {
  while (queued_buffers_.load(std::memory_order_acquire) <
         static_cast<int32_t>(kQueueSlotCount)) {
    if (!enqueueOne(buffer_queue_, false)) return false;
  }
  return true;
}

bool OpenSlEsOutput::resetQueueLocked() {
  if (player_ == nullptr || buffer_queue_ == nullptr) return false;
  const SLresult stop_result = (*player_)->SetPlayState(player_, SL_PLAYSTATE_STOPPED);
  const SLresult clear_result = (*buffer_queue_)->Clear(buffer_queue_);
  if (stop_result != SL_RESULT_SUCCESS || clear_result != SL_RESULT_SUCCESS) {
    last_error_code_.store(
        static_cast<int32_t>(stop_result != SL_RESULT_SUCCESS ? stop_result
                                                               : clear_result),
        std::memory_order_release);
    return false;
  }
  waitForCallbacks(callback_active_);
  next_enqueue_ = 0;
  next_completion_ = 0;
  queued_buffers_.store(0, std::memory_order_release);
  queued_audio_frames_.store(0, std::memory_order_release);
  for (QueueSlot& slot : slots_) slot.audio_frames = 0;
  return true;
}

bool OpenSlEsOutput::start() {
  std::lock_guard<std::mutex> lock(control_mutex_);
  if (!open_.load(std::memory_order_acquire) || player_ == nullptr ||
      released_.load(std::memory_order_acquire)) {
    return false;
  }
  SLuint32 state = SL_PLAYSTATE_STOPPED;
  SLresult result = (*player_)->GetPlayState(player_, &state);
  if (result != SL_RESULT_SUCCESS) {
    last_error_code_.store(static_cast<int32_t>(result), std::memory_order_release);
    return false;
  }
  if (state == SL_PLAYSTATE_PLAYING &&
      playing_requested_.load(std::memory_order_acquire)) {
    return true;
  }
  if (state == SL_PLAYSTATE_PLAYING) {
    // A prior enqueue failure stops callback replenishment. Stop and clear on
    // this control thread before attempting a clean queue restart.
    playing_requested_.store(false, std::memory_order_release);
    if (!resetQueueLocked()) return false;
  }
  if (!primeQueueLocked()) return false;
  playing_requested_.store(true, std::memory_order_release);
  result = (*player_)->SetPlayState(player_, SL_PLAYSTATE_PLAYING);
  if (result != SL_RESULT_SUCCESS) {
    playing_requested_.store(false, std::memory_order_release);
    last_error_code_.store(static_cast<int32_t>(result), std::memory_order_release);
    return false;
  }
  return true;
}

bool OpenSlEsOutput::pause() {
  playing_requested_.store(false, std::memory_order_release);
  std::lock_guard<std::mutex> lock(control_mutex_);
  if (player_ == nullptr) return false;
  const SLresult result = (*player_)->SetPlayState(player_, SL_PLAYSTATE_PAUSED);
  if (result != SL_RESULT_SUCCESS) {
    last_error_code_.store(static_cast<int32_t>(result), std::memory_order_release);
    return false;
  }
  return true;
}

bool OpenSlEsOutput::flush() {
  playing_requested_.store(false, std::memory_order_release);
  std::lock_guard<std::mutex> lock(control_mutex_);
  if (!resetQueueLocked()) return false;
  // STOPPED + Clear is the OpenSL callback-quiescent point. No renderer write
  // can race this reset because the Kotlin lifecycle methods share a monitor.
  ring_.resetQuiescent();
  frames_written_.store(0, std::memory_order_release);
  frames_completed_.store(0, std::memory_order_release);
  return true;
}

size_t OpenSlEsOutput::write(const void* source, size_t frame_count) noexcept {
  if (!open_.load(std::memory_order_acquire) ||
      released_.load(std::memory_order_acquire)) {
    return 0;
  }
  const size_t accepted = ring_.write(source, frame_count);
  if (accepted != 0) {
    frames_written_.fetch_add(static_cast<int64_t>(accepted),
                              std::memory_order_acq_rel);
  }
  return accepted;
}

void SLAPIENTRY OpenSlEsOutput::bufferQueueCallback(
    SLAndroidSimpleBufferQueueItf caller, void* context) {
  static_cast<OpenSlEsOutput*>(context)->onBufferQueue(caller);
}

void OpenSlEsOutput::onBufferQueue(SLAndroidSimpleBufferQueueItf caller) noexcept {
  ScopedCallbackActivity activity(callback_active_);
  const int32_t queued = queued_buffers_.load(std::memory_order_relaxed);
  if (queued <= 0) {
    last_error_code_.store(SL_RESULT_INTERNAL_ERROR, std::memory_order_release);
    playing_requested_.store(false, std::memory_order_release);
    return;
  }

  QueueSlot& completed = slots_[next_completion_];
  next_completion_ = (next_completion_ + 1) % kQueueSlotCount;
  queued_buffers_.fetch_sub(1, std::memory_order_acq_rel);
  const int64_t audio_frames = completed.audio_frames;
  completed.audio_frames = 0;
  queued_audio_frames_.fetch_sub(audio_frames, std::memory_order_acq_rel);
  frames_completed_.fetch_add(audio_frames, std::memory_order_acq_rel);

  // Even after pause/release, account for the buffer that just completed. Not
  // enqueuing another one is what lets the control thread safely drain/reset.
  if (!playing_requested_.load(std::memory_order_acquire) ||
      released_.load(std::memory_order_acquire)) {
    return;
  }
  enqueueOne(caller, true);
}

int64_t OpenSlEsOutput::positionFrames() const noexcept {
  return open_.load(std::memory_order_acquire)
             ? frames_completed_.load(std::memory_order_acquire)
             : -1;
}

NativeTelemetry OpenSlEsOutput::telemetry() {
  std::lock_guard<std::mutex> lock(control_mutex_);
  NativeTelemetry result;
  result.is_open = open_.load(std::memory_order_acquire) && player_ != nullptr;
  result.sample_rate = config_.sample_rate;
  result.channel_count = config_.channel_count;
  result.ring_capacity_frames = ring_.capacityFrames();
  result.buffer_capacity_frames =
      static_cast<int64_t>(buffer_frames_) * kQueueSlotCount;
  result.frames_per_buffer = buffer_frames_;
  result.sharing_mode = -1;
  result.low_latency = false;
  result.position_frames = result.is_open
                               ? frames_completed_.load(std::memory_order_acquire)
                               : -1;
  result.clock_source = result.is_open ? ClockSource::kOpenSlQueueCompletion
                                       : ClockSource::kNone;
  result.frames_written = frames_written_.load(std::memory_order_acquire);
  result.underrun_count = underrun_count_.load(std::memory_order_acquire);
  result.buffered_frames = static_cast<int64_t>(ring_.available()) +
                           queued_audio_frames_.load(std::memory_order_acquire);
  result.last_error_code = last_error_code_.load(std::memory_order_acquire);
  if (player_ != nullptr) {
    SLuint32 state = SL_PLAYSTATE_STOPPED;
    const SLresult get_state_result = (*player_)->GetPlayState(player_, &state);
    if (get_state_result == SL_RESULT_SUCCESS) {
      result.native_state = static_cast<int32_t>(state);
      result.is_playing = state == SL_PLAYSTATE_PLAYING &&
                          playing_requested_.load(std::memory_order_acquire);
    }
  }
  return result;
}

std::string OpenSlEsOutput::lastError() const {
  const int32_t error = last_error_code_.load(std::memory_order_acquire);
  if (error == SL_RESULT_SUCCESS) return {};
  const char* name = resultName(static_cast<SLresult>(error));
  return name != nullptr ? std::string(name)
                         : "OpenSL ES result " + std::to_string(error);
}

bool OpenSlEsOutput::isOpen() const noexcept {
  return open_.load(std::memory_order_acquire);
}

bool OpenSlEsOutput::isPlaying() const {
  std::lock_guard<std::mutex> lock(control_mutex_);
  if (player_ == nullptr) return false;
  SLuint32 state = SL_PLAYSTATE_STOPPED;
  return (*player_)->GetPlayState(player_, &state) == SL_RESULT_SUCCESS &&
         state == SL_PLAYSTATE_PLAYING &&
         playing_requested_.load(std::memory_order_acquire);
}

}  // namespace bitchord::audio
