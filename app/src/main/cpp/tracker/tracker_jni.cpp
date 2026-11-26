#include "tracker_decoder.h"

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <limits>

namespace {

using bitchord::tracker::Decoder;

Decoder* decoder(jlong handle) {
    return reinterpret_cast<Decoder*>(handle);
}

std::uint8_t* directBytes(JNIEnv* env, jobject buffer, jint offset, jlong* capacity) {
    if (buffer == nullptr || offset < 0) {
        return nullptr;
    }
    const jlong buffer_capacity = env->GetDirectBufferCapacity(buffer);
    auto* address = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (address == nullptr || buffer_capacity < 0 || offset > buffer_capacity) {
        return nullptr;
    }
    *capacity = buffer_capacity;
    return address + offset;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_tracker_TrackerDecoder_nativeCreate(
    JNIEnv* env, jobject, jobject input, jint offset, jint length) {
    jlong capacity = 0;
    std::uint8_t* bytes = directBytes(env, input, offset, &capacity);
    if (bytes == nullptr || length <= 0 || static_cast<jlong>(length) > capacity - offset) {
        return 0;
    }
    return reinterpret_cast<jlong>(Decoder::createFromMemory(bytes, static_cast<std::size_t>(length)).release());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_tracker_TrackerDecoder_nativeDurationUs(
    JNIEnv*, jobject, jlong handle) {
    auto* instance = decoder(handle);
    return instance == nullptr ? bitchord::tracker::kTimeUnset : instance->durationUs();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_tracker_TrackerDecoder_nativeRead(
    JNIEnv* env, jobject, jlong handle, jobject output, jint offset, jint frame_count) {
    auto* instance = decoder(handle);
    jlong capacity = 0;
    std::uint8_t* bytes = directBytes(env, output, offset, &capacity);
    if (instance == nullptr || bytes == nullptr || frame_count < 0 || (offset & 3) != 0) {
        return -1;
    }
    if (frame_count == 0) {
        return 0;
    }
    const jlong available = capacity - offset;
    if (static_cast<jlong>(frame_count) > available / bitchord::tracker::kBytesPerFrame) {
        return -1;
    }
    const std::size_t produced = instance->read(
        reinterpret_cast<float*>(bytes), static_cast<std::size_t>(frame_count));
    if (produced > static_cast<std::size_t>(std::numeric_limits<jint>::max())) {
        return -1;
    }
    return static_cast<jint>(produced);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_tracker_TrackerDecoder_nativeSeek(
    JNIEnv*, jobject, jlong handle, jlong time_us) {
    auto* instance = decoder(handle);
    if (instance == nullptr) {
        return -1;
    }
    instance->seekSeconds(time_us <= 0 ? 0.0 : static_cast<double>(time_us) / 1'000'000.0);
    return instance->positionFrame();
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_tracker_TrackerDecoder_nativeReset(
    JNIEnv*, jobject, jlong handle) {
    if (auto* instance = decoder(handle)) {
        instance->reset();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_tracker_TrackerDecoder_nativeDestroy(
    JNIEnv*, jobject, jlong handle) {
    delete decoder(handle);
}
