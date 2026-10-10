using System.Net;
using System.Net.Sockets;
using LanAudioStream.Core.Proto;
using LanAudioStream.Core.Util;

namespace LanAudioStream.Core.Net;

public enum LinkPhase
{
    Idle,
    Hello,
    Streaming,
}

public sealed class StreamServer : IDisposable
{
    private readonly Socket _sock;
    private readonly CancellationTokenSource _cts = new();
    private readonly byte[] _recvBuf = new byte[Proto.Wire.MaxDatagram];
    private readonly byte[] _lastPayload = new byte[Proto.Wire.MaxDatagram];  // PCM 无损模式单帧可达数 KB
    private readonly byte[] _txBuffer = new byte[Proto.Wire.MaxDatagram];
    private readonly object _sendGate = new();
    private readonly Timer _watchdog;

    private int _lastPayloadLen;
    private uint _seq;
    private long _bytesSent;
    private long _datagramsSent;
    private ulong _lastRrUs;
    private IPEndPoint? _client;
    private volatile bool _streaming;
    private volatile bool _sendPaused;
    private long _silenceFramesSent;

    public int Port { get; }
    public Wire.AudioFormatInfo Format { get; set; }
    public bool RedundancyEnabled { get; set; } = true;
    public bool VerboseLog { get; set; }

    /// <summary>客户端无反馈多久后暂停发送（毫秒）。不再直接中断会话，反馈恢复即自动继续。</summary>
    public int LinkTimeoutMs { get; set; } = 8000;

    public event Action<string>? Log;
    public event Action? ClientChanged;
    public event Action? StreamStarted;
    public event Action<ReceiverReport>? ReportReceived;
    public event Action<List<Wire.Setting>>? SettingsReceived;

    public LinkPhase Phase { get; private set; } = LinkPhase.Idle;
    public IPEndPoint? Client => _client;
    public bool Streaming => _streaming;

    /// <summary>客户端暂时无反馈、已暂停发送（会话仍保留）。</summary>
    public bool SendPaused => _sendPaused;

    /// <summary>DTX 静音标记包计数。</summary>
    public long SilenceFramesSent => Interlocked.Read(ref _silenceFramesSent);
    public long BytesSent => Interlocked.Read(ref _bytesSent);
    public long DatagramsSent => Interlocked.Read(ref _datagramsSent);
    public ReceiverReport LastReport { get; private set; }
    public ulong LastRrAgeUs => _lastRrUs == 0 ? ulong.MaxValue : Clock.NowUs() - _lastRrUs;

    public StreamServer(int port)
    {
        Port = port;
        _sock = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        // 独占数据端口：如果本机已经跑了一个桌面端实例，第二个实例必须直接报错，
        // 否则两个实例会同时往手机发包，听起来就是「时断时续」的杂音。
        _sock.ExclusiveAddressUse = true;
        _sock.Bind(new IPEndPoint(IPAddress.Any, port));
        _watchdog = new Timer(_ => Watchdog(), null, 500, 500);
    }

    public void Start()
    {
        _ = Task.Run(ReceiveLoopAsync);
    }

    private async Task ReceiveLoopAsync()
    {
        var from = new IPEndPoint(IPAddress.Any, 0);
        while (!_cts.IsCancellationRequested)
        {
            SocketReceiveFromResult r;
            try
            {
                r = await _sock.ReceiveFromAsync(_recvBuf.AsMemory(), SocketFlags.None, from, _cts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (SocketException ex)
            {
                if (ex.SocketErrorCode == SocketError.ConnectionReset) continue;
                Log?.Invoke("接收错误: " + ex.Message);
                break;
            }
            try
            {
                Handle(_recvBuf.AsSpan(0, r.ReceivedBytes), (IPEndPoint)r.RemoteEndPoint);
            }
            catch (Exception ex)
            {
                Log?.Invoke("处理数据包失败: " + ex.Message);
            }
        }
    }

    private void Handle(ReadOnlySpan<byte> datagram, IPEndPoint from)
    {
        if (!Proto.Wire.TryReadHeader(datagram, out var h))
        {
            return;
        }

        switch (h.Type)
        {
            case Proto.Wire.TypeHello:
                if (Proto.Wire.TryParseHello(datagram, out var nonce, out _))
                {
                    bool isNew = _client == null || !_client.Equals(from) || Phase == LinkPhase.Idle;
                    _client = from;
                    Phase = LinkPhase.Hello;
                    StopStreamingInternal();
                    SendHelloAck();
                    Log?.Invoke($"客户端 {from} 握手 (nonce={nonce})");
                    if (isNew) ClientChanged?.Invoke();
                }
                break;

            case Proto.Wire.TypeStart:
                if (_client != null && _client.Equals(from))
                {
                    if (!_streaming)
                    {
                        _seq = 0;
                        _lastPayloadLen = 0;
                        _sendPaused = false;
                        _streaming = true;
                        Phase = LinkPhase.Streaming;
                        _lastRrUs = Clock.NowUs();
                        StreamStarted?.Invoke();
                        SendHelloAck();
                        Log?.Invoke("客户端请求开始串流");
                        ClientChanged?.Invoke();
                    }
                }
                break;

            case Proto.Wire.TypeBye:
                if (_client != null && _client.Equals(from))
                {
                    Log?.Invoke($"客户端 {from} 主动断开");
                    StopStreamingInternal();
                    _client = null;
                    Phase = LinkPhase.Idle;
                    ClientChanged?.Invoke();
                }
                break;

            case Proto.Wire.TypeSyncReq:
                if (Proto.Wire.TryParseSyncReq(datagram, out var t1))
                {
                    ulong t2 = Clock.NowUs();
                    Span<byte> pkt = stackalloc byte[Proto.Wire.HeaderSize + 24];
                    int len = Proto.Wire.WriteSyncRsp(pkt, t1, t2, Clock.NowUs());
                    SendRaw(pkt[..len], from);
                }
                break;

            case Proto.Wire.TypeRr:
                if (Proto.Wire.TryParseRr(datagram, out var rr))
                {
                    if (_client == null || !_client.Equals(from)) _client = from;
                    LastReport = rr;
                    _lastRrUs = Clock.NowUs();
                    if (_sendPaused)
                    {
                        _sendPaused = false;
                        Log?.Invoke("客户端已恢复反馈，继续发送");
                        ClientChanged?.Invoke();
                    }
                    if (!_streaming && Phase == LinkPhase.Hello)
                    {
                        // phone kept reporting but never asked to start: treat as ready
                    }
                    ReportReceived?.Invoke(rr);
                }
                break;

            case Proto.Wire.TypeSettings:
                var items = Proto.Wire.TryParseSettings(datagram, out var parsed) ? parsed : null;
                if (items != null && items.Count > 0)
                {
                    Log?.Invoke($"收到手机端设置: {string.Join(", ", items.Select(i => i.Key + "=" + i.Value))}");
                    SettingsReceived?.Invoke(items);
                }
                break;

            case Proto.Wire.TypeData:
                break;
        }
    }

    public void SendHelloAck()
    {
        var ep = _client;
        if (ep == null) return;
        Span<byte> pkt = stackalloc byte[Proto.Wire.HeaderSize + 18];
        int len = Proto.Wire.WriteHelloAck(pkt, Format);
        SendRaw(pkt[..len], ep);
        if (VerboseLog) Log?.Invoke("已发送 HELLO_ACK: " + Format);
    }

    /// <summary>Sends the authoritative settings state to the phone (used on connect and on change).</summary>
    public void SendSettings(IReadOnlyList<Proto.Wire.Setting> items)
    {
        var ep = _client;
        if (ep == null || items.Count == 0) return;
        Span<byte> pkt = stackalloc byte[Proto.Wire.HeaderSize + 1 + items.Count * Proto.Wire.SettingsEntrySize];
        var arr = new Proto.Wire.Setting[items.Count];
        for (int i = 0; i < items.Count; i++) arr[i] = items[i];
        int len = Proto.Wire.WriteSettings(pkt, arr);
        SendRaw(pkt[..len], ep);
    }

    public bool SendAudioFrame(ReadOnlySpan<byte> payload, ulong captureUs, bool silence = false, bool send = true)
    {
        try
        {
            return SendAudioFrameCore(payload, captureUs, silence, send);
        }
        catch (Exception ex)
        {
            Errors++;
            LastError = ex.Message;
            Log?.Invoke("发送音频帧异常: " + ex.Message);
            return false;
        }
    }

    public long Errors { get; private set; }
    public string? LastError { get; private set; }

    private bool SendAudioFrameCore(ReadOnlySpan<byte> payload, ulong captureUs, bool silence, bool send)
    {
        var ep = _client;
        if (ep == null || !_streaming || _sendPaused) return false;
        if (!silence && payload.Length == 0) return false;

        lock (_sendGate)
        {
            if (!send)
            {
                // DTX 静音期间「只占序号不发包」的帧：接收端按序号补静音，时间轴保持连续
                _seq++;
                _lastPayloadLen = 0;
                return true;
            }
            if (silence)
            {
                // DTX 静音：只发 24 字节包头，接收端按序号补静音（不计丢包）
                Span<byte> head = _txBuffer.AsSpan(0, Proto.Wire.HeaderSize);
                Proto.Wire.WriteHeader(head, Proto.Wire.TypeData, Proto.Wire.FlagSilence, _seq, captureUs, 0, 0);
                if (!SendRaw(head, ep)) return false;
                Interlocked.Add(ref _bytesSent, Proto.Wire.HeaderSize);
                Interlocked.Increment(ref _datagramsSent);
                Interlocked.Increment(ref _silenceFramesSent);
                _seq++;
                _lastPayloadLen = 0;   // 静音期间不携带冗余
                return true;
            }

            int redLen = RedundancyEnabled && _lastPayloadLen > 0 ? _lastPayloadLen : 0;
            // 冗余与主负载在同一个报文里：超 MTU 会分片，分片丢失时冗余也一起丢，等于白付带宽
            if (redLen > 0 && Proto.Wire.HeaderSize + payload.Length + redLen > Proto.Wire.SafeDatagramSize)
            {
                redLen = 0;
            }
            int total = Proto.Wire.HeaderSize + payload.Length + redLen;
            if (total > _txBuffer.Length)
            {
                redLen = 0;   // 冗余放不下就放弃冗余，绝不抛异常
                total = Proto.Wire.HeaderSize + payload.Length;
            }
            if (total > _txBuffer.Length) return false;

            ushort flags = redLen > 0 ? Proto.Wire.FlagHasRedundancy : (ushort)0;
            Span<byte> span = _txBuffer.AsSpan(0, total);
            Proto.Wire.WriteHeader(span, Proto.Wire.TypeData, flags, _seq, captureUs, (ushort)payload.Length, (ushort)redLen);
            payload.CopyTo(span[Proto.Wire.HeaderSize..]);
            if (redLen > 0)
            {
                _lastPayload.AsSpan(0, redLen).CopyTo(span[(Proto.Wire.HeaderSize + payload.Length)..]);
            }

            bool ok = SendRaw(span, ep);
            if (!ok) return false;

            Interlocked.Add(ref _bytesSent, total);
            Interlocked.Increment(ref _datagramsSent);
            _seq++;
            if (payload.Length <= _lastPayload.Length)
            {
                payload.CopyTo(_lastPayload);
                _lastPayloadLen = payload.Length;
            }
            else
            {
                _lastPayloadLen = 0;
            }
            return true;
        }
    }

    private bool SendRaw(ReadOnlySpan<byte> data, IPEndPoint ep)
    {
        try
        {
            int sent = _sock.SendTo(data, SocketFlags.None, ep);
            return sent == data.Length;
        }
        catch (SocketException ex)
        {
            Log?.Invoke("发送失败: " + ex.Message);
            return false;
        }
    }

    private void Watchdog()
    {
        if (!_streaming || _client == null) return;
        long timeoutUs = Math.Max(1, LinkTimeoutMs) * 1000L;
        if (LastRrAgeUs <= (ulong)timeoutUs) return;
        if (!_sendPaused)
        {
            // 只暂停发送、保留会话：客户端（或手机端重连）恢复后立即可继续，不会因为几秒抖动就断流
            _sendPaused = true;
            Log?.Invoke($"客户端超过 {LinkTimeoutMs / 1000.0:0.#} 秒无反馈，暂停发送（保留会话，等待重连）");
            ClientChanged?.Invoke();
        }
    }

    private void StopStreamingInternal()
    {
        if (_streaming)
        {
            _streaming = false;
        }
        _sendPaused = false;
    }

    public void DisconnectClient()
    {
        var ep = _client;
        if (ep != null)
        {
            Span<byte> pkt = stackalloc byte[Proto.Wire.HeaderSize];
            Proto.Wire.WriteHeader(pkt, Proto.Wire.TypeBye, 0, 0, 0, 0, 0);
            SendRaw(pkt, ep);
        }
        StopStreamingInternal();
        _client = null;
        Phase = LinkPhase.Idle;
        ClientChanged?.Invoke();
    }

    public void Dispose()
    {
        var ep = _client;
        if (ep != null)
        {
            try
            {
                Span<byte> bye = stackalloc byte[Proto.Wire.HeaderSize];
                Proto.Wire.WriteHeader(bye, Proto.Wire.TypeBye, 0, 0, 0, 0, 0);
                _sock.SendTo(bye, SocketFlags.None, ep);
            }
            catch
            {
                // ignored
            }
        }
        _cts.Cancel();
        _watchdog.Dispose();
        try
        {
            _sock.Dispose();
        }
        catch
        {
            // ignored
        }
        _cts.Dispose();
    }
}
