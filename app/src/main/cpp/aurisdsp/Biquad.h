#ifndef AURIS_DSP_BIQUAD_H
#define AURIS_DSP_BIQUAD_H

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>

#include "AurisDspParams.h"

namespace aurisdsp {

/**
 * One double-precision transposed-direct-form-II section with eight channel
 * states. Coefficients use the RBJ Audio EQ Cookbook bilinear transforms.
 * BAND_PASS is the constant-0-dB-center-gain form. Shelves interpret Q through
 * alpha = sin(w0) / (2Q), rather than the cookbook's shelf-slope S form.
 */
class Biquad final {
public:
    Biquad() noexcept = default;

    void set(
        BiquadType type,
        double sampleRate,
        double frequencyHz,
        double q,
        double gainDb,
        bool enabled = true) noexcept {
        if (!enabled || !(sampleRate > 0.0)) {
            active_ = false;
            reset();
            return;
        }

        constexpr double kPi = 3.141592653589793238462643383279502884;
        const double nyquistLimited = sampleRate * 0.45;
        const double frequency = std::max(1.0, std::min(frequencyHz, nyquistLimited));
        const double safeQ = std::max(0.01, q);
        const double omega = 2.0 * kPi * frequency / sampleRate;
        const double cosine = std::cos(omega);
        const double sine = std::sin(omega);
        const double alpha = sine / (2.0 * safeQ);
        const double a = std::pow(10.0, gainDb / 40.0);

        double b0 = 1.0;
        double b1 = 0.0;
        double b2 = 0.0;
        double a0 = 1.0;
        double a1 = 0.0;
        double a2 = 0.0;

        switch (type) {
            case BiquadType::PEAKING:
                b0 = 1.0 + alpha * a;
                b1 = -2.0 * cosine;
                b2 = 1.0 - alpha * a;
                a0 = 1.0 + alpha / a;
                a1 = -2.0 * cosine;
                a2 = 1.0 - alpha / a;
                break;
            case BiquadType::LOW_SHELF: {
                const double twoSqrtAAlpha = 2.0 * std::sqrt(a) * alpha;
                b0 = a * ((a + 1.0) - (a - 1.0) * cosine + twoSqrtAAlpha);
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosine);
                b2 = a * ((a + 1.0) - (a - 1.0) * cosine - twoSqrtAAlpha);
                a0 = (a + 1.0) + (a - 1.0) * cosine + twoSqrtAAlpha;
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosine);
                a2 = (a + 1.0) + (a - 1.0) * cosine - twoSqrtAAlpha;
                break;
            }
            case BiquadType::HIGH_SHELF: {
                const double twoSqrtAAlpha = 2.0 * std::sqrt(a) * alpha;
                b0 = a * ((a + 1.0) + (a - 1.0) * cosine + twoSqrtAAlpha);
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosine);
                b2 = a * ((a + 1.0) + (a - 1.0) * cosine - twoSqrtAAlpha);
                a0 = (a + 1.0) - (a - 1.0) * cosine + twoSqrtAAlpha;
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosine);
                a2 = (a + 1.0) - (a - 1.0) * cosine - twoSqrtAAlpha;
                break;
            }
            case BiquadType::LOW_PASS:
                b0 = (1.0 - cosine) * 0.5;
                b1 = 1.0 - cosine;
                b2 = b0;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cosine;
                a2 = 1.0 - alpha;
                break;
            case BiquadType::HIGH_PASS:
                b0 = (1.0 + cosine) * 0.5;
                b1 = -(1.0 + cosine);
                b2 = b0;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cosine;
                a2 = 1.0 - alpha;
                break;
            case BiquadType::NOTCH:
                b0 = 1.0;
                b1 = -2.0 * cosine;
                b2 = 1.0;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cosine;
                a2 = 1.0 - alpha;
                break;
            case BiquadType::BAND_PASS:
                b0 = alpha;
                b1 = 0.0;
                b2 = -alpha;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cosine;
                a2 = 1.0 - alpha;
                break;
        }

        if (!std::isfinite(a0) || std::abs(a0) < 1.0e-30) {
            active_ = false;
            reset();
            return;
        }
        b0_ = b0 / a0;
        b1_ = b1 / a0;
        b2_ = b2 / a0;
        a1_ = a1 / a0;
        a2_ = a2 / a0;
        active_ = std::isfinite(b0_) && std::isfinite(b1_) &&
            std::isfinite(b2_) && std::isfinite(a1_) && std::isfinite(a2_);
        if (!active_) {
            reset();
        }
    }

    inline double process(double input, std::size_t channel) noexcept {
        const double output = b0_ * input + z1_[channel];
        z1_[channel] = b1_ * input - a1_ * output + z2_[channel];
        z2_[channel] = b2_ * input - a2_ * output;
        if (std::abs(z1_[channel]) < 1.0e-30) z1_[channel] = 0.0;
        if (std::abs(z2_[channel]) < 1.0e-30) z2_[channel] = 0.0;
        return output;
    }

    void reset() noexcept {
        for (std::size_t channel = 0; channel < kMaxChannels; ++channel) {
            z1_[channel] = 0.0;
            z2_[channel] = 0.0;
        }
    }

    bool active() const noexcept { return active_; }

    /**
     * Normalized (a0 == 1) coefficients after set(). Exposed read-only so host
     * tests can compare them against independently computed RBJ formulas.
     * Inactive sections report the neutral passthrough b0 == 1, rest 0.
     */
    struct Coefficients {
        double b0;
        double b1;
        double b2;
        double a1;
        double a2;
    };

    Coefficients coefficients() const noexcept {
        return Coefficients{b0_, b1_, b2_, a1_, a2_};
    }

private:
    double b0_ = 1.0;
    double b1_ = 0.0;
    double b2_ = 0.0;
    double a1_ = 0.0;
    double a2_ = 0.0;
    bool active_ = false;
    std::array<double, kMaxChannels> z1_{};
    std::array<double, kMaxChannels> z2_{};
};

}  // namespace aurisdsp

#endif  // AURIS_DSP_BIQUAD_H
