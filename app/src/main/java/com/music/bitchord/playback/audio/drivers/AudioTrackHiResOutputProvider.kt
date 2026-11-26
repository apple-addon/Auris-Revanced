package com.music.bitchord.playback.audio.drivers

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import com.music.bitchord.playback.audio.DirectAudioProbe

/** AudioTrack direct-PCM backend with an explicit, HAL-verified fallback. */
@OptIn(UnstableApi::class)
class AudioTrackHiResOutputProvider private constructor(
    context: Context,
    private val activeDevice: () -> AudioDeviceInfo?,
    private val directState: DirectState,
) : ForwardingAudioOutputProvider(
    AudioTrackAudioOutputProvider.Builder(context.applicationContext)
        .setAudioTrackBuilderModifier { builder, config ->
            if (!config.isOffload && !config.isTunneling && directState.allowed == true) {
                directState.applied = runCatching {
                    builder.javaClass.getMethod("setFlags", Int::class.javaPrimitiveType)
                        .invoke(builder, AUDIO_TRACK_FLAG_DIRECT)
                    true
                }.getOrDefault(false)
            }
        }
        .build(),
) {
    constructor(
        context: Context,
        activeDevice: () -> AudioDeviceInfo?,
    ) : this(context, activeDevice, DirectState())

    private class DirectState {
        var allowed: Boolean? = null
        var applied = false
    }

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    var lastSupport: DirectAudioProbe.DirectSupport? = null
        private set
    val lastDirect: Boolean get() = directState.applied

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        val format = formatConfig.format
        val support = DirectAudioProbe.probeDirectSupport(
            audioManager,
            format.sampleRate,
            format.channelCount,
            activeDevice(),
        )
        val exactDirect = when (format.pcmEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> support.supportsPcm16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> support.supportsPcm24
            AudioFormat.ENCODING_PCM_FLOAT -> support.supportsFloat
            AudioFormat.ENCODING_PCM_32BIT -> support.directProfiles.any { profile ->
                profile.format == AudioFormat.ENCODING_PCM_32BIT &&
                    format.sampleRate in profile.sampleRates &&
                    profile.channelMasks.any { Integer.bitCount(it) == format.channelCount }
            }
            else -> false
        }
        lastSupport = support
        directState.applied = false
        directState.allowed = support.isDirectSupported && exactDirect
        return super.getOutputConfig(formatConfig)
    }

    private companion object {
        // AudioTrack.Builder.setFlags is no longer exposed by the newest SDK
        // stubs, so it is invoked only when present on the device.
        const val AUDIO_TRACK_FLAG_DIRECT = 0x4
    }
}

/** Wraparound-safe 32-bit AudioTrack playback-head accumulator. */
class PlaybackHeadClock {
    private var initialized = false
    private var previousRaw = 0L
    private var frames = 0L

    @Synchronized
    fun update(rawPlaybackHead: Long, sampleRate: Int): Long {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val raw = rawPlaybackHead and 0xFFFFFFFFL
        if (!initialized) {
            initialized = true
            previousRaw = raw
            frames = raw
        } else {
            val delta = (raw - previousRaw) and 0xFFFFFFFFL
            // A position jump larger than half the counter is a new stream,
            // not four billion frames played between two clock reads.
            if (delta < 0x80000000L) frames += delta else frames = raw
            previousRaw = raw
        }
        return frames * 1_000_000L / sampleRate
    }

    @Synchronized
    fun reset() {
        initialized = false
        previousRaw = 0
        frames = 0
    }
}
