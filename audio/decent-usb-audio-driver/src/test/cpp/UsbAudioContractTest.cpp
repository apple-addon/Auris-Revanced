// Headless usbdevfs contract tests: raw packing, float conversion, finish/EOS
// semantics, feedback decoding and fail-closed error paths. No Android device
// or audio hardware is used; usbdevfs ioctls are faked.
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
#include <vector>
#include <unistd.h>
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
    va_end(args);
    errno = EAGAIN;
    return -1;
}

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

struct Fixture {
    UsbAudioContext ctx{};

    explicit Fixture(double framesPerMicroframe = 1.0, int maxPacket = 1024) {
        captured.clear();
        ctx.fd = -1;
        ctx.endpointOut = 1;
        ctx.endpointFeedback = 0;
        ctx.sampleRate = 8000;
        ctx.channelCount = 1;
        ctx.bitDepth = 16;
        ctx.bytesPerSample = 2;
        ctx.bytesPerFrame = 2;
        ctx.maxPacketSize = maxPacket;
        ctx.dataInterval = 1;
        ctx.feedbackPacketSize = 4;
        ctx.feedbackInterval = 1;
        ctx.usbSpeed = USB_SPEED_HIGH;
        ctx.calibratedFpmf = framesPerMicroframe;
        ctx.running.store(true);
        require(allocRing(&ctx), "ring allocation failed");
        require(allocFeedbackUrb(&ctx), "feedback URB allocation failed");
    }

    ~Fixture() {
        freeRing(&ctx);
        free(ctx.feedbackUrb);
    }
};

std::vector<uint8_t> sequence(int size) {
    std::vector<uint8_t> result(size);
    for (int i = 0; i < size; i++) result[i] = static_cast<uint8_t>(i * 37 + 11);
    return result;
}

// ── Raw packing (lossless integer ops used by the raw/DoP path) ────────

void padInt16ToInt32ShiftsLeft16() {
    const uint8_t src[] = {0x00, 0x80, 0xFF, 0x7F};  // -32768, 32767
    uint8_t dst[8] = {};
    padInt16ToInt32(src, dst, 2);
    int32_t out[2];
    memcpy(out, dst, sizeof(out));
    require(out[0] == INT32_MIN, "padInt16ToInt32 must map -32768 to INT32_MIN");
    require(out[1] == 32767 << 16, "padInt16ToInt32 must shift +32767 left 16");
}

void padInt24ToInt32SignExtendsAndShifts8() {
    const uint8_t pos[] = {0xFF, 0xFF, 0x7F};   // +8388607
    const uint8_t neg[] = {0x00, 0x00, 0x80};   // -8388608
    uint8_t dst[8] = {};
    padInt24ToInt32(pos, dst, 1);
    padInt24ToInt32(neg, dst + 4, 1);
    int32_t out[2];
    memcpy(out, dst, sizeof(out));
    require(out[0] == 8388607 << 8, "padInt24ToInt32 positive must sign-extend then shift 8");
    require(out[1] == INT32_MIN, "padInt24ToInt32 negative must sign-extend to INT32_MIN");
}

void padInt16ToInt24ZeroFillsLsb() {
    const uint8_t src[] = {0x34, 0x12};
    uint8_t dst[3] = {0xAA, 0xAA, 0xAA};
    padInt16ToInt24(src, dst, 1);
    require(dst[0] == 0 && dst[1] == 0x34 && dst[2] == 0x12,
            "padInt16ToInt24 must zero-fill the LSB byte");
}

void packInt32ToInt24TakesTopThreeBytes() {
    const uint8_t src[] = {0x01, 0x02, 0x03, 0x04};
    uint8_t dst[3] = {};
    packInt32ToInt24(src, dst, 1);
    require(dst[0] == 0x02 && dst[1] == 0x03 && dst[2] == 0x04,
            "packInt32ToInt24 must take bytes 1..3 (>>8), never the low bytes");
}

void packDownToInt16TakesTopBytes() {
    const uint8_t src24[] = {0x01, 0x02, 0x03};
    const uint8_t src32[] = {0x01, 0x02, 0x03, 0x04};
    uint8_t d16[2] = {}, d32[2] = {};
    packInt24ToInt16(src24, d16, 1);
    packInt32ToInt16(src32, d32, 1);
    require(d16[0] == 0x02 && d16[1] == 0x03, "packInt24ToInt16 keeps the top two bytes");
    require(d32[0] == 0x03 && d32[1] == 0x04, "packInt32ToInt16 keeps the top two bytes");
}

void floatConversionClampsAndRoundTripsExactly() {
    // 16-bit round trip: int16 / 32768.0f must convert back to the same int16.
    const int16_t samples[] = {0, 1, -1, 32767, -32768, 12345, -12345};
    float f[7];
    for (int i = 0; i < 7; i++) f[i] = samples[i] / 32768.0f;
    uint8_t dst[14] = {};
    convertFloatToInt16(f, dst, 7);
    int16_t out[7];
    memcpy(out, dst, sizeof(out));
    for (int i = 0; i < 7; i++) {
        require(out[i] == samples[i], "float->int16 must round-trip every 16-bit value exactly");
    }
    // Out-of-range floats clamp, never wrap.
    const float hot[] = {2.0f, -2.0f};
    convertFloatToInt16(hot, dst, 2);
    memcpy(out, dst, 4);
    require(out[0] == 32767 && out[1] == -32768, "float->int16 must clamp beyond +/-1.0");
    const float hot32[] = {1.5f, -1.5f};
    uint8_t dst32[8] = {};
    convertFloatToInt32(hot32, dst32, 2);
    int32_t out32[2];
    memcpy(out32, dst32, sizeof(out32));
    require(out32[0] == INT32_MAX && out32[1] == INT32_MIN, "float->int32 must clamp beyond +/-1.0");
}

// ── Packet period (USB clock basis) ─────────────────────────────────────

void packetPeriodMicroframesFullVsHighSpeed() {
    Fixture hs;
    hs.ctx.usbSpeed = USB_SPEED_HIGH;
    hs.ctx.dataInterval = 1;
    require(packetPeriodMicroframes(&hs.ctx) == 1.0, "HS bInterval=1 is one microframe");
    hs.ctx.dataInterval = 4;
    require(packetPeriodMicroframes(&hs.ctx) == 8.0, "HS bInterval=4 is 8 microframes");
    Fixture fs;
    fs.ctx.usbSpeed = USB_SPEED_FULL;
    fs.ctx.dataInterval = 3;
    require(packetPeriodMicroframes(&fs.ctx) == 24.0, "FS bInterval counts 1 ms frames (8 microframes each)");
}

// ── Async feedback decoding and the continuous-feedback guard ───────────

void feedbackCompletionUpdatesCalibratedClockInsideWindow() {
    Fixture f;
    f.ctx.sampleRate = 44100;
    f.ctx.dataInterval = 1;
    f.ctx.usbSpeed = USB_SPEED_HIGH;
    f.ctx.feedbackPacketSize = 4;
    const double nominal = 44100.0 / 8000.0;  // 5.5125 fpmf
    // Q16.16 raw value for nominal + 0.5% (inside the ±1% acceptance window).
    uint32_t raw = static_cast<uint32_t>(std::llround(nominal * 1.005 * 65536.0));
    f.ctx.feedbackBuffer[0] = raw & 0xFF;
    f.ctx.feedbackBuffer[1] = (raw >> 8) & 0xFF;
    f.ctx.feedbackBuffer[2] = (raw >> 16) & 0xFF;
    f.ctx.feedbackBuffer[3] = (raw >> 24) & 0xFF;
    f.ctx.feedbackUrb->iso_frame_desc[0].actual_length = 4;
    f.ctx.feedbackInFlight = true;
    f.ctx.calibratedFpmf = nominal;

    handleFeedbackCompletion(&f.ctx);
    // Q16.16 quantization: one LSB is 1/65536, so allow well under that.
    require(std::fabs(f.ctx.calibratedFpmf - nominal * 1.005) < 1e-4,
            "in-window async feedback must update calibratedFpmf");
    require(!f.ctx.feedbackInFlight, "completion clears the in-flight flag");
    // The continuous-feedback compile-time guard must refuse to resubmit.
    require(!submitFeedbackUrb(&f.ctx), "continuous feedback must stay disabled on Android xHCI");
    require(captured.empty(), "the feedback guard must not submit any URB");
}

void feedbackCompletionRejectsOutOfWindowClock() {
    Fixture f;
    f.ctx.sampleRate = 44100;
    f.ctx.dataInterval = 1;
    f.ctx.usbSpeed = USB_SPEED_HIGH;
    f.ctx.feedbackPacketSize = 4;
    const double nominal = 44100.0 / 8000.0;
    f.ctx.calibratedFpmf = nominal;
    // 10% fast — far outside the ±1% sanity window.
    uint32_t raw = static_cast<uint32_t>(std::llround(nominal * 1.10 * 65536.0));
    f.ctx.feedbackBuffer[0] = raw & 0xFF;
    f.ctx.feedbackBuffer[1] = (raw >> 8) & 0xFF;
    f.ctx.feedbackBuffer[2] = (raw >> 16) & 0xFF;
    f.ctx.feedbackBuffer[3] = (raw >> 24) & 0xFF;
    f.ctx.feedbackUrb->iso_frame_desc[0].actual_length = 4;
    f.ctx.feedbackInFlight = true;

    handleFeedbackCompletion(&f.ctx);
    require(f.ctx.calibratedFpmf == nominal,
            "implausible feedback must not move the calibrated clock");
}

void feedbackCompletionIgnoresShortPacket() {
    Fixture f;
    const double before = f.ctx.calibratedFpmf;
    f.ctx.feedbackUrb->iso_frame_desc[0].actual_length = 2;  // truncated
    f.ctx.feedbackInFlight = true;
    handleFeedbackCompletion(&f.ctx);
    require(f.ctx.calibratedFpmf == before, "a short feedback packet must not change the clock");
}

// ── Finish / EOS semantics ──────────────────────────────────────────────

void finishOnStoppedStreamFailsClosedAndRetainsResidual() {
    Fixture f;
    const auto input = sequence(20);  // 16-byte URB + 4 residual bytes
    submitPcmToUrbs(&f.ctx, input.data(), input.size());
    require(f.ctx.residualBytes == 4, "residual setup failed");
    f.ctx.running.store(false);
    require(!finishPcmUrbs(&f.ctx), "finish on a stopped stream must fail");
    require(f.ctx.residualBytes == 4, "a failed finish must retain the residual bytes");
    require(captured.size() == 1, "a failed finish must not submit anything");
}

void writeAfterFinishStartsANewEosGeneration() {
    Fixture f;
    const auto first = sequence(20);
    submitPcmToUrbs(&f.ctx, first.data(), first.size());
    require(finishPcmUrbs(&f.ctx), "first finish failed");
    require(f.ctx.finishSubmitted, "finish must mark the generation");
    const size_t afterFinish = captured.size();

    const auto second = sequence(16);  // exactly one full URB, no residual
    submitPcmToUrbs(&f.ctx, second.data(), second.size());
    require(!f.ctx.finishSubmitted, "a write after finish must open a new EOS generation");
    require(captured.size() == afterFinish + 1, "the new write must be submitted");
    require(finishPcmUrbs(&f.ctx), "second finish failed");
    require(captured.size() == afterFinish + 1, "empty-residual finish must not add padding");
}

void finishRejectsPacketBeyondMaxPacketSize() {
    // Seed a 4-byte residual directly; with fpmf=0.5 the first EOS packet is
    // nominal-zero, so the whole 4-byte short packet lands on the wire and
    // must be rejected against wMaxPacketSize=3 instead of being sent.
    Fixture f(0.5, /*maxPacket=*/3);
    const auto tail = sequence(4);
    memcpy(f.ctx.residualBuffer, tail.data(), tail.size());
    f.ctx.residualBytes = 4;
    require(!finishPcmUrbs(&f.ctx), "finish must fail when a short packet exceeds wMaxPacketSize");
    require(!f.ctx.running.load(), "a wMaxPacketSize violation must stop the stream");
    require(f.ctx.residualBytes == 4, "failed finish retains all unsubmitted bytes");
    require(captured.empty(), "rejected finish must not submit any URB");
}

void oversizedLeftoverFailsClosedInsteadOfDroppingBytes() {
    Fixture f;
    f.ctx.maxPacketSize = 0;       // disable the per-packet guard
    f.ctx.calibratedFpmf = 3000.0; // 3000 frames * 2 bytes = 6000 B per packet
    const auto input = sequence(100000);
    submitPcmToUrbs(&f.ctx, input.data(), input.size());
    require(!f.ctx.running.load(),
            "a leftover larger than the residual buffer must stop the stream, not drop bytes");
}

void normalWriteStillPacketizesExactly() {
    Fixture f;
    const auto input = sequence(32);  // exactly two 16-byte URBs at 8 pkts x 2 B
    submitPcmToUrbs(&f.ctx, input.data(), input.size());
    require(captured.size() == 2, "two full URBs expected");
    require(f.ctx.residualBytes == 0, "no residual after exact URBs");
    for (size_t u = 0; u < captured.size(); u++) {
        require(captured[u].packetBytes == std::vector<unsigned>(8, 2),
                "every packet must carry exactly one frame (2 bytes)");
        require(captured[u].bytes == std::vector<uint8_t>(input.begin() + u * 16,
                                                           input.begin() + (u + 1) * 16),
                "URB bytes must be bit-identical to the source");
    }
}

}  // namespace

int main() {
    try {
        padInt16ToInt32ShiftsLeft16();
        padInt24ToInt32SignExtendsAndShifts8();
        padInt16ToInt24ZeroFillsLsb();
        packInt32ToInt24TakesTopThreeBytes();
        packDownToInt16TakesTopBytes();
        floatConversionClampsAndRoundTripsExactly();
        packetPeriodMicroframesFullVsHighSpeed();
        feedbackCompletionUpdatesCalibratedClockInsideWindow();
        feedbackCompletionRejectsOutOfWindowClock();
        feedbackCompletionIgnoresShortPacket();
        finishOnStoppedStreamFailsClosedAndRetainsResidual();
        writeAfterFinishStartsANewEosGeneration();
        finishRejectsPacketBeyondMaxPacketSize();
        oversizedLeftoverFailsClosedInsteadOfDroppingBytes();
        normalWriteStillPacketizesExactly();
        std::cout << "UsbAudioContractTest: 15 tests passed\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "UsbAudioContractTest: " << error.what() << '\n';
        return 1;
    }
}
