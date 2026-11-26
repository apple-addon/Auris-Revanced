// Controllable fakes behind the stub JNI/AAudio/OpenSL ES headers, so the
// real outputs/*.cpp can be exercised headless.
#ifndef BITCHORD_TEST_FAKE_PLATFORM_H_
#define BITCHORD_TEST_FAKE_PLATFORM_H_

#include <aaudio/AAudio.h>

#include <cstdint>
#include <string>
#include <vector>

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

struct FakeAAudioState {
  // Open behavior.
  bool fail_exclusive_open = false;
  bool fail_shared_open = false;
  bool grant_wrong_format = false;  // open succeeds but reports a different rate
  std::vector<aaudio_sharing_mode_t> requested_sharing_modes;
  int open_attempts = 0;
  int streams_created = 0;

  // Stream behavior.
  aaudio_stream_state_t state = AAUDIO_STREAM_STATE_OPEN;
  int32_t xrun_count = 0;
  int64_t frames_read = 0;
  bool timestamp_available = false;
  int64_t timestamp_position = 0;
  int64_t timestamp_ns = 0;
  int32_t buffer_capacity = 2048;
  int32_t frames_per_callback = 256;

  // Recorded builder callbacks of the most recent open.
  AAudioStream_dataCallback data_callback = nullptr;
  void* data_user = nullptr;
  AAudioStream_errorCallback error_callback = nullptr;
  void* error_user = nullptr;
  AAudioStream* last_stream = nullptr;

  void reset() { *this = FakeAAudioState(); }
};
extern FakeAAudioState g_fake_aaudio;

struct FakeEnqueue {
  SLuint32 size = 0;
  std::vector<uint8_t> bytes;
};

struct FakeOpenSlState {
  bool fail_create_engine = false;
  bool fail_create_player = false;
  int enqueue_calls = 0;
  int clear_calls = 0;
  std::vector<FakeEnqueue> enqueued;
  slAndroidSimpleBufferQueueCallback callback = nullptr;
  void* callback_context = nullptr;
  SLuint32 play_state = SL_PLAYSTATE_STOPPED;
  SLAndroidSimpleBufferQueueItf queue_itf = nullptr;

  void reset() { *this = FakeOpenSlState(); }
  // Simulates the hardware completing the oldest queued buffer.
  void completeOneBuffer();
};
extern FakeOpenSlState g_fake_sles;

#endif  // BITCHORD_TEST_FAKE_PLATFORM_H_
