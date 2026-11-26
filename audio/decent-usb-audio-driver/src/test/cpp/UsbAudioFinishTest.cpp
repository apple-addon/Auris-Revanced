// Headless usbdevfs contract test. No Android device or audio hardware is used.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <new>
#include <stdexcept>
#include <string>
#include <unistd.h>
#include <vector>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>

namespace {
struct CapturedUrb {
    std::vector<uint8_t> bytes;
    std::vector<unsigned> packetBytes;
};
std::vector<CapturedUrb> captured;

extern "C" int fakeUsbIoctl(int fd, unsigned long request, ...);
}

#define ioctl fakeUsbIoctl
#include "../../main/jni/usb-audio-output.cpp"
#undef ioctl

namespace {
extern "C" int fakeUsbIoctl(int, unsigned long request, ...) {
    va_list args;
    va_start(args, request);
    if (request == USBDEVFS_SUBMITURB) {
        auto *urb = va_arg(args, struct usbdevfs_urb *);
        CapturedUrb copy;
        const auto *bytes = static_cast<const uint8_t *>(urb->buffer);
        copy.bytes.assign(bytes, bytes + urb->buffer_length);
        for (int i = 0; i < urb->number_of_packets; i++) {
            copy.packetBytes.push_back(urb->iso_frame_desc[i].length);
        }
        captured.push_back(std::move(copy));
        va_end(args);
        return 0;
    }
    if (request == USBDEVFS_REAPURBNDELAY) {
        va_end(args);
        errno = EAGAIN;
        return -1;
    }
    va_end(args);
    errno = ENOTSUP;
    return -1;
}

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

void requireBytes(const std::vector<uint8_t> &actual, const std::vector<uint8_t> &expected,
                  const std::string &message) {
    require(actual == expected, message);
}

struct Fixture {
    UsbAudioContext ctx{};

    explicit Fixture(double framesPerMicroframe = 1.0) {
        captured.clear();
        ctx.fd = -1;
        ctx.endpointOut = 1;
        ctx.sampleRate = 8000;
        ctx.channelCount = 1;
        ctx.bitDepth = 16;
        ctx.bytesPerSample = 2;
        ctx.bytesPerFrame = 2;
        ctx.maxPacketSize = 1024;
        ctx.dataInterval = 1;
        ctx.usbSpeed = USB_SPEED_HIGH;
        ctx.calibratedFpmf = framesPerMicroframe;
        ctx.running.store(true);
        require(allocRing(&ctx), "ring allocation failed");
    }

    ~Fixture() {
        freeRing(&ctx);
    }
};

std::vector<uint8_t> sequence(int size) {
    std::vector<uint8_t> result(size);
    for (int i = 0; i < size; i++) result[i] = static_cast<uint8_t>(i * 37 + 11);
    return result;
}

void residualFinishIsShortExactAndIdempotent() {
    Fixture fixture;
    const auto input = sequence(20); // One 16-byte PCM URB, then four actual residual bytes.
    submitPcmToUrbs(&fixture.ctx, input.data(), input.size());
    require(captured.size() == 1, "normal PCM write no longer fills exactly one URB");
    require(captured[0].packetBytes == std::vector<unsigned>(8, 2), "PCM packet layout changed");
    requireBytes(captured[0].bytes, std::vector<uint8_t>(input.begin(), input.begin() + 16),
                 "normal PCM write bytes changed");
    require(fixture.ctx.residualBytes == 4, "expected four residual bytes");

    require(finishPcmUrbs(&fixture.ctx), "finish failed");
    require(captured.size() == 2, "finish did not submit residual URB");
    require(captured[1].packetBytes == std::vector<unsigned>({2, 2}),
            "finish did not use actual short URB packet count");
    requireBytes(captured[1].bytes, std::vector<uint8_t>(input.begin() + 16, input.end()),
                 "finish changed or padded residual bytes");
    require(fixture.ctx.residualBytes == 0, "finish retained submitted bytes");

    require(finishPcmUrbs(&fixture.ctx), "idempotent finish failed");
    require(captured.size() == 2, "repeated finish duplicated audio");
}

void finalShortPacketContainsOnlyRealBytes() {
    Fixture fixture(1.5);
    const auto input = sequence(34); // 24-byte full URB plus ten residual bytes.
    submitPcmToUrbs(&fixture.ctx, input.data(), input.size());
    require(captured.size() == 1 && fixture.ctx.residualBytes == 10,
            "fractional-rate residual setup failed");

    require(finishPcmUrbs(&fixture.ctx), "short-packet finish failed");
    require(captured.size() == 2, "short-packet finish did not submit");
    require(captured[1].packetBytes == std::vector<unsigned>({2, 4, 2, 2}),
            "last packet was padded to nominal length or residual packetization changed");
    requireBytes(captured[1].bytes, std::vector<uint8_t>(input.begin() + 24, input.end()),
                 "short-packet finish changed source bytes");
}

void flushStillDiscardsAndDoesNotFinish() {
    Fixture fixture;
    const auto discarded = sequence(4);
    submitPcmToUrbs(&fixture.ctx, discarded.data(), discarded.size());
    require(captured.empty() && fixture.ctx.residualBytes == 4, "residual setup failed");

    Java_com_decent_usbaudio_UsbAudioStream_nativeFlush(
            nullptr, nullptr, reinterpret_cast<jlong>(&fixture.ctx));
    require(captured.empty(), "flush submitted audio instead of discarding it");
    require(fixture.ctx.residualBytes == 0, "flush did not discard residual bytes");

    const auto pcm = sequence(16);
    submitPcmToUrbs(&fixture.ctx, pcm.data(), pcm.size());
    require(captured.size() == 1, "post-flush PCM write failed");
    requireBytes(captured[0].bytes, pcm, "post-flush PCM included discarded bytes");
    require(finishPcmUrbs(&fixture.ctx), "empty finish after complete PCM URB failed");
    require(captured.size() == 1, "empty finish submitted synthetic audio");
}
}

int main() {
    try {
        residualFinishIsShortExactAndIdempotent();
        finalShortPacketContainsOnlyRealBytes();
        flushStillDiscardsAndDoesNotFinish();
        std::cout << "UsbAudioFinishTest: 3 tests passed\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "UsbAudioFinishTest: " << error.what() << '\n';
        return 1;
    }
}
