#include "tracker_decoder.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
#include <memory>
#include <vector>

namespace {

using bitchord::tracker::Decoder;
constexpr std::size_t kBlockFrames = 1024;

struct MemoryStream {
    const std::uint8_t* data;
    std::int64_t size;
    std::int64_t position = 0;
};

std::size_t streamRead(void* opaque, void* destination, std::size_t bytes) {
    auto& stream = *static_cast<MemoryStream*>(opaque);
    if (stream.position >= stream.size) {
        return 0;
    }
    const auto available = static_cast<std::uint64_t>(stream.size - stream.position);
    const std::size_t count = static_cast<std::size_t>(
        std::min<std::uint64_t>(available, bytes));
    std::memcpy(destination, stream.data + stream.position, count);
    stream.position += static_cast<std::int64_t>(count);
    return count;
}

int streamSeek(void* opaque, std::int64_t offset, int whence) {
    auto& stream = *static_cast<MemoryStream*>(opaque);
    std::int64_t origin = 0;
    if (whence == OPENMPT_STREAM_SEEK_CUR) {
        origin = stream.position;
    } else if (whence == OPENMPT_STREAM_SEEK_END) {
        origin = stream.size;
    } else if (whence != OPENMPT_STREAM_SEEK_SET) {
        return -1;
    }
    const __int128 target = static_cast<__int128>(origin) + offset;
    if (target < 0 || target > stream.size) {
        return -1;
    }
    stream.position = static_cast<std::int64_t>(target);
    return 0;
}

std::int64_t streamTell(void* opaque) {
    return static_cast<MemoryStream*>(opaque)->position;
}

void require(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "tracker smoke failure: " << message << '\n';
        std::exit(1);
    }
}

void checkPcm(const std::array<float, kBlockFrames * 2>& pcm, std::size_t frames) {
    for (std::size_t index = 0; index < frames * 2; ++index) {
        require(std::isfinite(pcm[index]), "non-finite Float32 sample");
    }
}

}  // namespace

int main(int argc, char** argv) {
    require(argc == 2, "usage: tracker_smoke fixture.mod");
    std::ifstream input(argv[1], std::ios::binary);
    require(input.good(), "cannot open fixture");
    const std::vector<std::uint8_t> bytes(
        (std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    require(!bytes.empty(), "empty fixture");

    auto memory = Decoder::createFromMemory(bytes.data(), bytes.size());
    require(memory != nullptr, "createFromMemory rejected a valid MOD");
    require(std::isfinite(memory->durationSeconds()) && memory->durationSeconds() > 0.0,
        "invalid duration");
    require(memory->durationUs() > 0, "invalid durationUs");
    require(memory->positionFrame() == 0 && memory->positionUs() == 0,
        "decoder did not start at zero");

    std::array<float, kBlockFrames * 2> pcm{};
    const std::size_t first_frames = memory->read(pcm.data(), kBlockFrames);
    require(first_frames > 0 && first_frames <= kBlockFrames, "initial block read failed");
    require(memory->positionFrame() == static_cast<std::int64_t>(first_frames),
        "frame position did not advance");
    checkPcm(pcm, first_frames);
    const auto initial = pcm;
    bool has_signal = std::any_of(
        pcm.begin(), pcm.begin() + first_frames * 2, [](float sample) { return sample != 0.0f; });

    MemoryStream stream{bytes.data(), static_cast<std::int64_t>(bytes.size())};
    openmpt_stream_callbacks callbacks{};
    callbacks.read = streamRead;
    callbacks.seek = streamSeek;
    callbacks.tell = streamTell;
    auto callback_decoder = Decoder::createFromCallbacks(callbacks, &stream);
    require(callback_decoder != nullptr, "createFromCallbacks rejected a valid MOD");
    require(std::abs(callback_decoder->durationSeconds() - memory->durationSeconds()) < 1e-9,
        "memory/callback duration mismatch");
    pcm.fill(0.0f);
    const std::size_t callback_frames = callback_decoder->read(pcm.data(), kBlockFrames);
    require(callback_frames == first_frames, "memory/callback block size mismatch");
    checkPcm(pcm, callback_frames);
    for (std::size_t index = 0; index < first_frames * 2; ++index) {
        require(std::abs(pcm[index] - initial[index]) < 1e-6f,
            "memory/callback PCM mismatch");
    }
    callback_decoder.reset();

    const double sought = memory->seekSeconds(memory->durationSeconds() * 0.5);
    require(std::isfinite(sought) && sought >= 0.0, "seek failed");
    require(memory->positionFrame() > 0, "seek did not move the frame position");
    require(memory->read(pcm.data(), kBlockFrames) > 0, "read after seek failed");

    memory->reset();
    require(memory->positionFrame() == 0 && memory->positionUs() == 0, "reset failed");
    pcm.fill(0.0f);
    const std::size_t reset_frames = memory->read(pcm.data(), kBlockFrames);
    has_signal = has_signal || std::any_of(
        pcm.begin(), pcm.begin() + reset_frames * 2, [](float sample) { return sample != 0.0f; });
    require(reset_frames == first_frames, "reset changed the first block size");
    for (std::size_t index = 0; index < reset_frames * 2; ++index) {
        require(std::abs(pcm[index] - initial[index]) < 1e-6f, "reset PCM mismatch");
    }

    std::uint64_t total_frames = reset_frames;
    const std::uint64_t frame_limit = static_cast<std::uint64_t>(
        (memory->durationSeconds() + 5.0) * bitchord::tracker::kSampleRate);
    while (true) {
        const std::size_t count = memory->read(pcm.data(), kBlockFrames);
        if (count == 0) {
            break;
        }
        checkPcm(pcm, count);
        has_signal = has_signal || std::any_of(
            pcm.begin(), pcm.begin() + count * 2, [](float sample) { return sample != 0.0f; });
        total_frames += count;
        require(total_frames <= frame_limit, "module did not reach EOS near its duration");
    }
    require(has_signal, "fixture rendered only silence");
    require(memory->ended(), "EOS did not set ended");
    require(memory->positionFrame() == static_cast<std::int64_t>(total_frames),
        "EOS frame position mismatch");
    require(Decoder::createFromMemory(bytes.data(), 0) == nullptr,
        "empty input was accepted");

    memory.reset();
    require(memory == nullptr, "destroy failed");
    std::cout << "tracker smoke passed: " << bytes.size() << " module bytes, "
              << total_frames << " frames at 48000 Hz stereo Float32\n";
    return 0;
}
