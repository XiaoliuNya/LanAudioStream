using LanAudioStream.App.Audio;
using LanAudioStream.Core.Net;
using LanAudioStream.Core.Proto;
using NAudio.CoreAudioApi;

namespace LanAudioStream.App;

internal static class Headless
{
    public static int Run(string[] args)
    {
        try { Console.OutputEncoding = System.Text.Encoding.UTF8; } catch { }
        string? Arg(string name)
        {
            int i = Array.IndexOf(args, name);
            return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
        }

        int port = int.Parse(Arg("--port") ?? Wire.DefaultStreamPort.ToString());
        int seconds = int.Parse(Arg("--seconds") ?? "20");
        int bitrate = int.Parse(Arg("--bitrate") ?? "128000");
        int frameMs = int.Parse(Arg("--frame") ?? "10");
        bool mute = args.Contains("--mute");

        Console.WriteLine($"[headless] 端口={port} 时长={seconds}s {bitrate / 1000}kbps {frameMs}ms 静音={mute}");

        using var enumerator = new MMDeviceEnumerator();
        var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        using var capture = new CaptureSource(device, 20);
        int startCodec = (Arg("--codec") ?? "opus").Equals("pcm", StringComparison.OrdinalIgnoreCase) ? Wire.CodecOpusPcm : Wire.CodecOpus;
        int startRate = int.Parse(Arg("--samplerate") ?? "48000");
        int startChannels = int.Parse(Arg("--channels") ?? "2");
        using var pipeline = new AudioPipeline(frameMs, bitrate, startChannels, false, 10, startCodec, startRate) { CaptureLatencyUs = 20000 };
        using var server = new StreamServer(port);
        using var announcer = new Announcer(port) { ServerName = $"LanAudioStream@{Environment.MachineName}" };
        using var muteControl = new MuteControl();

        // 一律以管线实际参数为准：管线会把采样率归一到 8/12/16/24/48k、帧长夹到 5–40ms（PCM ≤10ms）。
        // 若照命令行原值通告 HELLO_ACK，手机会用非法采样率建解码器（表现为"连上了但没声音、收包数不涨"）。
        int curBitrate = pipeline.BitrateBps;
        int curFrameMs = pipeline.FrameMs;
        int curChannels = pipeline.Channels;
        bool curRedundancy = pipeline.Codec != Wire.CodecOpusPcm;
        bool curMute = mute;
        bool curDtx = false;
        int curCodec = pipeline.Codec;
        int curRate = pipeline.SampleRate;
        server.RedundancyEnabled = curRedundancy;
        server.Format = new Wire.AudioFormatInfo((byte)curCodec, (byte)curChannels, (byte)curFrameMs,
            (byte)(curRedundancy ? 1 : 0), (uint)curRate, (ushort)(curRate * curFrameMs / 1000), (uint)curBitrate, 0);

        void PushSettings()
        {
            server.SendSettings(new List<Wire.Setting>
            {
                new(Wire.KeyBitrateKbps, (uint)(curBitrate / 1000)),
                new(Wire.KeyFrameMs, (uint)curFrameMs),
                new(Wire.KeyRedundancy, curRedundancy ? 1u : 0u),
                new(Wire.KeyCaptureBufferMs, 20u),
                new(Wire.KeyMuteSpeaker, curMute ? 1u : 0u),
                new(Wire.KeyChannels, (uint)curChannels),
                new(Wire.KeyDtx, curDtx ? 1u : 0u),
                new(Wire.KeyCodec, (uint)curCodec),
                new(Wire.KeySampleRate, (uint)curRate),
            });
        }

        server.SettingsReceived += list =>
        {
            foreach (var s in list)
            {
                switch (s.Key)
                {
                    case Wire.KeyBitrateKbps: curBitrate = (int)Math.Clamp(s.Value, 32u, 320u) * 1000; break;
                    case Wire.KeyFrameMs: curFrameMs = (int)Math.Clamp(s.Value, 5u, 40u); break;
                    case Wire.KeyChannels: curChannels = s.Value == 1 ? 1 : 2; break;
                    case Wire.KeyRedundancy: curRedundancy = s.Value != 0; break;
                    case Wire.KeyMuteSpeaker: curMute = s.Value != 0; muteControl.SetMuted(curMute); break;
                    case Wire.KeyDtx: curDtx = s.Value != 0; break;
                    case Wire.KeyCodec: curCodec = s.Value == Wire.CodecOpusPcm ? Wire.CodecOpusPcm : Wire.CodecOpus; break;
                    case Wire.KeySampleRate: curRate = (int)Math.Clamp(s.Value, 8000u, 48000u); break;
                }
            }
            pipeline.Reconfigure(curFrameMs, curBitrate, curChannels, curDtx, pipeline.Complexity, curCodec, curRate);
            // 回读管线实际生效值（采样率归一化、帧长夹取），保证通告与真实编码一致
            curRate = pipeline.SampleRate;
            curFrameMs = pipeline.FrameMs;
            curChannels = pipeline.Channels;
            curCodec = pipeline.Codec;
            curBitrate = pipeline.BitrateBps;
            curRedundancy = curRedundancy && curCodec != Wire.CodecOpusPcm;
            server.RedundancyEnabled = curRedundancy;
            server.Format = new Wire.AudioFormatInfo((byte)curCodec, (byte)curChannels, (byte)curFrameMs,
                (byte)(curRedundancy ? 1 : 0), (uint)curRate, (ushort)(curRate * curFrameMs / 1000), (uint)curBitrate, 0);
            server.SendHelloAck();
            PushSettings();
            Console.WriteLine($"[settings] 已应用: codec={curCodec} {curRate}Hz {curBitrate / 1000}kbps {curFrameMs}ms {curChannels}ch 冗余={curRedundancy} 静音={curMute}");
        };
        server.Log += m => Console.WriteLine("[server] " + m);
        announcer.Log += m => Console.WriteLine("[discovery] " + m);
        announcer.Start();
        server.Start();

        double level = 0;
        double maxLevel = 0;
        pipeline.LevelChanged += l => { level = l; if (l > maxLevel) maxLevel = l; };

        // cadence instrumentation: how bursty are the encoded frames handed to the network?
        var sendStopwatch = System.Diagnostics.Stopwatch.StartNew();
        long lastSendTicks = sendStopwatch.ElapsedTicks;
        long maxGapTicks = 0;
        long framesThisSecond = 0;
        pipeline.FrameReady += (payload, len, captureUs, silence, send) =>
        {
            long now = sendStopwatch.ElapsedTicks;
            long gap = now - lastSendTicks;
            if (gap > maxGapTicks) maxGapTicks = gap;
            lastSendTicks = now;
            framesThisSecond++;
            server.SendAudioFrame(payload.AsSpan(0, len), captureUs, silence, send);
        };
        capture.SamplesAvailable += (samples, count) => pipeline.Push(samples, count);
        pipeline.Start();
        capture.Start();
        if (mute) muteControl.SetMuted(true);
        server.StreamStarted += PushSettings;

        Console.WriteLine($"[headless] 采集: {device.FriendlyName} | {capture.SourceFormat} | 缓冲 {capture.EffectiveLatencyMs}ms");

        long lastBytes = 0;
        var start = DateTime.UtcNow;
        while ((DateTime.UtcNow - start).TotalSeconds < seconds)
        {
            Thread.Sleep(1000);
            double secs = (DateTime.UtcNow - start).TotalSeconds;
            long bytes = server.BytesSent;
            var rr = server.LastReport;
            bool fresh = server.LastRrAgeUs < 2_000_000;
            double gapMs = maxGapTicks * 1000.0 / System.Diagnostics.Stopwatch.Frequency;
            Console.Write($"[cadence] 帧/秒={framesThisSecond} 最大帧间隔={gapMs:0.0}ms ");
            maxGapTicks = 0;
            framesThisSecond = 0;
            Console.WriteLine($"[stat] 饥饿={pipeline.Starvations} 丢弃采样={pipeline.DroppedSamples} [{(DateTime.UtcNow - start).TotalSeconds,5:0.0}s] rms={maxLevel:0.0000} 发送={bytes / 1024.0:0.0}KiB 速率={(bytes - lastBytes) * 8 / 1000.0:0.0}kbps 客户端={server.Client?.ToString() ?? "-"} 状态={(server.Streaming ? "串流中" : "待机")} e2e={(fresh && rr.HasLatency ? (rr.E2eLatencyUs / 1000.0).ToString("0.0") + "ms" : "--")} 抖动={(fresh ? rr.JitterUs / 1000.0 : 0):0.0}ms 丢包={(fresh ? rr.LossPercent : 0):0.00}% 缓冲={(fresh ? rr.BufferUs / 1000.0 : 0):0.0}ms 编码={pipeline.AverageEncodeMs:0.00}ms");
            lastBytes = bytes;
            maxLevel = 0;
        }

        if (mute) muteControl.Restore();
        Console.WriteLine("[headless] 结束");
        return 0;
    }
}
