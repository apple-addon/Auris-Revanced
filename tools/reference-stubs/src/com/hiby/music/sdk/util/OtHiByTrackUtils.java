package com.hiby.music.sdk.util;

/** Original HiBy native track/output JNI contract. */
public final class OtHiByTrackUtils {
    private OtHiByTrackUtils() {}

    public static native int createTrack(int sampleRate, int channels, int bits, int format);
    public static native int destruct();
    public static native int latency();
    public static native long nowConsumed(long[] position);
    public static native int release();
    public static native int streamEnd();
    public static native int streamStart();
    public static native int write(byte[] data, int size);
}
