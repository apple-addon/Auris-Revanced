package com.music.bitchord.playback.dsd.raw

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import com.decent.usbaudio.UsbAudioDevice
import com.music.bitchord.playback.dsd.DsdTransportKind
import com.music.bitchord.playback.dsd.DsdTransportPlan
import com.music.bitchord.playback.dsd.DsdUsbQuirks
import com.music.bitchord.playback.dsd.NativeDsdPacking
import com.music.bitchord.playback.dsd.Uac2DsdDescriptorParser
import com.music.bitchord.playback.dsd.raw.render.RawDsdRenderer
import com.music.bitchord.playback.usb.ExclusiveUsbOutput
import com.music.bitchord.playback.usb.ExclusiveUsbRawDsdTransport
import com.music.bitchord.playback.usb.RawDsdUsbOutput
import java.io.IOException

/**
 * Integration contract for the playback-integration agent.
 *
 * All files under playback/dsd/raw and playback/usb are owned by the raw-DSD agent.
 * This object is the ONLY public surface that the rest of the codebase may call:
 *
 * ```
 * object RawDsdPlaybackIntegration {
 *     fun createExtractorsFactory(context: Context, output: ExclusiveUsbOutput, delegate: ExtractorsFactory): ExtractorsFactory
 *     fun createRenderer(output: ExclusiveUsbOutput): RawDsdRenderer?
 * }
 * ```
 *
 * The renderer and transport always share one conservative [DsdTransportPlan]. Raw UAC2
 * descriptors and VID/PID quirks are read honestly; DoP is chosen only when an exact 24-bit
 * PCM alt is verified and is not a raw-DSD alt. When no raw path is available the delegate
 * PCM [DsdExtractorsFactory] is used unchanged, and [createRenderer] returns null so stock
 * PCM DSD playback is preserved. A raw DSF/DFF/SACD track never silently becomes PCM when a
 * raw renderer was selected.
 */
@UnstableApi
object RawDsdPlaybackIntegration {

    /**
     * Wraps [delegate] so raw DSF/DFF/SACD extractors come first when the attached DAC can
     * carry raw DSD. Otherwise returns [delegate] behavior unchanged (stock PCM DSD).
     */
    fun createExtractorsFactory(
        context: Context,
        output: ExclusiveUsbOutput,
        delegate: ExtractorsFactory,
    ): ExtractorsFactory = RawDsdExtractorsFactory(context.applicationContext, output, delegate)

    /**
     * Creates a [RawDsdRenderer] over [ExclusiveUsbRawDsdTransport] when the attached DAC has a
     * verified raw transport. Both share the same conservative [DsdTransportPlan]. Returns null
     * when raw output is unavailable so the caller keeps its PCM renderer path.
     */
    fun createRenderer(output: ExclusiveUsbOutput): RawDsdRenderer? {
        val plan = discoverTransportPlan(output.rawPlanContext, output)
        if (plan.kind == DsdTransportKind.UNSUPPORTED) return null
        val transport = ExclusiveUsbRawDsdTransport(output, plan)
        return RawDsdRenderer(transport, plan)
    }

    /**
     * Discovers the conservative transport plan from the currently attached USB audio device.
     *
     * This reads real UAC2 raw descriptors and the VID/PID quirk table; it never invents support.
     * Native U32 is chosen only when a quirk-verified raw alt matches its slot format. DoP is
     * chosen only when an exact 24-bit PCM alt exists that is not itself a raw-DSD alt.
     */
    internal fun discoverTransportPlan(context: Context, output: RawDsdUsbOutput): DsdTransportPlan {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return DsdTransportPlan(DsdTransportKind.UNSUPPORTED, reason = "no UsbManager")
        val device = manager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any {
                dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        } ?: return DsdTransportPlan(DsdTransportKind.UNSUPPORTED, reason = "no USB audio device attached")
        if (!manager.hasPermission(device)) {
            return DsdTransportPlan(DsdTransportKind.UNSUPPORTED, reason = "USB permission missing for ${device.deviceName}")
        }

        val usbAudio = UsbAudioDevice.getInstance(context)
        val info = usbAudio.openDevice(device)
            ?: return DsdTransportPlan(
                DsdTransportKind.UNSUPPORTED,
                reason = "openDevice failed" + (usbAudio.lastOpenError?.let { ": $it" } ?: ""),
            )
        try {
            usbAudio.querySupportedSampleRates() // fills the alt/rate cache used by findAltSetting
            val descriptors = info.connection.rawDescriptors
                ?: return DsdTransportPlan(DsdTransportKind.UNSUPPORTED, reason = "UAC2 raw descriptors unreadable")
            val rawAlts = try {
                Uac2DsdDescriptorParser.parse(descriptors).filter { it.interfaceNumber == info.interfaceId }
            } catch (failure: IllegalArgumentException) {
                return DsdTransportPlan(
                    DsdTransportKind.UNSUPPORTED,
                    reason = "invalid UAC2 raw descriptors: ${failure.message}",
                )
            }

            // Exact 24-bit DoP availability: a PCM alt at 24 bits that is NOT a raw-DSD alt.
            val dopAvailable = run {
                val pcmAlt = usbAudio.findAltSetting(24, 176_400, 2) // DSD64 stereo reference
                pcmAlt != null && pcmAlt.second == 24 && rawAlts.none { it.alternateSetting == pcmAlt.first }
            }

            // Prefer a quirk-verified native raw alt on the playback interface.
            for (rawAlt in rawAlts) {
                val plan = DsdUsbQuirks.select(
                    vendorId = device.vendorId,
                    productId = device.productId,
                    rawAlt = rawAlt,
                    doPAvailable = dopAvailable,
                )
                if (plan.kind == DsdTransportKind.NATIVE) return plan
            }

            if (dopAvailable) {
                return DsdTransportPlan(
                    DsdTransportKind.DOP,
                    reason = "exact 24-bit DoP PCM alt verified; no native quirk matched",
                )
            }
            return DsdTransportPlan(
                DsdTransportKind.UNSUPPORTED,
                reason = "no verified native raw alt and no exact 24-bit DoP alt",
            )
        } finally {
            // Do not close the shared device here; ExclusiveUsbOutput owns the session lifecycle.
        }
    }

    /**
     * ExtractorsFactory that prepends raw DSF/DFF/SACD extractors when a raw transport plan is
     * available. Otherwise delegates entirely to [delegate] (stock PCM DSD + stock formats),
     * preserving AudioTrack fallback playback.
     */
    private class RawDsdExtractorsFactory(
        private val context: Context,
        private val output: ExclusiveUsbOutput,
        private val delegate: ExtractorsFactory,
    ) : ExtractorsFactory {
        @Volatile private var cachedPlan: DsdTransportPlan? = null

        private fun plan(): DsdTransportPlan =
            cachedPlan ?: discoverTransportPlan(context, output).also { cachedPlan = it }

        override fun createExtractors(): Array<Extractor> {
            val plan = plan()
            return if (plan.kind == DsdTransportKind.UNSUPPORTED) {
                delegate.createExtractors()
            } else {
                // Raw extractors emit audio/x-dsd U32 access units; the RawDsdRenderer consumes
                // them directly. DSF, DFF (incl. DST), and SACD are all covered.
                arrayOf(
                    RawDsdExtractor(),
                    SacdIsoExtractor(),
                    *delegate.createExtractors(),
                )
            }
        }

        override fun createExtractors(
            uri: android.net.Uri,
            responseHeaders: Map<String, List<String>>,
        ): Array<Extractor> {
            val plan = plan()
            return if (plan.kind == DsdTransportKind.UNSUPPORTED) {
                delegate.createExtractors(uri, responseHeaders)
            } else {
                arrayOf(
                    RawDsdExtractor(),
                    SacdIsoExtractor(),
                    *delegate.createExtractors(uri, responseHeaders),
                )
            }
        }
    }
}