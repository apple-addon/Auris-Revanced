#include "opensl_output.h"

#include <SLES/OpenSLES_AndroidConfiguration.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <new>
#include <string>
#include <thread>

namespace auris {
namespace {

std::string sl_error(const char *operation, SLresult result) {
    return std::string(operation) + " failed: OpenSL result " + std::to_string(result);
}

class CallbackScope {
public:
    explicit CallbackScope(std::atomic<uint32_t> &active) : active_(active) {
        active_.fetch_add(1, std::memory_order_acq_rel);
    }
    ~CallbackScope() { active_.fetch_sub(1, std::memory_order_acq_rel); }
private:
    std::atomic<uint32_t> &active_;
};

} // namespace

int opensl_error(uint32_t result) {
    return result == SL_RESULT_SUCCESS ? 0 : -20000 - static_cast<int>(result);
}

std::unique_ptr<OpenSlEsOutput> OpenSlEsOutput::open(const OutputSpec &spec, std::string &error) {
    if (!spec.valid()) {
        error = "Invalid OpenSL ES output specification";
        return nullptr;
    }
    auto output = std::unique_ptr<OpenSlEsOutput>(new (std::nothrow) OpenSlEsOutput(spec));
    if (!output) {
        error = "OpenSL ES allocation failed";
        return nullptr;
    }
    if (!output->initialize(error)) return nullptr;
    return output;
}

bool OpenSlEsOutput::initialize(std::string &error) {
    buffer_frames_ = std::max(1, (spec_.sample_rate + 99) / 100); // About 10 ms.
    buffer_bytes_ = buffer_frames_ * spec_.frame_bytes();
    const uint64_t minimum_ring = static_cast<uint64_t>(buffer_frames_) * kBufferCount * 4;
    const uint64_t requested_ring = static_cast<uint64_t>(spec_.requested_buffer_frames) * 2;
    const uint64_t ring_capacity = std::max<uint64_t>(
            std::max<uint64_t>(minimum_ring, spec_.sample_rate / 5), requested_ring);
    if (ring_capacity == 0 || ring_capacity > SIZE_MAX / spec_.channels / sizeof(float)) {
        error = "OpenSL ES ring dimensions overflow";
        return false;
    }
    ring_ = std::unique_ptr<SpscFrameRing>(new (std::nothrow)
            SpscFrameRing(static_cast<size_t>(ring_capacity), spec_.channels));
    buffers_ = std::unique_ptr<float[]>(new (std::nothrow)
            float[static_cast<size_t>(kBufferCount) * buffer_frames_ * spec_.channels]);
    if (!ring_ || !ring_->valid() || !buffers_) {
        error = "OpenSL ES preallocation failed";
        return false;
    }

    SLresult result = slCreateEngine(&engine_object_, 0, nullptr, 0, nullptr, nullptr);
    if (result != SL_RESULT_SUCCESS) {
        error = sl_error("slCreateEngine", result);
        return false;
    }
    result = (*engine_object_)->Realize(engine_object_, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        error = sl_error("Engine Realize", result);
        return false;
    }
    result = (*engine_object_)->GetInterface(engine_object_, SL_IID_ENGINE, &engine_);
    if (result != SL_RESULT_SUCCESS) {
        error = sl_error("GetInterface(ENGINE)", result);
        return false;
    }
    result = (*engine_)->CreateOutputMix(engine_, &output_mix_object_, 0, nullptr, nullptr);
    if (result != SL_RESULT_SUCCESS) {
        error = sl_error("CreateOutputMix", result);
        return false;
    }
    result = (*output_mix_object_)->Realize(output_mix_object_, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        error = sl_error("OutputMix Realize", result);
        return false;
    }

    SLDataLocator_AndroidSimpleBufferQueue queue_locator = {
            SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, kBufferCount};
    SLAndroidDataFormat_PCM_EX pcm = {
            SL_ANDROID_DATAFORMAT_PCM_EX,
            static_cast<SLuint32>(spec_.channels),
            static_cast<SLuint32>(spec_.sample_rate * 1000),
            SL_PCMSAMPLEFORMAT_FIXED_32,
            SL_PCMSAMPLEFORMAT_FIXED_32,
            SL_ANDROID_SPEAKER_USE_DEFAULT,
            SL_BYTEORDER_LITTLEENDIAN,
            SL_ANDROID_PCM_REPRESENTATION_FLOAT,
    };
    SLDataSource source = {&queue_locator, &pcm};
    SLDataLocator_OutputMix mix_locator = {SL_DATALOCATOR_OUTPUTMIX, output_mix_object_};
    SLDataSink sink = {&mix_locator, nullptr};
    const SLInterfaceID ids[] = {
            SL_IID_ANDROIDSIMPLEBUFFERQUEUE, SL_IID_PLAY, SL_IID_ANDROIDCONFIGURATION};
    const SLboolean required[] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE, SL_BOOLEAN_FALSE};
    result = (*engine_)->CreateAudioPlayer(engine_, &player_object_, &source, &sink, 3, ids, required);
    if (result != SL_RESULT_SUCCESS) {
        error = sl_error("CreateAudioPlayer(32-bit float)", result);
        return false;
    }

    SLAndroidConfigurationItf configuration = nullptr;
    if ((*player_object_)->GetInterface(player_object_, SL_IID_ANDROIDCONFIGURATION,
            &configuration) == SL_RESULT_SUCCESS && configuration) {
        const SLint32 stream_type = SL_ANDROID_STREAM_MEDIA;
        (*configuration)->SetConfiguration(configuration, SL_ANDROID_KEY_STREAM_TYPE,
                &stream_type, sizeof(stream_type));
        const SLuint32 latency = SL_ANDROID_PERFORMANCE_LATENCY;
        // Request only. Unlike PCM format validation, lack of a low-latency
        // performance mode does not invalidate an otherwise real float stream.
        (*configuration)->SetConfiguration(configuration, SL_ANDROID_KEY_PERFORMANCE_MODE,
                &latency, sizeof(latency));
    }

    result = (*player_object_)->Realize(player_object_, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        error = sl_error("AudioPlayer Realize", result);
        return false;
    }
    result = (*player_object_)->GetInterface(
            player_object_, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &queue_);
    if (result != SL_RESULT_SUCCESS || !queue_) {
        error = sl_error("GetInterface(ANDROIDSIMPLEBUFFERQUEUE)", result);
        return false;
    }
    result = (*player_object_)->GetInterface(player_object_, SL_IID_PLAY, &play_);
    if (result != SL_RESULT_SUCCESS || !play_) {
        error = sl_error("GetInterface(PLAY)", result);
        return false;
    }
    result = (*queue_)->RegisterCallback(queue_, buffer_callback, this);
    if (result != SL_RESULT_SUCCESS) {
        error = sl_error("Register buffer callback", result);
        return false;
    }
    result = (*play_)->SetPlayState(play_, SL_PLAYSTATE_STOPPED);
    if (result != SL_RESULT_SUCCESS) {
        error = sl_error("SetPlayState(STOPPED)", result);
        return false;
    }
    return true;
}

OpenSlEsOutput::~OpenSlEsOutput() {
    std::lock_guard<std::mutex> lock(control_lock_);
    close_locked();
}

void OpenSlEsOutput::close_locked() {
    quiesce_locked(false);
    if (player_object_) {
        (*player_object_)->Destroy(player_object_);
        player_object_ = nullptr;
        queue_ = nullptr;
        play_ = nullptr;
    }
    if (output_mix_object_) {
        (*output_mix_object_)->Destroy(output_mix_object_);
        output_mix_object_ = nullptr;
    }
    if (engine_object_) {
        (*engine_object_)->Destroy(engine_object_);
        engine_object_ = nullptr;
        engine_ = nullptr;
    }
}

int OpenSlEsOutput::enqueue_buffer(uint32_t index, bool count_underrun) {
    float *buffer = buffers_.get() + static_cast<size_t>(index) * buffer_frames_ * spec_.channels;
    const size_t pcm_frames = ring_->read(buffer, buffer_frames_);
    if (pcm_frames < buffer_frames_) {
        std::memset(buffer + static_cast<size_t>(pcm_frames) * spec_.channels, 0,
                static_cast<size_t>(buffer_frames_ - pcm_frames) * spec_.frame_bytes());
    }
    const SLresult result = (*queue_)->Enqueue(queue_, buffer, buffer_bytes_);
    if (result != SL_RESULT_SUCCESS) {
        const int code = opensl_error(result);
        set_failure(code);
        return code;
    }
    accounting_.record_enqueued(
            buffer_frames_, static_cast<uint32_t>(pcm_frames), count_underrun);
    return 0;
}

int OpenSlEsOutput::prime_locked() {
    callback_buffer_index_ = 0;
    for (uint32_t i = 0; i < kBufferCount; ++i) {
        if (int result = enqueue_buffer(i, /*count_underrun=*/false)) return result;
    }
    primed_ = true;
    return 0;
}

void SLAPIENTRY OpenSlEsOutput::buffer_callback(
        SLAndroidSimpleBufferQueueItf /*caller*/, void *context) {
    auto *output = static_cast<OpenSlEsOutput *>(context);
    if (!output) return;
    CallbackScope scope(output->callback_active_);
    if (!output->callbacks_enabled_.load(std::memory_order_acquire) || output->failure_code()) {
        return;
    }
    const uint32_t index = output->callback_buffer_index_;
    if (output->enqueue_buffer(index) == 0) {
        output->callback_buffer_index_ = (index + 1) % kBufferCount;
    }
}

int OpenSlEsOutput::play() {
    std::lock_guard<std::mutex> lock(control_lock_);
    if (!play_ || !queue_) return kErrorInvalidBuffer;
    if (int failure = failure_code()) return failure;
    if (running_.load(std::memory_order_acquire)) return 0;
    callbacks_enabled_.store(true, std::memory_order_release);
    if (!primed_) {
        if (int result = prime_locked()) return result;
    }
    const SLresult result = (*play_)->SetPlayState(play_, SL_PLAYSTATE_PLAYING);
    if (result != SL_RESULT_SUCCESS) {
        const int code = opensl_error(result);
        set_failure(code);
        return code;
    }
    running_.store(true, std::memory_order_release);
    return 0;
}

int OpenSlEsOutput::pause() {
    std::lock_guard<std::mutex> lock(control_lock_);
    if (!play_) return kErrorInvalidBuffer;
    if (int failure = failure_code()) return failure;
    if (!running_.exchange(false, std::memory_order_acq_rel)) return 0;
    const SLresult result = (*play_)->SetPlayState(play_, SL_PLAYSTATE_PAUSED);
    if (result != SL_RESULT_SUCCESS) {
        const int code = opensl_error(result);
        set_failure(code);
        return code;
    }
    return 0;
}

int OpenSlEsOutput::write(const uint8_t *data, int32_t byte_count) {
    std::lock_guard<std::mutex> lock(control_lock_);
    if (!ring_) return kErrorInvalidBuffer;
    if (int failure = failure_code()) return failure;
    if (!data || byte_count < 0 || byte_count % spec_.frame_bytes() != 0) {
        return kErrorInvalidArgument;
    }
    const size_t frames = static_cast<size_t>(byte_count / spec_.frame_bytes());
    const auto *input = reinterpret_cast<const float *>(data);
    return static_cast<int>(ring_->write(input, frames, gain_.load(std::memory_order_relaxed)));
}

void OpenSlEsOutput::wait_for_callback() {
    while (callback_active_.load(std::memory_order_acquire) != 0) std::this_thread::yield();
}

int OpenSlEsOutput::quiesce_locked(bool reset_position) {
    callbacks_enabled_.store(false, std::memory_order_release);
    running_.store(false, std::memory_order_release);
    int first_error = 0;
    if (play_) {
        const SLresult result = (*play_)->SetPlayState(play_, SL_PLAYSTATE_STOPPED);
        if (result != SL_RESULT_SUCCESS) first_error = opensl_error(result);
    }
    wait_for_callback();
    if (queue_) {
        const SLresult result = (*queue_)->Clear(queue_);
        if (result != SL_RESULT_SUCCESS && first_error == 0) first_error = opensl_error(result);
    }
    if (ring_) ring_->clear();
    primed_ = false;
    callback_buffer_index_ = 0;
    if (reset_position) accounting_.reset_position();
    if (first_error) set_failure(first_error);
    return first_error;
}

int OpenSlEsOutput::flush() {
    std::lock_guard<std::mutex> lock(control_lock_);
    if (!queue_) return kErrorInvalidBuffer;
    if (int failure = failure_code()) return failure;
    return quiesce_locked(true);
}

int OpenSlEsOutput::stop() {
    std::lock_guard<std::mutex> lock(control_lock_);
    if (!queue_) return kErrorInvalidBuffer;
    if (int failure = failure_code()) return failure;
    return quiesce_locked(false);
}

void OpenSlEsOutput::set_volume(float volume) {
    if (std::isfinite(volume) && volume >= 0.0f && volume <= 1.0f) {
        gain_.store(volume, std::memory_order_relaxed);
    }
}

int64_t OpenSlEsOutput::xrun_count() {
    return static_cast<int64_t>(accounting_.underruns());
}

OutputSnapshot OpenSlEsOutput::snapshot() {
    OutputSnapshot output;
    output.sample_rate = spec_.sample_rate;
    output.channels = spec_.channels;
    output.bits_per_sample = 32;
    output.buffer_size_frames = buffer_frames_ > 0 ? buffer_frames_ * kBufferCount : kUnknown;
    output.buffer_capacity_frames = ring_ ? ring_->capacity_frames() : kUnknown;
    output.xruns = static_cast<int64_t>(accounting_.underruns());
    output.output_frames = static_cast<int64_t>(accounting_.output_frames());
    output.position_us = frames_to_us(
            static_cast<int64_t>(accounting_.position_frames()), spec_.sample_rate);
    output.sharing = 1;
    output.audio_session_id = kUnknown; // OpenSL exposes no reliable session query.
    output.failure = failure_string();
    return output;
}

std::string OpenSlEsOutput::failure_string() const {
    const int code = failure_code();
    if (code == 0) return {};
    const int sl_result = -code - 20000;
    return "OpenSL ES queue/transport failure: result " + std::to_string(sl_result) +
            " (mapped error " + std::to_string(code) + ")";
}

} // namespace auris
