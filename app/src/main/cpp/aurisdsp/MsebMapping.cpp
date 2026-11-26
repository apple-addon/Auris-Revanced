#include "MsebMapping.h"

#include <cstddef>

namespace aurisdsp {
namespace {

struct RecoveredSection {
    double frequencyHz;
    double q;
    BiquadType type;
    double gainDbPerRawSliderUnit;
    std::size_t dimension;
};

// Exact initialization metadata recovered from libhibyservice.so (arm64),
// initialization 0x1820c0 and setters 0x183bec/0x183d48..0x183d90.
// See tools/aurisdsp/HIBY_MSEB_RECOVERED.md. The shared Biquad implementation
// uses the verified standard RBJ formulas from coefficient routine 0x1815c0.
constexpr RecoveredSection kRecoveredSections[kMsebSectionCount] = {
    {33.0, 0.4424999952316284, BiquadType::HIGH_SHELF, -0.06, 0},
    {231.0, 0.4424999952316284, BiquadType::HIGH_SHELF, -0.06, 0},
    {1617.0, 0.4424999952316284, BiquadType::HIGH_SHELF, -0.06, 0},
    {11319.0, 0.4424999952316284, BiquadType::HIGH_SHELF, -0.06, 0},
    {70.0, 0.7289000153541565, BiquadType::LOW_SHELF, 0.25, 1},
    {100.0, 0.8571000099182129, BiquadType::PEAKING, 0.20, 2},
    {200.0, 0.666700005531311, BiquadType::PEAKING, 0.12, 3},
    {650.0, 0.4041000008583069, BiquadType::PEAKING, 0.50, 4},
    {3000.0, 1.4140000343322754, BiquadType::PEAKING, 0.10, 5},
    {5800.0, 0.9991000294685364, BiquadType::PEAKING, 0.10, 6},
    {9200.0, 0.4041000008583069, BiquadType::PEAKING, 0.10, 7},
    {7500.0, 0.7289000153541565, BiquadType::PEAKING, 0.10, 8},
    {10000.0, 0.8571000099182129, BiquadType::HIGH_SHELF, 0.25, 9},
};

}  // namespace

void buildMsebMapping(
    const std::array<float, kMsebDimensionCount>& normalizedValues,
    float rangePercent,
    std::array<MsebSectionSpec, kMsebSectionCount>& output) noexcept {
    // Range is preset metadata: changing 25/50/100 must not rewrite or attenuate
    // the stored raw slider values. JNI carries normalized values, while native
    // gain scales are defined against the original -100..+100 slider domain.
    (void)rangePercent;
    for (std::size_t sectionIndex = 0; sectionIndex < kMsebSectionCount; ++sectionIndex) {
        const RecoveredSection& recovered = kRecoveredSections[sectionIndex];
        const double rawSlider = static_cast<double>(normalizedValues[recovered.dimension]) * 100.0;
        MsebSectionSpec& section = output[sectionIndex];
        section.type = recovered.type;
        section.frequencyHz = recovered.frequencyHz;
        section.q = recovered.q;
        section.gainDb = rawSlider * recovered.gainDbPerRawSliderUnit;
        section.enabled = rawSlider != 0.0;
    }
}

}  // namespace aurisdsp
