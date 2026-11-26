#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace paws_reference {

constexpr std::size_t kStreamingWavHeaderBytes = 44;
constexpr std::uint32_t kStreamingRiffSize = 0x7fffffffU;

inline void put_le16(std::uint8_t *dst, std::uint16_t value) {
    dst[0] = static_cast<std::uint8_t>(value);
    dst[1] = static_cast<std::uint8_t>(value >> 8U);
}

inline void put_le32(std::uint8_t *dst, std::uint32_t value) {
    dst[0] = static_cast<std::uint8_t>(value);
    dst[1] = static_cast<std::uint8_t>(value >> 8U);
    dst[2] = static_cast<std::uint8_t>(value >> 16U);
    dst[3] = static_cast<std::uint8_t>(value >> 24U);
}

inline std::uint32_t get_le32(const std::uint8_t *src) {
    return static_cast<std::uint32_t>(src[0]) |
            (static_cast<std::uint32_t>(src[1]) << 8U) |
            (static_cast<std::uint32_t>(src[2]) << 16U) |
            (static_cast<std::uint32_t>(src[3]) << 24U);
}

inline std::uint64_t get_le64(const std::uint8_t *src) {
    return static_cast<std::uint64_t>(get_le32(src)) |
            (static_cast<std::uint64_t>(get_le32(src + 4)) << 32U);
}

/** Same streaming convention as PAWS CcPluginService: huge RIFF size and zero data size. */
inline std::array<std::uint8_t, kStreamingWavHeaderBytes> make_streaming_wav_header(
        std::int32_t sample_rate,
        std::int32_t channels,
        std::int32_t format_tag,
        std::int32_t bytes_per_sample) {
    std::array<std::uint8_t, kStreamingWavHeaderBytes> h{};
    std::memcpy(h.data(), "RIFF", 4);
    put_le32(h.data() + 4, kStreamingRiffSize);
    std::memcpy(h.data() + 8, "WAVEfmt ", 8);
    put_le32(h.data() + 16, 16);
    put_le16(h.data() + 20, static_cast<std::uint16_t>(format_tag));
    put_le16(h.data() + 22, static_cast<std::uint16_t>(channels));
    put_le32(h.data() + 24, static_cast<std::uint32_t>(sample_rate));
    const std::uint16_t block_align = static_cast<std::uint16_t>(channels * bytes_per_sample);
    put_le32(h.data() + 28, static_cast<std::uint32_t>(sample_rate * block_align));
    put_le16(h.data() + 32, block_align);
    put_le16(h.data() + 34, static_cast<std::uint16_t>(bytes_per_sample * 8));
    std::memcpy(h.data() + 36, "data", 4);
    put_le32(h.data() + 40, 0);
    return h;
}

inline std::int32_t bytes_per_native_sample(std::int32_t native_format) {
    switch (native_format) {
        case 1: return 2;  // S16
        case 2: return 4;  // S32
        case 3: return 4;  // Float32
        case 4: return 8;  // Float64
        case 20: return 3; // packed 24-bit
        case 30: return 1; // U8
        default: return 0;
    }
}

} // namespace paws_reference
