using System.Net.Sockets;
using LanAudioStream.App.Audio;
using LanAudioStream.Core.Net;
using LanAudioStream.Core.Proto;
using LanAudioStream.Core.Util;
using NAudio.CoreAudioApi;

namespace LanAudioStream.App.UI;

public sealed class MainForm : Form
{
    private readonly AppSettings _settings;
    private readonly MMDeviceEnumerator _enumerator = new();
    private readonly MuteControl _mute;
    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 500 };

    private StreamServer? _server;
    private Announcer? _announcer;
    private CaptureSource? _capture;
    private AudioPipeline? _pipeline;

    private bool _running;
    private bool _suppressDirty;
    private long _lastBytes;
    private long _lastDatagrams;
    private ulong _lastTickUs;
    private double _sendKbps;
    private double _sendPps;

    // 已实际生效（运行中）的参数，用于避免无关的整条重配
    private int _appliedCodec = -1;
    private int _appliedRate = -1;
    private int _appliedChannels = -1;
    private int _appliedFrameMs = -1;
    private int _appliedBitrate = -1;
    private bool _appliedDtx;
    private int _appliedBufferMs = -1;
    private bool _appliedMute;

    private readonly List<(string Name, string Id)> _devices = new();

    private ComboBox _cmbDevice = null!;
    private ComboBox _cmbBitrate = null!;
    private ComboBox _cmbFrame = null!;
    private ComboBox _cmbChannels = null!;
    private ComboBox _cmbCodec = null!;
    private ComboBox _cmbSampleRate = null!;
    private ComboBox _cmbBuffer = null!;
    private CheckBox _chkRedundancy = null!;
    private CheckBox _chkDtx = null!;
    private CheckBox _chkMute = null!;
    private ComboBox _cmbJitter = null!;
    private CheckBox _chkAutoReconnect = null!;
    private ComboBox _cmbLinkTimeout = null!;
    private ComboBox _cmbStatusRefresh = null!;
    private CheckBox _chkAutoStart = null!;
    private NumericUpDown _numPort = null!;
    private Button _btnSave = null!;
    private Button _btnReset = null!;
    private Button _btnStart = null!;
    private Button _btnStop = null!;
    private Label _lblDirty = null!;
    private ListBox _log = null!;

    private readonly Dictionary<string, Label> _values = new();

    public MainForm()
    {
        _settings = AppSettings.Load();
        _mute = new MuteControl();
        // 手动 DPI 缩放：WinForms 的自动缩放（Font/Dpi 模式）在 200% 等场景下基准不一致，
        // 会导致控件尺寸不变而字体放大一倍，文字互相重叠。这里统一按 96 DPI 逻辑坐标设计，
        // 再按 DeviceDpi/96 精确缩放，并在 --dumpui 自检中验证无重叠/无裁切。
        AutoScaleMode = AutoScaleMode.None;
        Text = "LanAudioStream 桌面端 — 局域网纯音频串流 (v1.0.0)";
        ClientSize = new Size(980, 570);
        MinimumSize = new Size(980, 520);
        BackColor = Color.FromArgb(18, 20, 26);
        ForeColor = Color.FromArgb(224, 228, 236);
        Font = new Font("Microsoft YaHei UI", 9f);
        StartPosition = FormStartPosition.CenterScreen;
        BuildUi();
        CaptureLogicalLayout();
        Load += (_, _) =>
        {
            var wa = Screen.FromControl(this).WorkingArea;
            if (Width > wa.Width || Height > wa.Height) WindowState = FormWindowState.Maximized;
        };
        RefreshDevices();
        _timer.Interval = 1000 / Math.Clamp(_settings.StatusRefreshHz, 1, 5);
        _timer.Tick += (_, _) => Tick();
        _timer.Start();
        Log("就绪。点击“开始串流”，手机端打开 App 会自动发现本机。改动设置后请点“保存设置”。");
        if (_settings.AutoStart)
        {
            Shown += (_, _) => StartStreaming();
        }
    }

    // ------------------------------------------------------------ DPI 缩放
    private readonly Dictionary<Control, Rectangle> _logicalBounds = new();
    private Size _logicalClient;
    private Size _logicalMinimum;
    private float _appliedScale = 1f;

    private void CaptureLogicalLayout()
    {
        _logicalClient = ClientSize;
        _logicalMinimum = MinimumSize;
        _logicalBounds.Clear();
        CaptureLogical(this);
    }

    private void CaptureLogical(Control parent)
    {
        if (parent is UpDownBase) return; // NumericUpDown manages its own internals
        foreach (Control c in parent.Controls)
        {
            _logicalBounds[c] = c.Bounds;
            CaptureLogical(c);
        }
    }

    private void ApplyDpiScale()
    {
        float target = DeviceDpi / 96f;
        if (Math.Abs(target - _appliedScale) < 0.01f) return;
        _appliedScale = target;
        SuspendLayout();
        ClientSize = new Size((int)Math.Round(_logicalClient.Width * target), (int)Math.Round(_logicalClient.Height * target));
        MinimumSize = new Size((int)Math.Round(_logicalMinimum.Width * target), (int)Math.Round(_logicalMinimum.Height * target));
        foreach (var kv in _logicalBounds)
        {
            var b = kv.Value;
            kv.Key.Bounds = new Rectangle(
                (int)Math.Round(b.X * target),
                (int)Math.Round(b.Y * target),
                (int)Math.Round(b.Width * target),
                (int)Math.Round(b.Height * target));
        }
        ResumeLayout(true);
        Invalidate(true);
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        ApplyDpiScale();
    }

    protected override void OnDpiChangedAfterParent(EventArgs e)
    {
        base.OnDpiChangedAfterParent(e);
        ApplyDpiScale();
    }

    // ---------------------------------------------------------------- UI
    // 所有音频设置都在这里。改动只修改界面，点「保存设置」后才生效并同步到手机端。
    // 坐标按 96 DPI 逻辑单位书写，由 ApplyDpiScale() 统一缩放。
    private void BuildUi()
    {
        var grpSettings = new GroupBox
        {
            Text = "音频设置（修改后点“保存设置”生效并同步到手机端）",
            ForeColor = Color.FromArgb(200, 210, 225),
            Location = new Point(12, 10),
            Size = new Size(556, 380),
        };
        Controls.Add(grpSettings);

        grpSettings.Controls.Add(MakeLabel("音频设备", 14, 26));
        _cmbDevice = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Location = new Point(96, 22), Size = new Size(330, 24) };
        _cmbDevice.SelectedIndexChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_cmbDevice);
        var btnRefresh = new Button { Text = "刷新", Location = new Point(436, 21), Size = new Size(100, 26) };
        btnRefresh.Click += (_, _) => RefreshDevices();
        grpSettings.Controls.Add(btnRefresh);

        grpSettings.Controls.Add(MakeLabel("编码码率", 14, 56));
        _cmbBitrate = new ComboBox { DropDownStyle = ComboBoxStyle.DropDown, Location = new Point(96, 52), Size = new Size(110, 24) };
        _cmbBitrate.Items.AddRange(new object[] { "64 kbps", "96 kbps", "128 kbps", "160 kbps", "192 kbps", "256 kbps", "320 kbps" });
        _cmbBitrate.SelectedIndex = BitrateToIndex(_settings.BitrateBps);
        _cmbBitrate.SelectedIndexChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_cmbBitrate);

        grpSettings.Controls.Add(MakeLabel("帧长", 224, 56));
        _cmbFrame = new ComboBox { DropDownStyle = ComboBoxStyle.DropDown, Location = new Point(306, 52), Size = new Size(96, 24) };
        _cmbFrame.Items.AddRange(new object[] { "5 ms", "10 ms", "20 ms", "40 ms" });
        _cmbFrame.SelectedIndex = FrameToIndex(_settings.FrameMs);
        _cmbFrame.SelectedIndexChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_cmbFrame);

        grpSettings.Controls.Add(MakeLabel("声道", 420, 56, 50));
        _cmbChannels = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Location = new Point(470, 52), Size = new Size(70, 24) };
        _cmbChannels.Items.AddRange(new object[] { "立体声", "单声道" });
        _cmbChannels.SelectedIndex = _settings.Channels == 1 ? 1 : 0;
        _cmbChannels.SelectedIndexChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_cmbChannels);

        grpSettings.Controls.Add(MakeLabel("音频格式", 14, 88));
        _cmbCodec = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Location = new Point(96, 84), Size = new Size(110, 24) };
        _cmbCodec.Items.AddRange(new object[] { "Opus 压缩", "PCM 无损" });
        _cmbCodec.SelectedIndex = _settings.Codec == Wire.CodecOpusPcm ? 1 : 0;
        _cmbCodec.SelectedIndexChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_cmbCodec);

        grpSettings.Controls.Add(MakeLabel("采样率", 224, 88));
        _cmbSampleRate = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Location = new Point(306, 84), Size = new Size(96, 24) };
        // Opus 编码器只接受 8/12/16/24/48 kHz；历史上这里提供 44.1/32 kHz 会让编码器抛异常
        _cmbSampleRate.Items.AddRange(new object[] { "48 kHz", "24 kHz", "16 kHz", "12 kHz", "8 kHz" });
        _cmbSampleRate.SelectedIndex = RateToIndex(_settings.SampleRate);
        _cmbSampleRate.SelectedIndexChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_cmbSampleRate);

        grpSettings.Controls.Add(MakeLabel("采集缓冲", 14, 120));
        _cmbBuffer = new ComboBox { DropDownStyle = ComboBoxStyle.DropDown, Location = new Point(96, 116), Size = new Size(110, 24) };
        _cmbBuffer.Items.AddRange(new object[] { "10 ms", "20 ms", "30 ms", "50 ms" });
        _cmbBuffer.SelectedIndex = BufferToIndex(_settings.CaptureBufferMs);
        _cmbBuffer.SelectedIndexChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_cmbBuffer);

        _chkRedundancy = new CheckBox { Text = "抗丢包冗余", Location = new Point(224, 117), Size = new Size(130, 24), Checked = _settings.Redundancy };
        _chkRedundancy.CheckedChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_chkRedundancy);

        _chkDtx = new CheckBox { Text = "静音省流 DTX", Location = new Point(366, 117), Size = new Size(130, 24), Checked = _settings.Dtx };
        _chkDtx.CheckedChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_chkDtx);

        _chkMute = new CheckBox { Text = "静音电脑扬声器（保存后生效，串流不受影响）", Location = new Point(14, 148), Size = new Size(340, 24), Checked = _settings.MuteLocalSpeakers };
        _chkMute.CheckedChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_chkMute);

        grpSettings.Controls.Add(MakeLabel("抖动缓冲", 14, 184));
        _cmbJitter = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Location = new Point(96, 180), Size = new Size(110, 24) };
        _cmbJitter.Items.AddRange(new object[] { "自动", "延迟优先", "稳定优先" });
        _cmbJitter.SelectedIndex = Math.Clamp(_settings.JitterMode, 0, 2);
        _cmbJitter.SelectedIndexChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_cmbJitter);

        _chkAutoReconnect = new CheckBox { Text = "断线自动重连", Location = new Point(224, 181), Size = new Size(150, 24), Checked = _settings.AutoReconnect };
        _chkAutoReconnect.CheckedChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_chkAutoReconnect);

        grpSettings.Controls.Add(MakeLabel("端口", 14, 213, 44));
        _numPort = new NumericUpDown
        {
            Location = new Point(60, 210),
            Size = new Size(70, 24),
            Minimum = 1024,
            Maximum = 65535,
            Value = Math.Clamp(_settings.StreamPort, 1024, 65535),
        };
        _numPort.ValueChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_numPort);

        _chkAutoStart = new CheckBox { Text = "启动后自动串流", Location = new Point(150, 211), Size = new Size(160, 24), Checked = _settings.AutoStart };
        _chkAutoStart.CheckedChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_chkAutoStart);

        grpSettings.Controls.Add(MakeLabel("断连超时", 14, 244));
        _cmbLinkTimeout = new ComboBox { DropDownStyle = ComboBoxStyle.DropDown, Location = new Point(96, 240), Size = new Size(80, 24) };
        _cmbLinkTimeout.Items.AddRange(new object[] { "3 秒", "5 秒", "8 秒", "15 秒", "30 秒", "60 秒" });
        _cmbLinkTimeout.Text = _settings.LinkTimeoutSec + " 秒";
        _cmbLinkTimeout.TextChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_cmbLinkTimeout);

        grpSettings.Controls.Add(MakeLabel("状态刷新", 200, 244, 76));
        _cmbStatusRefresh = new ComboBox { DropDownStyle = ComboBoxStyle.DropDown, Location = new Point(280, 240), Size = new Size(70, 24) };
        _cmbStatusRefresh.Items.AddRange(new object[] { "1 Hz", "2 Hz", "5 Hz" });
        _cmbStatusRefresh.Text = _settings.StatusRefreshHz + " Hz";
        _cmbStatusRefresh.TextChanged += (_, _) => MarkDirty();
        grpSettings.Controls.Add(_cmbStatusRefresh);

        _btnSave = new Button { Text = "保存设置", Location = new Point(14, 274), Size = new Size(120, 30) };
        _btnSave.Click += (_, _) => SaveSettings();
        grpSettings.Controls.Add(_btnSave);

        _btnReset = new Button { Text = "重置所有设置", Location = new Point(140, 274), Size = new Size(130, 30) };
        _btnReset.Click += (_, _) => ResetAllSettings();
        grpSettings.Controls.Add(_btnReset);

        _btnStart = new Button { Text = "▶ 开始串流", Location = new Point(276, 274), Size = new Size(112, 30) };
        _btnStart.Click += (_, _) => StartStreaming();
        grpSettings.Controls.Add(_btnStart);

        _btnStop = new Button { Text = "■ 停止", Location = new Point(394, 274), Size = new Size(100, 30), Enabled = false };
        _btnStop.Click += (_, _) => StopStreaming("用户停止");
        grpSettings.Controls.Add(_btnStop);

        _lblDirty = new Label
        {
            Text = "",
            Location = new Point(14, 312),
            Size = new Size(520, 22),
            ForeColor = Color.FromArgb(255, 213, 79),
            TextAlign = ContentAlignment.MiddleLeft,
        };
        grpSettings.Controls.Add(_lblDirty);

        var grpStatus = new GroupBox
        {
            Text = "运行状态（本机）",
            ForeColor = Color.FromArgb(200, 210, 225),
            Location = new Point(578, 10),
            Size = new Size(390, 196),
        };
        Controls.Add(grpStatus);

        // 只显示本机可以直接得到的指标；手机端回传的指标不再显示
        string[] statusLabels =
        {
            "客户端", "会话状态", "当前编码", "采集设备", "采集+编码延迟", "发送码率", "发送包速率", "累计发送",
        };
        for (int i = 0; i < statusLabels.Length; i++)
        {
            grpStatus.Controls.Add(MakeLabel(statusLabels[i], 12, 26 + i * 18, 118));
            var lbl = MakeValue(134, 26 + i * 18, 244);
            _values[statusLabels[i]] = lbl;
            grpStatus.Controls.Add(lbl);
        }

        _log = new ListBox
        {
            Location = new Point(12, 398),
            Size = new Size(956, 132),
            BackColor = Color.FromArgb(12, 14, 18),
            ForeColor = Color.FromArgb(150, 210, 170),
            Font = new Font("Consolas", 8.5f),
            IntegralHeight = false,
            HorizontalScrollbar = true,
        };
        Controls.Add(_log);
        var btnClear = new Button { Text = "清空日志", Location = new Point(884, 534), Size = new Size(84, 24) };
        btnClear.Click += (_, _) => _log.Items.Clear();
        Controls.Add(btnClear);
    }

    private static Label MakeLabel(string text, int x, int y, int width = 70) => new()
    {
        Text = text,
        Location = new Point(x, y),
        Size = new Size(width, 20),
        ForeColor = Color.FromArgb(160, 172, 190),
        TextAlign = ContentAlignment.MiddleLeft,
    };

    private static Label MakeValue(int x, int y, int width) => new()
    {
        Text = "--",
        Location = new Point(x, y),
        Size = new Size(width, 20),
        ForeColor = Color.FromArgb(120, 220, 255),
        Font = new Font("Consolas", 9f, FontStyle.Bold),
        TextAlign = ContentAlignment.MiddleLeft,
    };

    // ------------------------------------------------------------- actions
    private void MarkDirty(string? hint = null)
    {
        if (_suppressDirty) return;
        _btnSave.Text = "保存设置 ●";
        _lblDirty.Text = hint ?? "有未保存的修改，点“保存设置”后生效并同步到手机端";
    }

    private void RefreshDevices()
    {
        var current = _cmbDevice.SelectedIndex >= 0 && _cmbDevice.SelectedIndex < _devices.Count
            ? _devices[_cmbDevice.SelectedIndex].Id
            : null;

        _devices.Clear();
        _cmbDevice.Items.Clear();
        _cmbDevice.Items.Add("默认播放设备（推荐，跟随系统切换）");
        foreach (var d in MuteControl.ListRenderDevices())
        {
            _devices.Add(d);
            _cmbDevice.Items.Add(d.Name);
        }
        int index = 0;
        if (current != null)
        {
            int found = _devices.FindIndex(d => d.Id == current);
            if (found >= 0) index = found + 1;
        }
        else if (!string.IsNullOrEmpty(_settings.DeviceName))
        {
            int found = _devices.FindIndex(d => d.Name == _settings.DeviceName);
            if (found >= 0) index = found + 1;
        }
        _cmbDevice.SelectedIndex = index;
    }

    private MMDevice? ResolveDevice()
    {
        int idx = _cmbDevice.SelectedIndex;
        if (idx > 0 && idx - 1 < _devices.Count)
        {
            try
            {
                return _enumerator.GetDevice(_devices[idx - 1].Id);
            }
            catch
            {
                // fall through to default
            }
        }
        try
        {
            return _enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        }
        catch (Exception ex)
        {
            Log("无法获取默认播放设备: " + ex.Message);
            return null;
        }
    }

    /// <summary>从可编辑下拉框里取出第一个整数（支持用户自行输入数值）。</summary>
    private static int ParseNumber(string? text, int fallback)
    {
        if (string.IsNullOrEmpty(text)) return fallback;
        var digits = new string(text.TakeWhile(char.IsDigit).ToArray());
        return int.TryParse(digits, out var v) ? v : fallback;
    }

    private int RawBitrateKbps => ParseNumber(_cmbBitrate.Text, _settings.BitrateBps / 1000);

    private int SelectedBitrate => Math.Clamp(RawBitrateKbps, 6, 512) * 1000;

    private static int BitrateToIndex(int bps) => bps switch
    {
        <= 64000 => 0,
        <= 96000 => 1,
        <= 128000 => 2,
        <= 160000 => 3,
        <= 192000 => 4,
        <= 256000 => 5,
        _ => 6,
    };

    // 帧长上限必须与管线一致（AudioPipeline.MaxFrameMs=40），否则上报给手机的 frameSamples 与实际编码不符
    private int SelectedFrameMs => Math.Clamp(ParseNumber(_cmbFrame.Text, _settings.FrameMs), 5, 40);

    private static int FrameToIndex(int ms) => ms switch { <= 5 => 0, <= 10 => 1, <= 20 => 2, _ => 3 };

    private int SelectedBufferMs => Math.Clamp(ParseNumber(_cmbBuffer.Text, _settings.CaptureBufferMs), 5, 200);

    private static int BufferToIndex(int ms) => ms switch { <= 10 => 0, <= 20 => 1, <= 30 => 2, _ => 3 };

    private int SelectedChannels => _cmbChannels.SelectedIndex == 1 ? 1 : 2;
    private int SelectedCodec => _cmbCodec.SelectedIndex == 1 ? Wire.CodecOpusPcm : Wire.CodecOpus;

    private int SelectedSampleRate => _cmbSampleRate.SelectedIndex switch
    {
        1 => 24000,
        2 => 16000,
        3 => 12000,
        4 => 8000,
        _ => 48000,
    };

    /// <summary>采样率 → 下拉索引。历史配置里的 44.1/32 kHz 会就近归到合法档位。</summary>
    private static int RateToIndex(int rate) => rate switch
    {
        >= 40000 => 0,
        >= 20000 => 1,
        >= 14000 => 2,
        >= 10000 => 3,
        _ => 4,
    };

    private int SelectedJitterMode => Math.Max(0, _cmbJitter.SelectedIndex);

    private string ChannelsText() => SelectedChannels == 1 ? "单声道" : "立体声";

    private string CodecText => SelectedCodec == Wire.CodecOpusPcm ? "PCM 无损" : "Opus";

    private void ApplyMute(bool muted)
    {
        if (!_mute.Available)
        {
            Log("静音失败: 未找到默认播放设备");
            return;
        }
        if (_mute.SetMuted(muted))
        {
            Log(muted ? $"已静音本机扬声器（{_mute.DeviceName}），串流不受影响" : $"已恢复本机扬声器（{_mute.DeviceName}）");
        }
        else
        {
            Log("静音操作失败: " + _mute.LastError);
        }
    }

    /// <summary>把界面上的值收集到 _settings（并做合法性归一化，例如 PCM 帧长上限）。</summary>
    private void CollectSettings()
    {
        int codec = SelectedCodec;
        int frameMs = SelectedFrameMs;
        if (codec == Wire.CodecOpusPcm && frameMs > 10)
        {
            frameMs = 10;
            _cmbFrame.SelectedIndex = 1;
            Log("PCM 无损模式帧长上限为 10 ms，已自动调整为 10 ms");
        }

        _settings.Codec = codec;
        _settings.FrameMs = frameMs;
        _settings.BitrateBps = SelectedBitrate;
        _settings.SampleRate = SelectedSampleRate;
        _settings.Channels = SelectedChannels;
        _settings.Redundancy = _chkRedundancy.Checked;
        _settings.Dtx = _chkDtx.Checked;
        _settings.CaptureBufferMs = SelectedBufferMs;
        _settings.MuteLocalSpeakers = _chkMute.Checked;
        _settings.JitterMode = SelectedJitterMode;
        _settings.AutoReconnect = _chkAutoReconnect.Checked;
        _settings.LinkTimeoutSec = Math.Clamp(ParseNumber(_cmbLinkTimeout.Text, _settings.LinkTimeoutSec), 1, 600);
        _settings.StatusRefreshHz = Math.Clamp(ParseNumber(_cmbStatusRefresh.Text, _settings.StatusRefreshHz), 1, 5);
        _settings.AutoStart = _chkAutoStart.Checked;
        _settings.StreamPort = (int)_numPort.Value;
        _settings.DeviceName = _cmbDevice.SelectedIndex > 0 && _cmbDevice.SelectedIndex - 1 < _devices.Count
            ? _devices[_cmbDevice.SelectedIndex - 1].Name
            : "";
    }

    /// <summary>「保存设置」：落盘 → 应用到运行中的管线 → 推送权威状态给手机端。</summary>
    private void SaveSettings()
    {
        CollectSettings();
        bool saved = _settings.Save();
        try
        {
            ApplySettingsToRuntime();
        }
        catch (Exception ex)
        {
            // 历史缺陷：参数非法（如配置里遗留的 44.1kHz）会抛到 UI 线程消息循环
            Log("设置应用失败: " + ex.Message);
            _lblDirty.Text = "设置应用失败（详见日志）";
            return;
        }
        _timer.Interval = 1000 / Math.Clamp(_settings.StatusRefreshHz, 1, 5);
        _btnSave.Text = "保存设置";
        _lblDirty.Text = !saved
            ? $"设置已应用，但未能写入磁盘（{AppSettings.FilePath}）: {_settings.LastError}"
            : _running && _server?.Client != null
                ? "设置已保存并已同步到手机端"
                : "设置已保存（开始串流后生效）";
        Log($"设置已保存: {CodecText} {_settings.SampleRate / 1000.0:0.#}kHz {_settings.Channels}ch " +
            $"{_settings.FrameMs}ms {_settings.BitrateBps / 1000}kbps 冗余={OnOff(_settings.Redundancy)} DTX={OnOff(_settings.Dtx)} " +
            $"采集缓冲={_settings.CaptureBufferMs}ms 静音={OnOff(_settings.MuteLocalSpeakers)} " +
            $"抖动={_cmbJitter.Text} 自动重连={OnOff(_settings.AutoReconnect)}");
    }

    /// <summary>「重置所有设置」：只恢复界面（默认值由 AppSettings 定义），点保存后才生效/同步。</summary>
    private void ResetAllSettings()
    {
        var d = new AppSettings();
        _suppressDirty = true;
        try
        {
            _cmbDevice.SelectedIndex = 0;
            _cmbBitrate.SelectedIndex = BitrateToIndex(d.BitrateBps);
            _cmbFrame.SelectedIndex = FrameToIndex(d.FrameMs);
            _cmbChannels.SelectedIndex = d.Channels == 1 ? 1 : 0;
            _cmbCodec.SelectedIndex = d.Codec == Wire.CodecOpusPcm ? 1 : 0;
            _cmbSampleRate.SelectedIndex = RateToIndex(d.SampleRate);
            _cmbBuffer.SelectedIndex = BufferToIndex(d.CaptureBufferMs);
            _chkRedundancy.Checked = d.Redundancy;
            _chkDtx.Checked = d.Dtx;
            _chkMute.Checked = d.MuteLocalSpeakers;
            _cmbJitter.SelectedIndex = Math.Clamp(d.JitterMode, 0, 2);
            _chkAutoReconnect.Checked = d.AutoReconnect;
            _cmbLinkTimeout.Text = d.LinkTimeoutSec + " 秒";
            _cmbStatusRefresh.Text = d.StatusRefreshHz + " Hz";
            _chkAutoStart.Checked = d.AutoStart;
            _numPort.Value = Math.Clamp(d.StreamPort, (int)_numPort.Minimum, (int)_numPort.Maximum);
        }
        finally
        {
            _suppressDirty = false;
        }
        MarkDirty("所有设置已恢复默认值，点“保存设置”后生效并同步到手机端");
    }

    private static string OnOff(bool v) => v ? "开" : "关";

    private void UpdateAppliedState()
    {
        _appliedCodec = _settings.Codec;
        _appliedRate = _settings.SampleRate;
        _appliedChannels = _settings.Channels;
        _appliedFrameMs = _settings.FrameMs;
        _appliedBitrate = _settings.BitrateBps;
        _appliedDtx = _settings.Dtx;
        _appliedBufferMs = _settings.CaptureBufferMs;
        _appliedMute = _settings.MuteLocalSpeakers;
    }

    /// <summary>把已收集的 _settings 应用到运行中的管线（只重配真正变化的项）。</summary>
    private void ApplySettingsToRuntime()
    {
        if (!_running || _server == null || _pipeline == null)
        {
            UpdateAppliedState();
            return;
        }

        bool audioChanged = _settings.Codec != _appliedCodec
                            || _settings.SampleRate != _appliedRate
                            || _settings.Channels != _appliedChannels
                            || _settings.FrameMs != _appliedFrameMs
                            || _settings.BitrateBps != _appliedBitrate
                            || _settings.Dtx != _appliedDtx;
        bool bufferChanged = _settings.CaptureBufferMs != _appliedBufferMs;
        bool muteChanged = _settings.MuteLocalSpeakers != _appliedMute;

        _pipeline.CaptureLatencyUs = _settings.CaptureBufferMs * 1000;
        _server.LinkTimeoutMs = Math.Max(1, _settings.LinkTimeoutSec) * 1000;
        if (audioChanged)
        {
            int requestedFrameMs = _settings.FrameMs;
            int requestedRate = _settings.SampleRate;
            _pipeline.Reconfigure(_settings.FrameMs, _settings.BitrateBps, _settings.Channels, _settings.Dtx,
                _pipeline.Complexity, _settings.Codec, _settings.SampleRate);
            // 管线是唯一事实源：帧长（≤40ms，PCM ≤10ms）与采样率（Opus 只支持 8/12/16/24/48k）
            // 会被管线夹取/归一化，这里回写后再生成 HELLO_ACK 与 SETTINGS，
            // 避免"上报 20/40ms 而实际按 10ms 编码"导致手机排程错乱、延迟持续变大。
            _settings.FrameMs = _pipeline.FrameMs;
            _settings.SampleRate = _pipeline.SampleRate;
            _settings.Codec = _pipeline.Codec;
            _settings.Channels = _pipeline.Channels;
            if (requestedFrameMs != _settings.FrameMs || requestedRate != _settings.SampleRate)
            {
                _suppressDirty = true;
                try
                {
                    _cmbFrame.Text = _settings.FrameMs + " ms";
                    _cmbSampleRate.SelectedIndex = RateToIndex(_settings.SampleRate);
                }
                finally
                {
                    _suppressDirty = false;
                }
                Log($"参数已按编码器能力调整: {_settings.SampleRate / 1000.0:0.#}kHz {_settings.FrameMs}ms");
            }
        }

        _server.RedundancyEnabled = _settings.Redundancy && _settings.Codec != Wire.CodecOpusPcm;
        _server.Format = BuildFormat();
        _server.SendHelloAck();

        if (bufferChanged)
        {
            RestartCapture();
        }
        if (muteChanged)
        {
            ApplyMute(_settings.MuteLocalSpeakers);
        }

        SendSettingsToPhone();
        UpdateAppliedState();
    }

    private Wire.AudioFormatInfo BuildFormat() => new(
        (byte)_settings.Codec,
        (byte)_settings.Channels,
        (byte)_settings.FrameMs,
        (byte)(_settings.Redundancy ? 1 : 0),
        (uint)_settings.SampleRate,
        (ushort)(_settings.SampleRate * _settings.FrameMs / 1000),
        (uint)_settings.BitrateBps,
        0);

    /// <summary>把当前权威设置推给手机端（连接建立时、以及每次保存后）。</summary>
    private void SendSettingsToPhone()
    {
        _server?.SendSettings(new List<Wire.Setting>
        {
            new(Wire.KeyCodec, (uint)_settings.Codec),
            new(Wire.KeySampleRate, (uint)_settings.SampleRate),
            new(Wire.KeyBitrateKbps, (uint)(_settings.BitrateBps / 1000)),
            new(Wire.KeyFrameMs, (uint)_settings.FrameMs),
            new(Wire.KeyChannels, (uint)_settings.Channels),
            new(Wire.KeyRedundancy, _settings.Redundancy ? 1u : 0u),
            new(Wire.KeyCaptureBufferMs, (uint)_settings.CaptureBufferMs),
            new(Wire.KeyMuteSpeaker, _settings.MuteLocalSpeakers ? 1u : 0u),
            new(Wire.KeyDtx, _settings.Dtx ? 1u : 0u),
            new(Wire.KeyJitterMode, (uint)_settings.JitterMode),
            new(Wire.KeyAutoReconnect, _settings.AutoReconnect ? 1u : 0u),
            new(Wire.KeyLinkTimeoutSec, (uint)_settings.LinkTimeoutSec),
        });
    }

    /// <summary>手机端点「保存设置」后下发的一组设置：校验后应用，并回推权威状态。</summary>
    private void OnSettingsReceived(List<Wire.Setting> items)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => OnSettingsReceived(items));
            return;
        }
        _suppressDirty = true;
        try
        {
            foreach (var s in items)
            {
                switch (s.Key)
                {
                    case Wire.KeyCodec:
                        _cmbCodec.SelectedIndex = s.Value == Wire.CodecOpusPcm ? 1 : 0;
                        break;
                    case Wire.KeySampleRate:
                        _cmbSampleRate.SelectedIndex = RateToIndex((int)Math.Clamp(s.Value, 8000u, 48000u));
                        break;
                    case Wire.KeyBitrateKbps:
                        _cmbBitrate.SelectedIndex = BitrateToIndex((int)Math.Clamp(s.Value, 32u, 320u) * 1000);
                        break;
                    case Wire.KeyFrameMs:
                        _cmbFrame.SelectedIndex = FrameToIndex((int)Math.Clamp(s.Value, 5u, 40u));
                        break;
                    case Wire.KeyChannels:
                        _cmbChannels.SelectedIndex = s.Value == 1 ? 1 : 0;
                        break;
                    case Wire.KeyRedundancy:
                        _chkRedundancy.Checked = s.Value != 0;
                        break;
                    case Wire.KeyCaptureBufferMs:
                        _cmbBuffer.SelectedIndex = BufferToIndex((int)Math.Clamp(s.Value, 5u, 60u));
                        break;
                    case Wire.KeyMuteSpeaker:
                        _chkMute.Checked = s.Value != 0;
                        break;
                    case Wire.KeyDtx:
                        _chkDtx.Checked = s.Value != 0;
                        break;
                    case Wire.KeyJitterMode:
                        _cmbJitter.SelectedIndex = (int)Math.Clamp(s.Value, 0u, 2u);
                        break;
                    case Wire.KeyAutoReconnect:
                        _chkAutoReconnect.Checked = s.Value != 0;
                        break;
                    case Wire.KeyLinkTimeoutSec:
                        _cmbLinkTimeout.Text = ((int)Math.Clamp(s.Value, 1u, 600u)) + " 秒";
                        break;
                }
            }
        }
        finally
        {
            _suppressDirty = false;
        }

        CollectSettings();
        _settings.Save();
        ApplySettingsToRuntime();
        _btnSave.Text = "保存设置";
        _lblDirty.Text = "已应用手机端保存的设置，并已回推权威状态";
        Log("已应用手机端保存的设置");
    }

    /// <summary>采集缓冲只能在采集源创建时设置，因此需要重建采集源。</summary>
    private void RestartCapture()
    {
        if (!_running) return;
        var device = ResolveDevice();
        if (device == null) return;
        try
        {
            _capture?.Dispose();
            _capture = new CaptureSource(device, _settings.CaptureBufferMs);
            _capture.SamplesAvailable += (samples, count) => _pipeline?.Push(samples, count);
            _capture.Start();
            Log($"采集缓冲已切换为 {_settings.CaptureBufferMs} ms");
        }
        catch (Exception ex)
        {
            Log("重建采集源失败: " + ex.Message);
        }
    }

    private void StartStreaming()
    {
        if (_running) return;
        var device = ResolveDevice();
        if (device == null)
        {
            MessageBox.Show("未找到可用播放设备。", "LanAudioStream", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        CollectSettings();
        _settings.Save();

        try
        {
            _pipeline = new AudioPipeline(_settings.FrameMs, _settings.BitrateBps, _settings.Channels, _settings.Dtx, 10, _settings.Codec, _settings.SampleRate)
            {
                CaptureLatencyUs = _settings.CaptureBufferMs * 1000,
            };
            _pipeline.FrameReady += OnFrameReady;
            _pipeline.Error += message => Log(message);

            _server = new StreamServer(_settings.StreamPort)
            {
                LinkTimeoutMs = _settings.LinkTimeoutSec * 1000,
            };
            _timer.Interval = 1000 / Math.Clamp(_settings.StatusRefreshHz, 1, 5);
            _server.RedundancyEnabled = _settings.Redundancy && _settings.Codec != Wire.CodecOpusPcm;
            _server.Format = BuildFormat();
            _server.Log += Log;
            _server.ClientChanged += OnClientChanged;
            _server.SettingsReceived += OnSettingsReceived;
            _server.StreamStarted += () =>
            {
                try
                {
                    BeginInvoke(() =>
                    {
                        Log("开始向手机推送音频帧");
                        SendSettingsToPhone();
                    });
                }
                catch
                {
                    // 窗体已销毁
                }
            };
            _server.Start();

            _capture = new CaptureSource(device, _settings.CaptureBufferMs);
            _capture.SamplesAvailable += (samples, count) => _pipeline?.Push(samples, count);

            _announcer = new Announcer(_settings.StreamPort)
            {
                ServerName = $"LanAudioStream@{Environment.MachineName}",
                VerboseLog = false,
            };
            _announcer.Log += Log;
            _announcer.Start();

            _pipeline.Start();
            _capture.Start();

            if (_settings.MuteLocalSpeakers) ApplyMute(true);

            _running = true;
            _lastBytes = 0;
            _lastDatagrams = 0;
            _lastTickUs = 0;
            _sendKbps = 0;
            _sendPps = 0;
            UpdateAppliedState();
            _btnStart.Enabled = false;
            _btnStop.Enabled = true;
            _log.Items.Clear();
            Log($"串流已启动：{device.FriendlyName} | {_capture.SourceFormat} | {CodecText} {_settings.BitrateBps / 1000} kbps / {_settings.FrameMs} ms");
            Log($"UDP 数据端口 {_settings.StreamPort}，发现端口 {Wire.DiscoveryPort}（如弹出防火墙提示请允许专用网络）");
            if (_capture.ConversionActive)
            {
                Log("注意：设备混音格式非 48 kHz/2ch/float32，已启用软件重采样");
            }
        }
        catch (Exception ex)
        {
            Log("启动失败: " + ex.Message);
            MessageBox.Show("启动失败: " + ex.Message, "LanAudioStream", MessageBoxButtons.OK, MessageBoxIcon.Error);
            StopStreaming("启动失败回滚");
        }
    }

    private void OnFrameReady(byte[] payload, int length, ulong captureUs, bool silence, bool send)
    {
        _server?.SendAudioFrame(payload.AsSpan(0, length), captureUs, silence, send);
    }

    private void OnClientChanged()
    {
        if (IsDisposed) return;
        try
        {
            BeginInvoke(() =>
            {
                var client = _server?.Client;
                Log(client == null
                    ? "客户端已断开"
                    : $"客户端 {client} {(_server!.Streaming ? "开始接收音频" : "已握手，等待开始")}");
            });
        }
        catch
        {
            // 窗体已销毁/句柄未创建
        }
    }

    private void StopStreaming(string reason)
    {
        if (!_running && _server == null) return;
        _running = false;
        try
        {
            _server?.DisconnectClient();
            _capture?.Dispose();
            _capture = null;
            _pipeline?.Dispose();
            _pipeline = null;
            _server?.Dispose();
            _server = null;
            _announcer?.Dispose();
            _announcer = null;
        }
        catch (Exception ex)
        {
            Log("停止时出错: " + ex.Message);
        }
        _mute.Restore();
        _lastBytes = 0;
        _lastDatagrams = 0;
        _lastTickUs = 0;
        _sendKbps = 0;
        _sendPps = 0;
        _btnStart.Enabled = true;
        _btnStop.Enabled = false;
        SetValue("客户端", "--");
        SetValue("会话状态", "已停止");
        SetValue("当前编码", "--");
        SetValue("采集设备", "--");
        SetValue("采集+编码延迟", "--");
        SetValue("发送码率", "--");
        SetValue("发送包速率", "--");
        SetValue("累计发送", "--");
        Log($"串流已停止（{reason}）");
    }

    private void Tick()
    {
        var now = Clock.NowUs();
        if (_server != null)
        {
            var bytes = _server.BytesSent;
            var datagrams = _server.DatagramsSent;
            if (_lastTickUs != 0 && now > _lastTickUs)
            {
                double secs = (now - _lastTickUs) / 1_000_000.0;
                _sendKbps = _sendKbps * 0.6 + (bytes - _lastBytes) * 8 / 1000.0 / secs * 0.4;
                _sendPps = _sendPps * 0.6 + (datagrams - _lastDatagrams) / secs * 0.4;
            }
            _lastBytes = bytes;
            _lastDatagrams = datagrams;
            _lastTickUs = now;

            SetValue("客户端", _server.Client?.ToString() ?? "--");
            SetValue("会话状态", !_server.Streaming ? (_server.Client != null ? "已连接(待播)" : "监听中")
                : _server.SendPaused ? "客户端无响应(保留会话)" : "串流中");
            SetValue("发送码率", $"{_sendKbps:0.0} kbps");
            SetValue("发送包速率", $"{_sendPps:0} 包/秒");
            SetValue("累计发送", $"{bytes / 1048576.0:0.00} MB");
            SetValue("采集+编码延迟", _capture == null || _pipeline == null
                ? "--"
                : $"{_capture.EffectiveLatencyMs + _pipeline.FrameMs + _pipeline.AverageEncodeMs:0.0} ms");
            SetValue("采集设备", _capture?.DeviceName ?? "--");
            SetValue("当前编码", _pipeline == null
                ? "--"
                : $"{(_pipeline.Codec == Wire.CodecOpusPcm ? "PCM16" : "Opus")} {_pipeline.SampleRate / 1000.0:0.#}kHz " +
                  $"{_pipeline.Channels}ch {_pipeline.FrameMs}ms {_pipeline.BitrateBps / 1000}kbps");

            if (_announcer != null) _announcer.Streaming = _server.Streaming;

        }
    }

    private void SetValue(string key, string text)
    {
        if (_values.TryGetValue(key, out var lbl)) lbl.Text = text;
    }

    private void Log(string message)
    {
        if (IsDisposed) return;
        void Append()
        {
            _log.Items.Add($"[{DateTime.Now:HH:mm:ss}] {message}");
            if (_log.Items.Count > 400) _log.Items.RemoveAt(0);
            _log.TopIndex = _log.Items.Count - 1;
        }
        try
        {
            if (_log.InvokeRequired) _log.BeginInvoke(Append);
            else Append();
        }
        catch
        {
            // 窗体已销毁/句柄未创建
        }
    }

    /// <summary>Dumps real geometry/font metrics and reports overlap/clipping problems.</summary>
    public string DumpLayout()
    {
        var sb = new System.Text.StringBuilder();
        sb.AppendLine($"DPI={DeviceDpi} scale={(float)DeviceDpi / 96f:0.###} client={ClientSize.Width}x{ClientSize.Height} " +
                      $"autoScaleMode={AutoScaleMode} autoScaleDims={AutoScaleDimensions} currentAutoScaleDims={CurrentAutoScaleDimensions}");
        int overlaps = 0, clipped = 0;
        Walk(this, sb, ref overlaps, ref clipped, 0);
        sb.AppendLine($"OVERLAPS={overlaps} CLIPPED={clipped}");
        return sb.ToString();
    }

    private static void Walk(Control parent, System.Text.StringBuilder sb, ref int overlaps, ref int clipped, int depth)
    {
        var kids = parent.Controls.Cast<Control>().ToList();
        foreach (var c in kids)
        {
            string text = c.Text ?? "";
            string label = text.Replace("\r", " ").Replace("\n", " ");
            if (label.Length > 20) label = label[..20] + "…";
            string fitInfo = "";
            if (text.Length > 0 && c is Label or CheckBox or Button or GroupBox)
            {
                var sz = TextRenderer.MeasureText(text, c.Font, new Size(int.MaxValue, int.MaxValue), TextFormatFlags.SingleLine);
                bool fits = c is GroupBox || sz.Width <= c.Width - 4;
                if (!fits) clipped++;
                fitInfo = $" textW={sz.Width} textH={sz.Height} fits={fits}";
            }
            sb.AppendLine($"{new string(' ', depth * 2)}[{c.GetType().Name}] \"{label}\" bounds={c.Bounds} " +
                          $"font={c.Font.Name}/{c.Font.SizeInPoints:0.#}pt/h={c.Font.Height}px{fitInfo}");
            Walk(c, sb, ref overlaps, ref clipped, depth + 1);
        }

        for (int i = 0; i < kids.Count; i++)
        {
            for (int j = i + 1; j < kids.Count; j++)
            {
                var a = TextRect(kids[i]);
                var b = TextRect(kids[j]);
                if (a.IsEmpty || b.IsEmpty) continue;
                var inter = Rectangle.Intersect(a, b);
                if (inter.Width > 2 && inter.Height > 2)
                {
                    overlaps++;
                    sb.AppendLine($"  !! TEXT OVERLAP {kids[i].GetType().Name}({kids[i].Text}) {a} x {kids[j].GetType().Name}({kids[j].Text}) {b} -> {inter}");
                }
            }
        }
    }

    /// <summary>Actual area the rendered text occupies (null/empty when the control shows no text).</summary>
    private static Rectangle TextRect(Control c)
    {
        if (c.Text.Length == 0 || c is ListBox or ProgressBar or NumericUpDown or ComboBox or GroupBox) return Rectangle.Empty;
        var measured = TextRenderer.MeasureText(c.Text, c.Font, new Size(int.MaxValue, int.MaxValue), TextFormatFlags.SingleLine);
        int x = c.Left + (c.Width - measured.Width) / 2;
        int y = c.Top + (c.Height - measured.Height) / 2;
        return new Rectangle(x, y, measured.Width, measured.Height);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        StopStreaming("程序退出");
        _mute.Restore();
        _timer.Stop();
        _enumerator.Dispose();
        _mute.Dispose();
        base.OnFormClosing(e);
    }
}
