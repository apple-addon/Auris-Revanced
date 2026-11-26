// Host-side stub of <aaudio/AAudio.h> declaring exactly the API surface used
// by outputs/aaudio_output.cpp. Implemented by fake_platform.cpp with a
// controllable fake stream.
#ifndef BITCHORD_TEST_STUB_AAUDIO_H_
#define BITCHORD_TEST_STUB_AAUDIO_H_

#include <cstdint>
#include <time.h>

typedef int32_t aaudio_result_t;
typedef int32_t aaudio_format_t;
typedef int32_t aaudio_direction_t;
typedef int32_t aaudio_performance_mode_t;
typedef int32_t aaudio_sharing_mode_t;
typedef int32_t aaudio_stream_state_t;
typedef int32_t aaudio_data_callback_result_t;

#define AAUDIO_OK 0
#define AAUDIO_ERROR_UNSPECIFIED -1
#define AAUDIO_ERROR_INTERNAL -895
#define AAUDIO_ERROR_INVALID_FORMAT -897
#define AAUDIO_ERROR_TIMEOUT -898
#define AAUDIO_ERROR_DISCONNECTED -899

#define AAUDIO_FORMAT_PCM_FLOAT 4
#define AAUDIO_DIRECTION_OUTPUT 1
#define AAUDIO_PERFORMANCE_MODE_LOW_LATENCY 12
#define AAUDIO_SHARING_MODE_EXCLUSIVE 0
#define AAUDIO_SHARING_MODE_SHARED 1
#define AAUDIO_UNSPECIFIED 0

#define AAUDIO_STREAM_STATE_OPEN 3
#define AAUDIO_STREAM_STATE_STARTED 5
#define AAUDIO_STREAM_STATE_PAUSED 6
#define AAUDIO_STREAM_STATE_STOPPED 7
#define AAUDIO_STREAM_STATE_FLUSHED 9

#define AAUDIO_CALLBACK_RESULT_CONTINUE 0
#define AAUDIO_CALLBACK_RESULT_STOP 1

struct AAudioStream;
struct AAudioStreamBuilder;
typedef struct AAudioStream AAudioStream;
typedef struct AAudioStreamBuilder AAudioStreamBuilder;

typedef aaudio_data_callback_result_t (*AAudioStream_dataCallback)(
    AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);
typedef void (*AAudioStream_errorCallback)(AAudioStream* stream, void* userData,
                                           aaudio_result_t error);

#ifdef __cplusplus
extern "C" {
#endif

aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder** builder);
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder*, aaudio_direction_t);
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder*, aaudio_format_t);
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder*, int32_t);
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder*, int32_t);
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder*, aaudio_sharing_mode_t);
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder*, aaudio_performance_mode_t);
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder*, AAudioStream_dataCallback, void*);
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder*, AAudioStream_errorCallback, void*);
void AAudioStreamBuilder_setDeviceId(AAudioStreamBuilder*, int32_t);
void AAudioStreamBuilder_setFramesPerDataCallback(AAudioStreamBuilder*, int32_t);
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder*, AAudioStream**);
void AAudioStreamBuilder_delete(AAudioStreamBuilder*);

aaudio_result_t AAudioStream_close(AAudioStream*);
aaudio_format_t AAudioStream_getFormat(AAudioStream*);
int32_t AAudioStream_getChannelCount(AAudioStream*);
int32_t AAudioStream_getSampleRate(AAudioStream*);
aaudio_stream_state_t AAudioStream_getState(AAudioStream*);
int32_t AAudioStream_getXRunCount(AAudioStream*);
int32_t AAudioStream_getBufferCapacityInFrames(AAudioStream*);
int32_t AAudioStream_getFramesPerDataCallback(AAudioStream*);
aaudio_sharing_mode_t AAudioStream_getSharingMode(AAudioStream*);
aaudio_performance_mode_t AAudioStream_getPerformanceMode(AAudioStream*);
int64_t AAudioStream_getFramesRead(AAudioStream*);
aaudio_result_t AAudioStream_getTimestamp(AAudioStream*, clockid_t, int64_t* framePosition,
                                          int64_t* timestampNanoseconds);
aaudio_result_t AAudioStream_requestStart(AAudioStream*);
aaudio_result_t AAudioStream_requestPause(AAudioStream*);
aaudio_result_t AAudioStream_requestFlush(AAudioStream*);
aaudio_result_t AAudioStream_waitForStateChange(AAudioStream*, aaudio_stream_state_t,
                                                aaudio_stream_state_t* nextState,
                                                int64_t timeoutNanoseconds);
const char* AAudio_convertResultToText(aaudio_result_t);

#ifdef __cplusplus
}
#endif

#endif  // BITCHORD_TEST_STUB_AAUDIO_H_
