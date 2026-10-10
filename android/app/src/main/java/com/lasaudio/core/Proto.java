package com.lasaudio.core;

import java.nio.charset.StandardCharsets;

/** Wire protocol constants and (de)serialisation. Mirrors the desktop implementation. */
public final class Proto {

    public static final byte VERSION = 1;
    public static final int HEADER_SIZE = 24;
    public static final int DISCOVERY_PORT = 47654;
    public static final int DEFAULT_STREAM_PORT = 47655;
    public static final int MAX_DATAGRAM = 8192;   // PCM 无损模式单包更大

    public static final byte TYPE_DATA = 1;
    public static final byte TYPE_HELLO = 2;
    public static final byte TYPE_HELLO_ACK = 3;
    public static final byte TYPE_START = 4;
    public static final byte TYPE_BYE = 5;
    public static final byte TYPE_PROBE = 6;
    public static final byte TYPE_ANNOUNCE = 7;
    public static final byte TYPE_SYNC_REQ = 8;
    public static final byte TYPE_SYNC_RSP = 9;
    public static final byte TYPE_RR = 10;
    public static final byte TYPE_SETTINGS = 11;

    // SETTINGS 键（双向）
    public static final byte KEY_BITRATE_KBPS = 1;
    public static final byte KEY_FRAME_MS = 2;
    public static final byte KEY_REDUNDANCY = 3;
    public static final byte KEY_CAPTURE_BUFFER_MS = 4;
    public static final byte KEY_MUTE_SPEAKER = 5;
    public static final byte KEY_CHANNELS = 6;
    public static final byte KEY_DTX = 7;
    public static final byte KEY_COMPLEXITY = 8;
    public static final byte KEY_JITTER_MODE = 9;
    public static final byte KEY_OUTPUT_VOLUME = 10;
    public static final byte KEY_LOW_LATENCY = 11;
    public static final int RR_BODY_SIZE = 56;
    public static final byte KEY_AUTO_RECONNECT = 12;
    public static final byte KEY_DEV_MODE = 15;     // 手机本地：详细日志
    public static final byte KEY_CODEC = 13;       // 1 = Opus, 2 = PCM16 无损
    public static final byte KEY_SAMPLE_RATE = 14; // 采样率 Hz
    public static final byte KEY_LINK_TIMEOUT = 16; // 断连超时（秒），双向同步
    public static final byte KEY_METRICS_HZ = 17;   // 实时指标刷新率（Hz，手机本地）
    public static final byte KEY_NOTIFY = 18;       // 通知栏显示指标（手机本地）

    public static final byte CODEC_OPUS_PCM = 2;

    public static final int FLAG_HAS_REDUNDANCY = 0x0001;
    /** DATA 包 payloadLen=0 且置位时表示「该序号是 DTX 静音」，接收端补静音且不计丢包。 */
    public static final int FLAG_SILENCE = 0x0002;
    public static final int FLAG_ANNOUNCE_STREAMING = 0x0001;

    public static final byte CODEC_OPUS = 1;

    private static final byte[] MAGIC = {'L', 'A', 'S', '1'};

    public static final long UNKNOWN = 0xFFFFFFFFL;

    private Proto() {
    }

    public static final class Header {
        public int type;
        public int flags;
        public long seq;
        public long captureUs;
        public int payloadLen;
        public int redLen;

        public boolean hasRedundancy() {
            return (flags & FLAG_HAS_REDUNDANCY) != 0;
        }
    }

    public static boolean readHeader(byte[] buf, int off, int len, Header out) {
        if (len < HEADER_SIZE) return false;
        if (buf[off] != MAGIC[0] || buf[off + 1] != MAGIC[1] || buf[off + 2] != MAGIC[2] || buf[off + 3] != MAGIC[3]) {
            return false;
        }
        if (buf[off + 4] != VERSION) return false;
        out.type = buf[off + 5] & 0xFF;
        out.flags = (buf[off + 6] & 0xFF) | ((buf[off + 7] & 0xFF) << 8);
        out.seq = u32(buf, off + 8);
        out.captureUs = u64(buf, off + 12);
        out.payloadLen = u16(buf, off + 20);
        out.redLen = u16(buf, off + 22);
        return len >= HEADER_SIZE + out.payloadLen + out.redLen;
    }

    public static void writeHeader(byte[] buf, int off, int type, int flags, long seq, long captureUs, int payloadLen, int redLen) {
        System.arraycopy(MAGIC, 0, buf, off, 4);
        buf[off + 4] = VERSION;
        buf[off + 5] = (byte) type;
        put16(buf, off + 6, flags);
        put32(buf, off + 8, seq);
        put64(buf, off + 12, captureUs);
        put16(buf, off + 20, payloadLen);
        put16(buf, off + 22, redLen);
    }

    public static int writeEmpty(byte[] buf, int off, int type) {
        writeHeader(buf, off, type, 0, 0, 0, 0, 0);
        return HEADER_SIZE;
    }

    public static int writeHello(byte[] buf, int off, long nonce, long desiredBitrate) {
        writeHeader(buf, off, TYPE_HELLO, 0, 0, 0, 10, 0);
        buf[off + HEADER_SIZE] = VERSION;
        buf[off + HEADER_SIZE + 1] = 0;
        put32(buf, off + HEADER_SIZE + 2, nonce);
        put32(buf, off + HEADER_SIZE + 6, desiredBitrate);
        return HEADER_SIZE + 10;
    }

    public static int writeSyncReq(byte[] buf, int off, long t1Us) {
        writeHeader(buf, off, TYPE_SYNC_REQ, 0, 0, 0, 8, 0);
        put64(buf, off + HEADER_SIZE, t1Us);
        return HEADER_SIZE + 8;
    }

    public static int writeRr(byte[] buf, int off, ReceiverReport rr) {
        writeHeader(buf, off, TYPE_RR, 0, 0, 0, 56, 0);
        int p = off + HEADER_SIZE;
        put32(buf, p, rr.seqMax);
        put32(buf, p + 4, rr.recv);
        put32(buf, p + 8, rr.lost);
        put32(buf, p + 12, rr.late);
        put32(buf, p + 16, rr.dup);
        put32(buf, p + 20, rr.jitterUs);
        put32(buf, p + 24, rr.e2eLatencyUs);
        put32(buf, p + 28, rr.bufferUs);
        put32(buf, p + 32, rr.recvBitrateBps);
        put32(buf, p + 36, rr.plcFrames);
        put32(buf, p + 40, rr.underruns);
        put16(buf, p + 44, rr.lossPermille);
        buf[p + 46] = 0;   // 保留（低延迟模式已取消）
        buf[p + 47] = 0;
        put32(buf, p + 48, rr.targetBufferUs);
        buf[p + 52] = 0;   // 保留（播放音量功能已移除）
        buf[p + 53] = (byte) rr.autoReconnect;
        buf[p + 54] = 0;
        buf[p + 55] = 0;
        return HEADER_SIZE + 56;
    }

    public static boolean parseAnnounce(byte[] buf, int off, int len, ServerInfo out, String host) {
        Header h = new Header();
        if (!readHeader(buf, off, len, h) || h.type != TYPE_ANNOUNCE) return false;
        int p = off + HEADER_SIZE;
        if (h.payloadLen < 8) return false;
        int port = u16(buf, p + 2);
        int flags = u16(buf, p + 4);
        int nameLen = u16(buf, p + 6);
        if (nameLen > h.payloadLen - 8) nameLen = h.payloadLen - 8;
        out.name = new String(buf, p + 8, nameLen, StandardCharsets.UTF_8);
        out.port = port;
        out.streaming = (flags & FLAG_ANNOUNCE_STREAMING) != 0;
        out.host = host;
        out.lastSeenMs = System.currentTimeMillis();
        return true;
    }

    public static boolean parseHelloAck(byte[] buf, int off, int len, AudioFormatInfo out) {
        Header h = new Header();
        if (!readHeader(buf, off, len, h) || h.type != TYPE_HELLO_ACK || h.payloadLen < 18) return false;
        int p = off + HEADER_SIZE;
        out.codec = buf[p] & 0xFF;
        out.channels = buf[p + 1] & 0xFF;
        out.frameMs = buf[p + 2] & 0xFF;
        out.redundancy = buf[p + 3] & 0xFF;
        out.sampleRate = (int) u32(buf, p + 4);
        out.frameSamples = u16(buf, p + 8);
        out.bitrate = u32(buf, p + 10);
        out.sessionId = u32(buf, p + 14);
        return true;
    }

    // ---- SETTINGS (TLV: key u8 + value u32 LE, per-entry optional) --------
    public static final int SETTINGS_ENTRY_SIZE = 5;

    public static final class Setting {
        public final byte key;
        public final long value;

        public Setting(byte key, long value) {
            this.key = key;
            this.value = value;
        }
    }

    public static int writeSettings(byte[] buf, int off, java.util.List<Setting> items) {
        int n = Math.min(items.size(), 32);
        int bodyLen = 1 + n * SETTINGS_ENTRY_SIZE;
        writeHeader(buf, off, TYPE_SETTINGS, 0, 0, 0, bodyLen, 0);
        int p = off + HEADER_SIZE;
        buf[p] = (byte) n;
        for (int i = 0; i < n; i++) {
            Setting s = items.get(i);
            buf[p + 1 + i * SETTINGS_ENTRY_SIZE] = s.key;
            put32(buf, p + 2 + i * SETTINGS_ENTRY_SIZE, s.value);
        }
        return HEADER_SIZE + bodyLen;
    }

    public static java.util.List<Setting> parseSettings(byte[] buf, int off, int len) {
        Header h = new Header();
        java.util.List<Setting> list = new java.util.ArrayList<>();
        if (!readHeader(buf, off, len, h) || h.type != TYPE_SETTINGS || h.payloadLen < 1) return list;
        int p = off + HEADER_SIZE;
        int count = buf[p] & 0xFF;
        for (int i = 0; i < count; i++) {
            int e = p + 1 + i * SETTINGS_ENTRY_SIZE;
            if (e + SETTINGS_ENTRY_SIZE > p + h.payloadLen) break;
            list.add(new Setting(buf[e], u32(buf, e + 1)));
        }
        return list;
    }

    public static boolean parseSyncRsp(byte[] buf, int off, int len, long[] out) {
        Header h = new Header();
        if (!readHeader(buf, off, len, h) || h.type != TYPE_SYNC_RSP || h.payloadLen < 24) return false;
        int p = off + HEADER_SIZE;
        out[0] = u64(buf, p);
        out[1] = u64(buf, p + 8);
        out[2] = u64(buf, p + 16);
        return true;
    }

    public static final class ServerInfo {
        public String name = "";
        public String host = "";
        public int port = DEFAULT_STREAM_PORT;
        public boolean streaming;
        public long lastSeenMs;

        @Override
        public String toString() {
            return name + " (" + host + ":" + port + ")" + (streaming ? " 串流中" : " 空闲");
        }
    }

    public static final class AudioFormatInfo {
        public int codec;
        public int channels;
        public int frameMs;
        public int redundancy;
        public int sampleRate;
        public int frameSamples;
        public long bitrate;
        public long sessionId;

        public AudioFormatInfo copy() {
            AudioFormatInfo c = new AudioFormatInfo();
            c.codec = codec;
            c.channels = channels;
            c.frameMs = frameMs;
            c.redundancy = redundancy;
            c.sampleRate = sampleRate;
            c.frameSamples = frameSamples;
            c.bitrate = bitrate;
            c.sessionId = sessionId;
            return c;
        }
    }

    public static final class ReceiverReport {
        public long seqMax;
        public long recv;
        public long lost;
        public long late;
        public long dup;
        public long jitterUs;
        public long e2eLatencyUs = UNKNOWN;
        public long bufferUs;
        public long recvBitrateBps;
        public long plcFrames;
        public long underruns;
        public int lossPermille;
        public long targetBufferUs;
        public int autoReconnect = 1;
    }

    // ---- little endian helpers -------------------------------------------
    static void put16(byte[] b, int p, int v) {
        b[p] = (byte) v;
        b[p + 1] = (byte) (v >> 8);
    }

    static void put32(byte[] b, int p, long v) {
        b[p] = (byte) v;
        b[p + 1] = (byte) (v >> 8);
        b[p + 2] = (byte) (v >> 16);
        b[p + 3] = (byte) (v >> 24);
    }

    static void put64(byte[] b, int p, long v) {
        for (int i = 0; i < 8; i++) b[p + i] = (byte) (v >> (8 * i));
    }

    static int u16(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }

    static long u32(byte[] b, int p) {
        return (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8) | ((b[p + 2] & 0xFFL) << 16) | ((b[p + 3] & 0xFFL) << 24);
    }

    static long u64(byte[] b, int p) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[p + i] & 0xFFL);
        return v;
    }
}
