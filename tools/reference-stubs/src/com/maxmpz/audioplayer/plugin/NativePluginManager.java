package com.maxmpz.audioplayer.plugin;

/** Original PAWS native-plugin discovery JNI contract. */
public class NativePluginManager {
    private static native NativePluginInfo[] native_get_plugins();
}
