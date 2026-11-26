package com.maxmpz.equalizer.eq;

/** Original PAWS PEQ command JNI contract. */
public final class PeqNativeUtils {
    private PeqNativeUtils() {}

    public static native int command(Object context, int command, byte[] input, byte[] output);
    public static native int getParameter(Object context, byte[] key, byte[] output);
    public static native int poke_fx(Object context);
    public static native int setEnabled(Object context, boolean enabled);
    public static native int setParameter(Object context, byte[] parameter);
    public static native int setParameterCommit(Object context);
    public static native int setParameterDeferred(Object context, byte[] parameter);
}
