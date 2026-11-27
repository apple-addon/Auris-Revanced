#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <thread>
#include <vector>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>

namespace {

constexpr size_t RING_BYTES = 1 << 20;
constexpr int OUTPUT_BYTES = 256 * 1024;
constexpr int WRITE_POLL_MS = 5;
constexpr auto MAX_WRITE_STALL = std::chrono::milliseconds(2000);

struct WriteResult {
    size_t bytes = 0;
    int error = 0;
    bool cancelled = false;
};

WriteResult writeAll(int fd, const uint8_t *data, size_t size,
                     const std::atomic<bool> *cancel) {
    WriteResult result;
    std::chrono::steady_clock::time_point stallStart{};
    while (result.bytes < size) {
        if (cancel && cancel->load(std::memory_order_acquire)) {
            result.cancelled = true;
            return result;
        }
        const ssize_t written = ::write(fd, data + result.bytes, size - result.bytes);
        if (written > 0) {
            result.bytes += static_cast<size_t>(written);
            stallStart = {};
            continue;
        }
        if (written == 0) {
            result.error = EIO;
            return result;
        }
        if (errno == EINTR) continue;
        if (errno != EAGAIN && errno != EWOULDBLOCK) {
            result.error = errno;
            return result;
        }

        const auto now = std::chrono::steady_clock::now();
        if (stallStart == std::chrono::steady_clock::time_point{}) stallStart = now;
        if (now - stallStart >= MAX_WRITE_STALL) {
            result.error = ETIMEDOUT;
            return result;
        }
        pollfd descriptor{fd, POLLOUT, 0};
        const int pollResult = ::poll(&descriptor, 1, WRITE_POLL_MS);
        if (pollResult < 0) {
            if (errno == EINTR) continue;
            result.error = errno;
            return result;
        }
        if (pollResult > 0 && (descriptor.revents & (POLLERR | POLLHUP | POLLNVAL))) {
            result.error = (descriptor.revents & POLLNVAL) ? EBADF : EPIPE;
            return result;
        }
    }
    return result;
}

bool validPcmFormat(int sampleRate, int channels) {
    if (sampleRate <= 0 || channels <= 0) return false;
    const uint64_t blockAlign = static_cast<uint64_t>(channels) * sizeof(float);
    const uint64_t byteRate = static_cast<uint64_t>(sampleRate) * blockAlign;
    return blockAlign <= std::numeric_limits<uint16_t>::max() &&
           byteRate <= std::numeric_limits<uint32_t>::max();
}

struct PawsInput {
    int readFd = -1;
    int writeFd = -1;
    std::atomic<bool> running{false};
    std::atomic<int> writerError{0};
    std::atomic<size_t> head{0};
    std::atomic<size_t> tail{0};
    std::thread writer;
    std::vector<uint8_t> ring = std::vector<uint8_t>(RING_BYTES);

    ~PawsInput() { close(); }

    bool start(int sampleRate, int channels) {
        if (!validPcmFormat(sampleRate, channels)) return false;

        // An open creates a fresh PawsInput. Reset the SPSC cursors before the
        // writer can observe them so no bytes from a previous session are visible.
        head.store(0, std::memory_order_relaxed);
        tail.store(0, std::memory_order_relaxed);
        writerError.store(0, std::memory_order_relaxed);
        running.store(false, std::memory_order_relaxed);

        int descriptors[2] = {-1, -1};
        if (::pipe(descriptors) != 0) return false;
        readFd = descriptors[0];
        writeFd = descriptors[1];
        const int flags = ::fcntl(writeFd, F_GETFL, 0);
        if (flags < 0 || ::fcntl(writeFd, F_SETFL, flags | O_NONBLOCK) < 0) {
            closeDescriptors();
            return false;
        }

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
        const WriteResult headerResult = writeAll(writeFd, header, sizeof(header), nullptr);
        if (headerResult.error != 0 || headerResult.bytes != sizeof(header)) {
            closeDescriptors();
            return false;
        }

        running.store(true, std::memory_order_release);
        try {
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
                    const WriteResult result = writeAll(
                            writeFd, ring.data() + (cursor % RING_BYTES), contiguous, &running);
                    if (result.bytes > 0) {
                        cursor += result.bytes;
                        tail.store(cursor, std::memory_order_release);
                    }
                    if (result.error != 0) {
                        writerError.store(-result.error, std::memory_order_release);
                        running.store(false, std::memory_order_release);
                        break;
                    }
                    if (result.cancelled) break;
                }
            });
        } catch (...) {
            running.store(false, std::memory_order_release);
            closeDescriptors();
            return false;
        }
        return true;
    }

    // Single producer: only the audio thread calls produce for a session.
    int produce(const uint8_t *data, size_t size) {
        const int error = writerError.load(std::memory_order_acquire);
        if (error != 0) return error;
        if (!running.load(std::memory_order_acquire)) return -1;
        if (!data && size != 0) return -2;
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

    void closeDescriptors() {
        if (readFd >= 0) ::close(readFd);
        if (writeFd >= 0) ::close(writeFd);
        readFd = writeFd = -1;
    }

    void close() {
        running.store(false, std::memory_order_release);
        // writeAll polls cancellation every WRITE_POLL_MS, including EAGAIN stalls.
        if (writer.joinable()) writer.join();
        closeDescriptors();
        // Do not clear ring here: an in-flight producer may still own this session.
    }
};

// The mutex only snapshots ownership. PCM copies, pipe I/O, PAWS calls, and JNI
// array operations never hold it. shared_ptr keeps a detached ring alive until
// an in-flight producer returns. Concurrent open/close remains memory-safe, but
// callers must serialize lifecycle calls for deterministic fd ownership.
std::mutex g_input_mutex;
std::shared_ptr<PawsInput> g_input;

std::shared_ptr<PawsInput> acquireInput() {
    std::lock_guard<std::mutex> lock(g_input_mutex);
    return g_input;
}

std::shared_ptr<PawsInput> replaceInput(std::shared_ptr<PawsInput> replacement) {
    std::lock_guard<std::mutex> lock(g_input_mutex);
    std::shared_ptr<PawsInput> previous = std::move(g_input);
    g_input = std::move(replacement);
    return previous;
}

void closeInput() {
    std::shared_ptr<PawsInput> previous = replaceInput(nullptr);
    if (previous) previous->close();
}

constexpr uint32_t OUTPUT_RETIRED = 1U << 31;

struct OutputBuffer {
    jbyteArray array = nullptr;
    std::atomic<uint32_t> users{0}; // Low 31 bits: active nativePawsReadOutput calls.
};

std::mutex g_output_mutex;
OutputBuffer *g_output = nullptr;

OutputBuffer *acquireOutput() {
    std::lock_guard<std::mutex> lock(g_output_mutex);
    if (!g_output) return nullptr;
    // A reachable buffer cannot be retired; detachment and retirement are ordered.
    g_output->users.fetch_add(1, std::memory_order_acq_rel);
    return g_output;
}

void destroyOutput(JNIEnv *env, OutputBuffer *output) {
    env->DeleteGlobalRef(output->array);
    delete output;
}

void releaseOutput(JNIEnv *env, OutputBuffer *output) {
    const uint32_t previous = output->users.fetch_sub(1, std::memory_order_acq_rel);
    if (previous == OUTPUT_RETIRED + 1) destroyOutput(env, output);
}

void retireOutput(JNIEnv *env, OutputBuffer *output) {
    if (!output) return;
    const uint32_t previous = output->users.fetch_or(OUTPUT_RETIRED, std::memory_order_acq_rel);
    if (previous == 0) destroyOutput(env, output);
}

int prepareOutput(JNIEnv *env) {
    auto *replacement = new (std::nothrow) OutputBuffer;
    if (!replacement) return -1;
    jbyteArray local = env->NewByteArray(OUTPUT_BYTES);
    if (!local) {
        delete replacement;
        return -1;
    }
    replacement->array = static_cast<jbyteArray>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!replacement->array) {
        delete replacement;
        return -2;
    }

    OutputBuffer *previous;
    {
        std::lock_guard<std::mutex> lock(g_output_mutex);
        previous = g_output;
        g_output = replacement;
    }
    // The last reader deletes a retired buffer; otherwise close/prepare deletes it.
    retireOutput(env, previous);
    return 0;
}

void closeOutput(JNIEnv *env) {
    OutputBuffer *previous;
    {
        std::lock_guard<std::mutex> lock(g_output_mutex);
        previous = g_output;
        g_output = nullptr;
    }
    retireOutput(env, previous);
}

using GetSamples = jint (*)(JNIEnv *, jclass, jlong, jbyteArray);

GetSamples getSamplesFunction() {
    static GetSamples fn = [] {
#if !defined(__aarch64__)
        __android_log_print(ANDROID_LOG_ERROR, "PAWS_PCM", "native_get_samples VA is verified only for arm64-v8a");
        return static_cast<GetSamples>(nullptr);
#else
        void *handle = dlopen("libauriscore.so", RTLD_NOW | RTLD_LOCAL);
        if (!handle) return static_cast<GetSamples>(nullptr);
        Dl_info info{};
        void *anchor = dlsym(handle, "pa_register_decoder");
        if (!anchor || dladdr(anchor, &info) == 0) return static_cast<GetSamples>(nullptr);
        // Exact packaged ARM64 hash b48e094d…; native_get_samples registration VA.
        return reinterpret_cast<GetSamples>(static_cast<uint8_t *>(info.dli_fbase) + 0x31b488);
#endif
    }();
    return fn;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativePawsOpenInput(
        JNIEnv *env, jobject, jint sampleRate, jint channels) {
    closeInput();
    std::shared_ptr<PawsInput> replacement;
    try {
        replacement = std::make_shared<PawsInput>();
    } catch (...) {
        return -1;
    }
    if (!replacement->start(sampleRate, channels)) return -1;

    // Every PCM session needs a fresh live array after nativePawsClose retired
    // the previous one; no output bytes are carried across graph generations.
    const int outputStatus = prepareOutput(env);
    if (outputStatus != 0) return outputStatus;

    const int openedFd = replacement->readFd;
    std::shared_ptr<PawsInput> previous = replaceInput(std::move(replacement));
    if (previous) previous->close();
    return openedFd;
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativePawsWriteInput(
        JNIEnv *env, jobject, jobject buffer, jint byteOffset, jint byteCount) {
    if (byteOffset < 0 || byteCount < 0) return -1;
    const auto *base = static_cast<const uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (!base && byteCount != 0) return -2;
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (capacity < 0 || static_cast<jlong>(byteOffset) + byteCount > capacity) return -3;
    std::shared_ptr<PawsInput> input = acquireInput();
    if (!input) return -1;
    const uint8_t *data = base ? base + byteOffset : nullptr;
    return input->produce(data, static_cast<size_t>(byteCount));
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativePawsPrepareOutput(
        JNIEnv *env, jobject) {
    return prepareOutput(env);
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativePawsReadOutput(
        JNIEnv *env, jobject, jlong nativeRef, jobject destination) {
    OutputBuffer *output = acquireOutput();
    if (!output) return -3;
    auto fn = getSamplesFunction();
    if (!fn) {
        releaseOutput(env, output);
        return -4;
    }
    // No lifecycle lock is held across the real PAWS call or JNI copies.
    const jint result = fn(env, nullptr, nativeRef, output->array);
    if (result <= 0) {
        releaseOutput(env, output);
        return result;
    }
    if (result > OUTPUT_BYTES) {
        releaseOutput(env, output);
        return -6;
    }
    auto *bytes = static_cast<uint8_t *>(env->GetDirectBufferAddress(destination));
    const jlong capacity = env->GetDirectBufferCapacity(destination);
    if (!bytes || capacity < result) {
        releaseOutput(env, output);
        return -5;
    }
    env->GetByteArrayRegion(output->array, 0, result, reinterpret_cast<jbyte *>(bytes));
    const bool failed = env->ExceptionCheck();
    releaseOutput(env, output);
    return failed ? -6 : result;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_reference_ReferenceNativeBridge_nativePawsClose(
        JNIEnv *env, jobject) {
    closeOutput(env);
    closeInput();
}

} // extern "C"
