// Headless contract tests for the shared native output header: config
// validation, power-of-two rounding, overflow-safe timestamp extrapolation
// (the "no fake padding" position clamp), and the telemetry index layout that
// the Kotlin decoder depends on.
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <limits>

#include "output_common.h"

namespace {

using bitchord::audio::ExtrapolateTimestampFrames;
using bitchord::audio::IsValidConfig;
using bitchord::audio::NativeTelemetry;
using bitchord::audio::OutputConfig;
using bitchord::audio::RoundUpPowerOfTwo;
using bitchord::audio::WriteTelemetry;

void check(bool condition, const char* message) {
  if (!condition) {
    std::cerr << "FAILED: " << message << '\n';
    std::exit(1);
  }
}

void testConfigValidation() {
  const OutputConfig good{48000, 2, 4096, 0, 0};
  check(IsValidConfig(good), "a normal stereo config is valid");

  check(!IsValidConfig({3999, 2, 4096, 0, 0}), "sample rate below 4 kHz rejected");
  check(!IsValidConfig({768001, 2, 4096, 0, 0}), "sample rate above 768 kHz rejected");
  check(IsValidConfig({768000, 8, 4096, 0, 0}), "the maximum rate and 8 channels are valid");
  check(!IsValidConfig({48000, 0, 4096, 0, 0}), "zero channels rejected");
  check(!IsValidConfig({48000, 9, 4096, 0, 0}), "more than 8 channels rejected");
  check(!IsValidConfig({48000, 2, 0, 0, 0}), "zero ring capacity rejected");
  check(!IsValidConfig({48000, 2, 1 << 23, 0, 0}), "ring above 2^22 frames rejected");
  check(!IsValidConfig({48000, 2, 4096, -1, 0}), "negative buffer size rejected");
  check(!IsValidConfig({48000, 2, 4096, 4097, 0}), "buffer larger than ring rejected");
  check(!IsValidConfig({48000, 2, 4096, 0, -1}), "negative device id rejected");
  // The int32 byte-size guard is defensive: even the maximum configuration
  // (8 channels * 2^22 frames * 4 bytes = 2^27) stays well inside int32, so
  // the JNI byte-count arithmetic can never overflow.
  check(IsValidConfig({768000, 8, 1 << 22, 0, 0}),
        "maximum ring fits int32 bytes and is valid");
  check(IsValidConfig({48000, 2, 1 << 22, 0, 0}),
        "stereo 2^22-frame ring fits int32 bytes and is valid");
}

void testRoundUpPowerOfTwo() {
  check(RoundUpPowerOfTwo(1) == 1, "1 stays 1");
  check(RoundUpPowerOfTwo(2) == 2, "2 stays 2");
  check(RoundUpPowerOfTwo(3) == 4, "3 rounds to 4");
  check(RoundUpPowerOfTwo(4096) == 4096, "exact power of two stays");
  check(RoundUpPowerOfTwo(4097) == 8192, "4097 rounds to 8192");
  check(RoundUpPowerOfTwo(1 << 22) == 1 << 22, "the maximum capacity stays exact");
}

void testExtrapolationClampsToWrittenFrames() {
  constexpr int64_t kRate = 48000;
  constexpr int64_t kWritten = 1000;
  // Exact timestamp at frame 500, now = timestamp + 10 ms -> 480 frames.
  const int64_t position = ExtrapolateTimestampFrames(
      500, 1000000000LL, 1000000000LL + 10000000LL, kRate, kWritten);
  check(position == 980, "extrapolation advances by elapsed time");

  // A timestamp far in the past must saturate at frames written, never
  // overflow: padded silence cannot move the position past real content.
  const int64_t clamped = ExtrapolateTimestampFrames(
      500, 0, std::numeric_limits<int64_t>::max() / 2, kRate, kWritten);
  check(clamped == kWritten, "distant-past timestamp clamps to frames written");

  const int64_t overflow = ExtrapolateTimestampFrames(
      500, 0, std::numeric_limits<int64_t>::max(), kRate, kWritten);
  check(overflow == kWritten, "near-max timestamp does not overflow int64");

  // Position already at the bound stays at the bound.
  check(ExtrapolateTimestampFrames(kWritten, 5, 6, kRate, kWritten) == kWritten,
        "position at the bound stays at the bound");

  // Invalid inputs report -1 instead of guessing.
  check(ExtrapolateTimestampFrames(-1, 0, 0, kRate, kWritten) == -1, "negative frame rejected");
  check(ExtrapolateTimestampFrames(0, -1, 0, kRate, kWritten) == -1, "negative timestamp rejected");
  check(ExtrapolateTimestampFrames(0, 10, 5, kRate, kWritten) == -1, "now before timestamp rejected");
  check(ExtrapolateTimestampFrames(0, 0, 0, 0, kWritten) == -1, "zero rate rejected");
  check(ExtrapolateTimestampFrames(0, 0, 0, kRate, -1) == -1, "negative bound rejected");

  // Fractional remainder: 0.5 s at 44.1 kHz = 22050 frames exactly (with a
  // bound high enough that the clamp does not engage).
  const int64_t half = ExtrapolateTimestampFrames(0, 0, 500000000LL, 44100, 1LL << 30);
  check(half == 22050, "fractional seconds convert exactly");
}

void testTelemetryIndexContract() {
  // The Kotlin decoder (decodeNativeAudioTelemetry) reads fixed indices; this
  // test pins the C++ side of that contract.
  check(bitchord::audio::kTelemetryValueCount == 19, "19 telemetry values");
  check(bitchord::audio::kOpen == 0 && bitchord::audio::kPlaying == 1, "open/playing indices");
  check(bitchord::audio::kSampleRate == 2 && bitchord::audio::kChannelCount == 3, "format indices");
  check(bitchord::audio::kRingCapacityFrames == 4 && bitchord::audio::kBufferCapacityFrames == 5 &&
            bitchord::audio::kFramesPerBuffer == 6,
        "buffer indices");
  check(bitchord::audio::kSharingMode == 7 && bitchord::audio::kLowLatency == 8, "mode indices");
  check(bitchord::audio::kPositionFrames == 9 && bitchord::audio::kClockSource == 10, "clock indices");
  check(bitchord::audio::kFramesWritten == 11 && bitchord::audio::kUnderrunCount == 12 &&
            bitchord::audio::kXrunCount == 13,
        "counter indices");
  check(bitchord::audio::kRecoveryCount == 14 && bitchord::audio::kDisconnectCount == 15 &&
            bitchord::audio::kBufferedFrames == 16,
        "recovery indices");
  check(bitchord::audio::kNativeState == 17 && bitchord::audio::kLastErrorCode == 18, "state indices");

  NativeTelemetry telemetry;
  telemetry.is_open = true;
  telemetry.is_playing = true;
  telemetry.sample_rate = 96000;
  telemetry.channel_count = 2;
  telemetry.ring_capacity_frames = 65536;
  telemetry.buffer_capacity_frames = 1024;
  telemetry.frames_per_buffer = 256;
  telemetry.sharing_mode = 1;  // shared, honestly reported
  telemetry.low_latency = true;
  telemetry.position_frames = 123456;
  telemetry.clock_source = bitchord::audio::ClockSource::kAAudioTimestamp;
  telemetry.frames_written = 200000;
  telemetry.underrun_count = 3;
  telemetry.xrun_count = 2;
  telemetry.recovery_count = 1;
  telemetry.disconnect_count = 1;
  telemetry.buffered_frames = 512;
  telemetry.native_state = 5;
  telemetry.last_error_code = -899;

  int64_t values[bitchord::audio::kTelemetryValueCount] = {};
  WriteTelemetry(telemetry, values);
  check(values[0] == 1 && values[1] == 1, "open/playing written");
  check(values[2] == 96000 && values[3] == 2, "format written");
  check(values[4] == 65536 && values[5] == 1024 && values[6] == 256, "buffer written");
  check(values[7] == 1 && values[8] == 1, "sharing mode/low latency written");
  check(values[9] == 123456 && values[10] == 1, "position/clock written");
  check(values[11] == 200000 && values[12] == 3 && values[13] == 2, "counters written");
  check(values[14] == 1 && values[15] == 1 && values[16] == 512, "recovery written");
  check(values[17] == 5 && values[18] == -899, "state/error written");
}

}  // namespace

int main() {
  testConfigValidation();
  testRoundUpPowerOfTwo();
  testExtrapolationClampsToWrittenFrames();
  testTelemetryIndexContract();
  std::cout << "OutputCommonTest: all tests passed\n";
  return 0;
}
