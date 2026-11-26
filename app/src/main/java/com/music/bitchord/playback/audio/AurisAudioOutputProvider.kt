package com.music.bitchord.playback.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.screens.audiophile.OutputDriver

/** Media3 1.11 output-provider host with native and AudioTrack fallback routes. */
@UnstableApi
class AurisAudioOutputProvider(
    context: Context,
    private val resolvedOutput: () -> ResolvedAudiophileOutput? = { null },
) : ForwardingAudioOutputProvider(
    AudioTrackAudioOutputProvider.Builder(context.applicationContext).build(),
) {
    @Volatile
    private var nativeChannelCount = 2

    private fun selectedBackend(): NativeOutputBackend {
        val resolved = resolvedOutput() ?: return AppSettings.nativeOutputBackend.value
        return when (resolved.effective.driver) {
            OutputDriver.AAUDIO -> NativeOutputBackend.AAUDIO
            OutputDriver.OPENSL_ES -> NativeOutputBackend.OPENSL_ES
            OutputDriver.AUDIO_TRACK,
            OutputDriver.HI_RES,
            OutputDriver.USB_EXCLUSIVE,
            -> NativeOutputBackend.AUDIO_TRACK
        }
    }

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        return when (selectedBackend()) {
            NativeOutputBackend.AUDIO_TRACK -> super.getFormatSupport(formatConfig)
            NativeOutputBackend.AAUDIO,
            NativeOutputBackend.OPENSL_ES,
            -> AudioOutputProvider.FormatSupport.Builder()
                .setFormatSupportLevel(
                    if (formatConfig.format.pcmEncoding == C.ENCODING_PCM_FLOAT) {
                        AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
                    } else {
                        AudioOutputProvider.FORMAT_SUPPORTED_WITH_TRANSCODING
                    },
                )
                .build()
        }
    }

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        if (selectedBackend() == NativeOutputBackend.AUDIO_TRACK) {
            return super.getOutputConfig(formatConfig)
        }
        val format = formatConfig.format
        nativeChannelCount = format.channelCount
        return AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(format.sampleRate)
            .setChannelMask(Util.getAudioTrackChannelConfig(format.channelCount))
            .setIsTunneling(false)
            .setIsOffload(false)
            .setBufferSize(
                resolvedOutput()?.profile?.bufferSizeFrames?.takeIf { it > 0 }
                    ?: formatConfig.preferredBufferSize,
            )
            .setAudioAttributes(formatConfig.audioAttributes)
            .setAudioSessionId(formatConfig.audioSessionId)
            .setVirtualDeviceId(formatConfig.virtualDeviceId)
            .setUsePlaybackParameters(false)
            .setUseOffloadGapless(false)
            .build()
    }

    override fun getAudioOutput(outputConfig: AudioOutputProvider.OutputConfig): AudioOutput {
        val backend = selectedBackend()
        if (backend == NativeOutputBackend.AUDIO_TRACK) return super.getAudioOutput(outputConfig)
        val stream = when (backend) {
            NativeOutputBackend.AAUDIO -> AAudioOutputProvider()
            NativeOutputBackend.OPENSL_ES -> OpenSlEsOutputProvider()
            NativeOutputBackend.AUDIO_TRACK -> error("covered above")
        }
        val opened = stream.open(
            NativeAudioOutputConfig(
                sampleRate = outputConfig.sampleRate,
                channelCount = nativeChannelCount,
                bufferSizeFrames = outputConfig.bufferSize,
                deviceId = outputConfig.virtualDeviceId,
            ),
        )
        return if (opened) {
            NativeAudioOutput(stream, outputConfig)
        } else {
            stream.release()
            super.getAudioOutput(outputConfig)
        }
    }
}
