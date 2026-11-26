// Minimal host-side JNI stub for compiling usb-audio-output.cpp in headless
// tests. Only the declarations used by the driver are provided; every method
// is a no-op because the tests never pass a real JNIEnv (they call the
// non-JNI entry points directly or pass nullptr where env is unused).
#ifndef USB_AUDIO_TEST_STUB_JNI_H_
#define USB_AUDIO_TEST_STUB_JNI_H_

#include <cstdint>

#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0
#define JNI_ABORT 2

typedef int32_t jint;
typedef int64_t jlong;
typedef uint8_t jboolean;
typedef uint8_t jbyte;
typedef float jfloat;
typedef struct _jobject* jobject;
typedef struct _jclass* jclass;
typedef struct _jarray* jarray;
typedef jarray jfloatArray;
typedef jarray jbyteArray;

struct JNIEnv {
  jint GetArrayLength(jarray) { return 0; }
  jfloat* GetFloatArrayElements(jfloatArray, jboolean*) { return nullptr; }
  void ReleaseFloatArrayElements(jfloatArray, jfloat*, jint) {}
  jbyte* GetByteArrayElements(jbyteArray, jboolean*) { return nullptr; }
  void ReleaseByteArrayElements(jbyteArray, jbyte*, jint) {}
};

#endif  // USB_AUDIO_TEST_STUB_JNI_H_
