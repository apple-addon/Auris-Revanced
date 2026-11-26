package com.maxmpz.dsp.data;

import java.util.concurrent.atomic.AtomicInteger;

/** Original PAWS band data and constructor semantics used by JNI. */
public final class Band {
    public static final AtomicInteger P = new AtomicInteger(1);
    public static final AtomicInteger Р = new AtomicInteger(1);

    public final boolean A;
    public int K;
    public float X;
    private int channels;
    private int frequency;
    private float q;
    private int type;
    public String x;
    public boolean y = true;
    public final float А;
    public boolean К;
    public final int Х;
    public int у;
    public final boolean х;

    public Band(
        int type,
        int frequency,
        float q,
        String name,
        boolean isEqu,
        boolean flag,
        int channels,
        int color
    ) {
        this.Х = P.getAndIncrement();
        if (type < 0 || type > 5) {
            throw new IllegalArgumentException("type=" + type);
        }
        this.type = type;
        this.frequency = frequency;
        this.q = q;
        this.channels = channels;
        this.у = color;
        this.x = name;
        this.A = isEqu;
        this.А = isEqu ? -1.0f : 0.0f;
        this.х = flag;
        this.K = Р.getAndIncrement();
    }

    public Band(int type, int frequency, float q, String name, boolean isEqu, boolean flag) {
        this(type, frequency, q, name, isEqu, flag, 0, 0);
    }
}
