package com.lasaudio.core;

/** Monotonic microsecond clock (same base as used for all protocol timestamps). */
public final class MicroClock {

    private MicroClock() {
    }

    public static long nowUs() {
        return System.nanoTime() / 1000L;
    }
}
