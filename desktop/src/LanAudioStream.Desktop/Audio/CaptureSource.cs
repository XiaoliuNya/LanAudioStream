using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace LanAudioStream.App.Audio;

public sealed class CaptureSource : IDisposable
{
    public const int SampleRate = 48000;
    public const int Channels = 2;

    private static readonly Guid SubFormatIeeeFloat = new("00000003-0000-0010-8000-00aa00389b71");

    private readonly WasapiRecorder _recorder;
    private readonly bool _needsConversion;
    private readonly BufferedWaveProvider? _buffer;
    private readonly ISampleProvider? _chain;
    private readonly float[] _scratch = new float[SampleRate * Channels];
    private Thread? _convertThread;
    private volatile bool _running;
    private readonly ManualResetEventSlim _stop = new(false);

    public string DeviceName { get; }
    public string SourceFormat { get; }
    public bool ConversionActive => _needsConversion;
    public int CaptureBufferMs { get; }
    public int EffectiveLatencyMs { get; private set; }

    public event Action<float[], int>? SamplesAvailable;

    public CaptureSource(MMDevice device, int bufferMs)
    {
        CaptureBufferMs = bufferMs;
        DeviceName = device.FriendlyName;
        _recorder = new WasapiRecorderBuilder()
            .WithDevice(device)
            .WithSharedMode()
            .WithEventSync()
            .WithLoopbackCapture()
            .WithBufferLength(bufferMs)
            .Build();

        var fmt = _recorder.WaveFormat;
        SourceFormat = $"{fmt.SampleRate} Hz / {fmt.Channels} ch / {fmt.BitsPerSample} bit {DescribeEncoding(fmt)}";
        _needsConversion = fmt.SampleRate != SampleRate
                           || fmt.Channels != Channels
                           || !IsFloat32(fmt);

        if (_needsConversion)
        {
            _buffer = new BufferedWaveProvider(fmt, TimeSpan.FromMilliseconds(500))
            {
                DiscardOnBufferOverflow = true,
            };
            ISampleProvider provider = _buffer.ToSampleProvider();
            if (fmt.Channels == 1) provider = new MonoToStereoSampleProvider(provider);
            else if (fmt.Channels > 2) provider = new FirstChannelsSampleProvider(provider, Channels);
            if (fmt.SampleRate != SampleRate) provider = new WdlResamplingSampleProvider(provider, SampleRate);
            _chain = provider;
        }

        _recorder.DataAvailable += OnDataAvailable;
    }

    private static bool IsFloat32(WaveFormat fmt)
    {
        if (fmt.BitsPerSample != 32) return false;
        if (fmt.Encoding == WaveFormatEncoding.IeeeFloat) return true;
        if (fmt.Encoding == WaveFormatEncoding.Extensible && fmt is WaveFormatExtensible ext)
        {
            return ext.SubFormat == SubFormatIeeeFloat;
        }
        return false;
    }

    private static string DescribeEncoding(WaveFormat fmt)
    {
        if (fmt.Encoding == WaveFormatEncoding.Extensible && fmt is WaveFormatExtensible ext)
        {
            string sub = ext.SubFormat == SubFormatIeeeFloat ? "float" : ext.SubFormat.ToString();
            return $"Extensible({sub})";
        }
        return fmt.Encoding.ToString();
    }

    public void Start()
    {
        _recorder.StartRecording();
        try
        {
            EffectiveLatencyMs = _recorder.LatencyMilliseconds;
        }
        catch
        {
            EffectiveLatencyMs = CaptureBufferMs;
        }
        if (_needsConversion)
        {
            _running = true;
            _convertThread = new Thread(ConvertLoop)
            {
                IsBackground = true,
                Name = "capture-convert",
                Priority = ThreadPriority.AboveNormal,
            };
            _convertThread.Start();
        }
    }

    public void Stop()
    {
        _running = false;
        _stop.Set();
        try
        {
            _recorder.StopRecording();
        }
        catch
        {
            // ignored
        }
        _convertThread?.Join(500);
        _convertThread = null;
    }

    private void OnDataAvailable(ReadOnlySpan<byte> buffer, AudioClientBufferFlags flags, long devicePosition, long qpcPosition)
    {
        if (_needsConversion)
        {
            _buffer?.AddSamples(buffer);
            return;
        }

        var floats = MemoryMarshal.Cast<byte, float>(buffer);
        if (floats.Length == 0) return;
        if (floats.Length > _scratch.Length)
        {
            var big = new float[floats.Length];
            floats.CopyTo(big);
            SamplesAvailable?.Invoke(big, big.Length);
            return;
        }
        floats.CopyTo(_scratch);
        SamplesAvailable?.Invoke(_scratch, floats.Length);
    }

    private void ConvertLoop()
    {
        var chain = _chain!;
        var buf = new float[SampleRate * Channels / 20];
        while (_running && !_stop.IsSet)
        {
            int read;
            try
            {
                read = chain.Read(buf);
            }
            catch
            {
                break;
            }
            if (read > 0) SamplesAvailable?.Invoke(buf, read);
            else _stop.Wait(2);
        }
    }

    public void Dispose()
    {
        Stop();
        _recorder.Dispose();
        _stop.Dispose();
    }
}

public sealed class FirstChannelsSampleProvider : ISampleProvider
{
    private readonly ISampleProvider _source;
    private readonly int _sourceChannels;
    private readonly int _outputChannels;
    private float[] _temp = new float[4096];

    public FirstChannelsSampleProvider(ISampleProvider source, int outputChannels)
    {
        _source = source;
        _sourceChannels = source.WaveFormat.Channels;
        _outputChannels = outputChannels;
        WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(source.WaveFormat.SampleRate, outputChannels);
    }

    public WaveFormat WaveFormat { get; }

    public int Read(Span<float> buffer)
    {
        int frames = buffer.Length / _outputChannels;
        int sourceSamples = frames * _sourceChannels;
        if (_temp.Length < sourceSamples) _temp = new float[sourceSamples];
        int read = _source.Read(_temp.AsSpan(0, sourceSamples));
        int readFrames = read / _sourceChannels;
        for (int f = 0; f < readFrames; f++)
        {
            for (int c = 0; c < _outputChannels; c++)
            {
                buffer[f * _outputChannels + c] = c < _sourceChannels ? _temp[f * _sourceChannels + c] : 0f;
            }
        }
        return readFrames * _outputChannels;
    }
}
