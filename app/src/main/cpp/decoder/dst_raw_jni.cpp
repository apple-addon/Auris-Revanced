// Raw DST -> DSD JNI for SACD ISO playback. Add this source to the existing
// bitchord_dst target, which already compiles dsd/dst/decoder.c.
#include <jni.h>
#include <cstdint>
#include <new>
#include <vector>
#include "../dsd/dst/decoder.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_decoder_NativeRawDstDecoder_create(JNIEnv*, jobject, jint channels) {
    if (channels < 1 || channels > 6) return 0;
    dst_decoder_t* decoder = nullptr;
    if (dst_decoder_init(&decoder, channels, 2822400) != 0) return 0;
    return reinterpret_cast<jlong>(decoder);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_decoder_NativeRawDstDecoder_decode(JNIEnv* env, jobject, jlong handle,
        jbyteArray input, jbyteArray output) {
    if (!handle || !input || !output) return -1;
    const int size = env->GetArrayLength(input);
    const int capacity = env->GetArrayLength(output);
    if (size < 2 || size > 65536 || capacity < 4704 || capacity > 28224 || capacity % 4704) return -1;
    jbyte* data = env->GetByteArrayElements(input, nullptr);
    if (!data) return -1;
    try {
        std::vector<uint8_t> decoded(capacity);
        int count = capacity;
        const int status = dst_decoder_decode(reinterpret_cast<dst_decoder_t*>(handle),
            reinterpret_cast<uint8_t*>(data), size, decoded.data(), &count);
        env->ReleaseByteArrayElements(input, data, JNI_ABORT);
        if (status != 0) return status;
        if (count != capacity) return -1;
        env->SetByteArrayRegion(output, 0, count, reinterpret_cast<jbyte*>(decoded.data()));
        return env->ExceptionCheck() ? -1 : count;
    } catch (const std::bad_alloc&) {
        env->ReleaseByteArrayElements(input, data, JNI_ABORT);
        return -100;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_decoder_NativeRawDstDecoder_destroy(JNIEnv*, jobject, jlong handle) {
    dst_decoder_close(reinterpret_cast<dst_decoder_t*>(handle));
}
