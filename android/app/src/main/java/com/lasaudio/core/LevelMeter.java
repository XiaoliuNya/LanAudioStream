package com.lasaudio.core;

/**
 * 左右声道响度电平表数据源。
 *
 * <p>数据<b>完全在接收端本地</b>从未经压缩的解码 PCM 统计（不依赖桌面端回传），
 * 采用「每帧 RMS + 自上次读取以来的峰值」，UI 侧按屏幕刷新率取用并做弹道平滑，
 * 因此不会错过瞬态、也不会有回传延迟。
 *
 * <p>纯 Java 实现，可在桌面 JVM 上验证（android/tools/CoreProbe.java）。
 */
public final class LevelMeter {

    private volatile float rmsLeft;
    private volatile float rmsRight;
    private volatile float peakLeft;
    private volatile float peakRight;
    private volatile long lastUpdateMs;

    /** 用一帧解码后的交错 PCM（16bit）更新电平；channels >= 2 时取前两个声道。 */
    public void update(short[] pcm, int frames, int channels) {
        if (pcm == null || frames <= 0) return;
        int ch = Math.max(1, channels);
        int n = Math.min(frames * ch, pcm.length);
        if (n <= 0) return;

        double sumLeft = 0;
        double sumRight = 0;
        int used;
        if (ch >= 2) {
            int f = n / ch;
            if (f <= 0) return;
            for (int i = 0; i < f; i++) {
                double l = pcm[i * ch] / 32768.0;
                double r = pcm[i * ch + 1] / 32768.0;
                sumLeft += l * l;
                sumRight += r * r;
            }
            used = f;
        } else {
            for (int i = 0; i < n; i++) {
                double v = pcm[i] / 32768.0;
                sumLeft += v * v;
            }
            sumRight = sumLeft;
            used = n;
        }

        float l = (float) Math.sqrt(sumLeft / used);
        float r = (float) Math.sqrt(sumRight / used);
        rmsLeft = l;
        rmsRight = r;
        if (l > peakLeft) peakLeft = l;
        if (r > peakRight) peakRight = r;
        lastUpdateMs = System.currentTimeMillis();
    }

    /** 本帧是静音（欠载/PLC/静音补偿）时调用，让电平自然回落。 */
    public void silence() {
        rmsLeft = 0f;
        rmsRight = 0f;
        lastUpdateMs = System.currentTimeMillis();
    }

    public float rmsLeft() {
        return rmsLeft;
    }

    public float rmsRight() {
        return rmsRight;
    }

    public long lastUpdateMs() {
        return lastUpdateMs;
    }

    /** 取走自上次调用以来出现的峰值（高帧率 UI 每帧调用，避免漏掉瞬态）。 */
    public float consumePeakLeft() {
        float v = peakLeft;
        peakLeft = 0f;
        return v;
    }

    public float consumePeakRight() {
        float v = peakRight;
        peakRight = 0f;
        return v;
    }

    public void reset() {
        rmsLeft = 0f;
        rmsRight = 0f;
        peakLeft = 0f;
        peakRight = 0f;
        lastUpdateMs = 0L;
    }
}
