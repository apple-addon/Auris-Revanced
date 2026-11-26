package com.maxmpz.audioplayer.equalizer;

import com.maxmpz.dsp.data.Band;

/** Original PAWS coefficient JNI contract; implementation is in libpowerampcore. */
public final class Coefs {
    private Coefs() {}

    public static native int native_calc_bands_coefs(int sampleRate, Band[] bands, double[][] output);
    public static native int native_calc_coefs(int type, int sampleRate, int frequency, float q, double[] output);
    public static native void native_filter_evaluate_freqs(
        int type, int sampleRate, int frequency, float q, float gain, float preamp,
        int count, float[] frequencies, float[] output
    );
    private static native boolean native_modify_coefs_for_gain(
        double[] coefs, int type, int sampleRate, int frequency, float gain, float preamp
    );
}
