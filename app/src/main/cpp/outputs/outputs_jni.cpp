#include <jni.h>

#include <cstdint>
#include <memory>
#include <new>
#include <string>

#include "aaudio_output.h"
#include "opensles_output.h"
#include "output_common.h"

namespace {

using bitchord::audio::AAudioOutput;
using bitchord::audio::NativeTelemetry;
using bitchord::audio::OpenSlEsOutput;
using bitchord::audio::OutputConfig;

constexpr const char* kIllegalArgument = "java/lang/IllegalArgumentException";
constexpr const char* kIllegalState = "java/lang/IllegalStateException";

template <typename T>
T* fromHandle(jlong handle) {
  return reinterpret_cast<T*>(static_cast<intptr_t>(handle));
}

OutputConfig readConfig(jint sample_rate, jint channel_count,
                        jint ring_capacity_frames, jint buffer_size_frames,
                        jint device_id) {
  return OutputConfig{sample_rate, channel_count, ring_capacity_frames,
                      buffer_size_frames, device_id};
}

bool validateConfig(JNIEnv* env, const OutputConfig& config) {
  if (bitchord::audio::IsValidConfig(config)) return true;
  env->ThrowNew(env->FindClass(kIllegalArgument), "Invalid native float PCM output config");
  return false;
}

template <typename T>
jlong openOutput(JNIEnv* env, const OutputConfig& config) {
  if (!validateConfig(env, config)) return 0;
  try {
    auto output = std::make_unique<T>(config);
    output->open();
    return static_cast<jlong>(reinterpret_cast<intptr_t>(output.release()));
  } catch (const std::bad_alloc&) {
    env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                  "Could not preallocate native audio output");
  } catch (...) {
    env->ThrowNew(env->FindClass(kIllegalState),
                  "Could not initialize native audio output");
  }
  return 0;
}

template <typename T>
jint writeOutput(JNIEnv* env, jlong handle, jobject buffer, jint position,
                 jint byte_count) {
  T* output = fromHandle<T>(handle);
  if (output == nullptr || byte_count == 0) return 0;
  auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
  const jlong capacity = env->GetDirectBufferCapacity(buffer);
  const int64_t end = static_cast<int64_t>(position) + byte_count;
  if (base == nullptr || capacity < 0 || position < 0 || byte_count < 0 ||
      end > capacity || byte_count % static_cast<jint>(output->frameBytes()) != 0) {
    env->ThrowNew(env->FindClass(kIllegalArgument),
                  "Expected an in-bounds direct buffer of complete float PCM frames");
    return 0;
  }
  return static_cast<jint>(output->write(
      base + position, static_cast<size_t>(byte_count) / output->frameBytes()));
}

template <typename T>
jlongArray telemetryArray(JNIEnv* env, T* output) {
  if (output == nullptr) return nullptr;
  const NativeTelemetry telemetry = output->telemetry();
  int64_t values[bitchord::audio::kTelemetryValueCount];
  bitchord::audio::WriteTelemetry(telemetry, values);
  jlongArray result = env->NewLongArray(bitchord::audio::kTelemetryValueCount);
  if (result == nullptr) return nullptr;
  jlong jni_values[bitchord::audio::kTelemetryValueCount];
  for (int index = 0; index < bitchord::audio::kTelemetryValueCount; ++index) {
    jni_values[index] = static_cast<jlong>(values[index]);
  }
  env->SetLongArrayRegion(result, 0, bitchord::audio::kTelemetryValueCount,
                          jni_values);
  return result;
}

template <typename T>
jstring errorString(JNIEnv* env, T* output) {
  if (output == nullptr) return nullptr;
  const std::string error = output->lastError();
  return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_audio_AAudioOutputProvider_nativeOpen(
    JNIEnv* env, jobject, jint sample_rate, jint channel_count,
    jint ring_capacity_frames, jint buffer_size_frames, jint device_id) {
  return openOutput<AAudioOutput>(
      env, readConfig(sample_rate, channel_count, ring_capacity_frames,
                      buffer_size_frames, device_id));
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_AAudioOutputProvider_nativeRelease(
    JNIEnv*, jobject, jlong handle) {
  delete fromHandle<AAudioOutput>(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_audio_AAudioOutputProvider_nativeStart(
    JNIEnv*, jobject, jlong handle) {
  AAudioOutput* output = fromHandle<AAudioOutput>(handle);
  return output != nullptr && output->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_audio_AAudioOutputProvider_nativePause(
    JNIEnv*, jobject, jlong handle) {
  AAudioOutput* output = fromHandle<AAudioOutput>(handle);
  return output != nullptr && output->pause() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_audio_AAudioOutputProvider_nativeFlush(
    JNIEnv*, jobject, jlong handle) {
  AAudioOutput* output = fromHandle<AAudioOutput>(handle);
  return output != nullptr && output->flush() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_audio_AAudioOutputProvider_nativeWrite(
    JNIEnv* env, jobject, jlong handle, jobject buffer, jint position,
    jint byte_count) {
  return writeOutput<AAudioOutput>(env, handle, buffer, position, byte_count);
}

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_audio_AAudioOutputProvider_nativePositionFrames(
    JNIEnv*, jobject, jlong handle) {
  AAudioOutput* output = fromHandle<AAudioOutput>(handle);
  return output != nullptr ? output->positionFrames() : -1;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_audio_AAudioOutputProvider_nativeIsOpen(
    JNIEnv*, jobject, jlong handle) {
  AAudioOutput* output = fromHandle<AAudioOutput>(handle);
  return output != nullptr && output->isOpen() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_audio_AAudioOutputProvider_nativeIsPlaying(
    JNIEnv*, jobject, jlong handle) {
  AAudioOutput* output = fromHandle<AAudioOutput>(handle);
  return output != nullptr && output->isPlaying() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_com_music_bitchord_playback_audio_AAudioOutputProvider_nativeTelemetry(
    JNIEnv* env, jobject, jlong handle) {
  return telemetryArray(env, fromHandle<AAudioOutput>(handle));
}

JNIEXPORT jstring JNICALL
Java_com_music_bitchord_playback_audio_AAudioOutputProvider_nativeLastError(
    JNIEnv* env, jobject, jlong handle) {
  return errorString(env, fromHandle<AAudioOutput>(handle));
}

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_audio_OpenSlEsOutputProvider_nativeOpen(
    JNIEnv* env, jobject, jint sample_rate, jint channel_count,
    jint ring_capacity_frames, jint buffer_size_frames, jint device_id) {
  return openOutput<OpenSlEsOutput>(
      env, readConfig(sample_rate, channel_count, ring_capacity_frames,
                      buffer_size_frames, device_id));
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_OpenSlEsOutputProvider_nativeRelease(
    JNIEnv*, jobject, jlong handle) {
  delete fromHandle<OpenSlEsOutput>(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_audio_OpenSlEsOutputProvider_nativeStart(
    JNIEnv*, jobject, jlong handle) {
  OpenSlEsOutput* output = fromHandle<OpenSlEsOutput>(handle);
  return output != nullptr && output->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_audio_OpenSlEsOutputProvider_nativePause(
    JNIEnv*, jobject, jlong handle) {
  OpenSlEsOutput* output = fromHandle<OpenSlEsOutput>(handle);
  return output != nullptr && output->pause() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_audio_OpenSlEsOutputProvider_nativeFlush(
    JNIEnv*, jobject, jlong handle) {
  OpenSlEsOutput* output = fromHandle<OpenSlEsOutput>(handle);
  return output != nullptr && output->flush() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_audio_OpenSlEsOutputProvider_nativeWrite(
    JNIEnv* env, jobject, jlong handle, jobject buffer, jint position,
    jint byte_count) {
  return writeOutput<OpenSlEsOutput>(env, handle, buffer, position, byte_count);
}

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_audio_OpenSlEsOutputProvider_nativePositionFrames(
    JNIEnv*, jobject, jlong handle) {
  OpenSlEsOutput* output = fromHandle<OpenSlEsOutput>(handle);
  return output != nullptr ? output->positionFrames() : -1;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_audio_OpenSlEsOutputProvider_nativeIsOpen(
    JNIEnv*, jobject, jlong handle) {
  OpenSlEsOutput* output = fromHandle<OpenSlEsOutput>(handle);
  return output != nullptr && output->isOpen() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_audio_OpenSlEsOutputProvider_nativeIsPlaying(
    JNIEnv*, jobject, jlong handle) {
  OpenSlEsOutput* output = fromHandle<OpenSlEsOutput>(handle);
  return output != nullptr && output->isPlaying() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_com_music_bitchord_playback_audio_OpenSlEsOutputProvider_nativeTelemetry(
    JNIEnv* env, jobject, jlong handle) {
  return telemetryArray(env, fromHandle<OpenSlEsOutput>(handle));
}

JNIEXPORT jstring JNICALL
Java_com_music_bitchord_playback_audio_OpenSlEsOutputProvider_nativeLastError(
    JNIEnv* env, jobject, jlong handle) {
  return errorString(env, fromHandle<OpenSlEsOutput>(handle));
}

}  // extern "C"
