using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using LanAudioStream.Core.Proto;

namespace LanAudioStream.Core.Net;

public sealed class Announcer : IDisposable
{
    private readonly Socket _sock;
    private readonly CancellationTokenSource _cts = new();
    private readonly byte[] _recvBuf = new byte[Proto.Wire.MaxDatagram];
    private readonly List<IPEndPoint> _knownClients = new();
    private readonly Timer _timer;
    private readonly object _gate = new();

    public string ServerName { get; set; } = Environment.MachineName;
    public int StreamPort { get; set; }
    public bool Enabled { get; set; } = true;
    public bool VerboseLog { get; set; }

    public event Action<string>? Log;
    public event Action<string, IPAddress>? ProbeSeen;

    public Announcer(int streamPort)
    {
        StreamPort = streamPort;
        _sock = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        _sock.ExclusiveAddressUse = false;
        _sock.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _sock.EnableBroadcast = true;
        _sock.Bind(new IPEndPoint(IPAddress.Any, Proto.Wire.DiscoveryPort));
        _timer = new Timer(_ => Broadcast(), null, Timeout.Infinite, Timeout.Infinite);
    }

    public void Start()
    {
        _ = Task.Run(ReceiveLoopAsync);
        _timer.Change(700, 1000);
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
            catch (SocketException)
            {
                continue;
            }

            var span = _recvBuf.AsSpan(0, r.ReceivedBytes);
            if (!Proto.Wire.TryReadHeader(span, out var h)) continue;
            var ep = (IPEndPoint)r.RemoteEndPoint;

            if (h.Type == Proto.Wire.TypeProbe)
            {
                Remember(ep);
                SendAnnounce(ep);
                Log?.Invoke($"收到发现探测 {ep.Address}");
                ProbeSeen?.Invoke(ServerName, ep.Address);
                if (VerboseLog) Log?.Invoke("已单播响应 ANNOUNCE -> " + ep);
            }
            else if (h.Type == Proto.Wire.TypeHello)
            {
                // phone is talking to a server that may not be us; ignore
            }
        }
    }

    private void Remember(IPEndPoint ep)
    {
        lock (_gate)
        {
            var existing = _knownClients.FirstOrDefault(c => c.Address.Equals(ep.Address));
            if (existing != null)
            {
                _knownClients.Remove(existing);
            }
            _knownClients.Insert(0, new IPEndPoint(ep.Address, Proto.Wire.DiscoveryPort));
            while (_knownClients.Count > 8) _knownClients.RemoveAt(_knownClients.Count - 1);
        }
    }

    public IReadOnlyList<IPEndPoint> KnownClients
    {
        get
        {
            lock (_gate) return _knownClients.ToList();
        }
    }

    private void Broadcast()
    {
        if (!Enabled) return;
        // 已有客户端在串流时不再周期广播（省电、少唤醒 WiFi）；仍会响应对方的 PROBE
        if (Streaming) return;
        foreach (var target in BroadcastTargets())
        {
            SendAnnounce(target);
        }
        foreach (var client in KnownClients)
        {
            SendAnnounce(client);
        }
    }

    private void SendAnnounce(IPEndPoint target)
    {
        Span<byte> pkt = stackalloc byte[Proto.Wire.HeaderSize + 8 + 96];
        int len = Proto.Wire.WriteAnnounce(pkt, ServerName, StreamPort, _streaming);
        try
        {
            _sock.SendTo(pkt[..len], SocketFlags.None, target);
        }
        catch (SocketException)
        {
            // ignore transient broadcast errors
        }
    }

    private bool _streaming;

    public bool Streaming
    {
        get => _streaming;
        set => _streaming = value;
    }

    private static IEnumerable<IPEndPoint> BroadcastTargets()
    {
        var list = new List<IPEndPoint> { new(IPAddress.Broadcast, Proto.Wire.DiscoveryPort) };
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (ua.IPv4Mask == null) continue;
                    var ip = ua.Address.GetAddressBytes();
                    var mask = ua.IPv4Mask.GetAddressBytes();
                    var bcast = new byte[4];
                    for (int i = 0; i < 4; i++) bcast[i] = (byte)(ip[i] | (mask[i] ^ 0xFF));
                    var b = new IPAddress(bcast);
                    if (!list.Any(x => x.Address.Equals(b))) list.Add(new IPEndPoint(b, Proto.Wire.DiscoveryPort));
                }
            }
        }
        catch (NetworkInformationException)
        {
            // ignore
        }
        return list;
    }

    public void Dispose()
    {
        _cts.Cancel();
        _timer.Dispose();
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
