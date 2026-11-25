#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr const char *TAG = "AurisNativeBridge";
std::atomic<jbyteArray> g_pcm_array{nullptr};
std::atomic<jint> g_pcm_capacity{0};

void *open_engine(const char *name) {
    dlerror();
    return dlopen(name, RTLD_NOW | RTLD_LOCAL);
}

template<typename T>
T symbol(void *handle, const char *name) {
    dlerror();
    void *address = dlsym(handle, name);
    if (!address) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "dlsym(%s): %s", name, dlerror());
    }
    return reinterpret_cast<T>(address);
}

jstring probe(JNIEnv *env, const char *library, const std::vector<const char *> &symbols) {
    void *handle = open_engine(library);
    if (!handle) {
        const char *error = dlerror();
        return env->NewStringUTF(error ? error : "dlopen failed");
    }
    for (const char *name : symbols) {
        if (!dlsym(handle, name)) {
            std::string message = std::string("dlsym missing ") + name;
            return env->NewStringUTF(message.c_str());
        }
    }
    return env->NewStringUTF("OK");
}

using CreateTrack = jint (*)(JNIEnv *, jclass, jint, jint, jint, jint);
using WriteTrack = jint (*)(JNIEnv *, jclass, jbyteArray, jint);
using IntCall = jint (*)(JNIEnv *, jclass);
using ConsumedCall = jlong (*)(JNIEnv *, jclass, jlongArray);

CreateTrack create_track() {
    static auto fn = symbol<CreateTrack>(open_engine("libhibyservice.so"),
            "Java_com_hiby_music_sdk_util_OtHiByTrackUtils_createTrack");
    return fn;
}

WriteTrack write_track() {
    static auto fn = symbol<WriteTrack>(open_engine("libhibyservice.so"),
            "Java_com_hiby_music_sdk_util_OtHiByTrackUtils_write");
    return fn;
}

IntCall int_call(const char *name) {
    return symbol<IntCall>(open_engine("libhibyservice.so"), name);
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativeProbe(
        JNIEnv *env, jobject, jstring engine) {
    const char *name = env->GetStringUTFChars(engine, nullptr);
    if (!name) return nullptr;
    jstring result;
    if (std::strcmp(name, "auriscore") == 0) {
        result = probe(env, "libauriscore.so", {
                "JNI_OnLoad", "pa_register_dsp", "pa_register_output", "pa_alloc_plugin_context"});
    } else if (std::strcmp(name, "hibyservice") == 0) {
        result = probe(env, "libhibyservice.so", {
                "Java_com_hiby_music_sdk_util_OtHiByTrackUtils_createTrack",
                "Java_com_hiby_music_sdk_util_OtHiByTrackUtils_write",
                "Java_com_hiby_music_sdk_util_DspUtil_OnDspAdd",
                "Java_com_hiby_music_sdk_util_DspUtil_SetDspInfoFloat"});
    } else {
        result = env->NewStringUTF("Unknown native engine probe");
    }
    env->ReleaseStringUTFChars(engine, name);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativePrepareHibyBuffer(
        JNIEnv *env, jobject, jint capacity) {
    if (capacity <= 0) return -1;
    jbyteArray old = g_pcm_array.exchange(nullptr);
    if (old) env->DeleteGlobalRef(old);
    g_pcm_capacity.store(0);
    jbyteArray local = env->NewByteArray(capacity);
    if (!local) return -2;
    jbyteArray global = static_cast<jbyteArray>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!global) return -3;
    g_pcm_array.store(global);
    g_pcm_capacity.store(capacity);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativeCreateHibyTrack(
        JNIEnv *env, jobject, jint sample_rate, jint channels, jint sample_bits, jint format_type) {
    auto fn = create_track();
    return fn ? fn(env, nullptr, sample_rate, channels, sample_bits, format_type) : -1001;
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativeWriteHibyPcm(
        JNIEnv *env, jobject, jobject buffer, jint byte_count) {
    if (byte_count < 0 || byte_count > g_pcm_capacity.load()) return -1002;
    auto *input = static_cast<const uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (!input || byte_count == 0) return byte_count == 0 ? 0 : -1003;
    jbyteArray array = g_pcm_array.load();
    if (!array) return -1004;
    auto fn = write_track();
    if (!fn) return -1005;
    auto *output = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(array, nullptr));
    if (!output) return -1006;
    std::memcpy(output, input, static_cast<size_t>(byte_count));
    env->ReleasePrimitiveArrayCritical(array, output, 0);
    // One preallocated array, no heap allocation and no thread attach/detach.
    return fn(env, nullptr, array, byte_count);
}

#define HIBY_INT_METHOD(kotlin_name, native_name) \
JNIEXPORT jint JNICALL \
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_##kotlin_name( \
        JNIEnv *env, jobject) { \
    auto fn = int_call(native_name); \
    return fn ? fn(env, nullptr) : -1007; \
}

HIBY_INT_METHOD(nativeHibyStreamStart,
        "Java_com_hiby_music_sdk_util_OtHiByTrackUtils_streamStart")
HIBY_INT_METHOD(nativeHibyStreamEnd,
        "Java_com_hiby_music_sdk_util_OtHiByTrackUtils_streamEnd")
HIBY_INT_METHOD(nativeHibyRelease,
        "Java_com_hiby_music_sdk_util_OtHiByTrackUtils_release")
HIBY_INT_METHOD(nativeHibyDestruct,
        "Java_com_hiby_music_sdk_util_OtHiByTrackUtils_destruct")
HIBY_INT_METHOD(nativeHibyLatency,
        "Java_com_hiby_music_sdk_util_OtHiByTrackUtils_latency")

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativeHibyNowConsumed(
        JNIEnv *env, jobject, jlongArray output) {
    auto fn = symbol<ConsumedCall>(open_engine("libhibyservice.so"),
            "Java_com_hiby_music_sdk_util_OtHiByTrackUtils_nowConsumed");
    if (!fn) return -1008;
    jlong result = fn(env, nullptr, output);
    jlong native_value = 0;
    if (output && env->GetArrayLength(output) > 0) {
        env->GetLongArrayRegion(output, 0, 1, &native_value);
    }
    return result != 0 ? result : native_value;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativeCloseHiby(
        JNIEnv *env, jobject) {
    jbyteArray old = g_pcm_array.exchange(nullptr);
    if (old) env->DeleteGlobalRef(old);
    g_pcm_capacity.store(0);
}

} // extern "C"
