#include "tracker_decoder.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <new>

namespace bitchord::tracker {
namespace {

const openmpt_module_initial_ctl kInitialCtls[] = {
    // Do not add a fade-out tail that is absent from the reported duration.
    {"play.at_end", "stop"},
    {nullptr, nullptr},
};

std::int64_t saturatingFrameCount(double seconds) noexcept {
    const long double frames = static_cast<long double>(seconds) * kSampleRate;
    if (frames >= static_cast<long double>(std::numeric_limits<std::int64_t>::max())) {
        return std::numeric_limits<std::int64_t>::max();
    }
    return static_cast<std::int64_t>(frames + 0.5L);
}

}  // namespace

Decoder::Decoder(openmpt_module* module) noexcept
    : module_(module), duration_seconds_(openmpt_module_get_duration_seconds(module)) {}

std::unique_ptr<Decoder> Decoder::adopt(openmpt_module* module) noexcept {
    if (module == nullptr) {
        return nullptr;
    }
    if (openmpt_module_set_repeat_count(module, 0) != 1) {
        openmpt_module_destroy(module);
        return nullptr;
    }
    std::unique_ptr<Decoder> decoder(new (std::nothrow) Decoder(module));
    if (!decoder) {
        openmpt_module_destroy(module);
    }
    return decoder;
}

std::unique_ptr<Decoder> Decoder::createFromMemory(const void* data, std::size_t size) noexcept {
    if (data == nullptr || size == 0) {
        return nullptr;
    }
    return adopt(openmpt_module_create_from_memory2(
        data,
        size,
        openmpt_log_func_silent,
        nullptr,
        nullptr,
        nullptr,
        nullptr,
        nullptr,
        kInitialCtls));
}

std::unique_ptr<Decoder> Decoder::createFromCallbacks(
    openmpt_stream_callbacks callbacks, void* stream) noexcept {
    if (stream == nullptr || callbacks.read == nullptr ||
        ((callbacks.seek == nullptr) != (callbacks.tell == nullptr))) {
        return nullptr;
    }
    return adopt(openmpt_module_create2(
        callbacks,
        stream,
        openmpt_log_func_silent,
        nullptr,
        nullptr,
        nullptr,
        nullptr,
        nullptr,
        kInitialCtls));
}

Decoder::~Decoder() {
    openmpt_module_destroy(module_);
}

std::int64_t Decoder::durationUs() const noexcept {
    if (!std::isfinite(duration_seconds_) || duration_seconds_ < 0.0) {
        return kTimeUnset;
    }
    const long double microseconds = static_cast<long double>(duration_seconds_) * 1'000'000.0L;
    if (microseconds >= static_cast<long double>(std::numeric_limits<std::int64_t>::max())) {
        return kTimeUnset;
    }
    return static_cast<std::int64_t>(microseconds + 0.5L);
}

double Decoder::seekSeconds(double seconds) noexcept {
    if (std::isnan(seconds) || seconds < 0.0) {
        seconds = 0.0;
    }
    if (std::isfinite(duration_seconds_)) {
        seconds = std::min(seconds, duration_seconds_);
    }
    double actual = openmpt_module_set_position_seconds(module_, seconds);
    if (!std::isfinite(actual) || actual < 0.0) {
        actual = std::isfinite(seconds) ? seconds : 0.0;
    }
    position_frame_ = saturatingFrameCount(actual);
    ended_ = false;
    return actual;
}

void Decoder::reset() noexcept {
    seekSeconds(0.0);
}

std::size_t Decoder::read(float* interleaved, std::size_t frame_count) noexcept {
    if (ended_ || frame_count == 0) {
        return 0;
    }
    if (interleaved == nullptr) {
        return 0;
    }
    const std::size_t produced = openmpt_module_read_interleaved_float_stereo(
        module_, kSampleRate, frame_count, interleaved);
    if (produced < frame_count) {
        ended_ = true;
    }
    const std::int64_t remaining =
        std::numeric_limits<std::int64_t>::max() - position_frame_;
    if (produced > static_cast<std::uint64_t>(remaining)) {
        position_frame_ = std::numeric_limits<std::int64_t>::max();
    } else {
        position_frame_ += static_cast<std::int64_t>(produced);
    }
    return produced;
}

std::int64_t Decoder::framesToUs(std::int64_t frames) noexcept {
    if (frames <= 0) {
        return 0;
    }
    const std::int64_t seconds = frames / kSampleRate;
    const std::int64_t remainder = frames % kSampleRate;
    if (seconds > std::numeric_limits<std::int64_t>::max() / 1'000'000) {
        return std::numeric_limits<std::int64_t>::max();
    }
    return seconds * 1'000'000 + remainder * 1'000'000 / kSampleRate;
}

}  // namespace bitchord::tracker
