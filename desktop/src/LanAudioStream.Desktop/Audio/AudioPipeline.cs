using System.Diagnostics;
using LanAudioStream.Core.Codec;
using LanAudioStream.Core.Proto;
using LanAudioStream.Core.Util;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace LanAudioStream.App.Audio;

/// <summary>采集(48k 立体声 float) → 可选重采样 → Opus 或 PCM16 打包 → 网络。</summary>
public sealed class AudioPipeline : IDisposable
{
    public const int CaptureRate = 48000;
    public const int CaptureChannels = 2;
    private const int MaxFrameMs = 40;
    /// <summary>DTX：帧 RMS 低于该值视为静音（约 -64 dBFS）。</summary>
    private const double DtxSilenceRms = 0.0006;
    /// <summary>DTX：静音段开头仍逐帧发送，之后按约 10 包/秒发送静音标记。</summary>
    private const int DtxBurstFrames = 3;

    /// <summary>
    /// 不可变的运行参数快照：每次重配整体替换一次（引用赋值是原子的），编码线程每轮开头取一次快照后
    /// 只使用局部变量，因此不会出现「检查非空之后又被置空」的竞态
    /// （历史上这里空引用直接把整个进程带崩）。
    /// </summary>
    private sealed class PumpConfig
    {
        public int FrameMs;
        public int SampleRate;
        public int Channels;
        public bool Dtx;
        public int Codec;
        public int BitrateBps;
        public int Complexity;
        public OpusEncoderWrapper? Encoder;
        /// <summary>上一代编码器：等编码线程不再持有它之后再释放（避免用到已 Dispose 的对象）。</summary>
        public OpusEncoderWrapper? Retired;
        public ISampleProvider? Resampler;
    }

    private readonly FloatRing _ring = new(CaptureRate * CaptureChannels * 2);
    private readonly float[] _frame;
    private readonly float[] _mono;
    private readonly byte[] _packet = new byte[Wire.MaxDatagram];
    private readonly object _reconfigGate = new();
    private volatile PumpConfig _cfg;
    private Thread? _thread;
    private volatile bool _running;
    private long _framesConsumed;
    private int _silenceRun;
    private long _levelCounter;
    private long _starvations;
    private long _encodeErrors;
    private string? _lastEncodeError;
    private ulong _captureBaseUs;
    private double _encodeMs;

    /// <summary>payload / 长度 / 采集时刻 / 是否 DTX 静音标记（长度为 0，接收端按序号补静音）。</summary>
    public event Action<byte[], int, ulong, bool, bool>? FrameReady;
    public event Action<double>? LevelChanged;
    public event Action<string>? Error;

    public int FrameMs => _cfg.FrameMs;
    public int BitrateBps => _cfg.BitrateBps;
    public int Channels => _cfg.Channels;
    public bool Dtx => _cfg.Dtx;
    public int Complexity => _cfg.Complexity;
    public int Codec => _cfg.Codec;
    public int SampleRate => _cfg.SampleRate;
    public bool IsPcm => _cfg.Codec == Wire.CodecOpusPcm;
    public int FrameSamples => _cfg.SampleRate * _cfg.FrameMs / 1000;
    public int CaptureLatencyUs { get; set; } = 20000;
    public long DroppedSamples => _ring.DroppedSamples;
    public long Starvations => Interlocked.Read(ref _starvations);
    public long EncodeErrors => Interlocked.Read(ref _encodeErrors);
    public string? LastEncodeError => _lastEncodeError;
    public double AverageEncodeMs => _encodeMs;

    public AudioPipeline(int frameMs, int bitrateBps, int channels = 2, bool dtx = false, int complexity = 10,
        int codec = Wire.CodecOpus, int sampleRate = CaptureRate)
    {
        _frame = new float[CaptureRate * CaptureChannels / 1000 * MaxFrameMs];
        _mono = new float[CaptureRate / 1000 * MaxFrameMs];
        _cfg = new PumpConfig
        {
            FrameMs = Math.Clamp(frameMs, 5, MaxFrameMs),
            SampleRate = sampleRate,
            Channels = Math.Clamp(channels, 1, 2),
            Dtx = dtx,
            Codec = codec,
            BitrateBps = bitrateBps,
            Complexity = Math.Clamp(complexity, 1, 10),
        };
        Reconfigure(frameMs, bitrateBps, channels, dtx, complexity, codec, sampleRate);
    }

    public void Reconfigure(int frameMs, int bitrateBps, int channels, bool dtx, int complexity, int codec, int sampleRate)
    {
        lock (_reconfigGate)
        {
            var previous = _cfg;
            int newCodec = codec == Wire.CodecOpusPcm ? Wire.CodecOpusPcm : Wire.CodecOpus;
            // Opus 编码器只接受 8/12/16/24/48 kHz：UI / 手机端 / 历史配置里的 44.1k 或 32k 必须就近归一化，
            // 否则 new OpusEncoder(44100, ...) 抛 ArgumentException，异常逃出后台线程会直接结束进程。
            int newRate = NormalizeSampleRate(sampleRate, newCodec == Wire.CodecOpusPcm);
            int newChannels = Math.Clamp(channels, 1, 2);
            int newComplexity = Math.Clamp(complexity, 1, 10);
            // PCM 每包更大，限制帧长以避免 IP 分片过多
            int newFrameMs = Math.Clamp(frameMs, 5, newCodec == Wire.CodecOpusPcm ? 10 : MaxFrameMs);

            OpusEncoderWrapper? encoder = null;
            if (newCodec != Wire.CodecOpusPcm)
            {
                // 一律新建编码器：旧实现会就地改写编码线程正在使用的同一个 wrapper（帧长/码率/复位状态），
                // 改帧长的瞬间 Concentus 会抛 "Not enough samples" 丢掉一帧（手机侧记成丢包并触发 PLC），
                // 而且那条分支从不释放旧编码器（泄漏）。
                encoder = new OpusEncoderWrapper(newRate, newChannels, newFrameMs, bitrateBps, dtx, newComplexity);
            }

            _cfg = new PumpConfig
            {
                FrameMs = newFrameMs,
                SampleRate = newRate,
                Channels = newChannels,
                Dtx = dtx,
                Codec = newCodec,
                BitrateBps = bitrateBps,
                Complexity = newComplexity,
                Encoder = encoder,
                Retired = previous.Encoder ?? previous.Retired,
                Resampler = newRate == CaptureRate
                    ? null
                    : new WdlResamplingSampleProvider(new RingSampleProvider(_ring, CaptureChannels), newRate),
            };
            // 上上一代编码器此时肯定没人再持有，可以安全释放
            previous.Retired?.Dispose();

            _ring.Clear();
            // 帧长/采样率变化后必须重置采集时间基准，否则 captureUs 会随时间漂移，
            // 手机算出来的「端到端延迟」会跟着漂（曾经出现过 +3.5s 的假延迟）。
            _framesConsumed = 0;
            _captureBaseUs = Clock.NowUs();
        }
    }

    public void Start()
    {
        _captureBaseUs = Clock.NowUs();
        _framesConsumed = 0;
        _cfg.Encoder?.Reset();
        _ring.Clear();
        _running = true;
        _thread = new Thread(PumpGuard)
        {
            IsBackground = true,
            Name = "audio-encode",
            Priority = ThreadPriority.Highest,
        };
        _thread.Start();
    }

    public void Stop()
    {
        _running = false;
        _thread?.Join(500);
        _thread = null;
    }

    public void Push(float[] samples, int count) => _ring.Write(samples.AsSpan(0, count));

    /// <summary>从环形缓冲拉取样点（供重采样器使用）。</summary>
    private sealed class RingSampleProvider : ISampleProvider
    {
        private readonly FloatRing _ring;
        private readonly float[] _scratch = new float[8192];

        public RingSampleProvider(FloatRing ring, int channels)
        {
            _ring = ring;
            WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(CaptureRate, channels);
        }

        public WaveFormat WaveFormat { get; }

        public int Read(Span<float> buffer)
        {
            int n = Math.Min(buffer.Length, _scratch.Length);
            int got = _ring.Read(_scratch, n);
            if (got > 0) _scratch.AsSpan(0, got).CopyTo(buffer);
            return got;
        }
    }

    private void ReportEncodeError(Exception ex)
    {
        long n = Interlocked.Increment(ref _encodeErrors);
        _lastEncodeError = ex.Message;
        if (n == 1 || n % 100 == 0)
        {
            Error?.Invoke($"编码失败 x{n}: {ex.Message}");
        }
    }

    /// <summary>把采样率就近归一到编码器允许的集合（Opus: 8/12/16/24/48 kHz；PCM: 16/24/48 kHz）。</summary>
    public static int NormalizeSampleRate(int rate, bool pcm)
    {
        int[] allowed = pcm ? new[] { 16000, 24000, 48000 } : new[] { 8000, 12000, 16000, 24000, 48000 };
        int best = allowed[^1];
        int bestDiff = int.MaxValue;
        foreach (int r in allowed)
        {
            int d = Math.Abs(r - rate);
            if (d < bestDiff)
            {
                bestDiff = d;
                best = r;
            }
        }
        return best;
    }

    /// <summary>
    /// 顶层兜底：任何逃出单帧处理的异常（例如订阅者回调抛错）只记日志并重启泵，
    /// 绝不让后台线程的未处理异常结束整个进程（历史上 PCM 崩溃就是这样把进程带走的）。
    /// </summary>
    private void PumpGuard()
    {
        while (_running)
        {
            try
            {
                PumpLoop();
            }
            catch (Exception ex)
            {
                ReportEncodeError(ex);
                Thread.Sleep(50);
            }
        }
    }

    private void PumpLoop()
    {
        bool starved = true;
        while (_running)
        {
            var cfg = _cfg;   // 本轮自洽快照
            int channels = cfg.Channels;
            int targetRate = cfg.SampleRate;
            int samplesPerChannel = targetRate * cfg.FrameMs / 1000;
            if (samplesPerChannel <= 0) { Thread.Sleep(5); continue; }

            if (cfg.Resampler == null)
            {
                int sourceNeed = samplesPerChannel * CaptureChannels;
                if (sourceNeed > _frame.Length) { Thread.Sleep(5); continue; }
                if (!_ring.WaitFor(sourceNeed, 20, () => !_running))
                {
                    starved = true;
                    Interlocked.Increment(ref _starvations);
                    continue;
                }
                int got = _ring.Read(_frame, sourceNeed);
                if (got < sourceNeed) continue;
            }
            else
            {
                if (_ring.Count < CaptureChannels * 480 && !_ring.WaitFor(CaptureChannels * 480, 20, () => !_running))
                {
                    starved = true;
                    continue;
                }
                int wanted = samplesPerChannel * CaptureChannels;
                if (wanted > _frame.Length) { Thread.Sleep(5); continue; }
                int read = 0;
                int guard = 0;
                while (read < wanted && guard++ < 40)
                {
                    int n = cfg.Resampler.Read(_frame.AsSpan(read, wanted - read));
                    if (n <= 0)
                    {
                        if (!_ring.WaitFor(CaptureChannels * 240, 10, () => !_running)) break;
                        continue;
                    }
                    read += n;
                }
                if (read < wanted) continue;
            }

            if (starved)
            {
                starved = false;
                _framesConsumed = 0;
                _captureBaseUs = Clock.NowUs() - (ulong)CaptureLatencyUs;
            }

            // 帧数 × 每帧样点数 ÷ 采样率 = 时间；这里必须按样点换算（曾误按帧数换算导致延迟缓慢漂移）
            ulong captureUs = _captureBaseUs +
                (ulong)(_framesConsumed * (long)samplesPerChannel * 1_000_000L / Math.Max(1, targetRate));
            _framesConsumed++;

            double sum = 0;
            for (int i = 0; i < samplesPerChannel * CaptureChannels; i++) sum += _frame[i] * (double)_frame[i];

            Span<float> src;
            if (channels == 1)
            {
                for (int i = 0; i < samplesPerChannel; i++)
                {
                    _mono[i] = (_frame[i * 2] + _frame[i * 2 + 1]) * 0.5f;
                }
                src = _mono.AsSpan(0, samplesPerChannel);
            }
            else
            {
                src = _frame.AsSpan(0, samplesPerChannel * CaptureChannels);
            }

            double rms = Math.Sqrt(sum / Math.Max(1, samplesPerChannel * CaptureChannels));
            bool quiet = cfg.Dtx && rms < DtxSilenceRms;

            int len;
            var sw = Stopwatch.StartNew();
            if (cfg.Codec == Wire.CodecOpusPcm)
            {
                len = 0;
                if (!quiet)
                {
                    len = src.Length * 2;
                    if (len > _packet.Length) len = _packet.Length;
                    for (int i = 0, o = 0; o + 1 < len; i++, o += 2)
                    {
                        short v = (short)Math.Clamp((int)(src[i] * 32767f), short.MinValue, short.MaxValue);
                        _packet[o] = (byte)v;
                        _packet[o + 1] = (byte)(v >> 8);
                    }
                }
                sw.Stop();
                _encodeMs = sw.Elapsed.TotalMilliseconds;
            }
            else
            {
                var encoder = cfg.Encoder;
                if (encoder == null)
                {
                    ReportEncodeError(new InvalidOperationException("编码器未就绪"));
                    continue;
                }
                try
                {
                    // 静音帧也照常喂给编码器，保持内部状态连续（Concentus 的 UseDTX 实测无效，DTX 由本层实现）
                    len = encoder.Encode(src, _packet);
                }
                catch (Exception ex)
                {
                    ReportEncodeError(ex);
                    continue;
                }
                sw.Stop();
                _encodeMs = _encodeMs == 0 ? sw.Elapsed.TotalMilliseconds : _encodeMs * 0.9 + sw.Elapsed.TotalMilliseconds * 0.1;
            }

            if (quiet)
            {
                // DTX 静音：只发少量「静音标记包」（24 字节包头），接收端按序号补静音且不计丢包
                _silenceRun++;
                int every = Math.Max(1, (int)Math.Round(100.0 / Math.Max(1, cfg.FrameMs)));
                bool sendMarker = _silenceRun <= DtxBurstFrames || ((_silenceRun - DtxBurstFrames) % every) == 0;
                // send=false 的帧只推进序号、不发包：接收端据此把空档补成静音，时间轴才不会错
                FrameReady?.Invoke(_packet, 0, captureUs, true, sendMarker);
            }
            else
            {
                _silenceRun = 0;
                if (len > 0)
                {
                    FrameReady?.Invoke(_packet, len, captureUs, false, true);
                }
            }
            _levelCounter++;
            if ((_levelCounter % 5) == 0)
            {
                LevelChanged?.Invoke(Math.Sqrt(sum / Math.Max(1, samplesPerChannel * CaptureChannels)));
            }
        }
    }

    public void Dispose()
    {
        Stop();
        _cfg.Encoder?.Dispose();
        _cfg.Retired?.Dispose();
    }
}
