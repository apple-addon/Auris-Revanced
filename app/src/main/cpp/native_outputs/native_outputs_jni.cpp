#include "aaudio_output.h"
#include "native_output.h"
#include "opensl_output.h"

#include <jni.h>
#include <cstdint>
#include <new>
#include <string>

namespace {

thread_local std::string last_open_error;

auris::NativeOutput *from_handle(jlong handle) {
    return reinterpret_cast<auris::NativeOutput *>(static_cast<intptr_t>(handle));
}

jlong to_handle(auris::NativeOutput *output) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(output));
}

auris::OutputSpec make_spec(
        jint sample_rate, jint channels, jint session_id, jint requested_buffer_frames,
        jint audio_device_id = 0) {
    auris::OutputSpec spec;
    spec.sample_rate = sample_rate;
    spec.channels = channels;
    spec.audio_session_id = session_id;
    spec.requested_buffer_frames = requested_buffer_frames;
    spec.audio_device_id = audio_device_id;
    return spec;
}

jstring open_error(JNIEnv *env) {
    if (last_open_error.empty()) return nullptr;
    jstring result = env->NewStringUTF(last_open_error.c_str());
    last_open_error.clear();
    return result;
}

template<typename Factory>
jlong create_output(Factory factory) {
    try {
        last_open_error.clear();
        auto output = factory(last_open_error);
        return output ? to_handle(output.release()) : 0;
    } catch (const std::exception &error) {
        last_open_error = std::string("Native output open exception: ") + error.what();
        return 0;
    } catch (...) {
        last_open_error = "Unknown native output open exception";
        return 0;
    }
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_output_NativeOutputBridge_nativeCreateAAudio(
        JNIEnv * /*env*/, jobject /*object*/, jint sample_rate, jint channels, jint session_id,
        jint requested_buffer_frames, jint audio_device_id, jboolean request_exclusive) {
    const auto spec = make_spec(
            sample_rate, channels, session_id, requested_buffer_frames, audio_device_id);
    return create_output([&](std::string &error) {
        return auris::AAudioOutput::open(spec, request_exclusive == JNI_TRUE, error);
    });
}

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_output_NativeOutputBridge_nativeCreateOpenSlEs(
        JNIEnv * /*env*/, jobject /*object*/, jint sample_rate, jint channels, jint session_id,
        jint requested_buffer_frames) {
    const auto spec = make_spec(sample_rate, channels, session_id, requested_buffer_frames);
    return create_output([&](std::string &error) {
        return auris::OpenSlEsOutput::open(spec, error);
    });
}

JNIEXPORT jstring JNICALL
Java_com_music_bitchord_playback_output_NativeOutputBridge_nativeConsumeOpenError(
        JNIEnv *env, jobject /*object*/) {
    return open_error(env);
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_output_NativeOutputBridge_nativePlay(
        JNIEnv * /*env*/, jobject /*object*/, jlong handle) {
    auto *output = from_handle(handle);
    return output ? output->play() : auris::kErrorInvalidBuffer;
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_output_NativeOutputBridge_nativePause(
        JNIEnv * /*env*/, jobject /*object*/, jlong handle) {
    auto *output = from_handle(handle);
    return output ? output->pause() : auris::kErrorInvalidBuffer;
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_output_NativeOutputBridge_nativeWrite(
        JNIEnv *env, jobject /*object*/, jlong handle, jobject buffer,
        jint offset_bytes, jint byte_count) {
    auto *output = from_handle(handle);
    if (!output || offset_bytes < 0 || byte_count < 0) return auris::kErrorInvalidArgument;
    if (byte_count == 0) return 0;
    auto *base = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!base || capacity < 0 || offset_bytes > capacity ||
            static_cast<jlong>(byte_count) > capacity - offset_bytes) {
        return auris::kErrorInvalidBuffer;
    }
    return output->write(base + offset_bytes, byte_count);
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_output_NativeOutputBridge_nativeFlush(
        JNIEnv * /*env*/, jobject /*object*/, jlong handle) {
    auto *output = from_handle(handle);
    return output ? output->flush() : auris::kErrorInvalidBuffer;
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_output_NativeOutputBridge_nativeStop(
        JNIEnv * /*env*/, jobject /*object*/, jlong handle) {
    auto *output = from_handle(handle);
    return output ? output->stop() : auris::kErrorInvalidBuffer;
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_output_NativeOutputBridge_nativeSetVolume(
        JNIEnv * /*env*/, jobject /*object*/, jlong handle, jfloat volume) {
    auto *output = from_handle(handle);
    if (!output) return auris::kErrorInvalidBuffer;
    output->set_volume(volume);
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_output_NativeOutputBridge_nativeXrunCount(
        JNIEnv * /*env*/, jobject /*object*/, jlong handle) {
    auto *output = from_handle(handle);
    return output ? output->xrun_count() : auris::kUnknown;
}

JNIEXPORT jstring JNICALL
Java_com_music_bitchord_playback_output_NativeOutputBridge_nativeSnapshot(
        JNIEnv *env, jobject /*object*/, jlong handle, jlongArray values) {
    if (!values || env->GetArrayLength(values) != 10) {
        return env->NewStringUTF("Invalid native telemetry array");
    }
    auto *output = from_handle(handle);
    if (!output) return env->NewStringUTF("Invalid native output handle");
    const auris::OutputSnapshot snapshot = output->snapshot();
    const jlong raw[] = {
            snapshot.sample_rate,
            snapshot.channels,
            snapshot.bits_per_sample,
            snapshot.buffer_size_frames,
            snapshot.buffer_capacity_frames,
            snapshot.xruns,
            snapshot.output_frames,
            snapshot.position_us,
            snapshot.sharing,
            snapshot.audio_session_id,
    };
    env->SetLongArrayRegion(values, 0, 10, raw);
    return snapshot.failure.empty() ? nullptr : env->NewStringUTF(snapshot.failure.c_str());
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_output_NativeOutputBridge_nativeRelease(
        JNIEnv * /*env*/, jobject /*object*/, jlong handle) {
    delete from_handle(handle);
}

} // extern "C"
