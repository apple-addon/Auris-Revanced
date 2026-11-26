package com.maxmpz.audioplayer.plugin;

/** Original PAWS internal-output JNI contract. */
public final class OutputInternalHelper {
    private final native int native_output_get_generic_property(int arg0, int arg1, int arg2);
    private final native long native_output_get_options(int arg0, int arg1, byte[] options);
    private final native int native_output_get_sample_formats(int arg0, int arg1);
    private final native int native_output_get_sample_rates(int arg0, int arg1, boolean arg2);
    private final native int native_output_get_supported_devices(int arg0);
}
