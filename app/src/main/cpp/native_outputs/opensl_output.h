#ifndef AURIS_OPENSL_OUTPUT_H
#define AURIS_OPENSL_OUTPUT_H

#include "native_output.h"
#include "output_accounting.h"
#include "spsc_frame_ring.h"

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <atomic>
#include <memory>
#include <mutex>

namespace auris {

class OpenSlEsOutput final : public NativeOutput {
public:
    static constexpr uint32_t kBufferCount = 4;

    static std::unique_ptr<OpenSlEsOutput> open(const OutputSpec &spec, std::string &error);
    ~OpenSlEsOutput() override;

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
    explicit OpenSlEsOutput(OutputSpec spec) : spec_(spec) {}

    bool initialize(std::string &error);
    int prime_locked();
    int enqueue_buffer(uint32_t index);
    int quiesce_locked(bool reset_position);
    void wait_for_callback();
    void close_locked();
    int failure_code() const { return failure_code_.load(std::memory_order_acquire); }
    void set_failure(int code) {
        int expected = 0;
        failure_code_.compare_exchange_strong(expected, code, std::memory_order_acq_rel);
    }
    std::string failure_string() const;

    const OutputSpec spec_;
    mutable std::mutex control_lock_;
    SLObjectItf engine_object_ = nullptr;
    SLEngineItf engine_ = nullptr;
    SLObjectItf output_mix_object_ = nullptr;
    SLObjectItf player_object_ = nullptr;
    SLAndroidSimpleBufferQueueItf queue_ = nullptr;
    SLPlayItf play_ = nullptr;

    std::unique_ptr<SpscFrameRing> ring_;
    std::unique_ptr<float[]> buffers_;
    uint32_t buffer_frames_ = 0;
    uint32_t buffer_bytes_ = 0;
    uint32_t callback_buffer_index_ = 0;
    bool primed_ = false;

    std::atomic<bool> callbacks_enabled_{false};
    std::atomic<bool> running_{false};
    std::atomic<uint32_t> callback_active_{0};
    std::atomic<int> failure_code_{0};
    std::atomic<float> gain_{1.0f};
    OutputAccounting accounting_;

    static void SLAPIENTRY buffer_callback(SLAndroidSimpleBufferQueueItf caller, void *context);
};

} // namespace auris

#endif
