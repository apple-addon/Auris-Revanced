// Host-side stub of <SLES/OpenSLES_Android.h> for headless tests.
#ifndef BITCHORD_TEST_STUB_OPENSLES_ANDROID_H_
#define BITCHORD_TEST_STUB_OPENSLES_ANDROID_H_

#include "OpenSLES.h"

#define SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE 0x800007BD
#define SL_ANDROID_DATAFORMAT_PCM_EX 0x00000005
#define SL_ANDROID_SPEAKER_USE_DEFAULT 0x00000000
#define SL_ANDROID_PCM_REPRESENTATION_FLOAT 0x00000003

#define SL_ANDROID_IID_ANDROIDSIMPLEBUFFERQUEUE_ID 0x10000003
extern const SLuint32 SL_IID_ANDROIDSIMPLEBUFFERQUEUE_[];
#define SL_IID_ANDROIDSIMPLEBUFFERQUEUE SL_IID_ANDROIDSIMPLEBUFFERQUEUE_

typedef struct SLDataLocator_AndroidSimpleBufferQueue {
  SLuint32 locatorType;
  SLuint32 numBuffers;
} SLDataLocator_AndroidSimpleBufferQueue;

typedef struct SLAndroidDataFormat_PCM_EX {
  SLuint32 formatType;
  SLuint32 numChannels;
  SLuint32 samplesPerSec;
  SLuint32 bitsPerSample;
  SLuint32 containerSize;
  SLuint32 channelMask;
  SLuint32 endianness;
  SLuint32 representation;
} SLAndroidDataFormat_PCM_EX;

typedef struct SLAndroidSimpleBufferQueueItf_ SLAndroidSimpleBufferQueueItf_;
typedef const SLAndroidSimpleBufferQueueItf_* const* SLAndroidSimpleBufferQueueItf;
typedef void (SLAPIENTRY* slAndroidSimpleBufferQueueCallback)(
    SLAndroidSimpleBufferQueueItf caller, void* pContext);
struct SLAndroidSimpleBufferQueueItf_ {
  SLresult (*RegisterCallback)(SLAndroidSimpleBufferQueueItf self,
                               slAndroidSimpleBufferQueueCallback callback, void* pContext);
  SLresult (*Enqueue)(SLAndroidSimpleBufferQueueItf self, const void* pBuffer, SLuint32 size);
  SLresult (*Clear)(SLAndroidSimpleBufferQueueItf self);
};

#endif  // BITCHORD_TEST_STUB_OPENSLES_ANDROID_H_
