package com.music.bitchord.playback.reference.paws

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Looper
import com.music.bitchord.playback.reference.NativeStreamState
import com.music.bitchord.playback.reference.ReferenceEngineLoader
import com.music.bitchord.playback.reference.ReferenceNativeBridge
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/** Original XML parameter IDs for Damp, Filter, Fade, PreDelay, PreDelayMix, Size, Mix. */
internal val PAWS_REVERB_PARAMETER_IDS = intArrayOf(7, 2, 3, 4, 6, 1, 5)

/** Live PAWS Pipeline2 + output.cc graph. All methods fail with the real JNI/reflection error. */
data class PawsEqBand(
    val frequencyHz: Int,
    val gainDb: Float,
    val q: Float = 1f,
    val type: Int = 0,
    val enabled: Boolean = true,
    val parametric: Boolean = false,
)

/** Exact values consumed by the original Pipeline2/u21 control methods. */
data class PawsControlState(
    val bands: List<PawsEqBand>,
    val preampLinear: Float = 1f,
    val equEnabled: Boolean = true,
    val toneEnabled: Boolean = true,
    val limiterEnabled: Boolean = false,
    val bassGain: Float = 0f,
    val trebleGain: Float = 0f,
    val stereoWide: Float = 0f,
    val balance: Float = 0f,
    val monoEnabled: Boolean = false,
    val tempo: Float = 1f,
    val reverbEnabled: Boolean = false,
    val reverbValues: FloatArray = FloatArray(7),
) {
    init {
        require(bands.isNotEmpty()) { "PAWS needs at least one EQ band" }
        require(preampLinear in 0f..2f) { "PAWS preamp must be linear 0..2" }
        require(bassGain in 0f..1f && trebleGain in 0f..1f) { "PAWS tone gains must be 0..1" }
        require(stereoWide in 0f..1f) { "PAWS stereo-wide must be 0..1" }
        require(balance in -1f..1f) { "PAWS balance must be -1..1" }
        require(tempo in 0.5f..3f) { "PAWS tempo must be 0.5..3" }
        require(reverbValues.size == 7) { "PAWS reverb expects seven values" }
    }
}

class PawsHostRuntime(private val loader: ClassLoader) {
    private var application: Any? = null
    private var pluginManager: Any? = null
    private var pipeline: Any? = null
    private var ccService: Any? = null
    private var nativeRef = 0L
    private var inputFd = -1
    private var desiredControls = PawsControlState(bands = DEFAULT_BANDS)
    private var pcmOpen = false
    private var pcmOwner = 0
    private var pcmChannels = 0
    private var producedFrames = 0L
    val isReady: Boolean get() = nativeRef > 0
    var outputActive = false
        private set

    fun initialize(context: Context) {
        check(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            "PAWS PCM output currently supports arm64-v8a only; ARMv7 has no verified native_get_samples address"
        }
        val app = loader.loadClass("com.auris.audioplayer.Application").getConstructor().newInstance()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
            invoke(app, context.applicationContext)
        }
        application = app
        pluginManager = app.javaClass.getField("u").get(app)
        val manager = checkNotNull(pluginManager)
        val pluginArray = manager.javaClass.getDeclaredMethod("native_get_plugins").apply {
            isAccessible = true
        }.invoke(null)
        manager.javaClass.getField("A").set(manager, pluginArray)
        manager.javaClass.getField("X").setBoolean(manager, true)

        val pipelineClass = loader.loadClass("com.auris.audioplayer.processing.Pipeline2")
        val i7 = loader.loadClass("ׅ/i7").getConstructor().newInstance()
        val wv = allocate(loader.loadClass("ׅ/wv"))
        val stateBus = Proxy.newProxyInstance(
            loader,
            arrayOf(loader.loadClass("com.auris.widget.StateBus")),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getBooleanState" -> false
                    "getFloatState" -> 0f
                    "getIntState", "getStateBusId" -> 0
                    "getLongState" -> 0L
                    "getObjectState", "getStringState", "getStateMsgBus" -> null
                    "toString" -> "AurisStateBus"
                    else -> defaultValue(method)
                }
            },
        )
        pipeline = pipelineClass.getConstructor(
            Context::class.java,
            i7.javaClass,
            wv.javaClass,
            Looper::class.java,
            manager.javaClass,
            stateBus.javaClass,
            Int::class.javaPrimitiveType,
        ).newInstance(app, i7, wv, Looper.getMainLooper(), manager, stateBus, 0)

        val outputInfo = pluginInfo("com.auris.audioplayer/output.cc")
        ccService = loader.loadClass("com.auris.audioplayer.output.cc.CcPluginService")
            .getConstructor(Context::class.java, outputInfo.javaClass)
            .newInstance(app, outputInfo)
        applyOutputConfig(outputInfo)
        val outputContext = pipelineParam(0x1000)
        check(outputContext > 0) { "PAWS output.cc context is unavailable" }
        loader.loadClass("com.auris.audioplayer.output.cc.CcPluginService")
            .getMethod("native_register", Long::class.javaPrimitiveType, ccService!!.javaClass)
            .invoke(null, outputContext, ccService)
        nativeRef = pipelineParam(0x1001)
        check(nativeRef > 0) { "PAWS output.cc native reference is unavailable" }
    }

    private fun pluginInfo(uniqName: String): Any {
        val manager = checkNotNull(pluginManager)
        val direct = manager.javaClass.getMethod("х", String::class.java).invoke(manager, uniqName)
        if (direct != null) return direct
        val shortName = uniqName.substringAfterLast('/')
        return manager.javaClass.getMethod("х", String::class.java).invoke(manager, shortName)
            ?: error("PAWS output plugin not registered: $uniqName")
    }

    private fun applyOutputConfig(outputInfo: Any) {
        val current = checkNotNull(pipeline).javaClass.getMethod("A").invoke(pipeline)
        val outputClass = loader.loadClass("ׅ/t01")
        val oldOutput = current.javaClass.getField("Н").get(current)
        val output = outputClass.getConstructor(
            outputInfo.javaClass,
            Int::class.javaPrimitiveType,
            String::class.java,
            ByteArray::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        ).newInstance(
            outputInfo,
            oldOutput.javaClass.getField("B").getInt(oldOutput),
            oldOutput.javaClass.getField("А").get(oldOutput),
            oldOutput.javaClass.getField("A").get(oldOutput),
            oldOutput.javaClass.getField("х").getBoolean(oldOutput),
            oldOutput.javaClass.getField("Х").get(oldOutput),
            oldOutput.javaClass.getField("X").get(oldOutput),
            oldOutput.javaClass.getField("x").get(oldOutput),
            PawsAbi.PCM_FLOAT32,
            oldOutput.javaClass.getField("у").getInt(oldOutput),
            oldOutput.javaClass.getField("К").getInt(oldOutput),
            oldOutput.javaClass.getField("K").getLong(oldOutput),
        )
        val p41Class = current.javaClass
        val next = p41Class.getConstructor(
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            outputClass, Boolean::class.javaPrimitiveType,
            loader.loadClass("ׅ/g92"), Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).newInstance(
            current.javaClass.getField("В").getLong(current),
            current.javaClass.getField("B").getInt(current),
            current.javaClass.getField("А").getInt(current),
            current.javaClass.getField("A").getInt(current),
            current.javaClass.getField("х").getInt(current),
            current.javaClass.getField("Х").getInt(current),
            current.javaClass.getField("X").getInt(current),
            current.javaClass.getField("x").getInt(current),
            current.javaClass.getField("y").getInt(current),
            current.javaClass.getField("у").get(current),
            current.javaClass.getField("К").getInt(current),
            current.javaClass.getField("K").getInt(current),
            output,
            current.javaClass.getField("H").getBoolean(current),
            current.javaClass.getField("Р").get(current),
            current.javaClass.getField("P").getInt(current),
            current.javaClass.getField("р").getLong(current),
            current.javaClass.getField("p").getInt(current),
        )
        checkNotNull(pipeline).javaClass.getField("x").get(pipeline).javaClass
            .getMethod("l0", p41Class).invoke(checkNotNull(pipeline).javaClass.getField("x").get(pipeline), next)
    }

    private fun pipelineParam(selector: Int): Long = checkNotNull(pipeline).javaClass
        .getMethod("Р", Int::class.javaPrimitiveType).invoke(pipeline, selector) as Long

    @Synchronized
    fun openPcm(sampleRate: Int, channels: Int, ownerToken: Int = 0) {
        require(sampleRate > 0 && channels > 0)
        check(nativeRef > 0) { "PAWS native output is not initialized" }
        if (pcmOpen && pcmOwner != ownerToken) {
            val reason = "PCM FAILED: PAWS graph is busy by another sink"
            ReferenceEngineLoader.markPawsOutput(NativeStreamState.FAILED, reason)
            error(reason)
        }
        closePcm(ownerToken)
        producedFrames = 0L
        pcmChannels = channels
        try {
            // nativePawsOpenInput also creates a fresh output.cc byte array.
            inputFd = ReferenceNativeBridge.nativePawsOpenInput(sampleRate, channels)
            check(inputFd >= 0) { "nativePawsOpenInput=$inputFd" }
            val handle = checkNotNull(pipeline).javaClass.getField("K").getLong(pipeline)
            val open = checkNotNull(pipeline).javaClass.getDeclaredMethod(
                "native_request_open",
                Long::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(pipeline, handle, "pipe:$inputFd", inputFd, null, 0, 0, 0) as Int
            check(open >= 0) { "Pipeline2.native_request_open(pipe)=$open" }
            val resume = checkNotNull(pipeline).javaClass.getDeclaredMethod(
                "native_resume", Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(pipeline, handle, true) as Int
            check(resume >= 0) { "Pipeline2.native_resume=$resume" }
            applyDesiredControls()
            pcmOwner = ownerToken
            pcmOpen = true
            outputActive = false
            ReferenceEngineLoader.markPawsOutput(
                NativeStreamState.OPEN,
                "PAWS GRAPH READY · waiting for output.cc PCM",
                producedFrames = 0,
            )
        } catch (error: Throwable) {
            closeNativePcm()
            val reason = "INIT FAILED: PAWS PCM open: ${rootReason(error)}"
            ReferenceEngineLoader.markPawsOutput(NativeStreamState.FAILED, reason, producedFrames = 0)
            throw error
        }
    }

    /** Updates real PAWS controls; a live graph fails closed on rejection. */
    @Synchronized
    fun setControls(controls: PawsControlState) {
        desiredControls = controls.copy(
            bands = controls.bands.toList(),
            reverbValues = controls.reverbValues.copyOf(),
        )
        if (!pcmOpen) return
        try {
            applyDesiredControls()
        } catch (error: Throwable) {
            failPcm(pcmOwner, "PAWS control update: ${rootReason(error)}")
            throw error
        }
    }

    private fun applyDesiredControls() {
        val controls = desiredControls
        val controller = checkNotNull(pipeline).javaClass.getField("x").get(pipeline)
        val type = controller.javaClass
        type.getMethod("V", Boolean::class.javaPrimitiveType).invoke(controller, controls.equEnabled)
        type.getMethod("i0", Boolean::class.javaPrimitiveType).invoke(controller, controls.toneEnabled)
        type.getMethod("Q", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .invoke(controller, 0, controls.bassGain)
        type.getMethod("Q", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .invoke(controller, 1, controls.trebleGain)
        type.getMethod("c0", Float::class.javaPrimitiveType).invoke(controller, controls.preampLinear)
        type.getMethod("Y", Boolean::class.javaPrimitiveType).invoke(controller, controls.limiterEnabled)
        type.getMethod("f0", Float::class.javaPrimitiveType).invoke(controller, controls.stereoWide)
        type.getMethod("I", Float::class.javaPrimitiveType).invoke(controller, controls.balance)
        type.getMethod("a0", Boolean::class.javaPrimitiveType).invoke(controller, controls.monoEnabled)
        type.getMethod("g0", Float::class.javaPrimitiveType).invoke(controller, controls.tempo)
        setEqBands(controls.bands)
        setReverb(controls.reverbEnabled, controls.reverbValues)
    }

    /** Real DSPI band structures followed by their gain pairs (commands 0x14/0x15). */
    fun setEqBands(bands: List<PawsEqBand>) {
        val manager = checkNotNull(pluginManager)
        val dspi = pluginInfo("com.auris.audioplayer/plugin.dspi")
        val pluginId = dspi.javaClass.getField("id").getInt(dspi)
        val accepted = checkNotNull(pipeline).javaClass.getMethod("B", ByteBuffer::class.java)
            .invoke(pipeline, PawsDspMessages.eqStructure(pluginId, bands)) as Boolean
        check(accepted) { "PAWS DSPI rejected the band structure" }
        val gainsAccepted = checkNotNull(pipeline).javaClass.getMethod("С", IntArray::class.java)
            .invoke(pipeline, PawsDspMessages.eqGains(pluginId, bands)) as Boolean
        check(gainsAccepted) { "PAWS DSPI rejected the gain pairs" }
    }

    /** Real ReverbDSPPlugin parameters using the original XML IDs, not visual-order guesses. */
    fun setReverb(enabled: Boolean, values: FloatArray) {
        require(values.size == 7) { "PAWS reverb expects seven values" }
        val reverb = pluginInfo("com.auris.audioplayer/plugin.reverb")
        val pluginId = reverb.javaClass.getField("id").getInt(reverb)
        val method = checkNotNull(pipeline).javaClass.getMethod(
            "В",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        check(method.invoke(pipeline, pluginId, 1, 0, if (enabled) 1 else 0, 0, 0, 0) as Boolean) {
            "PAWS reverb enable was rejected"
        }
        if (enabled) {
            values.forEachIndexed { index, value ->
                val parameter = PAWS_REVERB_PARAMETER_IDS[index]
                check(method.invoke(pipeline, pluginId, 2, 0, parameter, value.toRawBits(), 0, 0) as Boolean) {
                    "PAWS reverb parameter $parameter was rejected"
                }
            }
        }
    }

    /** Returns bytes accepted by the lock-free native input ring. */
    @Synchronized
    fun writePcm(
        ownerToken: Int,
        buffer: ByteBuffer,
        byteOffset: Int = 0,
        byteCount: Int = buffer.remaining(),
    ): Int {
        checkOpen(ownerToken)
        val result = ReferenceNativeBridge.nativePawsWriteInput(buffer, byteOffset, byteCount)
        if (result < 0) {
            failPcm(ownerToken, "PAWS input ring error=$result")
        }
        return result
    }

    /** Returns real PAWS-processed PCM bytes from output.cc. */
    @Synchronized
    fun readPcm(destination: ByteBuffer, ownerToken: Int): Int {
        checkOpen(ownerToken)
        val result = ReferenceNativeBridge.nativePawsReadOutput(nativeRef, destination)
        if (result < 0) {
            failPcm(ownerToken, "PAWS output.cc read error=$result")
            return result
        }
        if (result == 0) return 0
        val frameBytes = pcmChannels * Float.SIZE_BYTES
        if (result % frameBytes != 0) {
            failPcm(ownerToken, "PAWS output is not a whole Float32 frame: $result bytes")
            return -1
        }
        producedFrames += result / frameBytes
        if (!outputActive) {
            outputActive = true
            ReferenceEngineLoader.markPawsOutput(
                NativeStreamState.ACTIVE,
                "PAWS NATIVE ACTIVE · Float32 pipe → DSPI → output.cc",
                producedFrames,
            )
        }
        return result
    }

    @Synchronized
    fun failPcm(ownerToken: Int, reason: String) {
        if (!pcmOpen || pcmOwner != ownerToken) return
        closeNativePcm()
        val detail = if (reason.startsWith("PCM FAILED: ")) reason else "PCM FAILED: $reason"
        ReferenceEngineLoader.markPawsOutput(NativeStreamState.FAILED, detail, producedFrames)
    }

    @Synchronized
    fun closePcm(ownerToken: Int? = null) {
        if (ownerToken != null && pcmOpen && pcmOwner != ownerToken) return
        if (!pcmOpen && inputFd < 0) return
        closeNativePcm()
        ReferenceEngineLoader.markPawsOutput(NativeStreamState.CLOSED, producedFrames = 0)
    }

    private fun closeNativePcm() {
        pcmOpen = false
        outputActive = false
        pcmOwner = 0
        pcmChannels = 0
        if (inputFd >= 0) {
            runCatching {
                val handle = checkNotNull(pipeline).javaClass.getField("K").getLong(pipeline)
                checkNotNull(pipeline).javaClass.getDeclaredMethod(
                    "native_stop", Long::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                ).apply { isAccessible = true }.invoke(pipeline, handle, true, true)
            }
        }
        ReferenceNativeBridge.nativePawsClose()
        inputFd = -1
    }

    private fun checkOpen(ownerToken: Int) {
        check(pcmOpen && pcmOwner == ownerToken) { "PAWS pipeline is not open for this sink" }
    }

    private fun rootReason(error: Throwable): String {
        val cause = generateSequence(error) { current ->
            if (current is java.lang.reflect.InvocationTargetException) current.targetException else current.cause
        }.last()
        return "${cause.javaClass.simpleName}: ${cause.message ?: "no message"}"
    }

    fun close() {
        closePcm()
        if (nativeRef > 0) {
            runCatching {
                loader.loadClass("com.auris.audioplayer.output.cc.NativeRef")
                    .getDeclaredMethod("native_release", Long::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(null, nativeRef)
            }
            nativeRef = 0
        }
        runCatching {
            val handle = checkNotNull(pipeline).javaClass.getField("K").getLong(pipeline)
            checkNotNull(pipeline).javaClass.getDeclaredMethod("native_release", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(pipeline, handle)
        }
        pipeline = null
        ccService = null
        pluginManager = null
        application = null
    }

    private fun allocate(type: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)
    }

    private fun defaultValue(method: Method): Any? = when (method.returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Float.TYPE -> 0f
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
    }

    private companion object {
        val DEFAULT_BANDS = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
            .map { PawsEqBand(frequencyHz = it, gainDb = 0f, q = 1f) }
    }
}
