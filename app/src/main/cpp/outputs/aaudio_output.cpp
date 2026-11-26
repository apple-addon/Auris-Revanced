#include "aaudio_output.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <new>
#include <thread>

namespace bitchord::audio {
namespace {

constexpr int64_t kStateWaitTimeoutNanoseconds = 200000000LL;
constexpr auto kRecoveryPollInterval = std::chrono::milliseconds(5);
constexpr auto kRecoveryRetryInterval = std::chrono::milliseconds(250);

int64_t monotonicNanoseconds() {
  timespec time{};
  return clock_gettime(CLOCK_MONOTONIC, &time) == 0
             ? static_cast<int64_t>(time.tv_sec) * 1000000000LL + time.tv_nsec
             : 0;
}

bool isQuiescentState(aaudio_stream_state_t state) {
  return state == AAUDIO_STREAM_STATE_OPEN ||
         state == AAUDIO_STREAM_STATE_PAUSED ||
         state == AAUDIO_STREAM_STATE_STOPPED ||
         state == AAUDIO_STREAM_STATE_FLUSHED;
}

void waitForCallbacks(const std::atomic<uint32_t>& active) {
  while (active.load(std::memory_order_acquire) != 0) {
    std::this_thread::yield();
  }
}

}  // namespace

AAudioOutput::AAudioOutput(const OutputConfig& config)
    : config_(config),
      ring_(config.ring_capacity_frames, config.channel_count) {}

AAudioOutput::~AAudioOutput() {
  released_.store(true, std::memory_order_release);
  playing_requested_.store(false, std::memory_order_release);
  recovery_stop_.store(true, std::memory_order_release);
  recovery_cv_.notify_all();
  if (recovery_thread_.joinable()) recovery_thread_.join();
  std::lock_guard<std::mutex> lock(control_mutex_);
  closeStreamLocked();
}

bool AAudioOutput::open() {
  std::lock_guard<std::mutex> lock(control_mutex_);
  if (released_.load(std::memory_order_acquire)) return false;
  closeStreamLocked();
  timestamp_base_ = 0;
  last_position_.store(-1, std::memory_order_release);
  clock_source_.store(ClockSource::kNone, std::memory_order_release);
  frames_written_.store(0, std::memory_order_release);
  underrun_count_.store(0, std::memory_order_release);
  xrun_count_.store(0, std::memory_order_release);
  recovery_count_.store(0, std::memory_order_release);
  disconnect_count_.store(0, std::memory_order_release);
  recovery_reason_.store(kRecoveryNone, std::memory_order_release);
  ring_.resetQuiescent();
  if (!openWithExclusiveFallbackLocked()) return false;
  try {
    recovery_thread_ = std::thread(&AAudioOutput::recoveryLoop, this);
  } catch (...) {
    last_error_code_.store(AAUDIO_ERROR_INTERNAL, std::memory_order_release);
    closeStreamLocked();
    return false;
  }
  return true;
}

bool AAudioOutput::openAttemptLocked(aaudio_sharing_mode_t sharing_mode,
                                     AAudioStream** opened_stream) {
  *opened_stream = nullptr;
  AAudioStreamBuilder* builder = nullptr;
  aaudio_result_t result = AAudio_createStreamBuilder(&builder);
  if (result != AAUDIO_OK) {
    last_error_code_.store(result, std::memory_order_release);
    return false;
  }

  AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
  AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
  AAudioStreamBuilder_setSampleRate(builder, config_.sample_rate);
  AAudioStreamBuilder_setChannelCount(builder, config_.channel_count);
  AAudioStreamBuilder_setSharingMode(builder, sharing_mode);
  AAudioStreamBuilder_setPerformanceMode(
      builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  AAudioStreamBuilder_setDataCallback(builder, &AAudioOutput::dataCallback, this);
  AAudioStreamBuilder_setErrorCallback(builder, &AAudioOutput::errorCallback, this);
  if (config_.device_id != AAUDIO_UNSPECIFIED) {
    AAudioStreamBuilder_setDeviceId(builder, config_.device_id);
  }
  if (config_.buffer_size_frames > 0) {
    AAudioStreamBuilder_setFramesPerDataCallback(
        builder, config_.buffer_size_frames);
  }

  AAudioStream* candidate = nullptr;
  result = AAudioStreamBuilder_openStream(builder, &candidate);
  AAudioStreamBuilder_delete(builder);
  if (result != AAUDIO_OK) {
    last_error_code_.store(result, std::memory_order_release);
    return false;
  }

  const bool valid_format =
      AAudioStream_getFormat(candidate) == AAUDIO_FORMAT_PCM_FLOAT &&
      AAudioStream_getChannelCount(candidate) == config_.channel_count &&
      AAudioStream_getSampleRate(candidate) == config_.sample_rate;
  if (!valid_format) {
    AAudioStream_close(candidate);
    last_error_code_.store(AAUDIO_ERROR_INVALID_FORMAT, std::memory_order_release);
    return false;
  }

  *opened_stream = candidate;
  return true;
}

bool AAudioOutput::openWithExclusiveFallbackLocked() {
  AAudioStream* candidate = nullptr;
  if (!openAttemptLocked(AAUDIO_SHARING_MODE_EXCLUSIVE, &candidate)) {
    // A builder retains its failed open state on some Android releases, so the
    // shared fallback deliberately uses a newly created builder as well.
    if (!openAttemptLocked(AAUDIO_SHARING_MODE_SHARED, &candidate)) {
      return false;
    }
  }
  stream_.store(candidate, std::memory_order_release);
  open_.store(true, std::memory_order_release);
  last_stream_xrun_count_.store(0, std::memory_order_release);
  last_error_code_.store(AAUDIO_OK, std::memory_order_release);
  return true;
}

void AAudioOutput::closeStreamLocked() {
  AAudioStream* stream = stream_.exchange(nullptr, std::memory_order_acq_rel);
  open_.store(false, std::memory_order_release);
  if (stream != nullptr) {
    const aaudio_result_t result = AAudioStream_close(stream);
    if (result != AAUDIO_OK) {
      last_error_code_.store(result, std::memory_order_release);
    }
  }
  waitForCallbacks(callback_active_);
}

bool AAudioOutput::start() {
  std::lock_guard<std::mutex> lock(control_mutex_);
  AAudioStream* stream = stream_.load(std::memory_order_acquire);
  if (stream == nullptr || released_.load(std::memory_order_acquire)) return false;
  playing_requested_.store(true, std::memory_order_release);
  const aaudio_result_t result = AAudioStream_requestStart(stream);
  if (result != AAUDIO_OK) {
    playing_requested_.store(false, std::memory_order_release);
    last_error_code_.store(result, std::memory_order_release);
    return false;
  }
  return true;
}

bool AAudioOutput::waitForStateLocked(AAudioStream* stream,
                                      aaudio_stream_state_t current_state,
                                      aaudio_stream_state_t target_state) {
  const int64_t deadline = monotonicNanoseconds() + kStateWaitTimeoutNanoseconds;
  aaudio_stream_state_t observed = current_state;
  while (observed != target_state) {
    const int64_t remaining = deadline - monotonicNanoseconds();
    if (remaining <= 0) {
      last_error_code_.store(AAUDIO_ERROR_TIMEOUT, std::memory_order_release);
      return false;
    }
    aaudio_stream_state_t next = observed;
    const aaudio_result_t result = AAudioStream_waitForStateChange(
        stream, observed, &next, remaining);
    if (result != AAUDIO_OK && result != AAUDIO_ERROR_TIMEOUT) {
      last_error_code_.store(result, std::memory_order_release);
      return false;
    }
    observed = next;
  }
  return true;
}

bool AAudioOutput::pauseLocked() {
  AAudioStream* stream = stream_.load(std::memory_order_acquire);
  if (stream == nullptr) return false;
  aaudio_stream_state_t state = AAudioStream_getState(stream);
  if (state == AAUDIO_STREAM_STATE_PAUSED || isQuiescentState(state)) return true;
  const aaudio_result_t result = AAudioStream_requestPause(stream);
  if (result != AAUDIO_OK) {
    last_error_code_.store(result, std::memory_order_release);
    return false;
  }
  state = AAudioStream_getState(stream);
  return waitForStateLocked(stream, state, AAUDIO_STREAM_STATE_PAUSED);
}

bool AAudioOutput::pause() {
  playing_requested_.store(false, std::memory_order_release);
  std::lock_guard<std::mutex> lock(control_mutex_);
  return pauseLocked();
}

bool AAudioOutput::flushStreamLocked() {
  AAudioStream* stream = stream_.load(std::memory_order_acquire);
  if (stream == nullptr) return false;
  aaudio_stream_state_t state = AAudioStream_getState(stream);
  if (state == AAUDIO_STREAM_STATE_FLUSHED) return true;
  if (!isQuiescentState(state)) {
    last_error_code_.store(AAUDIO_ERROR_INVALID_STATE, std::memory_order_release);
    return false;
  }
  const aaudio_result_t result = AAudioStream_requestFlush(stream);
  if (result != AAUDIO_OK) {
    last_error_code_.store(result, std::memory_order_release);
    return false;
  }
  state = AAudioStream_getState(stream);
  return waitForStateLocked(stream, state, AAUDIO_STREAM_STATE_FLUSHED);
}

bool AAudioOutput::flush() {
  playing_requested_.store(false, std::memory_order_release);
  std::lock_guard<std::mutex> lock(control_mutex_);
  AAudioStream* stream = stream_.load(std::memory_order_acquire);
  if (stream == nullptr) return false;
  if (!pauseLocked() || !flushStreamLocked()) return false;
  waitForCallbacks(callback_active_);

  // Both the renderer (through the Kotlin monitor) and AAudio callback are now
  // quiescent. Frame counters survive AAudio's flush, so rebase rather than
  // pretending that the hardware counter itself returned to zero.
  ring_.resetQuiescent();
  frames_written_.store(0, std::memory_order_release);
  const int64_t raw_position = AAudioStream_getFramesRead(stream);
  timestamp_base_ = raw_position >= 0 ? -raw_position : 0;
  last_position_.store(0, std::memory_order_release);
  clock_source_.store(ClockSource::kNone, std::memory_order_release);
  last_stream_xrun_count_.store(AAudioStream_getXRunCount(stream),
                                std::memory_order_release);
  return true;
}

size_t AAudioOutput::write(const void* source, size_t frame_count) noexcept {
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

aaudio_data_callback_result_t AAudioOutput::dataCallback(
    AAudioStream* stream, void* user_data, void* audio_data,
    int32_t frame_count) {
  return static_cast<AAudioOutput*>(user_data)
      ->onData(stream, audio_data, frame_count);
}

aaudio_data_callback_result_t AAudioOutput::onData(
    AAudioStream* stream, void* audio_data, int32_t frame_count) noexcept {
  ScopedCallbackActivity activity(callback_active_);
  if (audio_data == nullptr || frame_count <= 0) {
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
  }
  const size_t byte_count = static_cast<size_t>(frame_count) * ring_.frameBytes();
  if (released_.load(std::memory_order_acquire) ||
      stream_.load(std::memory_order_acquire) != stream) {
    std::memset(audio_data, 0, byte_count);
    return AAUDIO_CALLBACK_RESULT_STOP;
  }

  const size_t frames_read = ring_.read(audio_data, frame_count);
  if (frames_read < static_cast<size_t>(frame_count)) {
    std::memset(static_cast<uint8_t*>(audio_data) + frames_read * ring_.frameBytes(),
                0, byte_count - frames_read * ring_.frameBytes());
    underrun_count_.fetch_add(1, std::memory_order_acq_rel);
  }

  // AAudio explicitly permits get* calls from its real-time callback. No
  // stream operation or recovery work is performed here.
  const int32_t current_xruns = AAudioStream_getXRunCount(stream);
  const int32_t previous_xruns =
      last_stream_xrun_count_.load(std::memory_order_relaxed);
  if (current_xruns > previous_xruns) {
    xrun_count_.fetch_add(current_xruns - previous_xruns,
                          std::memory_order_acq_rel);
    last_stream_xrun_count_.store(current_xruns, std::memory_order_relaxed);
    requestRecovery(kRecoveryXrun);
  }
  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AAudioOutput::errorCallback(AAudioStream* stream, void* user_data,
                                 aaudio_result_t error) {
  static_cast<AAudioOutput*>(user_data)->onError(stream, error);
}

void AAudioOutput::onError(AAudioStream* stream,
                           aaudio_result_t error) noexcept {
  ScopedCallbackActivity activity(callback_active_);
  if (released_.load(std::memory_order_acquire) ||
      stream_.load(std::memory_order_acquire) != stream) {
    return;
  }
  last_error_code_.store(error, std::memory_order_release);
  if (error == AAUDIO_ERROR_DISCONNECTED) {
    disconnect_count_.fetch_add(1, std::memory_order_acq_rel);
  }
  requestRecovery(kRecoveryError);
}

void AAudioOutput::requestRecovery(RecoveryReason reason) noexcept {
  int32_t current = recovery_reason_.load(std::memory_order_relaxed);
  while (current < reason &&
         !recovery_reason_.compare_exchange_weak(
             current, static_cast<int32_t>(reason), std::memory_order_acq_rel,
             std::memory_order_relaxed)) {
  }
  // The recovery thread polls this atomic. In particular, the callback does
  // not take a condition-variable mutex or allocate a notification object.
}

void AAudioOutput::preservePositionForNewStreamLocked() {
  const int64_t position = positionFramesLocked();
  if (position >= 0) timestamp_base_ = position;
  clock_source_.store(ClockSource::kNone, std::memory_order_release);
}

bool AAudioOutput::recover(RecoveryReason reason) {
  std::lock_guard<std::mutex> lock(control_mutex_);
  if (released_.load(std::memory_order_acquire) ||
      recovery_stop_.load(std::memory_order_acquire)) {
    return true;
  }

  if (reason == kRecoveryXrun &&
      stream_.load(std::memory_order_acquire) != nullptr) {
    if (pauseLocked() && flushStreamLocked()) {
      AAudioStream* stream = stream_.load(std::memory_order_acquire);
      last_stream_xrun_count_.store(AAudioStream_getXRunCount(stream),
                                    std::memory_order_release);
      if (!playing_requested_.load(std::memory_order_acquire)) {
        recovery_count_.fetch_add(1, std::memory_order_acq_rel);
        return true;
      }
      const aaudio_result_t result = AAudioStream_requestStart(stream);
      if (result == AAUDIO_OK) {
        recovery_count_.fetch_add(1, std::memory_order_acq_rel);
        return true;
      }
      last_error_code_.store(result, std::memory_order_release);
    }
  }

  const int32_t preceding_error = last_error_code_.load(std::memory_order_acquire);
  preservePositionForNewStreamLocked();
  closeStreamLocked();
  if (!openWithExclusiveFallbackLocked()) return false;
  if (preceding_error != AAUDIO_OK) {
    last_error_code_.store(preceding_error, std::memory_order_release);
  }

  if (playing_requested_.load(std::memory_order_acquire)) {
    AAudioStream* stream = stream_.load(std::memory_order_acquire);
    const aaudio_result_t result = AAudioStream_requestStart(stream);
    if (result != AAUDIO_OK) {
      last_error_code_.store(result, std::memory_order_release);
      return false;
    }
  }
  recovery_count_.fetch_add(1, std::memory_order_acq_rel);
  return true;
}

void AAudioOutput::recoveryLoop() noexcept {
  while (!recovery_stop_.load(std::memory_order_acquire)) {
    {
      std::unique_lock<std::mutex> lock(recovery_mutex_);
      recovery_cv_.wait_for(lock, kRecoveryPollInterval, [this] {
        return recovery_stop_.load(std::memory_order_acquire) ||
               recovery_reason_.load(std::memory_order_acquire) != kRecoveryNone;
      });
    }
    if (recovery_stop_.load(std::memory_order_acquire)) break;
    const RecoveryReason reason = static_cast<RecoveryReason>(
        recovery_reason_.exchange(kRecoveryNone, std::memory_order_acq_rel));
    if (reason == kRecoveryNone) continue;
    if (!recover(reason) &&
        !recovery_stop_.load(std::memory_order_acquire) &&
        !released_.load(std::memory_order_acquire)) {
      requestRecovery(kRecoveryError);
      std::unique_lock<std::mutex> lock(recovery_mutex_);
      recovery_cv_.wait_for(lock, kRecoveryRetryInterval, [this] {
        return recovery_stop_.load(std::memory_order_acquire);
      });
    }
  }
}

int64_t AAudioOutput::positionFramesLocked() {
  AAudioStream* stream = stream_.load(std::memory_order_acquire);
  if (stream == nullptr) {
    clock_source_.store(ClockSource::kNone, std::memory_order_release);
    return last_position_.load(std::memory_order_acquire);
  }

  const int64_t written = frames_written_.load(std::memory_order_acquire);
  const int64_t upper_bound = std::max<int64_t>(0, written - timestamp_base_);
  int64_t raw_position = -1;
  const int64_t now = monotonicNanoseconds();
  if (playing_requested_.load(std::memory_order_acquire) && now > 0) {
    int64_t timestamp_position = 0;
    int64_t timestamp_nanoseconds = 0;
    const aaudio_result_t result = AAudioStream_getTimestamp(
        stream, CLOCK_MONOTONIC, &timestamp_position, &timestamp_nanoseconds);
    if (result == AAUDIO_OK) {
      raw_position = ExtrapolateTimestampFrames(
          timestamp_position, timestamp_nanoseconds, now, config_.sample_rate,
          upper_bound);
      if (raw_position >= 0) {
        clock_source_.store(ClockSource::kAAudioTimestamp,
                            std::memory_order_release);
      }
    }
  }
  if (raw_position < 0) {
    raw_position = AAudioStream_getFramesRead(stream);
    if (raw_position >= 0) {
      clock_source_.store(ClockSource::kAAudioFramesRead,
                          std::memory_order_release);
      raw_position = std::min(raw_position, upper_bound);
    }
  }

  if (raw_position < 0) {
    clock_source_.store(ClockSource::kNone, std::memory_order_release);
    return last_position_.load(std::memory_order_acquire);
  }
  int64_t position = timestamp_base_ + raw_position;
  position = std::clamp<int64_t>(position, 0, written);
  const int64_t previous = last_position_.load(std::memory_order_acquire);
  if (previous >= 0) position = std::max(position, previous);
  position = std::min(position, written);
  last_position_.store(position, std::memory_order_release);
  return position;
}

int64_t AAudioOutput::positionFrames() {
  std::lock_guard<std::mutex> lock(control_mutex_);
  return positionFramesLocked();
}

NativeTelemetry AAudioOutput::telemetry() {
  std::lock_guard<std::mutex> lock(control_mutex_);
  NativeTelemetry result;
  AAudioStream* stream = stream_.load(std::memory_order_acquire);
  result.is_open = open_.load(std::memory_order_acquire) && stream != nullptr;
  result.sample_rate = config_.sample_rate;
  result.channel_count = config_.channel_count;
  result.ring_capacity_frames = ring_.capacityFrames();
  result.frames_written = frames_written_.load(std::memory_order_acquire);
  result.underrun_count = underrun_count_.load(std::memory_order_acquire);
  result.xrun_count = xrun_count_.load(std::memory_order_acquire);
  result.recovery_count = recovery_count_.load(std::memory_order_acquire);
  result.disconnect_count = disconnect_count_.load(std::memory_order_acquire);
  result.buffered_frames = ring_.available();
  result.last_error_code = last_error_code_.load(std::memory_order_acquire);
  result.position_frames = positionFramesLocked();
  result.clock_source = clock_source_.load(std::memory_order_acquire);
  if (stream != nullptr) {
    result.native_state = AAudioStream_getState(stream);
    result.is_playing = result.native_state == AAUDIO_STREAM_STATE_STARTED;
    result.buffer_capacity_frames = AAudioStream_getBufferCapacityInFrames(stream);
    result.frames_per_buffer = AAudioStream_getFramesPerDataCallback(stream);
    result.sharing_mode = AAudioStream_getSharingMode(stream);
    result.low_latency = AAudioStream_getPerformanceMode(stream) ==
                         AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
  }
  return result;
}

std::string AAudioOutput::lastError() const {
  const int32_t error = last_error_code_.load(std::memory_order_acquire);
  return error == AAUDIO_OK ? std::string()
                            : std::string(AAudio_convertResultToText(error));
}

bool AAudioOutput::isOpen() const noexcept {
  return open_.load(std::memory_order_acquire) &&
         stream_.load(std::memory_order_acquire) != nullptr;
}

bool AAudioOutput::isPlaying() const {
  std::lock_guard<std::mutex> lock(control_mutex_);
  AAudioStream* stream = stream_.load(std::memory_order_acquire);
  return stream != nullptr &&
         AAudioStream_getState(stream) == AAUDIO_STREAM_STATE_STARTED;
}

}  // namespace bitchord::audio
