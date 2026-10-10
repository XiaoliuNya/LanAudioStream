using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
#pragma warning disable CS0618
using Concentus.Enums;
using Concentus.Structs;
using LanAudioStream.Core.Proto;
using LanAudioStream.Core.Util;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace LanAudioStream.LoopbackClient;

internal static class Program
{
    private static int Main(string[] args)
    {
        try { Console.OutputEncoding = System.Text.Encoding.UTF8; } catch { }
        return MainCore(args);
    }

    private static int MainCore(string[] args)
    {
        var mode = args.Length > 0 ? args[0].ToLowerInvariant() : "listen";
        return mode switch
        {
            "listen" => Listen(args),
            "tone" => Tone(args),
            "analyze" => Analyze(args),
            _ => Usage(),
        };
    }

    private static int Usage()
    {
        Console.WriteLine("""
LanAudioStream 协议测试客户端
  LoopbackClient listen [--host 192.168.1.10] [--port 47655] [--seconds 15] [--out decoded.wav] [--dump packets.bin]
  LoopbackClient tone   [--freq 1000] [--seconds 10] [--device <substring>]
  LoopbackClient analyze <file.wav> [--freq 1000]
""");
        return 1;
    }

    private static string? Arg(string[] args, string name)
    {
        int i = Array.IndexOf(args, name);
        return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
    }

    // ------------------------------------------------------------ listen mode
    private static int Listen(string[] args)
    {
        int port = int.Parse(Arg(args, "--port") ?? Wire.DefaultStreamPort.ToString());
        int seconds = int.Parse(Arg(args, "--seconds") ?? "15");
        string? outFile = Arg(args, "--out");
        string? dumpFile = Arg(args, "--dump");
        string? host = Arg(args, "--host");

        using var sock = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        sock.Bind(new IPEndPoint(IPAddress.Any, 0));
        sock.ReceiveTimeout = 50;
        var local = (IPEndPoint)sock.LocalEndPoint!;
        Console.WriteLine($"[client] 本地端口 {local.Port}");

        IPEndPoint server;
        if (host != null)
        {
            server = new IPEndPoint(IPAddress.Parse(host), port);
        }
        else
        {
            server = Discover(sock, out port)!;
            if (server == null)
            {
                Console.WriteLine("[client] 未发现桌面端（超时）。请确认桌面端已点击“开始串流”。");
                return 2;
            }
        }
        Console.WriteLine($"[client] 目标服务器 {server}");

        var buffer = new byte[Wire.MaxDatagram];
        EndPoint from = new IPEndPoint(IPAddress.Any, 0);

        // handshake
        var hello = new byte[Wire.MaxDatagram];
        int helloLen = Wire.WriteHello(hello, (uint)Random.Shared.Next(), 0);
        Wire.AudioFormatInfo format = default;
        bool gotAck = false;
        for (int attempt = 0; attempt < 4 && !gotAck; attempt++)
        {
            sock.SendTo(hello.AsSpan(0, helloLen), server);
            var deadline = DateTime.UtcNow.AddMilliseconds(600);
            while (DateTime.UtcNow < deadline)
            {
                int n;
                try
                {
                    n = sock.ReceiveFrom(buffer, ref from);
                }
                catch (SocketException)
                {
                    continue;
                }
                if (Wire.TryParseHelloAck(buffer.AsSpan(0, n), out var info))
                {
                    format = info;
                    gotAck = true;
                    break;
                }
            }
        }
        if (!gotAck)
        {
            Console.WriteLine("[client] 握手失败：未收到 HELLO_ACK");
            return 3;
        }
        Console.WriteLine($"[client] HELLO_ACK: codec={format.Codec} ch={format.Channels} {format.SampleRate}Hz frameSamples={format.FrameSamples} frameMs={format.FrameMs} bitrate={format.BitrateBps} redundancy={format.Redundancy}");

        var start = new byte[Wire.MaxDatagram];
        int startLen = Wire.WriteEmpty(start, Wire.TypeStart);
        sock.SendTo(start.AsSpan(0, startLen), server);

        var decoder = new OpusDecoder((int)format.SampleRate, (int)format.Channels);
        var pcm = new short[5760 * (int)format.Channels];
        var wav = outFile != null ? new WavWriter(outFile, (int)format.SampleRate, (int)format.Channels) : null;
        var dump = dumpFile != null ? new FileStream(dumpFile, FileMode.Create, FileAccess.Write) : null;
        var dumpBuf = new byte[4];

        var pending = new Dictionary<uint, byte[]>();
        uint expected = uint.MaxValue;
        uint maxSeq = 0;
        long recv = 0, lost = 0, late = 0, dup = 0, recovered = 0, plc = 0;
        long bytes = 0;
        double jitterUs = 0;
        double offsetUs = 0;
        double minRttUs = double.MaxValue;
        double lastTransitUs = 0;
        uint lastSeq = 0;
        bool haveLast = false;
        long framesDecoded = 0;

        var startTime = DateTime.UtcNow;
        var nextRr = startTime;
        var lastRrAt = startTime;
        var nextSync = startTime;
        var nextReport = startTime.AddSeconds(1);
        long windowBytes = 0;
        double latencySum = 0;
        long latencyCount = 0;

        while ((DateTime.UtcNow - startTime).TotalSeconds < seconds)
        {
            var now = DateTime.UtcNow;
            if (now >= nextSync)
            {
                var req = new byte[Wire.MaxDatagram];
                ulong t1 = Clock.NowUs();
                int len = Wire.WriteSyncReq(req, t1);
                sock.SendTo(req.AsSpan(0, len), server);
                nextSync = now.AddMilliseconds(500);
            }
            if (now >= nextRr)
            {
                var rr = new ReceiverReport
                {
                    SeqMax = maxSeq,
                    Recv = (uint)recv,
                    Lost = (uint)lost,
                    Late = (uint)late,
                    Dup = (uint)dup,
                    JitterUs = (uint)Math.Min(jitterUs, uint.MaxValue),
                    E2eLatencyUs = latencyCount > 0 ? (uint)Math.Min(latencySum / latencyCount, uint.MaxValue) : ReceiverReport.Unknown,
                    BufferUs = (uint)(pending.Count * format.FrameMs * 1000),
                    RecvBitrateBps = (uint)Math.Min(windowBytes * 8 / Math.Max(0.001, (now - lastRrAt).TotalSeconds), uint.MaxValue),
                    PlcFrames = (uint)plc,
                    Underruns = 0,
                    LossPermille = recv + lost > 0 ? (ushort)Math.Min(65535, lost * 10000 / (recv + lost)) : (ushort)0,
                    LowLatencyMode = 0,
                };
                var pkt = new byte[Wire.MaxDatagram];
                int len = Wire.WriteRr(pkt, rr);
                sock.SendTo(pkt.AsSpan(0, len), server);
                windowBytes = 0;
                lastRrAt = now;
                nextRr = now.AddMilliseconds(100);
            }

            int received;
            try
            {
                received = sock.ReceiveFrom(buffer, ref from);
            }
            catch (SocketException ex) when (ex.SocketErrorCode is SocketError.TimedOut or SocketError.WouldBlock)
            {
                continue;
            }
            catch (SocketException ex) when (ex.SocketErrorCode == SocketError.ConnectionReset)
            {
                continue;
            }

            var arrivalUs = Clock.NowUs();
            var span = buffer.AsSpan(0, received);
            if (!Wire.TryReadHeader(span, out var h)) continue;

            if (h.Type == Wire.TypeHelloAck)
            {
                if (Wire.TryParseHelloAck(span, out var info)) format = info;
                continue;
            }
            if (h.Type == Wire.TypeSyncRsp)
            {
                ulong t4 = Clock.NowUs();
                if (Wire.TryParseSyncRsp(span, out var t1, out var t2, out var t3))
                {
                    double rtt = (t4 - t1) - (t3 - t2);
                    double off = ((double)t2 - t1 + (double)t3 - t4) / 2.0;
                    if (rtt < minRttUs)
                    {
                        minRttUs = rtt;
                        offsetUs = off;
                    }
                }
                continue;
            }
            if (h.Type == Wire.TypeBye)
            {
                Console.WriteLine("[client] 服务器发送 BYE");
                break;
            }
            if (h.Type != Wire.TypeData) continue;

            bytes += received;
            windowBytes += received;

            byte[] payload = span.Slice(Wire.HeaderSize, h.PayloadLen).ToArray();
            byte[]? red = h.RedLen > 0 ? span.Slice(Wire.HeaderSize + h.PayloadLen, h.RedLen).ToArray() : null;

            double transit = (double)arrivalUs - h.CaptureUs;
            if (haveLast)
            {
                double dd = transit - lastTransitUs;
                jitterUs += (Math.Abs(dd) - jitterUs) / 16.0;
            }
            lastTransitUs = transit;
            haveLast = true;
            double lat = arrivalUs - ((double)h.CaptureUs - offsetUs);
            latencySum += lat;
            latencyCount++;

            if (h.Seq < expected && expected != uint.MaxValue)
            {
                dup++;
                continue;
            }

            // 冗余负载携带的是「上一帧」：只有在确实缺这一帧、且它还没被跳过时才使用，
            // 否则会把别的序号的内容当成缺失帧解码（历史上这里填错了 seq）。
            if (red != null && h.Seq > 0 && expected != uint.MaxValue
                && h.Seq - 1 >= expected && !pending.ContainsKey(h.Seq - 1))
            {
                pending[h.Seq - 1] = red;
                recovered++;
            }
            pending[h.Seq] = payload;
            if (h.Seq > maxSeq) maxSeq = h.Seq;
            recv++;

            if (expected == uint.MaxValue) expected = pending.Keys.Min();

            while (pending.Remove(expected, out var frame))
            {
                int samples = decoder.Decode(frame.AsSpan(), pcm.AsSpan(), format.FrameSamples, false);
                framesDecoded++;
                wav?.Write(pcm, samples * (int)format.Channels);
                if (dump != null)
                {
                    dumpedFrames++;
                    dump.Write(BitConverter.GetBytes(expected));
                    dump.Write(BitConverter.GetBytes(frame.Length));
                    dump.Write(frame);
                }
                lastSeq = expected;
                expected++;
            }

            // drop frames that are too far behind
            foreach (var key in pending.Keys.ToList())
            {
                if (key + 8 < expected)
                {
                    pending.Remove(key);
                    late++;
                    lost++;
                }
            }

            if (DateTime.UtcNow >= nextReport)
            {
                double secs = (DateTime.UtcNow - startTime).TotalSeconds;
                double lossPct = recv + lost > 0 ? lost * 100.0 / (recv + lost) : 0;
                Console.WriteLine($"[{secs,5:0.0}s] recv={recv} lost={lost} dup={dup} recov={recovered} jitter={jitterUs / 1000:0.00}ms e2e={(latencyCount > 0 ? latencySum / latencyCount / 1000 : 0):0.0}ms loss={lossPct:0.00}% kbps={bytes * 8.0 / secs / 1000:0.0} decoded={framesDecoded}");
                nextReport = DateTime.UtcNow.AddSeconds(1);
            }
        }

        if (wav != null)
        {
            wav.Dispose();
            Console.WriteLine($"[client] 已写入 {outFile} ({framesDecoded} 帧)");
        }
        if (dump != null)
        {
            dump.Dispose();
            Console.WriteLine($"[client] 已写入 {dumpFile}: {dumpedFrames} 帧");
        }
        var bye = new byte[Wire.MaxDatagram];
        sock.SendTo(bye.AsSpan(0, Wire.WriteEmpty(bye, Wire.TypeBye)), server);
        return 0;
    }

    private static long dumpedFrames;

    private static IPEndPoint? Discover(Socket sock, out int port)
    {
        port = Wire.DefaultStreamPort;
        var probe = new byte[Wire.MaxDatagram];
        int probeLen = Wire.WriteEmpty(probe, Wire.TypeProbe);
        var targets = new List<IPEndPoint> { new(IPAddress.Broadcast, Wire.DiscoveryPort) };
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var ua in ni.GetIPProperties().UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                var ip = ua.Address.GetAddressBytes();
                var mask = ua.IPv4Mask.GetAddressBytes();
                var b = new byte[4];
                for (int i = 0; i < 4; i++) b[i] = (byte)(ip[i] | (mask[i] ^ 0xFF));
                targets.Add(new IPEndPoint(new IPAddress(b), Wire.DiscoveryPort));
            }
        }

        var buf = new byte[Wire.MaxDatagram];
        var deadline = DateTime.UtcNow.AddSeconds(6);
        int attempt = 0;
        try
        {
            sock.EnableBroadcast = true;
        }
        catch
        {
            // ignored
        }
        while (DateTime.UtcNow < deadline)
        {
            if (DateTime.UtcNow > deadline.AddMilliseconds(-5000) || attempt == 0 || attempt % 4 == 0)
            {
                foreach (var t in targets)
                {
                    try
                    {
                        sock.SendTo(probe.AsSpan(0, probeLen), t);
                    }
                    catch (SocketException)
                    {
                        // ignore
                    }
                }
            }
            attempt++;
            EndPoint from = new IPEndPoint(IPAddress.Any, 0);
            int n;
            try
            {
                n = sock.ReceiveFrom(buf, ref from);
            }
            catch (SocketException)
            {
                continue;
            }
            if (Wire.TryParseAnnounce(buf.AsSpan(0, n), out var name, out var streamPort, out var streaming))
            {
                Console.WriteLine($"[client] 发现 {name} @ {((IPEndPoint)from).Address}:{streamPort} (streaming={streaming})");
                port = streamPort;
                return new IPEndPoint(((IPEndPoint)from).Address, streamPort);
            }
        }
        return null;
    }

    // ------------------------------------------------------------- tone mode
    private static int Tone(string[] args)
    {
        double freq = double.Parse(Arg(args, "--freq") ?? "1000");
        int seconds = int.Parse(Arg(args, "--seconds") ?? "10");
        using var enumerator = new MMDeviceEnumerator();
        var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        var output = new WasapiOut(device, AudioClientShareMode.Shared, false, 100);
        var signal = new SignalGenerator(48000, 2) { Gain = 0.3, Frequency = freq, Type = SignalGeneratorType.Sin };
        output.Init(signal);
        Console.WriteLine($"[tone] 在 {device.FriendlyName} 播放 {freq} Hz {seconds} 秒");
        output.Play();
        Thread.Sleep(seconds * 1000);
        output.Stop();
        output.Dispose();
        Console.WriteLine("[tone] 结束");
        return 0;
    }

    // ---------------------------------------------------------- analyze mode
    private static int Analyze(string[] args)
    {
        string file = args[1];
        double freq = double.Parse(Arg(args, "--freq") ?? "1000");
        using var fs = File.OpenRead(file);
        using var br = new BinaryReader(fs);
        br.ReadBytes(12);
        int channels = 0, rate = 0, bits = 0;
        byte[] data = Array.Empty<byte>();
        while (fs.Position + 8 <= fs.Length)
        {
            var id = new string(br.ReadChars(4));
            int size = br.ReadInt32();
            if (size < 0 || fs.Position + size > fs.Length) break;
            if (id == "fmt ")
            {
                br.ReadInt16();
                channels = br.ReadInt16();
                rate = br.ReadInt32();
                br.ReadBytes(6);
                bits = br.ReadInt16();
                if (size > 16) br.ReadBytes(size - 16);
            }
            else if (id == "data")
            {
                data = br.ReadBytes(size);
            }
            else
            {
                br.ReadBytes(size);
            }
            if (size % 2 == 1 && fs.Position < fs.Length) br.ReadByte();
        }

        int bytesPerFrame = channels * (bits / 8);
        int frames = bytesPerFrame > 0 ? data.Length / bytesPerFrame : 0;
        Console.WriteLine($"[analyze] {file}: {rate} Hz {channels} ch {bits} bit {frames} 帧 ({frames / (double)Math.Max(1, rate):0.00} 秒)");

        // windowed RMS: quantifies audible dropouts (silent windows inside continuous tone)
        int window = Math.Max(1, rate / 20); // 50 ms
        int silent = 0, quiet = 0, windows = 0;
        double sumSqAll = 0;
        for (int start = 0; start + window <= frames; start += window)
        {
            double winSum = 0;
            for (int i = start; i < start + window; i++)
            {
                short s = BitConverter.ToInt16(data, i * bytesPerFrame);
                double v = s / 32768.0;
                winSum += v * v;
            }
            double winRms = Math.Sqrt(winSum / window);
            sumSqAll += winSum;
            windows++;
            if (winRms < 0.02) silent++;
            else if (winRms < 0.08) quiet++;
        }
        if (windows > 0)
        {
            Console.WriteLine($"[analyze] 50ms 窗口={windows} 静音窗口={silent} 偏弱窗口={quiet} " +
                              $"静音占比={silent * 100.0 / windows:0.00}% (整体 RMS={Math.Sqrt(sumSqAll / Math.Max(1, windows * (double)window)):0.0000})");
            Console.WriteLine(silent * 100.0 / windows < 1.0
                ? "[analyze] 结论: 音频连续，无可闻断音 ✓"
                : "[analyze] 结论: 存在可闻断音 ✗");
        }

        double sumSq = 0;
        double re = 0, im = 0;
        for (int i = 0; i < frames; i++)
        {
            short s = BitConverter.ToInt16(data, i * bytesPerFrame);
            double v = s / 32768.0;
            sumSq += v * v;
            double w = 2 * Math.PI * freq * i / rate;
            re += v * Math.Cos(w);
            im += v * Math.Sin(w);
        }
        double rms = Math.Sqrt(sumSq / Math.Max(1, frames));
        double mag = Math.Sqrt(re * re + im * im) / Math.Max(1, frames) * 2;
        Console.WriteLine($"[analyze] RMS={rms:0.0000} 目标频率({freq:0}Hz)幅值={mag:0.0000}");
        if (rms < 0.005) Console.WriteLine("[analyze] 结论: 音频过弱或为静音 ✗");
        else if (mag > 0.15) Console.WriteLine("[analyze] 结论: 检测到目标频率音频 ✓");
        else Console.WriteLine("[analyze] 结论: 有音频但未命中目标频率 ?");
        return 0;
    }
}
internal sealed class WavWriter : IDisposable
{
    private readonly FileStream _fs;
    private readonly BinaryWriter _bw;
    private readonly int _rate;
    private readonly int _channels;
    private int _dataBytes;

    public WavWriter(string path, int rate, int channels)
    {
        _rate = rate;
        _channels = channels;
        _fs = new FileStream(path, FileMode.Create, FileAccess.ReadWrite);
        _bw = new BinaryWriter(_fs);
        _bw.Write("RIFF"u8.ToArray());
        _bw.Write(0);
        _bw.Write("WAVE"u8.ToArray());
        _bw.Write("fmt "u8.ToArray());
        _bw.Write(16);
        _bw.Write((short)1);
        _bw.Write((short)channels);
        _bw.Write(rate);
        _bw.Write(rate * channels * 2);
        _bw.Write((short)(channels * 2));
        _bw.Write((short)16);
        _bw.Write("data"u8.ToArray());
        _bw.Write(0);
    }

    public void Write(short[] samples, int count)
    {
        for (int i = 0; i < count; i++) _bw.Write(samples[i]);
        _dataBytes += count * 2;
    }

    public void Dispose()
    {
        _bw.Flush();
        _fs.Seek(4, SeekOrigin.Begin);
        _bw.Write(36 + _dataBytes);
        _fs.Seek(40, SeekOrigin.Begin);
        _bw.Write(_dataBytes);
        _bw.Flush();
        _bw.Dispose();
        _fs.Dispose();
    }
}
