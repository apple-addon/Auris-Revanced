package com.music.bitchord.playback.decoder

/** Container identification only; these MIME types do not claim decoder availability. */
enum class TrackerFormat(val mimeType: String, val defaultExtension: String) {
    MOD("audio/x-mod", "mod"),
    IT("audio/x-it", "it"),
    S3M("audio/x-s3m", "s3m"),
    XM("audio/x-xm", "xm"),
}

/** Signature-based MOD/IT/S3M/XM probe, independent of unreliable file extensions. */
object TrackerSniffer {
    const val PROBE_BYTES = 1_084

    private val modSignatures = setOf(
        "M.K.", "M!K!", "M&K!", "N.T.", "FLT4", "FLT8", "OKTA", "OCTA", "CD61", "CD81",
    )

    fun sniff(header: ByteArray): TrackerFormat? {
        if (header.size >= 18 && header.textAt(0, 17) == "Extended Module: " && header[17] == 0x1A.toByte()) {
            return TrackerFormat.XM
        }
        if (header.size >= 48 && header.textAt(44, 4) == "SCRM") return TrackerFormat.S3M
        if (header.size >= 4 && header.textAt(0, 4) == "IMPM") return TrackerFormat.IT
        if (header.size < PROBE_BYTES) return null
        val signature = header.textAt(1_080, 4)
        if (signature in modSignatures || channelCountSignature(signature)) return TrackerFormat.MOD
        return null
    }

    private fun channelCountSignature(signature: String): Boolean {
        if (signature.length != 4) return false
        if (signature[0].isDigit() && signature[1].isDigit() && signature[2] == 'C' && signature[3] == 'H') {
            val channels = (signature[0] - '0') * 10 + (signature[1] - '0')
            return channels in 10..32
        }
        if (signature[0].isDigit() && signature.substring(1) == "CHN") {
            return signature[0] - '0' in 1..9
        }
        if (signature.startsWith("TDZ") && signature[3].isDigit()) return signature[3] - '0' in 1..9
        return false
    }
}
