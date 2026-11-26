#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <thread>
#include <vector>

#include "output_common.h"
#include "spsc_float_ring.h"

namespace {

using bitchord::audio::ExtrapolateTimestampFrames;
using bitchord::audio::SpscFloatRing;

void check(bool condition, const char* message) {
  if (!condition) {
    std::cerr << "FAILED: " << message << '\n';
    std::exit(1);
  }
}

void testWrapAndPartialWrite() {
  SpscFloatRing ring(3, 2);
  check(ring.capacityFrames() == 4, "capacity rounds to a power of two");
  check(ring.frameBytes() == 8, "stereo float frame size");

  const float first[] = {1, -1, 2, -2, 3, -3, 4, -4};
  check(ring.write(first, 4) == 4, "initial full write");
  check(ring.write(first, 1) == 0, "full ring applies backpressure");
  float output[8] = {};
  check(ring.read(output, 2) == 2, "partial read");
  check(output[0] == 1 && output[3] == -2, "partial read preserves order");

  const float second[] = {5, -5, 6, -6};
  check(ring.write(second, 2) == 2, "wrapped write");
  check(ring.read(output, 4) == 4, "wrapped read");
  const float expected[] = {3, -3, 4, -4, 5, -5, 6, -6};
  for (int index = 0; index < 8; ++index) {
    check(output[index] == expected[index], "wrapped PCM stays bit exact");
  }
  check(ring.available() == 0, "ring empty after drain");
  ring.resetQuiescent();
  check(ring.available() == 0 && ring.write(first, 4) == 4,
        "quiescent reset restores full capacity");
}

void testConcurrentSingleProducerSingleConsumer() {
  constexpr int kFrames = 100000;
  SpscFloatRing ring(64, 2);
  std::atomic<bool> failed{false};
  std::thread producer([&] {
    float input[6] = {};
    int next = 0;
    while (next < kFrames) {
      const int count = std::min(3, kFrames - next);
      for (int frame = 0; frame < count; ++frame) {
        input[frame * 2] = static_cast<float>(next + frame);
        input[frame * 2 + 1] = static_cast<float>(-(next + frame));
      }
      const size_t written = ring.write(input, count);
      if (written == 0) {
        std::this_thread::yield();
        continue;
      }
      next += static_cast<int>(written);
    }
  });
  std::thread consumer([&] {
    float output[10] = {};
    int next = 0;
    while (next < kFrames) {
      const size_t read = ring.read(output, 5);
      if (read == 0) {
        std::this_thread::yield();
        continue;
      }
      for (size_t frame = 0; frame < read; ++frame) {
        const float expected = static_cast<float>(next);
        if (output[frame * 2] != expected ||
            output[frame * 2 + 1] != -expected) {
          failed.store(true);
          return;
        }
        ++next;
      }
    }
  });
  producer.join();
  consumer.join();
  check(!failed.load(), "concurrent SPSC stream has no loss or reordering");
}

void testTimestampClock() {
  constexpr int64_t kSecond = 1000000000LL;
  check(ExtrapolateTimestampFrames(100, 5 * kSecond, 5 * kSecond, 48000, 1000) == 100,
        "timestamp without elapsed time");
  check(ExtrapolateTimestampFrames(100, 5 * kSecond,
                                   5 * kSecond + kSecond / 2, 48000, 100000) == 24100,
        "half-second timestamp extrapolation");
  check(ExtrapolateTimestampFrames(100, 5 * kSecond, 6 * kSecond, 48000, 200) == 200,
        "silence cannot advance beyond application frames");
  check(ExtrapolateTimestampFrames(100, 5 * kSecond, 4 * kSecond, 48000, 1000) == -1,
        "non-monotonic wall clock rejected");
  check(ExtrapolateTimestampFrames(0, 1, std::numeric_limits<int64_t>::max(),
                                   768000, std::numeric_limits<int64_t>::max()) >= 0,
        "large monotonic interval does not overflow");
}

}  // namespace

int main() {
  testWrapAndPartialWrite();
  testConcurrentSingleProducerSingleConsumer();
  testTimestampClock();
  std::cout << "SpscFloatRingTest: all tests passed\n";
  return 0;
}
