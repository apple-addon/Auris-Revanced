package com.music.bitchord.playback.reference

import android.content.Context

/** Reflection is confined to setup/control calls; never used by the PCM writer. */
class HibyHostRuntime(private val loader: ClassLoader) {
    private var smartAv: Any? = null
    private var dspUtil: Any? = null
    private var msebIndex: Int = -1

    fun initialize(context: Context) {
        val configuration = loader.loadClass("com.hiby.music.sdk.HibySdkConfiguration")
            .getConstructor(Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(true, -1)
        loader.loadClass("com.hiby.music.sdk.HibyMusicSdk")
            .getMethod("init", Context::class.java, configuration.javaClass)
            .invoke(null, context.applicationContext, configuration)
        smartAv = loader.loadClass("com.hiby.music.sdk.SmartAv").getMethod("getInstance").invoke(null)
        dspUtil = loader.loadClass("com.hiby.music.sdk.util.DspUtil").getMethod("getInstance").invoke(null)
    }

    fun setMseb(enabled: Boolean, values: IntArray, preampDb: Float) {
        require(values.size == MSEB_KEYS.size) { "MSEB needs 10 values" }
        val dsp = dspUtil ?: error("HiBy DspUtil not initialized")
        if (!enabled) {
            if (msebIndex >= 0) {
                dsp.javaClass.getMethod("OnDspRemove", Int::class.javaPrimitiveType).invoke(dsp, msebIndex)
                msebIndex = -1
            }
            return
        }
        if (msebIndex < 0) {
            msebIndex = dsp.javaClass.getMethod("OnDspAdd", String::class.java).invoke(dsp, "MSEB") as Int
            check(msebIndex >= 0) { "DspUtil.OnDspAdd(MSEB) failed: $msebIndex" }
        }
        values.forEachIndexed { index, value ->
            dsp.javaClass.getMethod(
                "SetDspInfoInt",
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
            ).invoke(dsp, msebIndex, MSEB_KEYS[index], value.coerceIn(-100, 100))
        }
        dsp.javaClass.getMethod(
            "SetDspInfoFloat",
            Int::class.javaPrimitiveType,
            String::class.java,
            Float::class.javaPrimitiveType,
        ).invoke(dsp, msebIndex, "preamp", preampDb.coerceIn(-45f, 45f))
    }

    fun setPeq(enabled: Boolean, preampDb: Float, bands: Array<FloatArray>) {
        require(bands.size == 10) { "HiBy PEQ needs 10 bands" }
        val av = smartAv ?: error("HiBy SmartAv not initialized")
        val method = av.javaClass.getMethod("native_setObjectAttr", String::class.java, Any::class.java)
        method.invoke(av, "peq_global_param", floatArrayOf(if (enabled) 1f else 0f, preampDb, if (enabled) 1f else 0f))
        bands.forEachIndexed { index, band ->
            require(band.size == 5) { "PEQ band $index needs enabled/frequency/gain/Q/type" }
            method.invoke(
                av,
                "peq_param",
                floatArrayOf(index.toFloat(), band[0], band[1], band[2], band[3], band[4]),
            )
        }
    }

    fun setOutputRule(key: String, value: Int) {
        val av = smartAv ?: error("HiBy SmartAv not initialized")
        av.javaClass.getMethod("native_setIntAttr", String::class.java, Int::class.javaPrimitiveType)
            .invoke(av, key, value)
    }

    companion object {
        val MSEB_KEYS = listOf(
            "master_temp",
            "bass0",
            "bass1",
            "thickness",
            "vocal",
            "female",
            "male_vocal",
            "female_vocal",
            "instruments",
            "air",
        )
    }
}
