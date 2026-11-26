package com.hiby.music.sdk;

/** Original HiBy SmartAv JNI contract. Library loading is handled separately. */
public class SmartAv {
    public SmartAv() {}

    public static native void nativeSetSmartAudioBackend(int backend);

    public native byte[] dumpAudioUnit();

    public native int native_SonyPlay(
        String path,
        int arg1,
        int arg2,
        int arg3,
        int arg4,
        int arg5,
        String arg6,
        String arg7,
        String arg8
    );

    public native int native_deinit();
    public native void native_flush();
    public native int native_getDuration();
    public native String native_getEmbeddedCue(String path);
    public native String native_getEmbeddedLyric(String path);
    public native int native_getIntAttr(String name);
    public native int native_getLocalMmqSampleBit();
    public native String native_getLyric(String path);
    public native void native_getMmqMessage();
    public native Object native_getObjectAttr(String name, int mmqFunctionIsOpen);
    public native Object native_getObjectSonyAttr(
        String arg0, String arg1, String arg2, String arg3, int arg4
    );
    public native int native_getRoonSampleBit();
    public native int native_getRoonSampleRate();
    public native int native_getSupportBitsMask();
    public native byte[] native_get_current_device_name();
    public native int native_get_usbdevice_type(int arg0, int arg1);
    public native int native_init(
        int deviceType,
        String externalDevicePath,
        int defaultOutput,
        boolean supportDstDecode,
        int mmqFunctionIsOpen,
        String pluginPath
    );
    public native void native_pause();
    public native int native_play(String path);
    public native int native_play(
        String path,
        int arg1,
        int arg2,
        int arg3,
        String arg4,
        int arg5,
        int arg6
    );
    public native int native_position();
    public native void native_replayGain(String arg0, String arg1, int arg2);
    public native void native_resetMmqMessageCatch();
    public native void native_resume();
    public native void native_seek(int position);
    public native void native_setDsd2pcmSampleRate(int arg0, int arg1);
    public native void native_setDsdSampleRateSupport(int sampleRate);
    public native void native_setIntAttr(String name, int value);
    public native void native_setLiveMode(int mode);
    public native void native_setMaxSampleRate(int sampleRate);
    public native void native_setMmqDither(int dither);
    public native void native_setMmqMaxRate(int sampleRate);
    public native void native_setObjectAttr(String name, Object value);
    public native int native_setOutputDevice(int device);
    public native void native_setPeqValue(String name, float value);
    public native void native_setPeqValue(String name, int value);
    public native int native_setResetAlt(int value);
    public native int native_setSampleBits(int bits);
    public native int native_setUacBusSpeed(int speed);
    public native int native_setUacDsdCompensate(int value);
    public native int native_setUacMaxDsdSampleRate(int sampleRate);
    public native int native_setUacPackageAdjust(int value);
    public native int native_setUacWorkMode(int mode);
    public native void native_stop();
}
