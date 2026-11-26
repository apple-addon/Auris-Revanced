#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <thread>
#include <vector>
#include <fcntl.h>
#include <unistd.h>

namespace {

constexpr size_t RING_BYTES = 1 << 20;
constexpr int OUTPUT_BYTES = 256 * 1024;

struct PawsInput {
    int readFd = -1;
    int writeFd = -1;
    std::atomic<bool> running{false};
    std::atomic<size_t> head{0};
    std::atomic<size_t> tail{0};
    std::thread writer;
    std::vector<uint8_t> ring = std::vector<uint8_t>(RING_BYTES);

    static void writeAll(int fd, const uint8_t *data, size_t size) {
        size_t offset = 0;
        while (offset < size) {
            ssize_t result = ::write(fd, data + offset, size - offset);
            if (result > 0) {
                offset += static_cast<size_t>(result);
            } else if (result < 0 && errno == EINTR) {
                continue;
            } else if (result < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                std::this_thread::yield();
            } else {
                break;
            }
        }
    }

    void start(int sampleRate, int channels) {
        if (ring.size() != RING_BYTES) ring.resize(RING_BYTES);
        int descriptors[2];
        if (::pipe(descriptors) != 0) return;
        readFd = descriptors[0];
        writeFd = descriptors[1];
        ::fcntl(writeFd, F_SETFL, ::fcntl(writeFd, F_GETFL, 0) | O_NONBLOCK);
        uint8_t header[44] = {};
        std::memcpy(header, "RIFF", 4);
        uint32_t riffSize = 0xFFFFFFFFU;
        std::memcpy(header + 4, &riffSize, 4);
        std::memcpy(header + 8, "WAVEfmt ", 8);
        uint32_t fmtSize = 16;
        std::memcpy(header + 16, &fmtSize, 4);
        uint16_t format = 3; // IEEE float32
        uint16_t channelCount = static_cast<uint16_t>(channels);
        uint32_t rate = static_cast<uint32_t>(sampleRate);
        uint16_t blockAlign = static_cast<uint16_t>(channels * 4);
        uint32_t byteRate = rate * blockAlign;
        uint16_t bits = 32;
        std::memcpy(header + 20, &format, 2);
        std::memcpy(header + 22, &channelCount, 2);
        std::memcpy(header + 24, &rate, 4);
        std::memcpy(header + 28, &byteRate, 4);
        std::memcpy(header + 32, &blockAlign, 2);
        std::memcpy(header + 34, &bits, 2);
        std::memcpy(header + 36, "data", 4);
        std::memcpy(header + 40, &riffSize, 4);
        writeAll(writeFd, header, sizeof(header));
        running.store(true, std::memory_order_release);
        writer = std::thread([this] {
            size_t cursor = 0;
            while (running.load(std::memory_order_acquire)) {
                const size_t headValue = head.load(std::memory_order_acquire);
                if (cursor == headValue) {
                    std::this_thread::yield();
                    continue;
                }
                const size_t available = headValue - cursor;
                const size_t contiguous = std::min(available, RING_BYTES - (cursor % RING_BYTES));
                writeAll(writeFd, ring.data() + (cursor % RING_BYTES), contiguous);
                cursor += contiguous;
                tail.store(cursor, std::memory_order_release);
            }
        });
    }

    int produce(const uint8_t *data, size_t size) {
        if (!running.load(std::memory_order_acquire)) return -1;
        const size_t headValue = head.load(std::memory_order_relaxed);
        const size_t tailValue = tail.load(std::memory_order_acquire);
        const size_t available = RING_BYTES - (headValue - tailValue);
        const size_t count = std::min(size, available);
        if (count == 0) return 0;
        const size_t first = std::min(count, RING_BYTES - (headValue % RING_BYTES));
        std::memcpy(ring.data() + (headValue % RING_BYTES), data, first);
        if (count > first) std::memcpy(ring.data(), data + first, count - first);
        head.store(headValue + count, std::memory_order_release);
        return static_cast<int>(count);
    }

    void close() {
        running.store(false, std::memory_order_release);
        if (writer.joinable()) writer.join();
        if (readFd >= 0) ::close(readFd);
        if (writeFd >= 0) ::close(writeFd);
        readFd = writeFd = -1;
        ring.clear();
        ring.shrink_to_fit();
    }
};

PawsInput g_input;
jbyteArray g_output_array = nullptr;

void releaseOutputArray(JNIEnv *env) {
    if (g_output_array) {
        env->DeleteGlobalRef(g_output_array);
        g_output_array = nullptr;
    }
}

using GetSamples = jint (*)(JNIEnv *, jclass, jlong, jbyteArray);

GetSamples getSamplesFunction() {
    static GetSamples fn = [] {
        void *handle = dlopen("libauriscore.so", RTLD_NOW | RTLD_LOCAL);
        if (!handle) return static_cast<GetSamples>(nullptr);
        Dl_info info{};
        void *anchor = dlsym(handle, "pa_register_decoder");
        if (!anchor || dladdr(anchor, &info) == 0) return static_cast<GetSamples>(nullptr);
        // Exact packaged hash b48e094d…; native_get_samples registration VA.
        return reinterpret_cast<GetSamples>(static_cast<uint8_t *>(info.dli_fbase) + 0x31b488);
    }();
    return fn;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativePawsOpenInput(
        JNIEnv *, jobject, jint sampleRate, jint channels) {
    g_input.close();
    g_input.start(sampleRate, channels);
    return g_input.readFd;
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativePawsWriteInput(
        JNIEnv *env, jobject, jobject buffer, jint byteOffset, jint byteCount) {
    if (byteOffset < 0 || byteCount < 0) return -1;
    const auto *base = static_cast<const uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (!base && byteCount != 0) return -2;
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (capacity < 0 || static_cast<jlong>(byteOffset) + byteCount > capacity) return -3;
    return g_input.produce(base + byteOffset, static_cast<size_t>(byteCount));
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativePawsPrepareOutput(
        JNIEnv *env, jobject) {
    releaseOutputArray(env);
    jbyteArray local = env->NewByteArray(OUTPUT_BYTES);
    if (!local) return -1;
    g_output_array = static_cast<jbyteArray>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return g_output_array ? 0 : -2;
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativePawsReadOutput(
        JNIEnv *env, jobject, jlong nativeRef, jobject destination) {
    if (!g_output_array) return -3;
    auto fn = getSamplesFunction();
    if (!fn) return -4;
    const jint result = fn(env, nullptr, nativeRef, g_output_array);
    if (result <= 0) return result;
    auto *bytes = static_cast<uint8_t *>(env->GetDirectBufferAddress(destination));
    const jlong capacity = env->GetDirectBufferCapacity(destination);
    if (!bytes || capacity < result) return -5;
    env->GetByteArrayRegion(g_output_array, 0, result, reinterpret_cast<jbyte *>(bytes));
    return env->ExceptionCheck() ? -6 : result;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativePawsClose(
        JNIEnv *env, jobject) {
    g_input.close();
    releaseOutputArray(env);
}

} // extern "C"
