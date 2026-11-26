#ifndef AURIS_AAUDIO_OUTPUT_H
#define AURIS_AAUDIO_OUTPUT_H

#include "native_output.h"

#include <aaudio/AAudio.h>
#include <atomic>
#include <memory>
#include <mutex>

namespace auris {

class AAudioOutput final : public NativeOutput {
public:
    /** Exclusive is attempted first; only an open/validation failure retries shared. */
    static std::unique_ptr<AAudioOutput> open(
            const OutputSpec &spec, bool request_exclusive, std::string &error);

    ~AAudioOutput() override;

    int play() override;
    int pause() override;
    int write(const uint8_t *data, int32_t byte_count) override;
    int flush() override;
    int stop() override;
    void set_volume(float volume) override;
    int64_t xrun_count() override;
    OutputSnapshot snapshot() override;
    int32_t frame_bytes() const override { return spec_.frame_bytes(); }

private:
    explicit AAudioOutput(OutputSpec spec) : spec_(spec) {}

    bool open_one(aaudio_sharing_mode_t sharing, std::string &error);
    void close_locked();
    int failure_code() const { return failure_code_.load(std::memory_order_acquire); }
    void set_failure(int code) {
        int expected = 0;
        failure_code_.compare_exchange_strong(expected, code, std::memory_order_acq_rel);
    }
    int wait_for_state_locked(aaudio_stream_state_t old_state, int64_t timeout_ns);
    std::string failure_string() const;

    const OutputSpec spec_;
    mutable std::mutex control_lock_;
    AAudioStream *stream_ = nullptr;
    std::unique_ptr<float[]> gain_scratch_;
    int32_t scratch_frames_ = 0;
    int64_t write_timeout_ns_ = 50000000;
    int64_t frame_origin_ = 0;
    int64_t last_position_us_ = 0;
    std::atomic<int> failure_code_{0};
    std::atomic<bool> running_{false};
    std::atomic<float> gain_{1.0f};

    static void error_callback(AAudioStream *stream, void *user_data, aaudio_result_t error);
};

} // namespace auris

#endif
