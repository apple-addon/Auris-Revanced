// Headless proof that a failed residual-merge allocation fails closed:
// the stream stops, the residual is retained, and no out-of-bounds read
// happens (previously dataLen covered residual+new bytes while data pointed
// at the new bytes alone).
#include <jni.h>
#include <android/log.h>
#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>

namespace {
bool g_failMalloc = false;
extern "C" int fakeUsbIoctl(int fd, unsigned long request, ...);
void* fakeMalloc(size_t size) {
    if (g_failMalloc) return nullptr;
    return malloc(size);  // real malloc, called after the macro is undef'd below
}
}

#define malloc fakeMalloc
#define ioctl fakeUsbIoctl
#include "../../main/jni/usb-audio-output.cpp"
#undef malloc
#undef ioctl

namespace {
extern "C" int fakeUsbIoctl(int, unsigned long request, ...) {
    va_list args;
    va_start(args, request);
    va_end(args);
    errno = EAGAIN;
    return -1;
}

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}
}

int main() {
    try {
        UsbAudioContext ctx{};
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
        ctx.calibratedFpmf = 1.0;
        ctx.running.store(true);
        require(allocRing(&ctx), "ring allocation failed");

        // Leave a 4-byte residual from a previous write.
        const uint8_t tail[4] = {0xDE, 0xAD, 0xBE, 0xEF};
        memcpy(ctx.residualBuffer, tail, 4);
        ctx.residualBytes = 4;

        // Next write must merge residual + new bytes; force the merge malloc
        // to fail and prove the stream fails closed without reading OOB.
        const uint8_t fresh[16] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        g_failMalloc = true;
        submitPcmToUrbs(&ctx, fresh, sizeof(fresh));  // must not crash or submit
        g_failMalloc = false;

        require(!ctx.running.load(), "merge-allocation failure must stop the stream");
        require(ctx.residualBytes == 4, "residual must be retained untouched on OOM");
        require(memcmp(ctx.residualBuffer, tail, 4) == 0, "retained residual bytes must be unchanged");
        require(ctx.urbsInFlight == 0, "OOM path must not submit partial URBs");

        freeRing(&ctx);
        std::cout << "UsbAudioOomTest: 1 test passed\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "UsbAudioOomTest: " << error.what() << '\n';
        return 1;
    }
}
