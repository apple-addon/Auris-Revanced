// Host-side stub of <SLES/OpenSLES.h> declaring exactly the API surface used
// by outputs/opensles_output.cpp. Implemented by fake_platform.cpp.
#ifndef BITCHORD_TEST_STUB_OPENSLES_H_
#define BITCHORD_TEST_STUB_OPENSLES_H_

#include <cstdint>

#define SLAPIENTRY

typedef uint32_t SLuint32;
typedef int32_t SLint32;
typedef uint32_t SLresult;
typedef SLuint32 SLboolean;
typedef SLuint32 SLmillibel;
typedef const SLuint32* SLInterfaceID;

#define SL_RESULT_SUCCESS 0x00000000
#define SL_RESULT_PRECONDITIONS_VIOLATED 0x00000001
#define SL_RESULT_PARAMETER_INVALID 0x00000002
#define SL_RESULT_MEMORY_FAILURE 0x00000003
#define SL_RESULT_RESOURCE_ERROR 0x00000004
#define SL_RESULT_RESOURCE_LOST 0x00000005
#define SL_RESULT_IO_ERROR 0x00000006
#define SL_RESULT_BUFFER_INSUFFICIENT 0x00000007
#define SL_RESULT_CONTENT_CORRUPTED 0x00000008
#define SL_RESULT_CONTENT_UNSUPPORTED 0x00000009
#define SL_RESULT_CONTENT_NOT_FOUND 0x0000000A
#define SL_RESULT_PERMISSION_DENIED 0x0000000B
#define SL_RESULT_FEATURE_UNSUPPORTED 0x0000000C
#define SL_RESULT_INTERNAL_ERROR 0x0000000D
#define SL_RESULT_UNKNOWN_ERROR 0x0000000E
#define SL_RESULT_OPERATION_ABORTED 0x0000000F
#define SL_RESULT_CONTROL_LOST 0x00000010

#define SL_BOOLEAN_FALSE 0
#define SL_BOOLEAN_TRUE 1

#define SL_ENGINEOPTION_THREADSAFE 0x00000001

typedef struct SLEngineOption {
  SLuint32 feature;
  SLuint32 data;
} SLEngineOption;

#define SL_PLAYSTATE_STOPPED 0x00000001
#define SL_PLAYSTATE_PAUSED 0x00000002
#define SL_PLAYSTATE_PLAYING 0x00000003

#define SL_PCMSAMPLEFORMAT_FIXED_16 0x0002
#define SL_PCMSAMPLEFORMAT_FIXED_32 0x0004
#define SL_BYTEORDER_LITTLEENDIAN 0x0001
#define SL_BYTEORDER_BIGENDIAN 0x0002

#define SL_DATALOCATOR_OUTPUTMIX 0x00000004

#define SL_IID_ENGINE_ID 0x10000001
extern const SLuint32 SL_IID_ENGINE_[];
#define SL_IID_ENGINE SL_IID_ENGINE_
#define SL_IID_PLAY_ID 0x10000002
extern const SLuint32 SL_IID_PLAY_[];
#define SL_IID_PLAY SL_IID_PLAY_

typedef struct SLObjectItf_ SLObjectItf_;
typedef const SLObjectItf_* const* SLObjectItf;
struct SLObjectItf_ {
  SLresult (*Realize)(SLObjectItf self, SLboolean async);
  SLresult (*GetInterface)(SLObjectItf self, SLInterfaceID iid, void* pInterface);
  void (*Destroy)(SLObjectItf self);
};

typedef struct SLEngineItf_ SLEngineItf_;
typedef const SLEngineItf_* const* SLEngineItf;
struct SLEngineItf_ {
  SLresult (*CreateOutputMix)(SLEngineItf self, SLObjectItf* pMix, SLuint32 numInterfaces,
                              const SLInterfaceID* pInterfaceIds, const SLboolean* pRequired);
  SLresult (*CreateAudioPlayer)(SLEngineItf self, SLObjectItf* pPlayer, const void* pAudioSrc,
                                const void* pAudioSnk, SLuint32 numInterfaces,
                                const SLInterfaceID* pInterfaceIds, const SLboolean* pRequired);
};

typedef struct SLPlayItf_ SLPlayItf_;
typedef const SLPlayItf_* const* SLPlayItf;
struct SLPlayItf_ {
  SLresult (*SetPlayState)(SLPlayItf self, SLuint32 state);
  SLresult (*GetPlayState)(SLPlayItf self, SLuint32* pState);
};

typedef struct SLDataSource {
  void* pLocator;
  void* pFormat;
} SLDataSource;
typedef struct SLDataSink {
  void* pLocator;
  void* pFormat;
} SLDataSink;
typedef struct SLDataLocator_OutputMix {
  SLuint32 locatorType;
  SLObjectItf outputMix;
} SLDataLocator_OutputMix;

#ifdef __cplusplus
extern "C" {
#endif
SLresult slCreateEngine(SLObjectItf* pEngine, SLuint32 numOptions, const SLEngineOption* pOptions,
                        SLuint32 numInterfaces, const SLInterfaceID* pInterfaceIds,
                        const SLboolean* pInterfaceRequired);
#ifdef __cplusplus
}
#endif

#endif  // BITCHORD_TEST_STUB_OPENSLES_H_
