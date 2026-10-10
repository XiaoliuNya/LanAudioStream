using NAudio.CoreAudioApi;

namespace LanAudioStream.App.Audio;

public sealed class MuteControl : IDisposable
{
    private readonly MMDeviceEnumerator _enumerator = new();
    private bool _saved;
    private bool _previousMute;

    public bool Available { get; private set; }
    public string DeviceName { get; private set; } = "(无)";
    public string? LastError { get; private set; }

    public MuteControl()
    {
        try
        {
            var device = _enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            DeviceName = device.FriendlyName;
            Available = true;
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
            Available = false;
        }
    }

    private MMDevice? DefaultDevice()
    {
        try
        {
            var device = _enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            DeviceName = device.FriendlyName;
            Available = true;
            return device;
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
            Available = false;
            return null;
        }
    }

    public bool IsMuted
    {
        get
        {
            var device = DefaultDevice();
            if (device == null) return false;
            try
            {
                return device.AudioEndpointVolume.Mute;
            }
            catch
            {
                return false;
            }
        }
    }

    public bool SetMuted(bool muted)
    {
        var device = DefaultDevice();
        if (device == null) return false;
        try
        {
            if (!_saved)
            {
                _previousMute = device.AudioEndpointVolume.Mute;
                _saved = true;
            }
            device.AudioEndpointVolume.Mute = muted;
            return true;
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
            return false;
        }
    }

    public void Restore()
    {
        if (!_saved) return;
        var device = DefaultDevice();
        if (device == null) return;
        try
        {
            device.AudioEndpointVolume.Mute = _previousMute;
        }
        catch
        {
            // ignored
        }
        _saved = false;
    }

    public static List<(string Name, string Id)> ListRenderDevices()
    {
        var list = new List<(string, string)>();
        try
        {
            using var enumerator = new MMDeviceEnumerator();
            foreach (var d in enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
            {
                list.Add((d.FriendlyName, d.ID));
            }
        }
        catch
        {
            // ignored
        }
        return list;
    }

    public void Dispose() => _enumerator.Dispose();
}
