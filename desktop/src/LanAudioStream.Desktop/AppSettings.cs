using System.Text.Json;

namespace LanAudioStream.App;

public sealed class AppSettings
{
    public int BitrateBps { get; set; } = 128000;
    public int FrameMs { get; set; } = 10;
    public int SampleRate { get; set; } = 48000;
    public int Channels { get; set; } = 2;
    public int Codec { get; set; } = 1;            // 1 = Opus, 2 = PCM16
    public bool Dtx { get; set; }
    public int StreamPort { get; set; } = 47655;
    public int CaptureBufferMs { get; set; } = 20;
    public bool Redundancy { get; set; } = true;
    public bool MuteLocalSpeakers { get; set; }
    public bool AutoStart { get; set; }
    public int LinkTimeoutSec { get; set; } = 8;   // 断连超时（秒）：双向同步
    public int StatusRefreshHz { get; set; } = 2;  // 桌面端状态刷新率（Hz，本机）
    public int JitterMode { get; set; }            // 手机端抖动策略 0 自动 / 1 延迟优先 / 2 稳定优先
    public bool AutoReconnect { get; set; } = true; // 手机端断线自动重连
    public string DeviceName { get; set; } = "";

    private static string Path => System.IO.Path.Combine(AppContext.BaseDirectory, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(Path))
            {
                var json = File.ReadAllText(Path);
                return JsonSerializer.Deserialize<AppSettings>(json) ?? new AppSettings();
            }
        }
        catch
        {
            // fall back to defaults
        }
        return new AppSettings();
    }

    /// <summary>最近一次保存失败的原因（供界面提示）。</summary>
    public string? LastError { get; private set; }

    /// <summary>配置文件路径（exe 同目录）。</summary>
    public static string FilePath => Path;

    public bool Save()
    {
        try
        {
            File.WriteAllText(Path, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
            LastError = null;
            return true;
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
            return false;
        }
    }
}
