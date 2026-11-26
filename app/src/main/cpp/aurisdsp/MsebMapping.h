#ifndef AURIS_DSP_MSEB_MAPPING_H
#define AURIS_DSP_MSEB_MAPPING_H

#include <array>
#include <cstddef>

#include "AurisDspParams.h"

namespace aurisdsp {

/** Recovered layout: four temperature shelves plus nine independent sections. */
constexpr std::size_t kMsebSectionCount = 13;

struct MsebSectionSpec {
    BiquadType type = BiquadType::PEAKING;
    double frequencyHz = 1000.0;
    double q = 1.0;
    double gainDb = 0.0;
    bool enabled = false;
};

/**
 * Convert one normalized ten-dimension snapshot into fixed filter sections.
 *
 * This is the sole MSEB curve-mapping entry point. MsebMapping.cpp contains the
 * recovered 13-section initialization table and raw-slider gain scales. Keep
 * revisions isolated there; the engine, parameter ABI, PEQ, and Poweramp-shaped
 * stages contain no MSEB coefficient metadata.
 */
void buildMsebMapping(
    const std::array<float, kMsebDimensionCount>& normalizedValues,
    float rangePercent,
    std::array<MsebSectionSpec, kMsebSectionCount>& output) noexcept;

}  // namespace aurisdsp

#endif  // AURIS_DSP_MSEB_MAPPING_H
