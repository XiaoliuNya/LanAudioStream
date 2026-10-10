package com.lasaudio.core;

import java.lang.reflect.Method;

/**
 * 线程优先级：把接收/播放线程提到音频级优先级，减少系统调度抖动造成的收包延迟与欠载。
 *
 * <p>纯 Java 实现（通过反射调用 {@code android.os.Process.setThreadPriority}），
 * 因此同一份代码也能在桌面 JVM 上编译/运行（非 Android 环境下自动忽略）。
 */
public final class ThreadTuner {

    /** android.os.Process.THREAD_PRIORITY_URGENT_AUDIO */
    public static final int URGENT_AUDIO = -19;
    /** android.os.Process.THREAD_PRIORITY_AUDIO */
    public static final int AUDIO = -16;

    private static Method setThreadPriority;
    private static boolean resolved;

    private ThreadTuner() {
    }

    /** 在目标线程的 run() 开头调用：提升当前线程（Java 优先级 + Android 音频优先级）。 */
    public static void tune(int priority) {
        try {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        } catch (Throwable ignored) {
        }
        try {
            if (!resolved) {
                resolved = true;
                Class<?> process = Class.forName("android.os.Process");
                setThreadPriority = process.getMethod("setThreadPriority", int.class);
            }
            if (setThreadPriority != null) {
                setThreadPriority.invoke(null, priority);
            }
        } catch (Throwable ignored) {
            // 非 Android 环境或平台不允许：忽略
        }
    }
}
