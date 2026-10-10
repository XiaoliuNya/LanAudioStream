package com.lasaudio.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LAN discovery + UDP session client. Plain Java (no Android APIs) so it can be driven from a
 * desktop JVM test harness as well.
 */
public final class StreamClient {

    public interface Listener {
        void onStateChanged(String state);

        void onLog(String message);

        void onServersUpdated(List<Proto.ServerInfo> servers);

        void onFormatChanged(Proto.AudioFormatInfo info);

        /** 桌面端当前的权威音频设置（key -> value）. */
        void onSettingsChanged(java.util.Map<Byte, Long> settings);
    }

    private static final long DEFAULT_DATA_TIMEOUT_MS = 8000;
    private static final long RR_INTERVAL_MS = 100;
    private static final long SYNC_INTERVAL_MS = 400;
    /** 时钟偏移重估周期：minRtt 是「历史最小值」，必须周期性重置才能跟上两端时钟漂移。 */
    private static final long CLOCK_RESYNC_MS = 60000;
    private static final int MAX_LOG = 200;
    /** 判定「网络停顿」的包间隔阈值（毫秒）。 */
    private static final long GAP_THRESHOLD_MS = 60;
    /** 超过该空档就不是网络停顿，而是发送端空闲（PC 静音 / DTX / 重连）：不计停顿、不推高缓冲。 */
    private static final long GAP_MAX_MS = 600;
    /** 停顿后观察突发的时长（毫秒）。 */
    private static final long BURST_WINDOW_MS = 30;

    private final JitterPlayer player;
    private final Listener listener;

    private final Object stateLock = new Object();
    private final Map<String, Proto.ServerInfo> servers = new LinkedHashMap<>();
    /** 去重用的原始 long 环形表（避免 LinkedHashSet<Long> 每包装箱）。 */
    private static final int SEEN_SLOTS = 512;
    private final long[] seenSeq = new long[SEEN_SLOTS];
    private int seenCount;
    private int seenHead;
    private final Object seenLock = new Object();
    private final java.util.Map<Byte, Long> desktopSettings = new java.util.LinkedHashMap<>();

    private volatile boolean running;
    private volatile String state = "已停止";
    private volatile String serverLabel = "-";
    private volatile String manualHost;
    private volatile int manualPort;
    private volatile boolean autoConnect = true;
    private volatile Proto.AudioFormatInfo format;
    private volatile long sessionStartMs;
    private volatile double recvBitrateBps;
    private volatile double payloadBps;
    private volatile long lastDataArrivalMs;
    /** 速率滑窗长度（毫秒）：界面按「指标刷新率」下发，取值 50–1000，默认 1 秒。 */
    private volatile int rateWindowMs = RATE_MAX_WINDOW_MS;
    /** 最近一次结算实际使用的窗口（毫秒）：包太稀时会自动放宽，供诊断显示。 */
    private volatile int rateWindowUsedMs = RATE_MAX_WINDOW_MS;
    /** 最近一次结算窗口内的包数。 */
    private volatile int rateSamples;

    // ---- 速率滑窗：按到达时间记录最近的 DATA 包，结算时按真实经过时间求和（零分配） ----
    private static final int RATE_SLOTS = 512;
    private static final int RATE_MIN_WINDOW_MS = 50;
    private static final int RATE_MAX_WINDOW_MS = 1000;
    /** 窗口内至少要有这么多包，否则自动放宽窗口（DTX 静音期每 100ms 才一个包头包）。 */
    private static final int RATE_MIN_SAMPLES = 2;
    private final long[] rateTimeMs = new long[RATE_SLOTS];
    private final int[] rateBytes = new int[RATE_SLOTS];
    private final int[] ratePayload = new int[RATE_SLOTS];
    private int rateHead;
    private int rateCount;
    private long rateStartMs;

    // ---- 诊断：到达间隔 / 停顿后的突发 ----
    private long lastPacketMs;
    private int burstCount;
    private long burstDeadlineMs;
    private volatile long lastGapMs;
    private volatile int gapEvents;
    private volatile int lastBurstCount;
    private Thread discoveryThread;
    private Thread sessionThread;
    private DatagramSocket discoverySocket;

    public StreamClient(PcmSink sink, Listener listener) {
        this.player = new JitterPlayer(sink);
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        setState("搜索桌面端…");
        discoveryThread = new Thread(this::discoveryLoop, "discovery");
        discoveryThread.setDaemon(true);
        discoveryThread.start();
        sessionThread = new Thread(this::sessionLoop, "session");
        sessionThread.setDaemon(true);
        sessionThread.start();
        player.start();
    }

    public void stop() {
        running = false;
        setState("已停止");
        try {
            if (discoverySocket != null) discoverySocket.close();
        } catch (Exception ignored) {
        }
        if (discoveryThread != null) discoveryThread.interrupt();
        if (sessionThread != null) sessionThread.interrupt();
        player.stop();
    }

    public void connectTo(String host, int port) {
        manualHost = host;
        manualPort = port;
        autoConnect = false;
        log("手动连接 " + host + ":" + port);
    }

    public void clearManualTarget() {
        manualHost = null;
        autoConnect = true;
    }

    public List<Proto.ServerInfo> getServers() {
        synchronized (stateLock) {
            return new ArrayList<>(servers.values());
        }
    }

    public RxStats getStats() {
        RxStats s = player.snapshot(state, serverLabel, sessionStartMs == 0 ? 0 : System.currentTimeMillis() - sessionStartMs);
        // 超过 2 秒没收到任何音频包时把速率类指标归零，避免停在最后一个窗口的旧值上
        if (lastDataArrivalMs != 0 && System.currentTimeMillis() - lastDataArrivalMs > 2000) {
            payloadBps = 0;
            recvBitrateBps = 0;
            rateSamples = 0;
        }
        s.recvBitrateBps = recvBitrateBps;
        s.speedBps = payloadBps;
        // 还没有任何样本（未开始串流）时报告「配置的窗口」，而不是上一轮的残留值
        s.rateWindowMs = rateSamples == 0 ? rateWindowMs : rateWindowUsedMs;
        s.rateSamples = rateSamples;
        s.lastGapMs = lastGapMs;
        s.gapEvents = gapEvents;
        s.lastBurstCount = lastBurstCount;
        return s;
    }

    /** Test hook: while paused the session loop stops draining the socket (simulated network stall). */
    public void setPaused(boolean value) {
        paused = value;
    }

    private volatile boolean paused;
    private volatile boolean sessionActive;
    private volatile boolean sessionEstablished;

    /** 手机端下发设置（逐项可选，桌面端会回推权威状态）。 */
    public synchronized void sendSettings(java.util.List<Proto.Setting> items) {
        pendingSettings = new java.util.ArrayList<>(items);
    }

    private volatile java.util.List<Proto.Setting> pendingSettings;

    public java.util.Map<Byte, Long> getDesktopSettings() {
        synchronized (stateLock) {
            return new java.util.LinkedHashMap<>(desktopSettings);
        }
    }

    public void setAutoReconnect(boolean value) {
        autoReconnect = value;
    }

    private volatile boolean autoReconnect = true;
    private volatile long dataTimeoutMs = DEFAULT_DATA_TIMEOUT_MS;

    public void setJitterMode(int mode) {
        player.setJitterMode(mode);
    }

    /** 断连超时（秒）：多久没收到音频就重连；与桌面端「客户端无响应」阈值是同一个设置。 */
    public void setLinkTimeoutSec(int sec) {
        dataTimeoutMs = Math.max(1, sec) * 1000L;
    }

    /**
     * 速率滑窗长度（毫秒）＝界面「指标刷新率」的周期，这样码率/实时速度的**原始数据**
     * 与指标刷新同频，而不是每秒才跳一次。取值夹到 50–1000ms。
     */
    public void setRateWindowMs(int ms) {
        rateWindowMs = Math.max(RATE_MIN_WINDOW_MS, Math.min(RATE_MAX_WINDOW_MS, ms));
    }

    /** 注入本机电平表（数据在手机本地从解码 PCM 统计）。 */
    public void setLevelMeter(LevelMeter meter) {
        player.setLevelMeter(meter);
    }

    /** 播放器的诊断信息（抖动缓冲自适应等）转给界面日志。 */
    public void setLogSink(java.util.function.Consumer<String> sink) {
        player.setLogSink(sink);
    }

    public Proto.AudioFormatInfo getFormat() {
        return format;
    }

    private void setState(String value) {
        if (value.equals(state)) return;
        state = value;
        if (listener != null) listener.onStateChanged(value);
    }

    private void log(String message) {
        if (listener != null) listener.onLog(message);
    }

    // ------------------------------------------------------------ discovery
    private void discoveryLoop() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(Proto.DISCOVERY_PORT));
            socket.setSoTimeout(200);
            discoverySocket = socket;
        } catch (Exception e) {
            log("发现端口绑定失败: " + e.getMessage());
            return;
        }

        byte[] probe = new byte[Proto.HEADER_SIZE + 8];
        int probeLen = Proto.writeEmpty(probe, 0, Proto.TYPE_PROBE);
        byte[] buf = new byte[Proto.MAX_DATAGRAM];
        long lastProbe = 0;

        while (running) {
            long now = System.currentTimeMillis();
            // 已连接后不再主动探测（省电/省流量）；断开后自动恢复
            if (sessionActive) {
                lastProbe = now;
                sleep(300);
                continue;
            }
            if (now - lastProbe > 1000) {
                lastProbe = now;
                for (InetAddress addr : broadcastAddresses()) {
                    try {
                        socket.send(new DatagramPacket(probe, probeLen, addr, Proto.DISCOVERY_PORT));
                    } catch (Exception ignored) {
                    }
                }
            }

            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException timeout) {
                pruneServers(now);
                continue;
            } catch (Exception e) {
                break;
            }

            Proto.ServerInfo info = new Proto.ServerInfo();
            if (Proto.parseAnnounce(packet.getData(), packet.getOffset(), packet.getLength(), info, packet.getAddress().getHostAddress())) {
                boolean isNew;
                synchronized (stateLock) {
                    isNew = !servers.containsKey(info.host);
                    servers.put(info.host, info);
                }
                if (isNew) {
                    log("发现桌面端: " + info);
                    if (listener != null) listener.onServersUpdated(getServers());
                }
            }
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    private void pruneServers(long now) {
        boolean changed = false;
        synchronized (stateLock) {
            List<String> dead = new ArrayList<>();
            for (Map.Entry<String, Proto.ServerInfo> e : servers.entrySet()) {
                if (now - e.getValue().lastSeenMs > 4000) dead.add(e.getKey());
            }
            for (String key : dead) {
                servers.remove(key);
                changed = true;
            }
        }
        if (changed && listener != null) listener.onServersUpdated(getServers());
    }

    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> list = new ArrayList<>();
        try {
            list.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null) list.add(b);
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    // --------------------------------------------------------------- session
    private void sessionLoop() {
        ThreadTuner.tune(ThreadTuner.AUDIO);   // 收包线程：音频级优先级
        long backoff = 300;
        while (running) {
            Proto.ServerInfo target = pickTarget();
            if (target == null) {
                setState(manualHost != null ? "等待桌面端…" : "搜索桌面端…");
                sleep(300);
                continue;
            }
            serverLabel = target.host + ":" + target.port + " (" + target.name + ")";
            try {
                runSession(target);
            } catch (Exception e) {
                if (running) log("会话中断: " + e.getMessage());
            }
            if (!running) break;
            if (sessionEstablished) {
                // 只有真正建立过会话才重置退避，握手失败/超时也走指数退避
                sessionEstablished = false;
                backoff = 300;
            }
            if (!autoReconnect) {
                setState("已断开");
                log("自动重连已关闭");
                sleep(500);
                continue;
            }
            setState("重连中…");
            sleep(backoff);
            backoff = Math.min(backoff * 2, 4000);
        }
    }

    private Proto.ServerInfo pickTarget() {
        String host = manualHost;
        if (host != null) {
            synchronized (stateLock) {
                Proto.ServerInfo known = servers.get(host);
                if (known != null) return known;
            }
            Proto.ServerInfo s = new Proto.ServerInfo();
            s.host = host;
            s.port = manualPort;
            s.name = host;
            return s;
        }
        if (!autoConnect) return null;
        synchronized (stateLock) {
            Proto.ServerInfo best = null;
            for (Proto.ServerInfo s : servers.values()) {
                if (best == null) best = s;
                else if (s.streaming && !best.streaming) best = s;
                else if (s.streaming == best.streaming && s.lastSeenMs > best.lastSeenMs) best = s;
            }
            return best;
        }
    }

    private void runSession(Proto.ServerInfo target) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(20);
        socket.setSendBufferSize(256 * 1024);
        // 大一点的内核接收缓冲：短暂卡顿期间到达的包不会因为缓冲溢出被丢掉
        try {
            socket.setReceiveBufferSize(1024 * 1024);
        } catch (Exception ignored) {
        }
        byte[] buf = new byte[Proto.MAX_DATAGRAM];
        byte[] out = new byte[Proto.MAX_DATAGRAM];
        long nonce = System.nanoTime() & 0x7FFFFFFF;
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByName(target.host), target.port);

        try {
            // ---- handshake
            setState("握手…");
            int helloLen = Proto.writeHello(out, 0, nonce, 0);
            Proto.AudioFormatInfo info = null;
            for (int attempt = 0; attempt < 5 && running && info == null; attempt++) {
                socket.send(new DatagramPacket(out, helloLen, remote));
                long deadline = System.currentTimeMillis() + 400;
                while (System.currentTimeMillis() < deadline) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(p);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    Proto.Header h = new Proto.Header();
                    if (!Proto.readHeader(p.getData(), p.getOffset(), p.getLength(), h)) continue;
                    if (h.type == Proto.TYPE_HELLO_ACK) {
                        Proto.AudioFormatInfo parsed = new Proto.AudioFormatInfo();
                        if (Proto.parseHelloAck(p.getData(), p.getOffset(), p.getLength(), parsed)) {
                            info = parsed;
                            break;
                        }
                    }
                }
            }
            if (info == null) {
                setState("未响应");
                log("握手失败: 无 HELLO_ACK");
                return;
            }

            applyFormat(info);
            resetSeen();
            resetRates();
            player.resetSession();
            int startLen = Proto.writeEmpty(out, 0, Proto.TYPE_START);
            socket.send(new DatagramPacket(out, startLen, remote));
            sessionStartMs = System.currentTimeMillis();
            sessionActive = true;
            sessionEstablished = true;
            setState("串流中");
            log("已连接 " + serverLabel + " | " + info.sampleRate + "Hz " + info.channels + "ch "
                    + info.frameMs + "ms " + (info.bitrate / 1000) + "kbps" + (info.redundancy != 0 ? " 冗余开" : ""));

            byte[] rr = new byte[Proto.HEADER_SIZE + Proto.RR_BODY_SIZE];
            byte[] sync = new byte[Proto.HEADER_SIZE + 8];
            long lastRr = 0;
            long lastSync = 0;
            long lastData = System.currentTimeMillis();
            long minRtt = Long.MAX_VALUE;
            long lastClockResyncMs = System.currentTimeMillis();
            long bestOffset = 0;

            while (running) {
                long now = System.currentTimeMillis();
                if (now - lastSync > SYNC_INTERVAL_MS) {
                    lastSync = now;
                    int n = Proto.writeSyncReq(sync, 0, MicroClock.nowUs());
                    socket.send(new DatagramPacket(sync, n, remote));
                }
                if (now - lastRr > RR_INTERVAL_MS) {
                    lastRr = now;
                    RxStats s = player.snapshot(state, serverLabel, 0);
                    Proto.ReceiverReport report = new Proto.ReceiverReport();
                    report.seqMax = s.seqMax;
                    report.recv = s.recv;
                    report.lost = s.lost;
                    report.late = s.late;
                    report.dup = s.dup;
                    report.jitterUs = (long) Math.min(s.jitterUs, 0xFFFFFFFFL);
                    report.e2eLatencyUs = s.e2eUs < 0 ? Proto.UNKNOWN : (long) Math.min(Math.max(s.e2eUs, 0), 0xFFFFFFFFL);
                    report.bufferUs = (long) s.bufferUs;
                    report.recvBitrateBps = (long) recvBitrateBps;
                    report.plcFrames = s.plcFrames;
                    report.underruns = s.underruns;
                    long total = s.recv + s.lost;
                    report.lossPermille = total == 0 ? 0 : (int) Math.min(65535, s.lost * 10000 / total);
                    report.targetBufferUs = (long) s.targetBufferUs;
                    report.autoReconnect = autoReconnect ? 1 : 0;
                    int n = Proto.writeRr(rr, 0, report);
                    socket.send(new DatagramPacket(rr, n, remote));
                }

                java.util.List<Proto.Setting> pending = pendingSettings;
                if (pending != null) {
                    pendingSettings = null;
                    byte[] setBuf = new byte[Proto.MAX_DATAGRAM];
                    int n = Proto.writeSettings(setBuf, 0, pending);
                    socket.send(new DatagramPacket(setBuf, n, remote));
                    log("已下发 " + pending.size() + " 项设置到电脑端");
                }

                if (paused) {
                    sleep(5);
                    continue;
                }

                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (SocketTimeoutException e) {
                    if (System.currentTimeMillis() - lastData > dataTimeoutMs) {
                        log("超过 " + (dataTimeoutMs / 1000) + " 秒未收到音频，重连");
                        setState("重连中…");
                        return;
                    }
                    continue;
                }

                Proto.Header h = new Proto.Header();
                if (!Proto.readHeader(p.getData(), p.getOffset(), p.getLength(), h)) continue;

                if (h.type == Proto.TYPE_DATA) {
                    long nowMs = System.currentTimeMillis();
                    lastData = nowMs;
                    lastDataArrivalMs = nowMs;
                    int payloadLen = Math.max(0, h.payloadLen);
                    int datagramLen = p.getLength();
                    // 速率窗口用单调时钟（墙钟被 NTP 校时/手动改时间会让窗口读数异常）
                    pushRateSample(MicroClock.nowUs() / 1000L, datagramLen, payloadLen);
                    if ((h.flags & Proto.FLAG_SILENCE) != 0 && h.payloadLen == 0) {
                        // DTX 静音标记：只有 24 字节包头，接收端按序号补静音（不计丢包）。
                        // 不喂给 trackArrival：静音段每 100ms 才一个标记包，会被当成「网络停顿」
                        // 把抖动缓冲一路推高（实测静音 10 秒后目标被顶到 400ms 上限）。
                        deliverSilence(h.seq, h.captureUs);
                    } else {
                        trackArrival(nowMs);
                        // 直接把收包缓冲的区间交给播放器（它自己拷贝到缓冲池），这里不再逐包分配
                        deliver(h.seq, p.getData(), p.getOffset() + Proto.HEADER_SIZE, h.payloadLen, h.captureUs, false);
                        if (h.redLen > 0) {
                            deliver(h.seq - 1, p.getData(), p.getOffset() + Proto.HEADER_SIZE + h.payloadLen, h.redLen,
                                    h.captureUs - info.frameMs * 1000L, true);
                        }
                    }
                } else if (h.type == Proto.TYPE_SYNC_RSP) {
                    long t4 = MicroClock.nowUs();
                    long[] times = new long[3];
                    if (Proto.parseSyncRsp(p.getData(), p.getOffset(), p.getLength(), times)) {
                        long t1 = times[0];
                        long t2 = times[1];
                        long t3 = times[2];
                        long rtt = (t4 - t1) - (t3 - t2);
                        long offset = ((t2 - t1) + (t3 - t4)) / 2;
                        // minRtt 是整会话历史最小值：握手后很快触底，offset 从此被冻结，
                        // 而两端时钟各自自由走（典型 10–100 ppm ≈ 36–360 ms/小时），
                        // 长会话的端到端延迟读数会单调漂移。每 60 秒重置一次，让偏移重新收敛。
                        if (now - lastClockResyncMs > CLOCK_RESYNC_MS) {
                            lastClockResyncMs = now;
                            minRtt = Long.MAX_VALUE;
                        }
                        if (rtt >= 0 && rtt < minRtt) {
                            minRtt = rtt;
                            bestOffset = offset;
                            player.setClockSync(bestOffset, minRtt);
                        }
                    }
                } else if (h.type == Proto.TYPE_HELLO_ACK) {
                    Proto.AudioFormatInfo parsed = new Proto.AudioFormatInfo();
                    if (Proto.parseHelloAck(p.getData(), p.getOffset(), p.getLength(), parsed)) {
                        applyFormat(parsed);
                        log("桌面端更新编码参数: " + parsed.frameMs + "ms " + (parsed.bitrate / 1000) + "kbps");
                    }
                } else if (h.type == Proto.TYPE_SETTINGS) {
                    java.util.List<Proto.Setting> items = Proto.parseSettings(p.getData(), p.getOffset(), p.getLength());
                    if (!items.isEmpty()) {
                        synchronized (stateLock) {
                            for (Proto.Setting st : items) desktopSettings.put(st.key, st.value);
                        }
                        StringBuilder sb = new StringBuilder();
                        for (Proto.Setting st : items) sb.append(settingName(st.key)).append('=').append(st.value).append(' ');
                        log("电脑端设置: " + sb.toString().trim());
                        if (listener != null) listener.onSettingsChanged(getDesktopSettings());
                    }
                } else if (h.type == Proto.TYPE_BYE) {
                    log("桌面端断开连接");
                    setState("重连中…");
                    return;
                }
            }
        } finally {
            sessionActive = false;
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void deliver(long seq, byte[] data, int off, int len, long captureUs, boolean recovered) {
        if (seq < 0 || len <= 0) return;
        synchronized (seenLock) {
            if (seenOrAddLocked(seq)) return;
        }
        player.onFrame(seq, data, off, len, captureUs, recovered);
    }

    /** 返回 true 表示这个序号已经处理过（重复包）。 */
    private boolean seenOrAddLocked(long seq) {
        for (int i = 0; i < seenCount; i++) {
            if (seenSeq[i] == seq) return true;
        }
        if (seenCount < SEEN_SLOTS) {
            seenSeq[seenCount++] = seq;
        } else {
            seenSeq[seenHead] = seq;
            seenHead = (seenHead + 1) % SEEN_SLOTS;
        }
        return false;
    }

    private void resetSeen() {
        synchronized (seenLock) {
            seenCount = 0;
            seenHead = 0;
        }
    }


    /** 诊断：包到达间隔，记录「停顿」以及停顿后短时间内的突发包数。 */
    private void trackArrival(long nowMs) {
        if (lastPacketMs != 0) {
            long gap = nowMs - lastPacketMs;
            if (gap > GAP_THRESHOLD_MS && gap <= GAP_MAX_MS) {
                lastGapMs = gap;
                gapEvents++;
                player.noteArrivalGap(gap);
                burstCount = 0;
                lastBurstCount = 0;
                burstDeadlineMs = nowMs + BURST_WINDOW_MS;
            } else if (burstDeadlineMs != 0 && nowMs <= burstDeadlineMs) {
                burstCount++;
                lastBurstCount = burstCount;
            }
        }
        lastPacketMs = nowMs;
    }

    /**
     * 速率滑窗结算（只在接收线程上执行）：记录一个 DATA 包，并按当前窗口长度把
     * 码率/实时速度更新成「最近 rateWindowMs 毫秒」的真实值。
     * 窗口 = 指标刷新周期，因此界面每次刷新取到的都是新数据（1 秒窗口时会重复显示旧值）。
     * 窗口内包数不足（DTX/静音期每 100ms 才一个包头包）时按 2 倍放宽，最多到 1 秒，
     * 否则短窗口在静音期只会在 0 与真值之间乱跳。
     */
    private void pushRateSample(long nowMs, int datagramBytes, int payloadBytes) {
        if (rateStartMs == 0) rateStartMs = nowMs;
        rateTimeMs[rateHead] = nowMs;
        rateBytes[rateHead] = datagramBytes;
        ratePayload[rateHead] = payloadBytes;
        rateHead = (rateHead + 1) % RATE_SLOTS;
        if (rateCount < RATE_SLOTS) rateCount++;

        int effective = rateWindowMs;
        int samples = countSince(nowMs - effective);
        while (samples < RATE_MIN_SAMPLES && effective < RATE_MAX_WINDOW_MS) {
            effective = Math.min(effective * 2, RATE_MAX_WINDOW_MS);
            samples = countSince(nowMs - effective);
        }

        // 会话刚开始时窗口还没被数据填满，用真正经过的时间做分母，避免低估
        long spanMs = Math.min(effective, nowMs - rateStartMs);
        if (spanMs < 20) spanMs = 20;
        long bytes = 0;
        long payload = 0;
        for (int i = 0; i < rateCount; i++) {
            int idx = (rateHead - 1 - i + RATE_SLOTS * 2) % RATE_SLOTS;
            if (nowMs - rateTimeMs[idx] > effective) break;
            bytes += rateBytes[idx];
            payload += ratePayload[idx];
        }
        double winSec = spanMs / 1000.0;
        recvBitrateBps = bytes * 8.0 / winSec;
        payloadBps = payload / winSec;
        rateSamples = samples;
        rateWindowUsedMs = effective;
    }

    /** 滑窗内（时间戳 >= cutoffMs）的包数，按到达顺序从最新往回数。 */
    private int countSince(long cutoffMs) {
        int n = 0;
        for (int i = 0; i < rateCount; i++) {
            int idx = (rateHead - 1 - i + RATE_SLOTS * 2) % RATE_SLOTS;
            if (rateTimeMs[idx] < cutoffMs) break;
            n++;
        }
        return n;
    }

    /** 新会话重新开始计时（等价于旧的「每秒窗口」在循环入口被初始化）。 */
    private void resetRates() {
        rateHead = 0;
        rateCount = 0;
        rateStartMs = 0;
        recvBitrateBps = 0;
        payloadBps = 0;
        rateSamples = 0;
        rateWindowUsedMs = rateWindowMs;
    }

    private void deliverSilence(long seq, long captureUs) {
        if (seq < 0) return;
        synchronized (seenLock) {
            if (seenOrAddLocked(seq)) return;
        }
        player.onSilenceFrame(seq, captureUs);
    }

    private void applyFormat(Proto.AudioFormatInfo info) {
        // 防御：对端若通告了非法参数（历史版本会发 44.1/32kHz、帧长 60ms），这里就地归一化。
        // 否则 OpusDecoderWrapper 构造失败、decoder 为 null，表现是"连上了但一点声音都没有、收包数也不涨"。
        int rawRate = info.sampleRate;
        int rawFrame = info.frameMs;
        info.sampleRate = legalSampleRate(info.sampleRate);
        info.channels = Math.max(1, Math.min(2, info.channels));
        info.frameMs = Math.max(5, Math.min(40, info.frameMs == 0 ? 10 : info.frameMs));
        if (rawRate != info.sampleRate || rawFrame != info.frameMs) {
            log("对端参数非法，已归一化: " + rawRate + "Hz/" + rawFrame + "ms → "
                    + info.sampleRate + "Hz/" + info.frameMs + "ms");
        }
        Proto.AudioFormatInfo previous = format;
        format = info.copy();
        boolean same = previous != null
                && previous.sampleRate == info.sampleRate
                && previous.channels == info.channels
                && previous.frameMs == info.frameMs
                && previous.codec == info.codec;
        if (same) return;
        try {
            OpusDecoderWrapper decoder = null;
            if (info.codec != Proto.CODEC_OPUS_PCM) {
                decoder = new OpusDecoderWrapper(info.sampleRate, Math.max(1, info.channels));
            }
            player.configure(decoder, info.sampleRate, Math.max(1, info.channels),
                    info.frameMs == 0 ? 10 : info.frameMs, info.codec);
        } catch (Exception e) {
            log("解码器初始化失败: " + e.getMessage());
        }
        resetSeen();
        if (listener != null) listener.onFormatChanged(format);
    }

    /** 把采样率归一到 Opus 解码器接受的集合（8/12/16/24/48 kHz）。 */
    private static int legalSampleRate(int rate) {
        int[] allowed = { 8000, 12000, 16000, 24000, 48000 };
        int best = allowed[allowed.length - 1];
        int bestDiff = Integer.MAX_VALUE;
        for (int r : allowed) {
            int d = Math.abs(r - rate);
            if (d < bestDiff) {
                bestDiff = d;
                best = r;
            }
        }
        return best;
    }

    private static String settingName(byte key) {
        switch (key) {
            case Proto.KEY_BITRATE_KBPS: return "码率kbps";
            case Proto.KEY_FRAME_MS: return "帧长ms";
            case Proto.KEY_REDUNDANCY: return "冗余";
            case Proto.KEY_CAPTURE_BUFFER_MS: return "采集缓冲ms";
            case Proto.KEY_MUTE_SPEAKER: return "静音电脑";
            case Proto.KEY_CHANNELS: return "声道";
            case Proto.KEY_DTX: return "DTX";
            case Proto.KEY_JITTER_MODE: return "抖动策略";
            case Proto.KEY_AUTO_RECONNECT: return "自动重连";
            default: return "key" + key;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
