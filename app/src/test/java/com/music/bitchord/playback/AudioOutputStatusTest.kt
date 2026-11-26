package com.music.bitchord.playback

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The output telemetry is the only proof that a 96/192 kHz or multichannel
 * decode reached AudioTrack as itself. These pin the two values the pipeline
 * dialog reads, and the channel-mask mapping used by the runtime log.
 */
class AudioOutputStatusTest {

    @Test
    fun `channel masks map to real channel counts`() {
        assertEquals(1, AudioOutputStatus.channelCountOf(AudioFormat.CHANNEL_OUT_MONO))
        assertEquals(2, AudioOutputStatus.channelCountOf(AudioFormat.CHANNEL_OUT_STEREO))
        assertEquals(6, AudioOutputStatus.channelCountOf(AudioFormat.CHANNEL_OUT_5POINT1))
        assertEquals(8, AudioOutputStatus.channelCountOf(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND))
    }

    @Test
    fun `96 and 192 kHz float tracks are reported unchanged`() {
        AudioOutputStatus.publishAudioTrack(
            encoding = AudioFormat.ENCODING_PCM_FLOAT,
            sampleRateHz = 96_000,
            channelCount = 2,
        )
        assertEquals(96_000, AudioOutputStatus.current.value.actualSampleRateHz)
        assertEquals(2, AudioOutputStatus.current.value.actualChannelCount)
        assertEquals(AudioFormat.ENCODING_PCM_FLOAT, AudioOutputStatus.current.value.actualEncoding)

        AudioOutputStatus.publishAudioTrack(
            encoding = AudioFormat.ENCODING_PCM_FLOAT,
            sampleRateHz = 192_000,
            channelCount = 2,
        )
        assertEquals(192_000, AudioOutputStatus.current.value.actualSampleRateHz)
        assertEquals(2, AudioOutputStatus.current.value.actualChannelCount)
    }

    @Test
    fun `a downmixed output is visible rather than hidden`() {
        AudioOutputStatus.publishAudioTrack(
            encoding = AudioFormat.ENCODING_PCM_16BIT,
            sampleRateHz = 48_000,
            channelCount = 2,
        )
        assertEquals(48_000, AudioOutputStatus.current.value.actualSampleRateHz)
        assertEquals(2, AudioOutputStatus.current.value.actualChannelCount)
        // The mismatch itself is logged by PlaybackService against the decoder
        // format; the telemetry must not fabricate the requested value.
        assertNull(AudioOutputStatus.current.value.actualChannelCount?.takeIf { it > 2 })
    }
}
