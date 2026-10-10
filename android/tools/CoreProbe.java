package com.lasaudio.tools;

import com.lasaudio.core.JitterPlayer;
import com.lasaudio.core.LevelMeter;
import com.lasaudio.core.OpusDecoderWrapper;
import com.lasaudio.core.PcmSink;
import com.lasaudio.core.Proto;
import com.lasaudio.core.RxStats;
import com.lasaudio.core.StreamClient;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;

/**
 * Desktop-JVM harness for the Android core: proves that the exact receiver code that ships in the
 * APK (discovery, handshake, jitter buffer, Opus decoding, playout) works against the real desktop
 * server, without needing an Android device.
 */
public final class CoreProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: CoreProbe decode <packets.bin> <out.wav> | CoreProbe client [--seconds N] [--out file.wav] [--lowlatency] [--host ip --port p]");
            return;
        }
        switch (args[0]) {
            case "decode" -> decode(args[1], args[2]);
            case "client" -> client(args);
            default -> System.out.println("unknown mode " + args[0]);
        }
    }

    private static byte keyFor(String name) {
        switch (name) {
            case "bitrate": return Proto.KEY_BITRATE_KBPS;
            case "frame": return Proto.KEY_FRAME_MS;
            case "redundancy": return Proto.KEY_REDUNDANCY;
            case "buffer": return Proto.KEY_CAPTURE_BUFFER_MS;
            case "mute": return Proto.KEY_MUTE_SPEAKER;
            case "channels": return Proto.KEY_CHANNELS;
            case "dtx": return Proto.KEY_DTX;
            case "jitter": return Proto.KEY_JITTER_MODE;
            case "volume": return Proto.KEY_OUTPUT_VOLUME;
            case "codec": return Proto.KEY_CODEC;
            case "sampleRate": return Proto.KEY_SAMPLE_RATE;
            case "lowlatency": return Proto.KEY_LOW_LATENCY;
            case "autoreconnect": return Proto.KEY_AUTO_RECONNECT;
            default: return 0;
        }
    }

    private static String arg(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(name)) return args[i + 1];
        return def;
    }

    private static boolean flag(String[] args, String name) {
        for (String a : args) if (a.equals(name)) return true;
        return false;
    }

    /** Decodes a captured packet dump (written by the Windows conformance client) into a WAV file. */
    private static void decode(String dumpFile, String wavFile) throws Exception {
        byte[] raw = readAll(dumpFile);
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        WavWriter wav = new WavWriter(wavFile, 48000, 2);
        OpusDecoderWrapper decoder = new OpusDecoderWrapper(48000, 2);
        short[] pcm = new short[5760 * 2];
        int frames = 0;
        while (buffer.remaining() >= 8) {
            long seq = buffer.getInt() & 0xFFFFFFFFL;
            int len = buffer.getInt();
            if (len <= 0 || len > buffer.remaining()) break;
            byte[] payload = new byte[len];
            buffer.get(payload);
            int samples = decoder.decode(payload, 0, len, pcm, 480);
            wav.write(pcm, samples * 2);
            frames++;
        }
        wav.close();
        System.out.println("decoded " + frames + " frames -> " + wavFile);
    }

    /** Runs the full Android receiver stack on the JVM against the desktop server. */
    private static void client(String[] args) throws Exception {
        int seconds = Integer.parseInt(arg(args, "--seconds", "15"));
        String out = arg(args, "--out", null);
        String host = arg(args, "--host", null);
        int port = Integer.parseInt(arg(args, "--port", String.valueOf(Proto.DEFAULT_STREAM_PORT)));

        PacingSink sink = new PacingSink(out);
        LevelMeter levelMeter = new LevelMeter();
        StreamClient client = new StreamClient(sink, new StreamClient.Listener() {
            @Override
            public void onStateChanged(String state) {
                System.out.println("[state] " + state);
            }

            @Override
            public void onLog(String message) {
                System.out.println("[log] " + message);
            }

            @Override
            public void onServersUpdated(List<Proto.ServerInfo> servers) {
                System.out.println("[discovery] " + servers);
            }

            @Override
            public void onFormatChanged(Proto.AudioFormatInfo info) {
                System.out.println("[format] codec=" + (info.codec == Proto.CODEC_OPUS_PCM ? "PCM" : "Opus")
                        + " " + info.sampleRate + "Hz " + info.channels + "ch "
                        + info.frameMs + "ms " + (info.bitrate / 1000) + "kbps");
            }

            @Override
            public void onSettingsChanged(java.util.Map<Byte, Long> settings) {
                System.out.println("[settings] " + settings);
            }
        });
        if (host != null) client.connectTo(host, port);
        client.setLevelMeter(levelMeter);
        // --rate-window <ms>：模拟界面下发的「指标刷新周期」，验证速率原始数据同频更新
        int rateWindow = Integer.parseInt(arg(args, "--rate-window", "0"));
        if (rateWindow > 0) client.setRateWindowMs(rateWindow);
        client.start();

        // --set bitrate=192,frame=20,channels=1,redundancy=0,dtx=1,mute=1,buffer=30,jitter=1
        String setSpec = arg(args, "--set", null);
        if (setSpec != null) {
            new Thread(() -> {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException ignored) {
                }
                java.util.List<Proto.Setting> list = new java.util.ArrayList<>();
                for (String part : setSpec.split(",")) {
                    String[] kv = part.split("=");
                    if (kv.length != 2) continue;
                    byte key = keyFor(kv[0].trim());
                    if (key == 0) continue;
                    list.add(new Proto.Setting(key, Long.parseLong(kv[1].trim())));
                }
                System.out.println("[set] sending " + setSpec);
                client.sendSettings(list);
            }).start();
        }

        double speed = 0;
        long lastBytes = 0;
        long lastMs = System.currentTimeMillis();
        long start = System.currentTimeMillis();
        // optional network-stall injection (simulates WiFi power-save hiccups)
        // --print-ms <ms>：打印间隔，用来观察速率原始数据的更新节奏（默认 1s）
        long printMs = Long.parseLong(arg(args, "--print-ms", "1000"));
        boolean csv = arg(args, "--csv", null) != null;
        int stallMs = Integer.parseInt(arg(args, "--stall", "0"));
        int stallEveryMs = Integer.parseInt(arg(args, "--stall-every", "1000"));
        long nextStall = start + stallEveryMs;
        while (System.currentTimeMillis() - start < seconds * 1000L) {
            if (stallMs > 0 && System.currentTimeMillis() >= nextStall) {
                nextStall = System.currentTimeMillis() + stallEveryMs;
                client.setPaused(true);
                Thread.sleep(stallMs);
                client.setPaused(false);
            }
            Thread.sleep(printMs);
            RxStats s = client.getStats();
            if (csv) {
                // ASCII-only CSV: t,e2e_ms,jitter_ms,queue_ms,target_ms,device_ms,loss_pct,underruns,plc
                System.out.println(String.format(Locale.US, "%.1f,%.0f,%.2f,%.0f,%.0f,%.0f,%.2f,%d,%d,%d,%d",
                        (System.currentTimeMillis() - start) / 1000.0,
                        s.e2eUs < 0 ? -1 : s.e2eUs / 1000.0,
                        s.jitterUs / 1000.0,
                        s.bufferUs / 1000.0,
                        s.targetBufferUs / 1000.0,
                        s.outputBufferUs / 1000.0,
                        s.lossPercent(), s.underruns, s.plcFrames, s.idleSilenceFrames, s.recv));
            }
            double sinkRms = sink.secondCount > 0 ? Math.sqrt(sink.secondAcc / sink.secondCount) : 0;
            sink.secondAcc = 0;
            sink.secondCount = 0;
            long now = System.currentTimeMillis();
            System.out.println("[sink] 本机解码 RMS=" + String.format(Locale.US, "%.4f", sinkRms));
            long delta = s.bytesReceived - lastBytes;
            double inst = delta / 1024.0 * 1000.0 / Math.max(1, now - lastMs);
            speed = speed == 0 ? inst : speed * 0.5 + inst * 0.5;
            lastBytes = s.bytesReceived;
            lastMs = now;
            System.out.println(String.format(Locale.US,
                    "[%4.1fs] 收包=%d 晚=%d 重=%d %s 延迟=%.1fms 抖动=%.2fms 码率=%.0fkbps 速度=%.1fKB/s 窗口=%dms/%d包 丢包=%.2f%% 队列=%.0f/目标%.0fms 设备缓冲=%.0fms 解码=%d PLC=%d 欠载=%d(补静音%d,解码错误%d) 恢复=%d RTT=%.1fms 电平L=%.3f R=%.3f",
                    (now - start) / 1000.0, s.recv, s.late, s.dup, s.state,
                    s.e2eUs < 0 ? -1 : s.e2eUs / 1000.0,
                    s.jitterUs / 1000.0,
                    s.recvBitrateBps / 1000.0,
                    speed,
                    s.rateWindowMs, s.rateSamples,
                    s.lossPercent(),
                    s.bufferUs / 1000.0,
                    s.targetBufferUs / 1000.0,
                    s.outputBufferUs / 1000.0,
                    s.decodedFrames, s.plcFrames, s.underruns, s.silenceFrames, s.decodeErrors, s.recovered,
                    s.rttUs / 1000.0,
                    levelMeter.rmsLeft(), levelMeter.rmsRight()));
        }
        client.stop();
        sink.close();
        System.out.println("done");
    }

    /** Sink that writes PCM to a WAV file and paces itself like a real audio device. */
    private static final class PacingSink implements PcmSink {
        private WavWriter wav;
        private final String path;
        private int sampleRate = 48000;
        private int channels = 2;
        private long framesWritten;
        private long framesSubmitted;
        volatile double secondAcc;
        volatile long secondCount;
        private long deviceStartNanos;
        private int bufferMillis;

        PacingSink(String path) {
            this.path = path;
        }

        @Override
        public synchronized int openAndGetBufferMillis(int sampleRate, int channels) {
            try {
                if (wav != null) wav.close();
                this.sampleRate = sampleRate;
                this.channels = channels;
                this.framesWritten = 0;
                this.framesSubmitted = 0;
                this.deviceStartNanos = 0;
                this.bufferMillis = 80; // same as the Android sink
                this.wav = path == null ? null : new WavWriter(path, sampleRate, channels);
                return bufferMillis;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /** Models AudioTrack: accept instantly while the device buffer has room, then block. */
        @Override
        public synchronized void write(short[] pcm, int samplesPerChannel) {
            if (wav != null) {
                try {
                    wav.write(pcm, samplesPerChannel * channels);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            if (deviceStartNanos == 0) deviceStartNanos = System.nanoTime();
            for (int i = 0; i < samplesPerChannel * channels; i++) {
                double v = pcm[i] / 32768.0;
                secondAcc += v * v;
            }
            secondCount += samplesPerChannel * channels;
            framesSubmitted += samplesPerChannel;
            framesWritten += samplesPerChannel;
            long bufferFrames = (long) sampleRate * bufferMillis / 1000;
            while (true) {
                long elapsed = System.nanoTime() - deviceStartNanos;
                long played = elapsed * sampleRate / 1_000_000_000L;
                long queued = framesSubmitted - played;
                if (queued <= bufferFrames) break;
                long waitNanos = (queued - bufferFrames) * 1_000_000_000L / sampleRate;
                try {
                    Thread.sleep(Math.max(1, Math.min(5, waitNanos / 1_000_000L)));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        @Override
        public synchronized int bufferedMillis() {
            if (deviceStartNanos == 0) return 0;
            long elapsed = System.nanoTime() - deviceStartNanos;
            long played = elapsed * sampleRate / 1_000_000_000L;
            long queued = framesSubmitted - played;
            if (queued <= 0) return 0;
            return (int) (queued * 1000L / sampleRate);
        }

        @Override
        public synchronized void close() {
            try {
                if (wav != null) {
                    wav.close();
                    wav = null;
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static byte[] readAll(String file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return in.readAllBytes();
        }
    }

    private static final class WavWriter {
        private final FileOutputStream out;
        private final String path;
        private long dataBytes;

        WavWriter(String path, int rate, int channels) throws IOException {
            this.path = path;
            out = new FileOutputStream(path);
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put("RIFF".getBytes());
            header.putInt(0);
            header.put("WAVE".getBytes());
            header.put("fmt ".getBytes());
            header.putInt(16);
            header.putShort((short) 1);
            header.putShort((short) channels);
            header.putInt(rate);
            header.putInt(rate * channels * 2);
            header.putShort((short) (channels * 2));
            header.putShort((short) 16);
            header.put("data".getBytes());
            header.putInt(0);
            out.write(header.array());
        }

        void write(short[] pcm, int count) throws IOException {
            ByteBuffer bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) bb.putShort(pcm[i]);
            out.write(bb.array());
            dataBytes += count * 2L;
        }

        void close() throws IOException {
            out.close();
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path, "rw")) {
                raf.seek(4);
                raf.write(intLE((int) (36 + dataBytes)));
                raf.seek(40);
                raf.write(intLE((int) dataBytes));
            }
        }

        private static byte[] intLE(int v) {
            return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
        }
    }
}
