using System.Buffers.Binary;
using System.Net;
using System.Text;

namespace LanAudioStream.Core.Proto;

public readonly struct PacketHeader
{
    public byte Version { get; init; }
    public byte Type { get; init; }
    public ushort Flags { get; init; }
    public uint Seq { get; init; }
    public ulong CaptureUs { get; init; }
    public ushort PayloadLen { get; init; }
    public ushort RedLen { get; init; }

    public bool HasRedundancy => (Flags & Wire.FlagHasRedundancy) != 0;
}

public struct ReceiverReport
{
    public uint SeqMax;
    public uint Recv;
    public uint Lost;
    public uint Late;
    public uint Dup;
    public uint JitterUs;
    public uint E2eLatencyUs;
    public uint BufferUs;
    public uint RecvBitrateBps;
    public uint PlcFrames;
    public uint Underruns;
    public ushort LossPermille;
    public byte LowLatencyMode;
    public byte Reserved;
    public uint TargetBufferUs;
    public byte OutputVolume;
    public byte AutoReconnect;

    public const uint Unknown = uint.MaxValue;
    public double LossPercent => LossPermille / 10.0;
    public bool HasLatency => E2eLatencyUs != Unknown;
}

public static class Wire
{
    public const byte Version = 1;
    public const int HeaderSize = 24;
    public const int DiscoveryPort = 47654;
    public const int DefaultStreamPort = 47655;
    public const int MaxOpusPayload = 1275;   // 单个 Opus 包上限（防 IP 分片）
    public const int SafeDatagramSize = 1400; // 单报文预算（1500 MTU - 头），冗余超出就放弃
    public const int MaxDatagram = 8192;      // PCM 无损模式单包更大

    public static ReadOnlySpan<byte> Magic => "LAS1"u8;

    public const byte TypeData = 1;
    public const byte TypeHello = 2;
    public const byte TypeHelloAck = 3;
    public const byte TypeStart = 4;
    public const byte TypeBye = 5;
    public const byte TypeProbe = 6;
    public const byte TypeAnnounce = 7;
    public const byte TypeSyncReq = 8;
    public const byte TypeSyncRsp = 9;
    public const byte TypeRr = 10;
    public const byte TypeSettings = 11;

    // ---- SETTINGS 键（手机 ↔ 桌面端双向）--------------------------------
    public const byte KeyBitrateKbps = 1;
    public const byte KeyFrameMs = 2;
    public const byte KeyRedundancy = 3;
    public const byte KeyCaptureBufferMs = 4;
    public const byte KeyMuteSpeaker = 5;
    public const byte KeyChannels = 6;
    public const byte KeyDtx = 7;
    public const byte KeyComplexity = 8;
    public const byte KeyJitterMode = 9;      // 0 自动 / 1 最小 / 2 最大（手机本地）
    public const byte KeyOutputVolume = 10;   // 0-100（手机本地）
    public const byte KeyLowLatency = 11;     // 手机本地
    public const byte KeyAutoReconnect = 12;
    public const byte KeyDevMode = 15;       // 手机本地：详细日志
    public const byte KeyCodec = 13;          // 1 = Opus, 2 = PCM16 无损
    public const byte KeySampleRate = 14;     // 采样率 Hz
    public const byte KeyLinkTimeoutSec = 16; // 断连超时（秒）：桌面端无 RR / 手机端无音频 的判定阈值（双向同步）

    public const byte CodecOpusPcm = 2;

    public const ushort FlagHasRedundancy = 0x0001;
    public const ushort FlagAnnounceStreaming = 0x0001;

    /// <summary>DATA 包 payloadLen=0 且置位时表示「该序号是 DTX 静音」，接收端补静音但不计丢包。</summary>
    public const ushort FlagSilence = 0x0002;

    public const byte CodecOpus = 1;

    public static void WriteHeader(Span<byte> buf, byte type, ushort flags, uint seq, ulong captureUs, ushort payloadLen, ushort redLen)
    {
        Magic.CopyTo(buf);
        buf[4] = Version;
        buf[5] = type;
        BinaryPrimitives.WriteUInt16LittleEndian(buf[6..], flags);
        BinaryPrimitives.WriteUInt32LittleEndian(buf[8..], seq);
        BinaryPrimitives.WriteUInt64LittleEndian(buf[12..], captureUs);
        BinaryPrimitives.WriteUInt16LittleEndian(buf[20..], payloadLen);
        BinaryPrimitives.WriteUInt16LittleEndian(buf[22..], redLen);
    }

    public static bool TryReadHeader(ReadOnlySpan<byte> buf, out PacketHeader header)
    {
        header = default;
        if (buf.Length < HeaderSize) return false;
        if (!buf[..4].SequenceEqual(Magic)) return false;
        if (buf[4] != Version) return false;
        var h = new PacketHeader
        {
            Version = buf[4],
            Type = buf[5],
            Flags = BinaryPrimitives.ReadUInt16LittleEndian(buf[6..]),
            Seq = BinaryPrimitives.ReadUInt32LittleEndian(buf[8..]),
            CaptureUs = BinaryPrimitives.ReadUInt64LittleEndian(buf[12..]),
            PayloadLen = BinaryPrimitives.ReadUInt16LittleEndian(buf[20..]),
            RedLen = BinaryPrimitives.ReadUInt16LittleEndian(buf[22..]),
        };
        if (buf.Length < HeaderSize + h.PayloadLen + h.RedLen) return false;
        header = h;
        return true;
    }

    public static int WriteSimple(Span<byte> buf, byte type, ReadOnlySpan<byte> body)
    {
        WriteHeader(buf, type, 0, 0, 0, (ushort)body.Length, 0);
        body.CopyTo(buf[HeaderSize..]);
        return HeaderSize + body.Length;
    }

    public static int WriteEmpty(Span<byte> buf, byte type) => WriteSimple(buf, type, ReadOnlySpan<byte>.Empty);

    // ---- ANNOUNCE -------------------------------------------------------
    public static int WriteAnnounce(Span<byte> buf, string name, int streamPort, bool streaming)
    {
        var nb = Encoding.UTF8.GetBytes(name);
        if (nb.Length > 80) nb = nb[..80];
        Span<byte> body = stackalloc byte[8 + nb.Length];
        body[0] = Version;
        body[1] = 0;
        BinaryPrimitives.WriteUInt16LittleEndian(body[2..], (ushort)streamPort);
        BinaryPrimitives.WriteUInt16LittleEndian(body[4..], streaming ? FlagAnnounceStreaming : (ushort)0);
        BinaryPrimitives.WriteUInt16LittleEndian(body[6..], (ushort)nb.Length);
        nb.CopyTo(body[8..]);
        return WriteSimple(buf, TypeAnnounce, body);
    }

    public static bool TryParseAnnounce(ReadOnlySpan<byte> datagram, out string name, out int streamPort, out bool streaming)
    {
        name = string.Empty;
        streamPort = 0;
        streaming = false;
        if (!TryReadHeader(datagram, out var h) || h.Type != TypeAnnounce) return false;
        var body = datagram.Slice(HeaderSize, h.PayloadLen);
        if (body.Length < 8) return false;
        streamPort = BinaryPrimitives.ReadUInt16LittleEndian(body[2..]);
        streaming = (BinaryPrimitives.ReadUInt16LittleEndian(body[4..]) & FlagAnnounceStreaming) != 0;
        int nameLen = BinaryPrimitives.ReadUInt16LittleEndian(body[6..]);
        if (nameLen > body.Length - 8) nameLen = body.Length - 8;
        name = Encoding.UTF8.GetString(body.Slice(8, nameLen));
        return true;
    }

    // ---- HELLO / HELLO_ACK ---------------------------------------------
    public static int WriteHello(Span<byte> buf, uint nonce, uint desiredBitrateBps)
    {
        Span<byte> body = stackalloc byte[10];
        body[0] = Version;
        body[1] = 0;
        BinaryPrimitives.WriteUInt32LittleEndian(body[2..], nonce);
        BinaryPrimitives.WriteUInt32LittleEndian(body[6..], desiredBitrateBps);
        return WriteSimple(buf, TypeHello, body);
    }

    public static bool TryParseHello(ReadOnlySpan<byte> datagram, out uint nonce, out uint desiredBitrateBps)
    {
        nonce = 0;
        desiredBitrateBps = 0;
        if (!TryReadHeader(datagram, out var h) || h.Type != TypeHello) return false;
        if (h.PayloadLen < 10) return false;
        var body = datagram.Slice(HeaderSize, h.PayloadLen);
        nonce = BinaryPrimitives.ReadUInt32LittleEndian(body[2..]);
        desiredBitrateBps = BinaryPrimitives.ReadUInt32LittleEndian(body[6..]);
        return true;
    }

    public readonly record struct AudioFormatInfo(
        byte Codec, byte Channels, byte FrameMs, byte Redundancy,
        uint SampleRate, ushort FrameSamples, uint BitrateBps, uint SessionId);

    public static int WriteHelloAck(Span<byte> buf, in AudioFormatInfo info)
    {
        Span<byte> body = stackalloc byte[18];
        body[0] = info.Codec;
        body[1] = info.Channels;
        body[2] = info.FrameMs;
        body[3] = info.Redundancy;
        BinaryPrimitives.WriteUInt32LittleEndian(body[4..], info.SampleRate);
        BinaryPrimitives.WriteUInt16LittleEndian(body[8..], info.FrameSamples);
        BinaryPrimitives.WriteUInt32LittleEndian(body[10..], info.BitrateBps);
        BinaryPrimitives.WriteUInt32LittleEndian(body[14..], info.SessionId);
        return WriteSimple(buf, TypeHelloAck, body);
    }

    public static bool TryParseHelloAck(ReadOnlySpan<byte> datagram, out AudioFormatInfo info)
    {
        info = default;
        if (!TryReadHeader(datagram, out var h) || h.Type != TypeHelloAck) return false;
        if (h.PayloadLen < 18) return false;
        var body = datagram.Slice(HeaderSize, h.PayloadLen);
        info = new AudioFormatInfo(
            body[0], body[1], body[2], body[3],
            BinaryPrimitives.ReadUInt32LittleEndian(body[4..]),
            BinaryPrimitives.ReadUInt16LittleEndian(body[8..]),
            BinaryPrimitives.ReadUInt32LittleEndian(body[10..]),
            BinaryPrimitives.ReadUInt32LittleEndian(body[14..]));
        return true;
    }

    // ---- SYNC -----------------------------------------------------------
    public static int WriteSyncReq(Span<byte> buf, ulong t1Us)
    {
        Span<byte> body = stackalloc byte[8];
        BinaryPrimitives.WriteUInt64LittleEndian(body, t1Us);
        return WriteSimple(buf, TypeSyncReq, body);
    }

    public static int WriteSyncRsp(Span<byte> buf, ulong t1Us, ulong t2Us, ulong t3Us)
    {
        Span<byte> body = stackalloc byte[24];
        BinaryPrimitives.WriteUInt64LittleEndian(body, t1Us);
        BinaryPrimitives.WriteUInt64LittleEndian(body[8..], t2Us);
        BinaryPrimitives.WriteUInt64LittleEndian(body[16..], t3Us);
        return WriteSimple(buf, TypeSyncRsp, body);
    }

    public static bool TryParseSyncReq(ReadOnlySpan<byte> datagram, out ulong t1Us)
    {
        t1Us = 0;
        if (!TryReadHeader(datagram, out var h) || h.Type != TypeSyncReq || h.PayloadLen < 8) return false;
        t1Us = BinaryPrimitives.ReadUInt64LittleEndian(datagram[HeaderSize..]);
        return true;
    }

    public static bool TryParseSyncRsp(ReadOnlySpan<byte> datagram, out ulong t1Us, out ulong t2Us, out ulong t3Us)
    {
        t1Us = 0;
        t2Us = 0;
        t3Us = 0;
        if (!TryReadHeader(datagram, out var h) || h.Type != TypeSyncRsp || h.PayloadLen < 24) return false;
        var body = datagram.Slice(HeaderSize, h.PayloadLen);
        t1Us = BinaryPrimitives.ReadUInt64LittleEndian(body);
        t2Us = BinaryPrimitives.ReadUInt64LittleEndian(body[8..]);
        t3Us = BinaryPrimitives.ReadUInt64LittleEndian(body[16..]);
        return true;
    }

    // ---- SETTINGS（TLV：key u8 + value u32 LE，逐项可选）---------------
    public readonly record struct Setting(byte Key, uint Value);

    public const int SettingsEntrySize = 5;

    public static int WriteSettings(Span<byte> buf, ReadOnlySpan<Setting> items)
    {
        int n = Math.Min(items.Length, 32);
        Span<byte> body = stackalloc byte[1 + n * SettingsEntrySize];
        body[0] = (byte)n;
        for (int i = 0; i < n; i++)
        {
            body[1 + i * SettingsEntrySize] = items[i].Key;
            BinaryPrimitives.WriteUInt32LittleEndian(body[(2 + i * SettingsEntrySize)..], items[i].Value);
        }
        return WriteSimple(buf, TypeSettings, body);
    }

    public static bool TryParseSettings(ReadOnlySpan<byte> datagram, out List<Setting> items)
    {
        items = new List<Setting>();
        if (!TryReadHeader(datagram, out var h) || h.Type != TypeSettings || h.PayloadLen < 1) return false;
        var body = datagram.Slice(HeaderSize, h.PayloadLen);
        int count = body[0];
        for (int i = 0; i < count; i++)
        {
            int off = 1 + i * SettingsEntrySize;
            if (off + SettingsEntrySize > body.Length) break;
            byte key = body[off];
            uint value = BinaryPrimitives.ReadUInt32LittleEndian(body[(off + 1)..]);
            items.Add(new Setting(key, value));
        }
        return true;
    }

    // ---- RECEIVER REPORT ------------------------------------------------
    public const int RrBodySize = 56;

    public static int WriteRr(Span<byte> buf, in ReceiverReport rr)
    {
        Span<byte> body = stackalloc byte[RrBodySize];
        BinaryPrimitives.WriteUInt32LittleEndian(body, rr.SeqMax);
        BinaryPrimitives.WriteUInt32LittleEndian(body[4..], rr.Recv);
        BinaryPrimitives.WriteUInt32LittleEndian(body[8..], rr.Lost);
        BinaryPrimitives.WriteUInt32LittleEndian(body[12..], rr.Late);
        BinaryPrimitives.WriteUInt32LittleEndian(body[16..], rr.Dup);
        BinaryPrimitives.WriteUInt32LittleEndian(body[20..], rr.JitterUs);
        BinaryPrimitives.WriteUInt32LittleEndian(body[24..], rr.E2eLatencyUs);
        BinaryPrimitives.WriteUInt32LittleEndian(body[28..], rr.BufferUs);
        BinaryPrimitives.WriteUInt32LittleEndian(body[32..], rr.RecvBitrateBps);
        BinaryPrimitives.WriteUInt32LittleEndian(body[36..], rr.PlcFrames);
        BinaryPrimitives.WriteUInt32LittleEndian(body[40..], rr.Underruns);
        BinaryPrimitives.WriteUInt16LittleEndian(body[44..], rr.LossPermille);
        body[46] = rr.LowLatencyMode;
        body[47] = rr.Reserved;
        BinaryPrimitives.WriteUInt32LittleEndian(body[48..], rr.TargetBufferUs);
        body[52] = rr.OutputVolume;
        body[53] = (byte)rr.AutoReconnect;
        body[54] = 0;
        body[55] = 0;
        return WriteSimple(buf, TypeRr, body);
    }

    public static bool TryParseRr(ReadOnlySpan<byte> datagram, out ReceiverReport rr)
    {
        rr = default;
        if (!TryReadHeader(datagram, out var h) || h.Type != TypeRr || h.PayloadLen < RrBodySize) return false;
        var b = datagram.Slice(HeaderSize, h.PayloadLen);
        rr = new ReceiverReport
        {
            SeqMax = BinaryPrimitives.ReadUInt32LittleEndian(b),
            Recv = BinaryPrimitives.ReadUInt32LittleEndian(b[4..]),
            Lost = BinaryPrimitives.ReadUInt32LittleEndian(b[8..]),
            Late = BinaryPrimitives.ReadUInt32LittleEndian(b[12..]),
            Dup = BinaryPrimitives.ReadUInt32LittleEndian(b[16..]),
            JitterUs = BinaryPrimitives.ReadUInt32LittleEndian(b[20..]),
            E2eLatencyUs = BinaryPrimitives.ReadUInt32LittleEndian(b[24..]),
            BufferUs = BinaryPrimitives.ReadUInt32LittleEndian(b[28..]),
            RecvBitrateBps = BinaryPrimitives.ReadUInt32LittleEndian(b[32..]),
            PlcFrames = BinaryPrimitives.ReadUInt32LittleEndian(b[36..]),
            Underruns = BinaryPrimitives.ReadUInt32LittleEndian(b[40..]),
            LossPermille = BinaryPrimitives.ReadUInt16LittleEndian(b[44..]),
            LowLatencyMode = b[46],
            Reserved = b[47],
            TargetBufferUs = BinaryPrimitives.ReadUInt32LittleEndian(b[48..]),
            OutputVolume = b[52],
            AutoReconnect = b[53],
        };
        return true;
    }

    public static string DescribeType(byte type) => type switch
    {
        TypeData => "DATA",
        TypeHello => "HELLO",
        TypeHelloAck => "HELLO_ACK",
        TypeStart => "START",
        TypeBye => "BYE",
        TypeProbe => "PROBE",
        TypeAnnounce => "ANNOUNCE",
        TypeSyncReq => "SYNC_REQ",
        TypeSyncRsp => "SYNC_RSP",
        TypeRr => "RR",
        TypeSettings => "SETTINGS",
        _ => "UNKNOWN"
    };

    public static IPAddress BroadcastAddress => IPAddress.Broadcast;
}
