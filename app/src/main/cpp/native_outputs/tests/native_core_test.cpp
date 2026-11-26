#include "native_output.h"
#include "output_accounting.h"
#include "spsc_frame_ring.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <pthread.h>
#include <sched.h>

namespace {

int failures = 0;

void check(bool condition, const char *message) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", message);
        ++failures;
    }
}

void test_wraparound_and_gain() {
    auris::SpscFrameRing ring(4, 2);
    const float first[] = {0, 10, 1, 11, 2, 12};
    const float second[] = {3, 13, 4, 14, 5, 15};
    float output[16] = {};

    check(ring.valid(), "ring allocation");
    check(ring.write(first, 3, 1.0f) == 3, "initial three-frame write");
    check(ring.readable_frames() == 3 && ring.writable_frames() == 1, "fullness accounting");
    check(ring.read(output, 2) == 2, "initial read");
    check(output[0] == 0 && output[3] == 11, "initial samples preserved");
    check(ring.write(second, 3, 1.0f) == 3, "wrapped write uses released space");
    check(ring.read(output, 8) == 4, "read is capped to available frames");
    for (int frame = 0; frame < 4; ++frame) {
        check(output[frame * 2] == frame + 2, "wrapped left channel ordering");
        check(output[frame * 2 + 1] == frame + 12, "wrapped right channel ordering");
    }
    check(ring.read(output, 1) == 0 && ring.writable_frames() == 4, "empty ring state");

    ring.clear();
    check(ring.write(first, 3, 0.5f) == 3, "gain write");
    check(ring.read(output, 3) == 3, "gain read");
    check(output[0] == 0 && output[2] == 0.5f && output[5] == 6.0f, "gain applied once");
    ring.clear();
    check(ring.readable_frames() == 0 && ring.writable_frames() == 4, "quiesced clear");
}

struct ProducerContext {
    auris::SpscFrameRing *ring;
    std::atomic<bool> done{false};
    static constexpr int count = 200000;
};

void *produce(void *opaque) {
    auto &context = *static_cast<ProducerContext *>(opaque);
    int next = 0;
    while (next < ProducerContext::count) {
        float input[37];
        const int chunk = std::min<int>(37, ProducerContext::count - next);
        for (int i = 0; i < chunk; ++i) input[i] = static_cast<float>(next + i);
        const size_t written = context.ring->write(input, chunk, 1.0f);
        next += static_cast<int>(written);
        if (written == 0) sched_yield();
    }
    context.done.store(true, std::memory_order_release);
    return nullptr;
}

void test_spsc_threads() {
    auris::SpscFrameRing ring(61, 1);
    ProducerContext context{&ring};
    pthread_t producer;
    check(pthread_create(&producer, nullptr, produce, &context) == 0, "producer thread starts");

    int next = 0;
    float output[23];
    while (next < ProducerContext::count) {
        const size_t read = ring.read(output, 23);
        for (size_t i = 0; i < read; ++i) {
            check(output[i] == static_cast<float>(next), "SPSC cross-thread sample order");
            ++next;
        }
        if (read == 0) {
            check(!context.done.load(std::memory_order_acquire), "producer must not finish early");
            sched_yield();
        }
    }
    pthread_join(producer, nullptr);
    check(ring.readable_frames() == 0, "SPSC drained exactly");
}

void test_accounting() {
    auris::OutputAccounting accounting;
    accounting.record_enqueued(480, 480);
    check(accounting.output_frames() == 480 && accounting.position_frames() == 480,
            "full buffer clock");
    check(accounting.underruns() == 0 && accounting.missing_frames() == 0, "no false underrun");
    accounting.record_enqueued(480, 120);
    check(accounting.output_frames() == 960 && accounting.position_frames() == 960,
            "short buffer still advances hardware queue clock");
    check(accounting.underruns() == 1 && accounting.missing_frames() == 360,
            "short buffer accounting");
    accounting.reset_position();
    check(accounting.position_frames() == 0 && accounting.output_frames() == 960,
            "flush resets position only");
    check(accounting.underruns() == 1, "flush preserves lifetime xruns");
    check(auris::frames_to_us(48000, 48000) == 1000000, "frame to time conversion");
    check(auris::frames_to_us(1000000000000000LL, 48000) == 20833333333333333LL,
            "large in-range conversion does not saturate early");
    check(auris::frames_to_us(-1, 48000) == auris::kUnknown, "negative frame count unknown");
    check(auris::frames_to_us(INT64_MAX, 1) == INT64_MAX, "time conversion saturates");
}

} // namespace

int main() {
    test_wraparound_and_gain();
    test_spsc_threads();
    test_accounting();
    if (failures == 0) std::printf("native output core tests passed\n");
    return failures == 0 ? 0 : 1;
}
