package com.hiby.music.sdk.util;

public final class AudioUtils {
    private AudioUtils() {}
    public static native void nativeSetDirectBuffer(Object buffer, int size);
}
