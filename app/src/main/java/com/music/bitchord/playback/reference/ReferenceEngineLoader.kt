package com.music.bitchord.playback.reference

import android.content.Context
import android.os.Build
import android.util.Log
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ReferenceEngineMode
import com.music.bitchord.playback.audio.drivers.OutputDriverTelemetry
import com.music.bitchord.playback.reference.paws.PawsHostRuntime
import dalvik.system.DexClassLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/** Exact live state of one native engine, including the real failure cause. */
enum class NativeLoadState { LOADED_ACTIVE, FAILED, BYPASSED }

data class NativeEngineStatus(
    val state: NativeLoadState,
    val detail: String,
    val capabilities: Set<String> = emptySet(),
)

data class ReferenceEngineSnapshot(
    val paws: NativeEngineStatus = NativeEngineStatus(NativeLoadState.BYPASSED, "Quality Options OFF"),
    val hiby: NativeEngineStatus = NativeEngineStatus(NativeLoadState.BYPASSED, "Quality Options OFF"),
    val hibyOutputActive: Boolean = false,
    val pawsOutputActive: Boolean = false,
    val outputDetail: String? = null,
    val driverTelemetry: OutputDriverTelemetry? = null,
)

/** Ordered, fail-closed loader for the exact rebranded JNI host DEX archives. */
object ReferenceEngineLoader {
    private const val TAG = "ReferenceEngineLoader"
    private const val PAWS_ASSET = "reference/paws_host.apk"
    private const val HIBY_ASSET = "reference/hiby_host.apk"

    private val lock = Any()
    private val mutableStatus = MutableStateFlow(ReferenceEngineSnapshot())
    val status: StateFlow<ReferenceEngineSnapshot> = mutableStatus.asStateFlow()

    private var appContext: Context? = null
    private var pawsLoader: DexClassLoader? = null
    private var pawsRuntime: PawsHostRuntime? = null
    private var hibyRuntime: HibyHostRuntime? = null
    private var loadedMode: ReferenceEngineMode? = null

    val hibyHost: HibyHostRuntime?
        get() = hibyRuntime?.takeIf { mutableStatus.value.hiby.state == NativeLoadState.LOADED_ACTIVE }

    val pawsHost: PawsHostRuntime?
        get() = pawsRuntime?.takeIf { mutableStatus.value.paws.state == NativeLoadState.LOADED_ACTIVE }

    /** Must run off the audio thread. Native loading stays untouched while Quality Options is OFF. */
    fun ensureLoaded(context: Context, mode: ReferenceEngineMode = AppSettings.referenceEngineMode.value) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (!AppSettings.qualityOptions.value || mode == ReferenceEngineMode.BIT_PERFECT) {
                bypass(mode == ReferenceEngineMode.BIT_PERFECT)
                return
            }
            if (loadedMode == mode) return
            closeWriterOnly()
            loadedMode = mode
            mutableStatus.value = ReferenceEngineSnapshot()
            if (mode == ReferenceEngineMode.POWERAMP || mode == ReferenceEngineMode.HYBRID) {
                loadPaws(context)
            }
            if (mode == ReferenceEngineMode.HIBY || mode == ReferenceEngineMode.HYBRID) {
                loadHiby(context)
            }
        }
    }

    private fun loadPaws(context: Context) {
        mutableStatus.value = mutableStatus.value.copy(
            paws = NativeEngineStatus(NativeLoadState.BYPASSED, "Loading libauriscore.so"),
        )
        try {
            ReferenceNativeBridge.ensureLoaded()
            val loader = openHost(context, PAWS_ASSET, "paws")
            loader.loadClass("com.auris.bridge.NativeLoader").getMethod("load").invoke(null)
            val probe = ReferenceNativeBridge.nativeProbe("auriscore")
            check(probe == "OK") { probe }
            pawsLoader = loader
            pawsRuntime = PawsHostRuntime(loader).also { it.initialize(context) }
            mutableStatus.value = mutableStatus.value.copy(
                paws = NativeEngineStatus(
                    NativeLoadState.LOADED_ACTIVE,
                    "libauriscore.so and exact com.auris JNI hosts loaded",
                    setOf("Pipeline2", "DSPI", "Reverb", "Resampler", "output.cc"),
                ),
            )
            Log.i(TAG, "PAWS native core loaded; PCM transform requires a live Pipeline2 graph")
        } catch (error: Throwable) {
            val reason = rootCause(error)
            pawsRuntime = null
            mutableStatus.value = mutableStatus.value.copy(
                paws = NativeEngineStatus(NativeLoadState.FAILED, reason),
            )
            Log.e(TAG, "PAWS INIT FAILED: $reason", error)
        }
    }

    private fun loadHiby(context: Context) {
        mutableStatus.value = mutableStatus.value.copy(
            hiby = NativeEngineStatus(NativeLoadState.BYPASSED, "Loading libhibyservice.so"),
        )
        try {
            ReferenceNativeBridge.ensureLoaded()
            extractPlugins(context)
            val loader = openHost(context, HIBY_ASSET, "hiby")
            loader.loadClass("com.hiby.music.sdk.bridge.NativeLoader").getMethod("load").invoke(null)
            val probe = ReferenceNativeBridge.nativeProbe("hibyservice")
            check(probe == "OK") { probe }
            hibyRuntime = HibyHostRuntime(loader).also {
                it.initialize(context)
                it.applyOutputRules()
            }
            mutableStatus.value = mutableStatus.value.copy(
                hiby = NativeEngineStatus(
                    NativeLoadState.LOADED_ACTIVE,
                    "libhibyservice.so, SmartAv, DspUtil and OtHiByTrackUtils ready",
                    setOf("MSEB", "PEQ Combined", "OtHiByTrackUtils", "DSD", "USB"),
                ),
            )
            Log.i(TAG, "HIBY native core and live DSP host loaded")
        } catch (error: Throwable) {
            val reason = rootCause(error)
            hibyRuntime = null
            mutableStatus.value = mutableStatus.value.copy(
                hiby = NativeEngineStatus(NativeLoadState.FAILED, reason),
            )
            Log.e(TAG, "HIBY INIT FAILED: $reason", error)
        }
    }

    private fun openHost(context: Context, assetName: String, id: String): DexClassLoader {
        val directory = File(context.codeCacheDir, "reference-engines").apply { mkdirs() }
        val host = File(directory, "$id-host.apk")
        copyAssetAtomic(context, assetName, host)
        val optimized = File(directory, "$id-dex").apply { mkdirs() }
        return DexClassLoader(
            host.absolutePath,
            optimized.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            ReferenceEngineLoader::class.java.classLoader,
        )
    }

    private fun extractPlugins(context: Context) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
            ?: throw IOException("No supported PAWS/HiBy ABI in ${Build.SUPPORTED_ABIS.joinToString()}")
        val destination = File(context.filesDir, "Plugins").apply { mkdirs() }
        context.assets.list("Plugins/$abi").orEmpty().forEach { name ->
            copyAssetAtomic(context, "Plugins/$abi/$name", File(destination, name))
        }
    }

    private fun copyAssetAtomic(context: Context, assetName: String, destination: File) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        try {
            context.assets.open(assetName).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (destination.exists() && destination.length() == temporary.length() && destination.readBytes().contentEquals(temporary.readBytes())) {
                temporary.delete()
                return
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw IOException("Unable to extract $assetName: ${rootCause(error)}", error)
        }
    }

    fun markHibyOutput(active: Boolean, detail: String?) {
        mutableStatus.value = mutableStatus.value.copy(hibyOutputActive = active, outputDetail = detail)
    }

    fun markPawsOutput(active: Boolean, detail: String?) {
        mutableStatus.value = mutableStatus.value.copy(pawsOutputActive = active, outputDetail = detail)
    }

    fun markDriver(telemetry: OutputDriverTelemetry) {
        mutableStatus.value = mutableStatus.value.copy(driverTelemetry = telemetry)
    }

    fun bypass(bitPerfect: Boolean = false) {
        synchronized(lock) {
            closeWriterOnly()
            loadedMode = null
            runCatching { pawsRuntime?.close() }
            pawsRuntime = null
            hibyRuntime = null
            pawsLoader = null
            val detail = if (bitPerfect) "BIT-PERFECT DIRECT" else "Quality Options OFF"
            mutableStatus.value = ReferenceEngineSnapshot(
                paws = NativeEngineStatus(NativeLoadState.BYPASSED, detail),
                hiby = NativeEngineStatus(NativeLoadState.BYPASSED, detail),
                outputDetail = detail,
            )
        }
    }

    private fun closeWriterOnly() {
        runCatching { HibyNativeTrackWriter.close() }
        runCatching { pawsRuntime?.closePcm() }
        mutableStatus.value = mutableStatus.value.copy(hibyOutputActive = false, pawsOutputActive = false)
    }

    fun telemetrySummary(): String = referenceTelemetrySummary(
        AppSettings.qualityOptions.value,
        AppSettings.referenceEngineMode.value,
        mutableStatus.value,
    )

    private fun rootCause(error: Throwable): String {
        val cause = generateSequence(error) { current ->
            when (current) {
                is InvocationTargetException -> current.targetException
                else -> current.cause
            }
        }.last()
        return "${cause.javaClass.simpleName}: ${cause.message ?: "no message"}"
    }
}
