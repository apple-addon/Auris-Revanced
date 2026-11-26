// Host-side JNI stub for headless tests of the outputs JNI layer. JNIEnv is a
// plain struct whose methods forward to the fake state in fake_platform.cpp,
// so tests can inspect thrown exceptions, direct-buffer bounds and the
// contents of telemetry arrays.
#ifndef BITCHORD_TEST_STUB_JNI_H_
#define BITCHORD_TEST_STUB_JNI_H_

#include <cstdint>
#include <string>
#include <vector>

#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0

typedef int32_t jint;
typedef int64_t jlong;
typedef uint8_t jboolean;
typedef float jfloat;

struct _jobject {
  virtual ~_jobject() = default;
};
typedef _jobject* jobject;
typedef _jobject* jclass;
typedef _jobject* jstring;
typedef _jobject* jarray;
typedef jarray jlongArray;

// Fake objects the tests allocate to play the role of Java objects.
struct FakeDirectBuffer : _jobject {
  void* address = nullptr;
  jlong capacity = 0;
};
struct FakeLongArray : _jobject {
  std::vector<jlong> values;
};
struct FakeString : _jobject {
  std::string text;
};

// Recorded pending exception (tests assert on this after JNI calls).
struct PendingException {
  bool thrown = false;
  std::string class_name;
  std::string message;
};
extern PendingException g_pendingException;
void resetPendingException();

struct JNIEnv {
  jclass FindClass(const char* name);
  jint ThrowNew(jclass clazz, const char* message);
  void* GetDirectBufferAddress(jobject buffer);
  jlong GetDirectBufferCapacity(jobject buffer);
  jlongArray NewLongArray(jint length);
  void SetLongArrayRegion(jlongArray array, jint start, jint length, const jlong* values);
  jstring NewStringUTF(const char* bytes);
};

#endif  // BITCHORD_TEST_STUB_JNI_H_
