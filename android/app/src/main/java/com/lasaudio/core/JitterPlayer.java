package com.lasaudio.core;

import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Adaptive playout buffer.
 *
 * <p>关键点：
 * <ul>
 *   <li>维持一个明确的目标缓冲时延，短暂网络卡顿由缓冲吸收而不是补静音；</li>
 *   <li>下一帧还没到时，只要音频设备里还有音频就继续等（最多 400ms），保住发送端的帧冗余恢复；</li>
 *   <li><b>只有「真正饿死」才加大目标缓冲</b>：丢包由 PLC 补偿，不再计入自适应（否则周期性丢包会把延迟一路棘轮推高）；</li>
 *   <li>回落更快（干净 6 秒即回落一档），避免延迟只涨不降；</li>
 *   <li>接收→播放路径零分配（缓冲与 Frame 复用），降低 GC 抖动。</li>
 * </ul>
 *
 * <p>纯 Java 实现，便于在桌面 JVM 上用同一份代码验证（android/tools/CoreProbe.java）。
 */
public final class JitterPlayer {

    private static final long WAIT_STEP_MS = 4;
    private static final long START_TIMEOUT_MS = 150;
    private static final long LONG_IDLE_MS = 1500;
    private static final long MAX_STALL_WAIT_MS = 400;
    /**
     * 设备里还剩这么多音频时必须立刻写入。播放按绝对时刻表推进，设备稳态只有约 1 帧，
     * 所以门槛必须小于 1 帧（6ms）才能让「等一帧晚到的包（帧冗余恢复）」真正生效；
     * 取 25ms 时该判定恒为真，等于从不等待（实测注入停顿后 device=0，欠载持续累积）。
     */
    private static final int MIN_DEVICE_BUFFER_MS = 6;
    /** 队列比目标深这么多就主动剪掉最旧的未播帧，换回有界延迟（实测 170ms 队列 / 60ms 目标）。 */
    private static final int DRAIN_MARGIN_MS = 40;
    private static final long DRAIN_MIN_INTERVAL_MS = 1000;
    /** 超过该到达空档就不是网络停顿，而是发送端本来没在放声音（PC 静音 / DTX / 刚连接）。 */
    private static final long MAX_GAP_MS = 600;
    /** 这么久没收到任何包（含 DTX 标记）就认为发送端空闲：只写静音，不计欠载、不加深缓冲。 */
    private static final long IDLE_AFTER_MS = 250;
    /** 停顿后多久内还有包到达才算「流还在继续」（用于确认网络停顿）。 */
    private static final long GAP_CONFIRM_MS = 250;
    private static final int ADAPT_INTERVAL_MS = 2000;
    private static final int TARGET_STEP_UP_MS = 10;
    private static final int TARGET_STEP_DOWN_MS = 10;
    private static final int CLEAN_RUNS_BEFORE_STEP_DOWN = 3;

    private static final int MIN_TARGET_MS = 40;
    private static final int MAX_TARGET_MS = 400;   // 网络会成串交付时（例如射频 250ms 缓冲）需要更深的缓冲兜住
    private static final int GAP_MARGIN_MS = 50;    // 覆盖一次停顿所需的余量
    private static final long GAP_QUIET_MS = 30000; // 连续 30 秒没有停顿才允许回落
    private static final int START_TARGET_MS = 60;

    private static final int POOL_SLOTS = 96;
    private static final int MIN_BUFFER = 512;

    private final PcmSink sink;
    private final Object lock = new Object();
    private final TreeMap<Long, Frame> queue = new TreeMap<>();

    private volatile boolean running;
    private Thread thread;

    private OpusDecoderWrapper decoder;
    private int sampleRate = 48000;
    private int channels = 2;
    private int frameSamples = 480;
    private int frameMs = 10;
    private int codec = Proto.CODEC_OPUS;
    private long frameUs = 10_000L;

    private long expectedSeq = -1;
    private long lastReceivedSeq = -1;
    private long lastSilenceSeq = -1;
    private long lastCaptureUs;
    private boolean started;
    private long firstArrivalMs;
    private long lastFrameMs = System.currentTimeMillis();
    /** 最近一次收到任何音频包（含 DTX 标记）的时刻：区分「丢包」与「发送端本来就没声音」。 */
    private long lastArrivalMs;
    /** 最近一帧是 DTX 静音标记。 */
    private boolean dtxActive;
    private long lastDrainMs;
    /** 待确认的到达停顿：只有「停顿后流还在继续」才算网络停顿（否则是发送端本来没声音）。 */
    private long pendingGapMs;
    private long pendingGapAtMs;

    private int minTargetMs = MIN_TARGET_MS;
    private int maxTargetMs = MAX_TARGET_MS;
    private int targetMs = START_TARGET_MS;
    private int prefillFrames = 2;
    private int maxQueueFrames = 22;
    private long scheduleUs;
    private long framesScheduled;
    private long lastAdaptMs;
    private int underrunsSinceAdapt;
    private int plcSinceAdapt;
    private long maxObservedGapMs;
    private long lastGapAtMs;
    private int cleanRuns;
    private volatile int jitterMode;

    private long plcFrames;
    private long underruns;
    private long silenceFrames;
    private long decodedFrames;
    private long lateFrames;
    private long trimmedFrames;
    private long decodeErrors;
    private long recoveredFrames;
    private long dtxFrames;
    private long dtxPlayed;
    /** 发送端空闲时补的静音帧数（不计入欠载）。 */
    private long idleSilenceFrames;
    private long recvCount;
    private long dupCount;
    private long bytesReceived;
    private double jitterUs;
    private volatile double e2eUs = -1;
    private volatile double bufferUs;
    private volatile int deviceBufferedMs;
    private volatile int reportedTargetMs;
    private double lastTransitUs;
    private boolean haveTransit;

    private volatile long clockOffsetUs;
    private volatile long rttUs;
    private LevelMeter levelMeter;
    private volatile Consumer<String> logSink;

    private short[] pcmOut = new short[5760 * 2];
    private short[] silence = new short[5760 * 2];

    // ---- 零分配：PCM 负载缓冲池 + Frame 复用 ----
    private final BufferPool pool = new BufferPool();
    private final Frame[] freeFrames = new Frame[POOL_SLOTS];
    private int freeFrameCount;

    public JitterPlayer(PcmSink sink) {
        this.sink = sink;
    }

    private static final class Frame {
        byte[] buf;
        int len;
        long captureUs;
        boolean silent;
    }

    private static final class BufferPool {
        private final byte[][] bufs = new byte[POOL_SLOTS][];
        private int count;

        synchronized byte[] acquire(int len) {
            for (int i = 0; i < count; i++) {
                byte[] b = bufs[i];
                if (b.length >= len) {
                    bufs[i] = bufs[--count];
                    bufs[count] = null;
                    return b;
                }
            }
            return new byte[Math.max(len, MIN_BUFFER)];
        }

        synchronized void release(byte[] b) {
            if (b == null || count >= bufs.length) return;
            bufs[count++] = b;
        }
    }

    /** 只在持有 lock 时调用。 */
    private Frame obtainFrame(byte[] buf, int len, long captureUs, boolean silent) {
        Frame f;
        if (freeFrameCount > 0) {
            f = freeFrames[--freeFrameCount];
            freeFrames[freeFrameCount] = null;
        } else {
            f = new Frame();
        }
        f.buf = buf;
        f.len = len;
        f.captureUs = captureUs;
        f.silent = silent;
        return f;
    }

    /** 只回收 Frame 对象本身（缓冲可能仍在解码/播放，不能归还缓冲池）。只在持有 lock 时调用。 */
    private void recycleFrameObject(Frame f) {
        if (f == null) return;
        if (freeFrameCount < freeFrames.length) {
            freeFrames[freeFrameCount++] = f;
        }
    }

    /** 回收 Frame 及其缓冲（帧已确定不再播放时使用）。只在持有 lock 时调用。 */
    private void recycleFrame(Frame f) {
        if (f == null) return;
        if (f.buf != null) {
            pool.release(f.buf);
            f.buf = null;
        }
        recycleFrameObject(f);
    }

    private void releaseAllFrames() {
        for (Frame f : queue.values()) recycleFrame(f);
        queue.clear();
    }

    /** 调试/诊断输出（抖动缓冲自适应、异常等）。 */
    public void setLogSink(Consumer<String> sink) {
        this.logSink = sink;
    }

    private void log(String message) {
        Consumer<String> s = logSink;
        if (s == null) return;
        try {
            s.accept(message);
        } catch (Throwable ignored) {
        }
    }

    /** 当前自适应目标缓冲（毫秒）。 */
    public int targetBufferMs() {
        return reportedTargetMs;
    }

    /** 最近观察到的最大到达停顿（毫秒）。 */
    public long maxObservedGapMs() {
        return maxObservedGapMs;
    }

    /**
     * 通知一次「包到达停顿」。发送端/网络把若干包成串交付时（实测 250ms 停顿后一次性冲出 25~30 包、不丢包），
     * 目标缓冲必须至少覆盖这段停顿，否则每次停顿都会补静音。
     * 只有自动模式才调整；延迟优先/稳定优先由用户固定。
     */
    public void noteArrivalGap(long gapMs) {
        if (gapMs < 60) return;
        // 发送端很久没发包（PC 没在放声音 / DTX 静音段 / 刚连上）不是「网络停顿」：
        // 实测停播 10 秒后再播放会把目标缓冲顶到 400ms 上限，并且 25 秒内不回落。
        if (gapMs > MAX_GAP_MS) return;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            // 不立刻加深：先记下来，等「停顿之后还有包继续到达」再确认（见 confirmPendingGapLocked）。
            // 否则 PC 停止放声音（发送端直接不发包）产生的空档会被当成网络停顿，
            // 把目标缓冲顶到 400ms 上限并长期不回落（实测 e2e 从 216ms 变 576ms）。
            pendingGapMs = Math.max(pendingGapMs, gapMs);
            pendingGapAtMs = now;
            if (gapMs > maxObservedGapMs) maxObservedGapMs = gapMs;
            if (jitterMode != 0) return;
            lastGapAtMs = now;
        }
    }

    /** 到达后确认：只有在停顿结束后仍持续收到包，才把目标缓冲加深到「停顿 + 余量」。 */
    private void confirmPendingGapLocked(long nowMs) {
        if (pendingGapMs == 0) return;
        if (nowMs - pendingGapAtMs > GAP_CONFIRM_MS) {
            pendingGapMs = 0;   // 停顿之后没有后续流量：发送端空闲，不是网络停顿
            return;
        }
        long needed = pendingGapMs + GAP_MARGIN_MS;
        pendingGapMs = 0;
        lastGapAtMs = nowMs;
        if (jitterMode != 0) return;
        if (needed > targetMs && targetMs < maxTargetMs) {
            int before = targetMs;
            targetMs = (int) Math.min(maxTargetMs, needed);
            updateTargetLocked();
            log("缓冲 " + before + "→" + targetMs + "ms（停顿 " + (needed - GAP_MARGIN_MS) + "ms）");
        }
    }

    public void configure(OpusDecoderWrapper decoder, int sampleRate, int channels, int frameMs, int codec) {
        synchronized (lock) {
            this.decoder = decoder;
            this.codec = codec;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.frameMs = frameMs;
            this.frameSamples = sampleRate * frameMs / 1000;
            this.frameUs = frameMs * 1000L;
            applyModeLocked();
            int need = 5760 * Math.max(2, channels);
            if (pcmOut.length < need) {
                pcmOut = new short[need];
                silence = new short[need];
            }
            deviceBufferedMs = sink.openAndGetBufferMillis(sampleRate, channels);
            resetSessionLocked();
            lock.notifyAll();
        }
    }

    private void applyModeLocked() {
        minTargetMs = MIN_TARGET_MS;
        maxTargetMs = MAX_TARGET_MS;
        if (targetMs < minTargetMs) targetMs = minTargetMs;
        if (targetMs > maxTargetMs) targetMs = maxTargetMs;
        updateTargetLocked();
    }

    private void updateTargetLocked() {
        prefillFrames = Math.max(1, (targetMs + frameMs - 1) / frameMs);
        maxQueueFrames = Math.max(prefillFrames + 4, (maxTargetMs + frameMs - 1) / frameMs + 4);
        reportedTargetMs = targetMs;
    }

    private void resetSessionLocked() {
        releaseAllFrames();
        expectedSeq = -1;
        lastReceivedSeq = -1;
        lastSilenceSeq = -1;
        lastCaptureUs = 0;
        started = false;
        firstArrivalMs = 0;
        haveTransit = false;
        jitterUs = 0;
        bufferUs = 0;
        scheduleUs = 0;
        framesScheduled = 0;
        lastAdaptMs = System.currentTimeMillis();
        underrunsSinceAdapt = 0;
        plcSinceAdapt = 0;
        cleanRuns = 0;
    }

    /** 每次（重新）连接时清空会话状态：序号从 0 重新开始，不能被误判为过晚帧。 */
    public void resetSession() {
        synchronized (lock) {
            resetSessionLocked();
        }
    }

    /** 把一个收到（或冗余恢复）的音频帧入队；{@code src} 由调用方复用，这里会拷贝到缓冲池里。 */
    public void onFrame(long seq, byte[] src, int off, int len, long captureUs, boolean redundancyRecovered) {
        long arrivalUs = MicroClock.nowUs();
        synchronized (lock) {
            if (decoder == null && codec != Proto.CODEC_OPUS_PCM) return;
            lastArrivalMs = System.currentTimeMillis();
            dtxActive = false;
            confirmPendingGapLocked(lastArrivalMs);
            if (expectedSeq >= 0 && seq < expectedSeq - 1) {
                lateFrames++;
                return;
            }
            if (queue.containsKey(seq)) {
                dupCount++;
                return;
            }
            if (lastSilenceSeq >= 0 && seq > lastSilenceSeq + 1) {
                fillSilenceGap(lastSilenceSeq, seq, captureUs);
            }
            lastSilenceSeq = -1;   // 收到真实音频帧，DTX 静音段结束
            if (redundancyRecovered) recoveredFrames++;
            byte[] buf = pool.acquire(len);
            System.arraycopy(src, off, buf, 0, len);
            queue.put(seq, obtainFrame(buf, len, captureUs, false));
            if (seq > lastReceivedSeq) lastReceivedSeq = seq;
            if (firstArrivalMs == 0) firstArrivalMs = System.currentTimeMillis();
            recvCount++;
            bytesReceived += len + Proto.HEADER_SIZE;

            double transit = arrivalUs - captureUs;
            if (haveTransit) {
                double d = transit - lastTransitUs;
                jitterUs += (Math.abs(d) - jitterUs) / 16.0;
            }
            lastTransitUs = transit;
            haveTransit = true;

            if (queue.size() > maxQueueFrames) {
                // 硬上限：真正丢掉最旧的「未播」帧并把 expectedSeq 前移。
                // 旧实现遇到 k >= expectedSeq 就 break，而队列里的帧恒 >= expectedSeq，
                // 等于永远不裁剪 —— 任何一次落后都会变成永久延迟。
                int drop = queue.size() - prefillFrames;
                boolean trimmed = false;
                for (int i = 0; i < drop; i++) {
                    Long k = queue.isEmpty() ? null : queue.firstKey();
                    if (k == null) break;
                    recycleFrame(queue.remove(k));
                    trimmedFrames++;
                    lateFrames++;
                    trimmed = true;
                }
                if (trimmed && !queue.isEmpty()) expectedSeq = queue.firstKey();
            }
            lastFrameMs = System.currentTimeMillis();
            lock.notifyAll();
        }
    }

    /**
     * DTX 静音标记：发送端在静音段只发少量 24 字节标记包，这里按序号把空档补成静音帧，
     * 播放时直接写 0，不计欠载也不计丢包。
     */
    public void onSilenceFrame(long seq, long captureUs) {
        synchronized (lock) {
            if (decoder == null && codec != Proto.CODEC_OPUS_PCM) return;
            lastArrivalMs = System.currentTimeMillis();
            dtxActive = true;
            confirmPendingGapLocked(lastArrivalMs);
            if (expectedSeq >= 0 && seq < expectedSeq - 1) return;
            if (queue.containsKey(seq)) {
                dupCount++;
                return;
            }
            if (lastSilenceSeq >= 0 && seq > lastSilenceSeq + 1) {
                fillSilenceGap(lastSilenceSeq, seq, captureUs);
            }
            queue.put(seq, obtainFrame(null, 0, captureUs, true));
            lastSilenceSeq = seq;
            if (seq > lastReceivedSeq) lastReceivedSeq = seq;
            if (firstArrivalMs == 0) firstArrivalMs = System.currentTimeMillis();
            recvCount++;
            dtxFrames++;
            lastFrameMs = System.currentTimeMillis();
            lock.notifyAll();
        }
    }

    /** 把 DTX 静音段里没发包的序号补成静音帧（不计丢包/欠载）。只在持有 lock 时调用。 */
    private void fillSilenceGap(long fromExclusive, long toExclusive, long captureUs) {
        for (long s = fromExclusive + 1; s < toExclusive; s++) {
            if (expectedSeq >= 0 && s < expectedSeq) continue;
            long cu = captureUs - (toExclusive - s) * frameUs;
            if (!queue.containsKey(s)) {
                queue.put(s, obtainFrame(null, 0, cu, true));
                recvCount++;
                dtxFrames++;
            }
        }
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "jitter-player");
        thread.setDaemon(true);
        try {
            thread.setPriority(Thread.MAX_PRIORITY);
        } catch (Throwable ignored) {
        }
        thread.start();
    }

    public void stop() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        sink.close();
    }

    private void loop() {
        ThreadTuner.tune(ThreadTuner.URGENT_AUDIO);   // 播放线程：音频级最高优先级
        while (running) {
            try {
                loopOnce();
            } catch (Throwable t) {
                lastError = t.toString();
                errors++;
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }
    }

    private volatile String lastError = "-";
    private volatile long errors;

    public String getLastError() {
        return lastError;
    }

    public long getErrors() {
        return errors;
    }

    private void loopOnce() {
        byte[] payloadBuf = null;
        int payloadLen = 0;
        long captureUs = 0;
        boolean plc = false;
        boolean silenceFrame = false;
        boolean dtxSilent = false;
        boolean idleSilence = false;
        long waitUs = 0;
        // 锁内快照（configure() 会在接收线程上整体替换这些字段，出锁后再读会拿到不一致的组合）
        OpusDecoderWrapper dec;
        short[] out;
        int fs;
        int chs;
        int codecLocal;
        long offsetUs;

        synchronized (lock) {
            if ((decoder == null && codec != Proto.CODEC_OPUS_PCM) || !running) {
                waitFor(50);
                return;
            }
            if (expectedSeq < 0) {
                if (queue.isEmpty()) {
                    waitFor(20);
                    return;
                }
                expectedSeq = queue.firstKey();
                started = false;
                firstArrivalMs = System.currentTimeMillis();
                scheduleUs = 0;
                framesScheduled = 0;
            }

            adaptTargetLocked();

            if (!started) {
                boolean enough = queue.size() >= prefillFrames;
                boolean timeout = firstArrivalMs != 0 && System.currentTimeMillis() - firstArrivalMs > START_TIMEOUT_MS;
                if (!enough && !timeout) {
                    waitFor(5);
                    return;
                }
                started = true;
                scheduleUs = MicroClock.nowUs() + (long) targetMs * 1000L;
                framesScheduled = 0;
            }

            long waitStart = System.currentTimeMillis();
            while (running && !queue.containsKey(expectedSeq) && lastReceivedSeq <= expectedSeq) {
                if (deviceBufferedMs <= MIN_DEVICE_BUFFER_MS) break;
                if (System.currentTimeMillis() - waitStart > MAX_STALL_WAIT_MS) break;
                waitFor(WAIT_STEP_MS);
            }

            long nowUs = MicroClock.nowUs();
            if (scheduleUs == 0) scheduleUs = nowUs;
            long dueUs = scheduleUs + framesScheduled * frameUs;
            if (dueUs > nowUs) {
                waitUs = Math.min(dueUs - nowUs, 120_000L);
            } else if (nowUs - dueUs > 4 * frameUs) {
                scheduleUs = nowUs;
                framesScheduled = 0;
            }

            // 深度回落：队列+设备里的音频比目标深太多时，主动跳掉最旧的未播帧，
            // 用一次「剪接」换回有界延迟（旧实现只能在 440ms 硬上限处裁剪，且那个分支恒为空操作）；
            // 只在自动模式下、且不比目标深时才收敛，避免与「停顿后加深缓冲」互相打架。
            if (started) {
                long depthUs = queue.size() * frameUs + deviceBufferedMs * 1000L;
                long drainLimitUs = (targetMs + DRAIN_MARGIN_MS) * 1000L;
                if (queue.size() > prefillFrames && depthUs > drainLimitUs
                        && System.currentTimeMillis() - lastDrainMs > DRAIN_MIN_INTERVAL_MS) {
                    int drop = (int) Math.min(queue.size() - Math.max(1, prefillFrames),
                            (depthUs - (long) targetMs * 1000L) / Math.max(1, frameUs));
                    int actually = 0;
                    for (int i = 0; i < drop; i++) {
                        Long k = queue.isEmpty() ? null : queue.firstKey();
                        if (k == null) break;
                        recycleFrame(queue.remove(k));
                        trimmedFrames++;
                        lateFrames++;
                        actually++;
                    }
                    if (actually > 0 && !queue.isEmpty()) expectedSeq = queue.firstKey();
                    lastDrainMs = System.currentTimeMillis();
                    if (actually > 0) {
                        log("缓冲过深，剪掉 " + actually + " 帧（队列 " + (depthUs / 1000) + "ms → 目标 "
                                + targetMs + "ms）");
                    }
                }
            }

            Frame f = queue.remove(expectedSeq);
            if (f != null) {
                payloadBuf = f.buf;
                payloadLen = f.len;
                captureUs = f.captureUs;
                lastCaptureUs = f.captureUs;
                dtxSilent = f.silent;
                f.buf = null;              // 缓冲借用给本方法，稍后解码完再归还缓冲池
                recycleFrameObject(f);     // Frame 对象立即复用
            } else if (lastReceivedSeq > expectedSeq) {
                plc = true;
                captureUs = lastCaptureUs + frameUs;
                lastCaptureUs = captureUs;
            } else {
                silenceFrame = true;
                // 发送端本来就没在放声音（PC 静音 / DTX 静音段）时不算「欠载」：
                // 否则一段静音就会把欠载计数与自适应目标缓冲一起推高（实测静音 10s 目标涨到 400ms）。
                idleSilence = System.currentTimeMillis() - lastArrivalMs > IDLE_AFTER_MS || dtxActive;
                captureUs = lastCaptureUs + frameUs;
                lastCaptureUs = captureUs;
            }
            expectedSeq++;
            framesScheduled++;
            bufferUs = queue.size() * frameUs;
            lastFrameMs = System.currentTimeMillis();
            // 取一次自洽快照：解码器/帧长/声道/输出缓冲/时钟偏移在锁外只读这些局部变量
            dec = decoder;
            out = pcmOut;
            fs = frameSamples;
            chs = channels;
            codecLocal = codec;
            offsetUs = clockOffsetUs;
        }

        if (waitUs > 0) {
            long deadline = MicroClock.nowUs() + waitUs;
            while (running && MicroClock.nowUs() < deadline) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }

        int written = 0;
        boolean needSilence = false;
        try {
            if (dtxSilent) {
                needSilence = true;   // DTX 静音帧：直接写 0，不计欠载/丢包
            } else if (plc && dec != null) {
                written = dec.decodeLost(out, fs);
                plcFrames++;
                synchronized (lock) {
                    plcSinceAdapt++;
                }
            } else if (plc || silenceFrame) {
                needSilence = true;
            } else if (codecLocal == Proto.CODEC_OPUS_PCM) {
                // PCM16 无损：负载就是交错的 16bit 采样
                int samples = Math.min(payloadLen / 2, out.length);
                for (int i = 0; i < samples; i++) {
                    out[i] = (short) ((payloadBuf[i * 2] & 0xFF) | (payloadBuf[i * 2 + 1] << 8));
                }
                written = samples / chs;
                decodedFrames++;
            } else {
                written = dec.decode(payloadBuf, 0, payloadLen, out, fs);
                decodedFrames++;
            }
        } catch (Exception e) {
            needSilence = true;
            decodeErrors++;
        }
        if (payloadBuf != null) {
            pool.release(payloadBuf);
            payloadBuf = null;
        }

        if (needSilence) {
            int count = fs * chs;
            short[] sil = silence;
            if (sil.length < count)
            {
                sil = new short[count];
                silence = sil;
            }
            java.util.Arrays.fill(sil, 0, count, (short) 0);
            if (levelMeter != null) levelMeter.silence();
            sink.write(sil, fs);
            if (dtxSilent) {
                synchronized (lock) {
                    dtxPlayed++;
                }
            } else if (idleSilence) {
                // 发送端空闲：只写静音，不计欠载、不驱动自适应（避免"没有声音"被当成"丢帧"）
                synchronized (lock) {
                    idleSilenceFrames++;
                }
            } else {
                synchronized (lock) {
                    underruns++;
                    silenceFrames++;
                    underrunsSinceAdapt++;
                }
            }
            deviceBufferedMs = sink.bufferedMillis();
            return;
        }

        if (written > 0) {
            long nowUs = MicroClock.nowUs();
            long captureClientUs = captureUs - offsetUs;
            long playoutUs = nowUs + sink.bufferedMillis() * 1000L;
            e2eUs = playoutUs - captureClientUs;
            if (levelMeter != null) levelMeter.update(out, written, chs);
            sink.write(out, written);
        }
        deviceBufferedMs = sink.bufferedMillis();

        synchronized (lock) {
            if (System.currentTimeMillis() - lastFrameMs > LONG_IDLE_MS && queue.isEmpty()) {
                started = false;
                expectedSeq = -1;
                firstArrivalMs = 0;
            }
        }
    }

    /** 0 = 自动（按网络自适应）, 1 = 固定偏小（延迟优先）, 2 = 固定偏大（稳定优先）. */
    public void setJitterMode(int mode) {
        synchronized (lock) {
            jitterMode = Math.max(0, Math.min(2, mode));
            if (jitterMode == 1) {
                targetMs = minTargetMs;
                updateTargetLocked();
            } else if (jitterMode == 2) {
                targetMs = maxTargetMs;
                updateTargetLocked();
            }
            scheduleUs = 0;
            framesScheduled = 0;
            log("缓冲策略=" + (jitterMode == 0 ? "自动" : jitterMode == 1 ? "延迟优先" : "稳定优先")
                    + " 目标" + targetMs + "ms");
        }
    }

    /**
     * 自适应：只有「真正饿死（补静音）」才加大目标缓冲；丢包由 PLC 补偿，不再计入（否则周期性丢包会把延迟一路棘轮推高）。
     * 链路干净 3 个周期（6 秒）即回落一档。
     */
    private void adaptTargetLocked() {
        if (jitterMode != 0) return;
        long now = System.currentTimeMillis();
        if (now - lastAdaptMs < ADAPT_INTERVAL_MS) return;
        lastAdaptMs = now;
        if (underrunsSinceAdapt > 0) {
            cleanRuns = 0;
            if (targetMs < maxTargetMs) {
                int before = targetMs;
                targetMs = Math.min(maxTargetMs, targetMs + TARGET_STEP_UP_MS);
                updateTargetLocked();
                log("缓冲 " + before + "→" + targetMs + "ms（欠载 x" + underrunsSinceAdapt + "）");
            }
        } else {
            cleanRuns++;
            // 只要最近 30 秒内出现过停顿（例如射频成串交付），就保持当前深度，不要回落
            boolean quiet = lastGapAtMs == 0 || now - lastGapAtMs > GAP_QUIET_MS;
            if (quiet && cleanRuns >= CLEAN_RUNS_BEFORE_STEP_DOWN && targetMs > minTargetMs) {
                int before = targetMs;
                targetMs = Math.max(minTargetMs, targetMs - TARGET_STEP_DOWN_MS);
                updateTargetLocked();
                cleanRuns = 0;
                maxObservedGapMs = Math.max(0, maxObservedGapMs - 40);   // 长时间干净后逐步忘掉历史停顿
                log("缓冲 " + before + "→" + targetMs + "ms（回升）");
            }
        }
        underrunsSinceAdapt = 0;
        plcSinceAdapt = 0;
    }

    private void waitFor(long ms) {
        try {
            lock.wait(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /** 注入本机电平表：每帧解码后本地统计左右声道响度。 */
    public void setLevelMeter(LevelMeter meter) {
        this.levelMeter = meter;
    }

    public void setClockSync(long offsetUs, long rttUs) {
        this.clockOffsetUs = offsetUs;
        this.rttUs = rttUs;
    }

    public RxStats snapshot(String state, String server, long sessionMs) {
        RxStats s = new RxStats();
        synchronized (lock) {
            s.recv = recvCount;
            s.dup = dupCount;
            s.lost = plcFrames;
            s.late = lateFrames;
            s.recovered = recoveredFrames;
            s.plcFrames = plcFrames;
            s.underruns = underruns;
        s.idleSilenceFrames = idleSilenceFrames;
            s.decodedFrames = decodedFrames;
            s.overflowDrops = trimmedFrames;
            s.silenceFrames = silenceFrames;
            s.decodeErrors = decodeErrors;
            s.dtxSilenceFrames = dtxPlayed;
            s.seqMax = lastReceivedSeq < 0 ? 0 : lastReceivedSeq;
            s.jitterUs = jitterUs;
            s.e2eUs = e2eUs;
            s.bufferUs = bufferUs;
            s.targetBufferUs = reportedTargetMs * 1000.0;
            s.bytesReceived = bytesReceived;
            s.rttUs = rttUs;
            s.clockOffsetUs = clockOffsetUs;
            s.outputBufferUs = deviceBufferedMs * 1000.0;
            s.state = state;
            s.server = server;
            s.sampleRate = sampleRate;
            s.channels = channels;
            s.frameMs = frameMs;
            s.sessionMs = sessionMs;
        }
        return s;
    }

    public long queuedFrames() {
        synchronized (lock) {
            return queue.size();
        }
    }
}
