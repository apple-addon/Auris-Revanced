package com.hiby.music.sdk.util;

public final class AudioUtilsHiRes {
    private AudioUtilsHiRes() {}
    public static native void nativeSetDirectBuffer(Object buffer, int size);
}
