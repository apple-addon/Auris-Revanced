#include "aaudio_output.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <dlfcn.h>
#include <new>
#include <string>
#include <time.h>

namespace auris {
namespace {

using SetSessionId = void (*)(AAudioStreamBuilder *, aaudio_session_id_t);
using GetSessionId = aaudio_session_id_t (*)(AAudioStream *);
using SetUsage = void (*)(AAudioStreamBuilder *, aaudio_usage_t);
using SetContentType = void (*)(AAudioStreamBuilder *, aaudio_content_type_t);

SetSessionId set_session_id() {
    return reinterpret_cast<SetSessionId>(dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setSessionId"));
}

GetSessionId get_session_id() {
    return reinterpret_cast<GetSessionId>(dlsym(RTLD_DEFAULT, "AAudioStream_getSessionId"));
}

// Usage/content classification was added in API 28. dlsym keeps the minSdk 26
// build valid; on API 26/27 the platform's default classification applies.
void set_usage_if_available(AAudioStreamBuilder *builder) {
    auto usage = reinterpret_cast<SetUsage>(
            dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setUsage"));
    if (usage) usage(builder, AAUDIO_USAGE_MEDIA);
    auto content = reinterpret_cast<SetContentType>(
            dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setContentType"));
    if (content) content(builder, AAUDIO_CONTENT_TYPE_MUSIC);
}

std::string result_error(const char *operation, aaudio_result_t result) {
    return std::string(operation) + ": " + AAudio_convertResultToText(result) +
            " (" + std::to_string(result) + ")";
}

int64_t monotonic_ns() {
    timespec time{};
    return clock_gettime(CLOCK_MONOTONIC, &time) == 0
            ? static_cast<int64_t>(time.tv_sec) * 1000000000LL + time.tv_nsec
            : 0;
}

void update_maximum(int64_t &target, int64_t candidate) {
    if (candidate > target) target = candidate;
}

} // namespace

std::unique_ptr<AAudioOutput> AAudioOutput::open(
        const OutputSpec &spec, bool request_exclusive, std::string &error) {
    if (!spec.valid()) {
        error = "Invalid AAudio output specification";
        return nullptr;
    }

    std::string exclusive_error;
    if (request_exclusive) {
        auto output = std::unique_ptr<AAudioOutput>(new (std::nothrow) AAudioOutput(spec));
        if (!output) {
            error = "AAudio allocation failed";
            return nullptr;
        }
        if (output->open_one(AAUDIO_SHARING_MODE_EXCLUSIVE, exclusive_error)) return output;
    }

    auto output = std::unique_ptr<AAudioOutput>(new (std::nothrow) AAudioOutput(spec));
    if (!output) {
        error = "AAudio allocation failed";
        return nullptr;
    }
    std::string shared_error;
    if (output->open_one(AAUDIO_SHARING_MODE_SHARED, shared_error)) return output;
    error = request_exclusive
            ? "Exclusive AAudio failed [" + exclusive_error + "]; shared AAudio failed [" +
                    shared_error + "]"
            : "Shared AAudio failed [" + shared_error + "]";
    return nullptr;
}

bool AAudioOutput::open_one(aaudio_sharing_mode_t sharing, std::string &error) {
    AAudioStreamBuilder *builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || !builder) {
        error = result_error("AAudio_createStreamBuilder", result);
        return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    set_usage_if_available(builder);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setSampleRate(builder, spec_.sample_rate);
    AAudioStreamBuilder_setChannelCount(builder, spec_.channels);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, sharing);
    AAudioStreamBuilder_setErrorCallback(builder, error_callback, this);
    if (spec_.audio_device_id > 0) {
        AAudioStreamBuilder_setDeviceId(builder, spec_.audio_device_id);
    }
    if (spec_.requested_buffer_frames > 0) {
        AAudioStreamBuilder_setBufferCapacityInFrames(builder, spec_.requested_buffer_frames);
    }
    // Session IDs were added in API 28. dlsym keeps the minSdk 26 binary safe;
    // an unavailable ID remains unknown rather than pretending one was attached.
    if (spec_.audio_session_id >= 0) {
        if (auto function = set_session_id()) function(builder, spec_.audio_session_id);
    }

    result = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || !stream_) {
        stream_ = nullptr;
        error = result_error("AAudioStreamBuilder_openStream(float, low latency)", result);
        return false;
    }

    const int actual_rate = AAudioStream_getSampleRate(stream_);
    const int actual_channels = AAudioStream_getChannelCount(stream_);
    const auto actual_format = AAudioStream_getFormat(stream_);
    const int actual_device = AAudioStream_getDeviceId(stream_);
    if (actual_rate != spec_.sample_rate || actual_channels != spec_.channels ||
            actual_format != AAUDIO_FORMAT_PCM_FLOAT ||
            (spec_.audio_device_id > 0 && actual_device != spec_.audio_device_id)) {
        error = "AAudio negotiated an incompatible stream: rate=" + std::to_string(actual_rate) +
                ", channels=" + std::to_string(actual_channels) +
                ", format=" + std::to_string(actual_format) +
                ", device=" + std::to_string(actual_device) +
                ", requestedDevice=" + std::to_string(spec_.audio_device_id);
        close_locked();
        return false;
    }

    if (spec_.requested_buffer_frames > 0) {
        // The actual value is queried for telemetry; AAudio is allowed to clamp it.
        AAudioStream_setBufferSizeInFrames(stream_, spec_.requested_buffer_frames);
    }
    const int buffer_frames = std::max(0, AAudioStream_getBufferSizeInFrames(stream_));
    const int burst_frames = std::max(0, AAudioStream_getFramesPerBurst(stream_));
    const int quarter_second = std::max(1, spec_.sample_rate / 4);
    scratch_frames_ = std::max(1024, std::min(quarter_second,
            std::max(buffer_frames, burst_frames)));
    gain_scratch_ = std::unique_ptr<float[]>(new (std::nothrow)
            float[static_cast<size_t>(scratch_frames_) * spec_.channels]);
    if (!gain_scratch_) {
        error = "AAudio gain scratch allocation failed";
        close_locked();
        return false;
    }
    const int64_t buffer_time_ns = buffer_frames > 0
            ? static_cast<int64_t>(buffer_frames) * 1000000000LL / spec_.sample_rate
            : 0;
    write_timeout_ns_ = std::min<int64_t>(100000000,
            std::max<int64_t>(20000000, buffer_time_ns * 2));
    return true;
}

AAudioOutput::~AAudioOutput() {
    std::lock_guard<std::mutex> lock(control_lock_);
    close_locked();
}

void AAudioOutput::close_locked() {
    running_.store(false, std::memory_order_release);
    if (stream_) {
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

void AAudioOutput::error_callback(
        AAudioStream * /*stream*/, void *user_data, aaudio_result_t error) {
    auto *output = static_cast<AAudioOutput *>(user_data);
    if (!output) return;
    output->running_.store(false, std::memory_order_release);
    output->set_failure(error);
    // AAudio explicitly forbids close/stop/pause/write from this callback.
    // The next control/write call observes the error and fails closed.
}

int AAudioOutput::play() {
    std::lock_guard<std::mutex> lock(control_lock_);
    if (!stream_) return AAUDIO_ERROR_INVALID_HANDLE;
    if (int failure = failure_code()) return failure;
    if (running_.load(std::memory_order_acquire)) return 0;
    const aaudio_result_t result = AAudioStream_requestStart(stream_);
    if (result != AAUDIO_OK) {
        set_failure(result);
        return result;
    }
    running_.store(true, std::memory_order_release);
    return 0;
}

int AAudioOutput::pause() {
    std::lock_guard<std::mutex> lock(control_lock_);
    if (!stream_) return AAUDIO_ERROR_INVALID_HANDLE;
    if (int failure = failure_code()) return failure;
    if (!running_.exchange(false, std::memory_order_acq_rel)) return 0;
    const aaudio_result_t result = AAudioStream_requestPause(stream_);
    if (result != AAUDIO_OK) {
        set_failure(result);
        return result;
    }
    return 0;
}

int AAudioOutput::write(const uint8_t *data, int32_t byte_count) {
    std::lock_guard<std::mutex> lock(control_lock_);
    if (!stream_) return AAUDIO_ERROR_INVALID_HANDLE;
    if (int failure = failure_code()) return failure;
    if (!data || byte_count < 0 || byte_count % spec_.frame_bytes() != 0) {
        return kErrorInvalidArgument;
    }
    int32_t remaining_frames = byte_count / spec_.frame_bytes();
    int32_t total_frames = 0;
    const float gain = gain_.load(std::memory_order_relaxed);

    while (remaining_frames > 0) {
        const int32_t chunk = std::min(remaining_frames, scratch_frames_);
        const uint8_t *source = data + static_cast<size_t>(total_frames) * spec_.frame_bytes();
        const void *write_data = source;
        if (gain != 1.0f) {
            auto *scratch = gain_scratch_.get();
            const size_t sample_count = static_cast<size_t>(chunk) * spec_.channels;
            const auto *input = reinterpret_cast<const float *>(source);
            for (size_t i = 0; i < sample_count; ++i) scratch[i] = input[i] * gain;
            write_data = scratch;
        }
        const aaudio_result_t result = AAudioStream_write(
                stream_, write_data, chunk, write_timeout_ns_);
        if (result < 0) {
            if (result == AAUDIO_ERROR_WOULD_BLOCK) break;
            set_failure(result);
            return total_frames > 0 ? total_frames : result;
        }
        total_frames += result;
        remaining_frames -= result;
        if (result < chunk) break;
    }
    return total_frames;
}

int AAudioOutput::wait_for_state_locked(
        aaudio_stream_state_t old_state, int64_t timeout_ns) {
    aaudio_stream_state_t next_state = AAUDIO_STREAM_STATE_UNKNOWN;
    const aaudio_result_t result = AAudioStream_waitForStateChange(
            stream_, old_state, &next_state, timeout_ns);
    if (result != AAUDIO_OK && result != AAUDIO_ERROR_TIMEOUT) {
        set_failure(result);
        return result;
    }
    if (result == AAUDIO_ERROR_TIMEOUT) {
        set_failure(AAUDIO_ERROR_TIMEOUT);
        return AAUDIO_ERROR_TIMEOUT;
    }
    return 0;
}

int AAudioOutput::flush() {
    std::lock_guard<std::mutex> lock(control_lock_);
    if (!stream_) return AAUDIO_ERROR_INVALID_HANDLE;
    if (int failure = failure_code()) return failure;

    aaudio_stream_state_t state = AAudioStream_getState(stream_);
    if (state == AAUDIO_STREAM_STATE_STARTING || state == AAUDIO_STREAM_STATE_STARTED) {
        const aaudio_result_t result = AAudioStream_requestPause(stream_);
        running_.store(false, std::memory_order_release);
        if (result != AAUDIO_OK) {
            set_failure(result);
            return result;
        }
        if (int result_code = wait_for_state_locked(state, 100000000)) return result_code;
        state = AAudioStream_getState(stream_);
    } else if (state == AAUDIO_STREAM_STATE_PAUSING || state == AAUDIO_STREAM_STATE_STOPPING ||
            state == AAUDIO_STREAM_STATE_FLUSHING) {
        if (int result_code = wait_for_state_locked(state, 100000000)) return result_code;
        state = AAudioStream_getState(stream_);
    }

    const aaudio_result_t result = AAudioStream_requestFlush(stream_);
    if (result != AAUDIO_OK) {
        set_failure(result);
        return result;
    }
    state = AAudioStream_getState(stream_);
    if (state == AAUDIO_STREAM_STATE_FLUSHING) {
        if (int result_code = wait_for_state_locked(state, 100000000)) return result_code;
    }
    const int64_t frames_read = AAudioStream_getFramesRead(stream_);
    if (frames_read < 0) {
        set_failure(static_cast<int>(frames_read));
        return static_cast<int>(frames_read);
    }
    frame_origin_ = frames_read;
    last_position_us_ = 0;
    running_.store(false, std::memory_order_release);
    return 0;
}

int AAudioOutput::stop() {
    std::lock_guard<std::mutex> lock(control_lock_);
    if (!stream_) return AAUDIO_ERROR_INVALID_HANDLE;
    if (int failure = failure_code()) return failure;
    running_.store(false, std::memory_order_release);
    const aaudio_stream_state_t state = AAudioStream_getState(stream_);
    if (state == AAUDIO_STREAM_STATE_OPEN || state == AAUDIO_STREAM_STATE_STOPPED ||
            state == AAUDIO_STREAM_STATE_FLUSHED) {
        return 0;
    }
    const aaudio_result_t result = AAudioStream_requestStop(stream_);
    if (result != AAUDIO_OK) {
        set_failure(result);
        return result;
    }
    return 0;
}

void AAudioOutput::set_volume(float volume) {
    if (std::isfinite(volume) && volume >= 0.0f && volume <= 1.0f) {
        gain_.store(volume, std::memory_order_relaxed);
    }
}

int64_t AAudioOutput::xrun_count() {
    std::lock_guard<std::mutex> lock(control_lock_);
    if (!stream_) return kUnknown;
    const int32_t result = AAudioStream_getXRunCount(stream_);
    return result >= 0 ? result : kUnknown;
}

OutputSnapshot AAudioOutput::snapshot() {
    std::lock_guard<std::mutex> lock(control_lock_);
    OutputSnapshot output;
    output.sample_rate = spec_.sample_rate;
    output.channels = spec_.channels;
    output.bits_per_sample = 32;
    output.audio_session_id = kUnknown;
    output.failure = failure_string();
    if (!stream_) return output;

    output.sample_rate = AAudioStream_getSampleRate(stream_);
    output.channels = AAudioStream_getChannelCount(stream_);
    output.bits_per_sample = AAudioStream_getFormat(stream_) == AAUDIO_FORMAT_PCM_FLOAT ? 32 : kUnknown;
    const int buffer_size = AAudioStream_getBufferSizeInFrames(stream_);
    const int buffer_capacity = AAudioStream_getBufferCapacityInFrames(stream_);
    output.buffer_size_frames = buffer_size >= 0 ? buffer_size : kUnknown;
    output.buffer_capacity_frames = buffer_capacity >= 0 ? buffer_capacity : kUnknown;
    const int xruns = AAudioStream_getXRunCount(stream_);
    output.xruns = xruns >= 0 ? xruns : kUnknown;
    const auto sharing = AAudioStream_getSharingMode(stream_);
    output.sharing = sharing == AAUDIO_SHARING_MODE_EXCLUSIVE ? 2
            : sharing == AAUDIO_SHARING_MODE_SHARED ? 1 : 0;
    if (auto function = get_session_id()) {
        const int session = function(stream_);
        output.audio_session_id = session > 0 ? session : kUnknown;
    }

    const int64_t frames_read = AAudioStream_getFramesRead(stream_);
    output.output_frames = frames_read >= 0 ? frames_read : kUnknown;
    int64_t absolute_timestamp_frame = 0;
    int64_t timestamp_ns = 0;
    const aaudio_result_t timestamp_result = AAudioStream_getTimestamp(
            stream_, CLOCK_MONOTONIC, &absolute_timestamp_frame, &timestamp_ns);
    if (timestamp_result == AAUDIO_OK && absolute_timestamp_frame >= 0 && timestamp_ns > 0) {
        int64_t position = frames_to_us(
                std::max<int64_t>(0, absolute_timestamp_frame - frame_origin_), spec_.sample_rate);
        const int64_t now_ns = monotonic_ns();
        if (running_.load(std::memory_order_acquire) && now_ns > timestamp_ns && position >= 0) {
            const int64_t elapsed_us = (now_ns - timestamp_ns) / 1000;
            position = INT64_MAX - position < elapsed_us ? INT64_MAX : position + elapsed_us;
        }
        update_maximum(last_position_us_, position);
        output.position_us = last_position_us_;
    } else if (frames_read >= 0) {
        const int64_t position = frames_to_us(
                std::max<int64_t>(0, frames_read - frame_origin_), spec_.sample_rate);
        update_maximum(last_position_us_, position);
        output.position_us = last_position_us_;
    } else if (last_position_us_ > 0) {
        output.position_us = last_position_us_;
    }
    return output;
}

std::string AAudioOutput::failure_string() const {
    const int code = failure_code();
    if (code == 0) return {};
    return result_error("AAudio stream error/disconnect", code);
}

} // namespace auris
