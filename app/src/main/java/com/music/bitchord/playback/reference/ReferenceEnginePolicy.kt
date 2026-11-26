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
            snapshot.pawsOutputActive -> "PAWS NATIVE ACTIVE"
            snapshot.paws.state == NativeLoadState.LOADED_ACTIVE -> "PAWS NATIVE READY"
            else -> "PAWS BYPASSED: ${snapshot.paws.detail}"
        }
        ReferenceEngineMode.HIBY -> when {
            snapshot.hiby.state == NativeLoadState.FAILED -> "INIT FAILED: ${snapshot.hiby.detail}"
            snapshot.hibyOutputActive -> "HIBY NATIVE ACTIVE"
            snapshot.hiby.state == NativeLoadState.LOADED_ACTIVE -> "HIBY NATIVE READY"
            else -> "HIBY BYPASSED: ${snapshot.hiby.detail}"
        }
        ReferenceEngineMode.HYBRID -> {
            val failure = listOf(snapshot.paws, snapshot.hiby).firstOrNull { it.state == NativeLoadState.FAILED }
            if (failure != null) {
                "INIT FAILED: ${failure.detail}"
            } else {
                "INIT FAILED: Hybrid needs an in-memory PAWS PCM transform; libauriscore exports only a live Pipeline2 graph"
            }
        }
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
