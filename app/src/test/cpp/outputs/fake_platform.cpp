#include "fake_platform.h"

#include <jni.h>

#include <cstring>
#include <new>

// ── Fake JNI ────────────────────────────────────────────────────────────

PendingException g_pendingException;
void resetPendingException() { g_pendingException = PendingException(); }

jclass JNIEnv::FindClass(const char* name) {
  static _jobject fake_class;
  g_pendingException.class_name = name ? name : "";
  return &fake_class;
}

jint JNIEnv::ThrowNew(jclass, const char* message) {
  g_pendingException.thrown = true;
  g_pendingException.message = message ? message : "";
  return 0;
}

void* JNIEnv::GetDirectBufferAddress(jobject buffer) {
  return buffer ? static_cast<FakeDirectBuffer*>(buffer)->address : nullptr;
}

jlong JNIEnv::GetDirectBufferCapacity(jobject buffer) {
  return buffer ? static_cast<FakeDirectBuffer*>(buffer)->capacity : -1;
}

jlongArray JNIEnv::NewLongArray(jint length) {
  if (length < 0) return nullptr;
  auto* array = new (std::nothrow) FakeLongArray();
  if (array != nullptr) array->values.assign(static_cast<size_t>(length), 0);
  return array;
}

void JNIEnv::SetLongArrayRegion(jlongArray array, jint start, jint length, const jlong* values) {
  auto* target = static_cast<FakeLongArray*>(array);
  if (target == nullptr || values == nullptr) return;
  for (jint i = 0; i < length; ++i) {
    target->values.at(static_cast<size_t>(start + i)) = values[i];
  }
}

jstring JNIEnv::NewStringUTF(const char* bytes) {
  auto* string = new (std::nothrow) FakeString();
  if (string != nullptr) string->text = bytes ? bytes : "";
  return string;
}

// ── Fake AAudio ─────────────────────────────────────────────────────────

FakeAAudioState g_fake_aaudio;

struct AAudioStream {
  aaudio_sharing_mode_t sharing_mode = AAUDIO_SHARING_MODE_SHARED;
  int32_t sample_rate = 0;
  int32_t channel_count = 0;
  aaudio_stream_state_t state = AAUDIO_STREAM_STATE_OPEN;
  bool closed = false;
};

struct AAudioStreamBuilder {
  aaudio_sharing_mode_t sharing_mode = AAUDIO_SHARING_MODE_SHARED;
  aaudio_format_t format = AAUDIO_FORMAT_PCM_FLOAT;
  int32_t sample_rate = 0;
  int32_t channel_count = 0;
  int32_t device_id = 0;
  int32_t frames_per_callback = 0;
  AAudioStream_dataCallback data_callback = nullptr;
  void* data_user = nullptr;
  AAudioStream_errorCallback error_callback = nullptr;
  void* error_user = nullptr;
};

aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder** builder) {
  *builder = new (std::nothrow) AAudioStreamBuilder();
  return *builder != nullptr ? AAUDIO_OK : AAUDIO_ERROR_INTERNAL;
}

void AAudioStreamBuilder_setDirection(AAudioStreamBuilder*, aaudio_direction_t) {}
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder* b, aaudio_format_t f) { b->format = f; }
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder* b, int32_t r) { b->sample_rate = r; }
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder* b, int32_t c) { b->channel_count = c; }
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder* b, aaudio_sharing_mode_t m) {
  b->sharing_mode = m;
}
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder*, aaudio_performance_mode_t) {}
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder* b, AAudioStream_dataCallback cb,
                                         void* user) {
  b->data_callback = cb;
  b->data_user = user;
}
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder* b, AAudioStream_errorCallback cb,
                                          void* user) {
  b->error_callback = cb;
  b->error_user = user;
}
void AAudioStreamBuilder_setDeviceId(AAudioStreamBuilder* b, int32_t d) { b->device_id = d; }
void AAudioStreamBuilder_setFramesPerDataCallback(AAudioStreamBuilder* b, int32_t f) {
  b->frames_per_callback = f;
}

aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder* b, AAudioStream** out) {
  *out = nullptr;
  g_fake_aaudio.open_attempts++;
  g_fake_aaudio.requested_sharing_modes.push_back(b->sharing_mode);
  if ((b->sharing_mode == AAUDIO_SHARING_MODE_EXCLUSIVE && g_fake_aaudio.fail_exclusive_open) ||
      (b->sharing_mode == AAUDIO_SHARING_MODE_SHARED && g_fake_aaudio.fail_shared_open)) {
    return AAUDIO_ERROR_INTERNAL;
  }
  auto* stream = new (std::nothrow) AAudioStream();
  if (stream == nullptr) return AAUDIO_ERROR_INTERNAL;
  stream->sharing_mode = b->sharing_mode;
  stream->sample_rate =
      g_fake_aaudio.grant_wrong_format ? b->sample_rate + 1 : b->sample_rate;
  stream->channel_count = b->channel_count;
  stream->state = g_fake_aaudio.state;
  g_fake_aaudio.streams_created++;
  g_fake_aaudio.data_callback = b->data_callback;
  g_fake_aaudio.data_user = b->data_user;
  g_fake_aaudio.error_callback = b->error_callback;
  g_fake_aaudio.error_user = b->error_user;
  g_fake_aaudio.last_stream = stream;
  *out = stream;
  return AAUDIO_OK;
}

void AAudioStreamBuilder_delete(AAudioStreamBuilder* b) { delete b; }

aaudio_result_t AAudioStream_close(AAudioStream* stream) {
  if (stream == nullptr) return AAUDIO_ERROR_INTERNAL;
  stream->closed = true;
  delete stream;
  return AAUDIO_OK;
}

aaudio_format_t AAudioStream_getFormat(AAudioStream*) { return AAUDIO_FORMAT_PCM_FLOAT; }
int32_t AAudioStream_getChannelCount(AAudioStream* s) { return s->channel_count; }
int32_t AAudioStream_getSampleRate(AAudioStream* s) { return s->sample_rate; }
aaudio_stream_state_t AAudioStream_getState(AAudioStream* s) { return s->state; }
int32_t AAudioStream_getXRunCount(AAudioStream*) { return g_fake_aaudio.xrun_count; }
int32_t AAudioStream_getBufferCapacityInFrames(AAudioStream*) { return g_fake_aaudio.buffer_capacity; }
int32_t AAudioStream_getFramesPerDataCallback(AAudioStream*) {
  return g_fake_aaudio.frames_per_callback;
}
aaudio_sharing_mode_t AAudioStream_getSharingMode(AAudioStream* s) { return s->sharing_mode; }
aaudio_performance_mode_t AAudioStream_getPerformanceMode(AAudioStream*) {
  return AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
}
int64_t AAudioStream_getFramesRead(AAudioStream*) { return g_fake_aaudio.frames_read; }

aaudio_result_t AAudioStream_getTimestamp(AAudioStream*, clockid_t, int64_t* frame_position,
                                          int64_t* timestamp_ns) {
  if (!g_fake_aaudio.timestamp_available) return AAUDIO_ERROR_UNSPECIFIED;
  *frame_position = g_fake_aaudio.timestamp_position;
  *timestamp_ns = g_fake_aaudio.timestamp_ns;
  return AAUDIO_OK;
}

aaudio_result_t AAudioStream_requestStart(AAudioStream* s) {
  s->state = AAUDIO_STREAM_STATE_STARTED;
  return AAUDIO_OK;
}
aaudio_result_t AAudioStream_requestPause(AAudioStream* s) {
  s->state = AAUDIO_STREAM_STATE_PAUSED;
  return AAUDIO_OK;
}
aaudio_result_t AAudioStream_requestFlush(AAudioStream* s) {
  s->state = AAUDIO_STREAM_STATE_FLUSHED;
  return AAUDIO_OK;
}
aaudio_result_t AAudioStream_waitForStateChange(AAudioStream* s, aaudio_stream_state_t,
                                                aaudio_stream_state_t* next, int64_t) {
  *next = s->state;  // fakes transition instantly
  return AAUDIO_OK;
}

const char* AAudio_convertResultToText(aaudio_result_t result) {
  switch (result) {
    case AAUDIO_OK: return "AAUDIO_OK";
    case AAUDIO_ERROR_DISCONNECTED: return "AAUDIO_ERROR_DISCONNECTED";
    case AAUDIO_ERROR_TIMEOUT: return "AAUDIO_ERROR_TIMEOUT";
    default: return "AAUDIO_ERROR";
  }
}

// ── Fake OpenSL ES ──────────────────────────────────────────────────────

const SLuint32 SL_IID_ENGINE_[] = {SL_IID_ENGINE_ID};
const SLuint32 SL_IID_PLAY_[] = {SL_IID_PLAY_ID};
const SLuint32 SL_IID_ANDROIDSIMPLEBUFFERQUEUE_[] = {SL_ANDROID_IID_ANDROIDSIMPLEBUFFERQUEUE_ID};

FakeOpenSlState g_fake_sles;

static SLresult fakeObjectRealize(SLObjectItf, SLboolean) { return SL_RESULT_SUCCESS; }
static void fakeObjectDestroy(SLObjectItf) {}

static SLresult fakeEngineCreateOutputMix(SLEngineItf, SLObjectItf* pMix, SLuint32,
                                          const SLInterfaceID*, const SLboolean*) {
  static SLObjectItf_ mix_vtbl = {fakeObjectRealize, nullptr, fakeObjectDestroy};
  static SLObjectItf_ mix = {&mix_vtbl};
  *pMix = &mix;
  return SL_RESULT_SUCCESS;
}

static SLresult fakeQueueRegisterCallback(SLAndroidSimpleBufferQueueItf self,
                                          slAndroidSimpleBufferQueueCallback cb, void* ctx) {
  g_fake_sles.callback = cb;
  g_fake_sles.callback_context = ctx;
  g_fake_sles.queue_itf = self;
  return SL_RESULT_SUCCESS;
}
static SLresult fakeQueueEnqueue(SLAndroidSimpleBufferQueueItf, const void* buffer, SLuint32 size) {
  FakeEnqueue entry;
  entry.size = size;
  const auto* bytes = static_cast<const uint8_t*>(buffer);
  entry.bytes.assign(bytes, bytes + size);
  g_fake_sles.enqueued.push_back(std::move(entry));
  g_fake_sles.enqueue_calls++;
  return SL_RESULT_SUCCESS;
}
static SLresult fakeQueueClear(SLAndroidSimpleBufferQueueItf) {
  g_fake_sles.clear_calls++;
  return SL_RESULT_SUCCESS;
}

static SLresult fakePlayerGetInterface(SLObjectItf, SLInterfaceID iid, void* out);
static SLresult fakeEngineCreateAudioPlayer(SLEngineItf, SLObjectItf* pPlayer, const void*,
                                            const void*, SLuint32, const SLInterfaceID*,
                                            const SLboolean*) {
  if (g_fake_sles.fail_create_player) return SL_RESULT_RESOURCE_ERROR;
  static SLObjectItf_ player_vtbl = {fakeObjectRealize, fakePlayerGetInterface, fakeObjectDestroy};
  static SLObjectItf_ player = {&player_vtbl};
  *pPlayer = &player;
  return SL_RESULT_SUCCESS;
}
static SLresult fakeEngineGetInterface(SLObjectItf, SLInterfaceID, void* out) {
  static SLEngineItf_ engine_vtbl = {fakeEngineCreateOutputMix, fakeEngineCreateAudioPlayer};
  static SLEngineItf_ engine = {&engine_vtbl};
  *static_cast<SLEngineItf*>(out) = &engine;
  return SL_RESULT_SUCCESS;
}

static SLresult fakePlayerSetPlayState(SLPlayItf, SLuint32 state) {
  g_fake_sles.play_state = state;
  return SL_RESULT_SUCCESS;
}
static SLresult fakePlayerGetPlayState(SLPlayItf, SLuint32* state) {
  *state = g_fake_sles.play_state;
  return SL_RESULT_SUCCESS;
}
static SLresult fakePlayerGetInterface(SLObjectItf, SLInterfaceID iid, void* out) {
  static SLPlayItf_ play_vtbl = {fakePlayerSetPlayState, fakePlayerGetPlayState};
  static SLPlayItf_ play = {&play_vtbl};
  static SLAndroidSimpleBufferQueueItf_ queue_vtbl = {fakeQueueRegisterCallback, fakeQueueEnqueue,
                                                       fakeQueueClear};
  static SLAndroidSimpleBufferQueueItf_ queue = {&queue_vtbl};
  if (iid != nullptr && *iid == SL_IID_PLAY_ID) {
    *static_cast<SLPlayItf*>(out) = &play;
  } else {
    *static_cast<SLAndroidSimpleBufferQueueItf*>(out) = &queue;
  }
  return SL_RESULT_SUCCESS;
}

SLresult slCreateEngine(SLObjectItf* pEngine, SLuint32, const SLEngineOption*, SLuint32,
                        const SLInterfaceID*, const SLboolean*) {
  if (g_fake_sles.fail_create_engine) return SL_RESULT_RESOURCE_ERROR;
  static SLObjectItf_ engine_obj_vtbl = {fakeObjectRealize, fakeEngineGetInterface,
                                         fakeObjectDestroy};
  static SLObjectItf_ engine_obj = {&engine_obj_vtbl};
  *pEngine = &engine_obj;
  return SL_RESULT_SUCCESS;
}

void FakeOpenSlState::completeOneBuffer() {
  if (callback != nullptr && queue_itf != nullptr) {
    callback(queue_itf, callback_context);
  }
}
