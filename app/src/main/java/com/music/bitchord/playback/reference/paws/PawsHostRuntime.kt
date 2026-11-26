package com.music.bitchord.playback.reference.paws

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import com.music.bitchord.playback.reference.ReferenceEngineLoader
import com.music.bitchord.playback.reference.ReferenceNativeBridge
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/** Live PAWS Pipeline2 + output.cc graph. All methods fail with the real JNI/reflection error. */
data class PawsEqBand(
    val frequencyHz: Int,
    val gainDb: Float,
    val q: Float = 1f,
    val type: Int = 0,
    val enabled: Boolean = true,
    val parametric: Boolean = false,
)

class PawsHostRuntime(private val loader: ClassLoader) {
    private var application: Any? = null
    private var pluginManager: Any? = null
    private var pipeline: Any? = null
    private var ccService: Any? = null
    private var nativeRef = 0L
    private var inputFd = -1
    private var defaultsApplied = false
    val isReady: Boolean get() = nativeRef > 0
    var outputActive = false
        private set

    fun initialize(context: Context) {
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
        check(ReferenceNativeBridge.nativePawsPrepareOutput() == 0) { "nativePawsPrepareOutput failed" }
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

    fun openPcm(sampleRate: Int, channels: Int) {
        require(sampleRate > 0 && channels > 0)
        closePcm()
        inputFd = ReferenceNativeBridge.nativePawsOpenInput(sampleRate, channels)
        check(inputFd >= 0) { "Unable to create PAWS PCM pipe" }
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
        if (!defaultsApplied) {
            setEqBands(DEFAULT_BANDS)
            setReverb(false, FloatArray(7))
            defaultsApplied = true
        }
        outputActive = true
        ReferenceEngineLoader.markPawsOutput(true, "PAWS NATIVE ACTIVE · Float32 pipe → DSPI → output.cc")
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

    /** Real ReverbDSPPlugin float parameters; indexes 1–7 map to the seven hub knobs. */
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
                check(method.invoke(pipeline, pluginId, 2, 0, index + 1, value.toRawBits(), 0, 0) as Boolean) {
                    "PAWS reverb parameter ${index + 1} was rejected"
                }
            }
        }
    }

    /** Returns bytes accepted by the lock-free native input ring. */
    fun writePcm(buffer: ByteBuffer, byteOffset: Int = 0, byteCount: Int = buffer.remaining()): Int {
        check(outputActive) { "PAWS pipeline is not open" }
        return ReferenceNativeBridge.nativePawsWriteInput(buffer, byteOffset, byteCount)
    }

    /** Returns real PAWS-processed PCM bytes from output.cc. */
    fun readPcm(destination: ByteBuffer): Int =
        ReferenceNativeBridge.nativePawsReadOutput(nativeRef, destination)

    fun closePcm() {
        if (!outputActive && inputFd < 0) return
        outputActive = false
        ReferenceEngineLoader.markPawsOutput(false, null)
        runCatching {
            val handle = checkNotNull(pipeline).javaClass.getField("K").getLong(pipeline)
            checkNotNull(pipeline).javaClass.getDeclaredMethod(
                "native_stop", Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(pipeline, handle, true, true)
        }
        ReferenceNativeBridge.nativePawsClose()
        inputFd = -1
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
