package com.music.bitchord.playback.reference

import com.music.bitchord.data.settings.ReferenceEngineMode

/** Pure state labels used by both the pipeline UI and JVM contract tests. */
fun referenceTelemetrySummary(
    qualityOptions: Boolean,
    mode: ReferenceEngineMode,
    snapshot: ReferenceEngineSnapshot,
): String {
    if (!qualityOptions) return "Quality Options OFF"
    return when (mode) {
        ReferenceEngineMode.BIT_PERFECT -> "BIT-PERFECT DIRECT"
        ReferenceEngineMode.POWERAMP -> when {
            snapshot.paws.state == NativeLoadState.FAILED -> "INIT FAILED: ${snapshot.paws.detail}"
            snapshot.pawsOutput.failure != null -> snapshot.pawsOutput.failure!!
            snapshot.pawsOutputActive -> "PAWS NATIVE ACTIVE"
            snapshot.pawsOutput.state == NativeStreamState.OPEN -> "PAWS GRAPH READY / WAITING FOR PCM"
            snapshot.paws.state == NativeLoadState.LOADED_ACTIVE -> "PAWS NATIVE READY"
            else -> "PAWS BYPASSED: ${snapshot.paws.detail}"
        }
        ReferenceEngineMode.HIBY -> when {
            snapshot.hiby.state == NativeLoadState.FAILED -> "INIT FAILED: ${snapshot.hiby.detail}"
            snapshot.hibyOutput.failure != null -> snapshot.hibyOutput.failure!!
            snapshot.hibyOutputActive -> "HIBY NATIVE ACTIVE"
            snapshot.hibyOutput.state == NativeStreamState.OPEN -> "HIBY OUTPUT READY / WAITING FOR PCM"
            snapshot.hiby.state == NativeLoadState.LOADED_ACTIVE -> "HIBY NATIVE READY"
            else -> "HIBY BYPASSED: ${snapshot.hiby.detail}"
        }
        ReferenceEngineMode.HYBRID -> {
            val engineFailure = listOf(snapshot.paws, snapshot.hiby)
                .firstOrNull { it.state == NativeLoadState.FAILED }
            val streamFailure = snapshot.pawsOutput.failure ?: snapshot.hibyOutput.failure
            when {
                engineFailure != null -> "INIT FAILED: ${engineFailure.detail}"
                streamFailure != null -> streamFailure
                snapshot.pawsOutputActive && snapshot.hibyOutputActive -> "HYBRID NATIVE ACTIVE"
                snapshot.paws.state == NativeLoadState.LOADED_ACTIVE &&
                    snapshot.hiby.state == NativeLoadState.LOADED_ACTIVE -> "HYBRID NATIVE READY"
                else -> "HYBRID BYPASSED: native engines are not both loaded"
            }
        }
    }
}

private val NativeStreamStatus.failure: String?
    get() {
        if (state != NativeStreamState.FAILED) return null
        val reason = detail?.ifBlank { null } ?: "native PCM stream failed"
        return if (reason.startsWith("INIT FAILED: ") || reason.startsWith("PCM FAILED: ")) {
            reason
        } else {
            "PCM FAILED: $reason"
        }
    }

/** Native OtHiByTrackUtils result and output-frame arithmetic. */
object HibyPcmContract {
    fun consumedBytes(byteCount: Int, nativeResult: Int): Int = when {
        byteCount <= 0 -> 0
        nativeResult == 0 -> byteCount
        nativeResult > 0 -> (byteCount - nativeResult).coerceIn(0, byteCount)
        else -> 0
    }

    fun framesToUs(frames: Long, sampleRate: Int): Long {
        require(sampleRate > 0) { "sampleRate must be positive" }
        return frames.coerceAtLeast(0) * 1_000_000L / sampleRate
    }
}
