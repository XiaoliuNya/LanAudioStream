using Concentus.Enums;
using Concentus.Structs;
using LanAudioStream.Core.Proto;

#pragma warning disable CS0618 // managed Opus implementation is intentional (no native dependency)

namespace LanAudioStream.Core.Codec;

public sealed class OpusEncoderWrapper : IDisposable
{
    private OpusEncoder _encoder;
    private int _channels;

    public int SampleRate { get; private set; }
    public int Channels => _channels;
    public int FrameMs { get; private set; }
    public int FrameSamples => SampleRate * FrameMs / 1000;
    public int BitrateBps { get; private set; }
    public bool InbandFec { get; private set; }
    public bool Dtx { get; private set; }
    public int Complexity { get; private set; } = 10;

    public OpusEncoderWrapper(int sampleRate, int channels, int frameMs, int bitrateBps, bool dtx = false, int complexity = 10)
    {
        SampleRate = sampleRate;
        _channels = Math.Clamp(channels, 1, 2);
        _encoder = Create(_channels);
        Reconfigure(sampleRate, frameMs, bitrateBps, true, dtx, complexity, _channels);
    }

    private OpusEncoder Create(int channels) => new(SampleRate, channels, OpusApplication.OPUS_APPLICATION_AUDIO)
    {
        Complexity = 10,
        UseVBR = true,
        SignalType = OpusSignal.OPUS_SIGNAL_MUSIC,
    };

    /// <summary>采样率变化时必须重建编码器，否则 FrameSamples 与实际送入的样点数不符（Concentus 会抛异常）。</summary>
    public void Reconfigure(int sampleRate, int frameMs, int bitrateBps, bool fec, bool dtx, int complexity, int channels)
    {
        channels = Math.Clamp(channels, 1, 2);
        if (channels != _channels || sampleRate != SampleRate)
        {
            _encoder.Dispose();
            SampleRate = sampleRate;
            _channels = channels;
            _encoder = Create(channels);
        }

        FrameMs = frameMs;
        BitrateBps = bitrateBps;
        Complexity = Math.Clamp(complexity, 1, 10);
        InbandFec = fec && frameMs >= 10;
        Dtx = dtx;
        _encoder.Bitrate = bitrateBps;
        _encoder.UseInbandFEC = InbandFec;
        _encoder.PacketLossPercent = InbandFec ? 10 : 0;
        _encoder.Complexity = Complexity;
        _encoder.UseVBR = true;
        _encoder.UseDTX = dtx;
    }

    public void Reset() => _encoder.ResetState();

    /// <summary>Opus 单包输出上限用协议常量：超过 1500 MTU 会被 IP 分片，任一分片丢失等于整包丢失。</summary>
    public int Encode(ReadOnlySpan<float> pcm, Span<byte> dst)
        => _encoder.Encode(pcm, FrameSamples, dst, Math.Min(Wire.MaxOpusPayload, dst.Length));

    public void Dispose() => _encoder.Dispose();
}
