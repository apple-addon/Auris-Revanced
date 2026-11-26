#include <jni.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <new>

#include "AurisDspEngine.h"

namespace {

aurisdsp::Engine* fromHandle(jlong handle) noexcept {
    return reinterpret_cast<aurisdsp::Engine*>(static_cast<std::intptr_t>(handle));
}

jlong toHandle(aurisdsp::Engine* engine) noexcept {
    return static_cast<jlong>(reinterpret_cast<std::intptr_t>(engine));
}

}  // namespace

// Kotlin declaration: com.music.bitchord.playback.audio.AurisDspNative.
// The same exports work for an object or a class with native methods.
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_audio_AurisDspNative_nativeCreate(
    JNIEnv*, jobject) {
    return toHandle(new (std::nothrow) aurisdsp::Engine());
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_audio_AurisDspNative_nativeConfigure(
    JNIEnv*, jobject, jlong handle, jint sampleRate, jint channels, jint maxFrames) {
    aurisdsp::Engine* engine = fromHandle(handle);
    if (engine == nullptr) return JNI_FALSE;
    return engine->configure(sampleRate, channels, maxFrames) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_AurisDspNative_nativeProcess(
    JNIEnv* env, jobject, jlong handle, jobject directFloatBuffer, jint frames) {
    aurisdsp::Engine* engine = fromHandle(handle);
    if (engine == nullptr || directFloatBuffer == nullptr || frames < 0) return;
    if (frames == 0) return;
    void* address = env->GetDirectBufferAddress(directFloatBuffer);
    const jlong capacity = env->GetDirectBufferCapacity(directFloatBuffer);
    if (address == nullptr || capacity < 0 || engine->channels() <= 0) return;

    const std::size_t bytesPerFrame = static_cast<std::size_t>(engine->channels()) * sizeof(float);
    const std::size_t frameCount = static_cast<std::size_t>(frames);
    if (frameCount > static_cast<std::size_t>(capacity) / bytesPerFrame) return;

    // A zero-position direct FloatBuffer view is required. No JNI attachment,
    // array access, object allocation, lock, or heap allocation occurs here.
    engine->process(static_cast<float*>(address), frameCount);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_AurisDspNative_nativeSetParams(
    JNIEnv* env, jobject, jlong handle, jfloatArray values) {
    aurisdsp::Engine* engine = fromHandle(handle);
    if (engine == nullptr || values == nullptr) return;
    if (env->GetArrayLength(values) != static_cast<jsize>(aurisdsp::kParamCount)) return;
    std::array<float, aurisdsp::kParamCount> snapshot{};
    env->GetFloatArrayRegion(values, 0, static_cast<jsize>(snapshot.size()), snapshot.data());
    if (env->ExceptionCheck()) return;
    engine->setParams(snapshot.data(), snapshot.size());
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_AurisDspNative_nativeReset(
    JNIEnv*, jobject, jlong handle) {
    aurisdsp::Engine* engine = fromHandle(handle);
    if (engine != nullptr) engine->reset();
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_AurisDspNative_nativeDestroy(
    JNIEnv*, jobject, jlong handle) {
    delete fromHandle(handle);
}

JNIEXPORT jstring JNICALL
Java_com_music_bitchord_playback_audio_AurisDspNative_nativeGetVersionHash(
    JNIEnv* env, jobject) {
    return env->NewStringUTF(aurisdsp::versionHash());
}

}  // extern "C"
