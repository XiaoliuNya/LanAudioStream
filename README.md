**[English](README.en.md) | 简体中文**

# LanAudioStream —— 局域网纯音频串流（Windows → Android）

把 Windows 11 正在播放的**系统声音**，以低延迟、高质量的方式串流到同一局域网的 Android 手机上播放。
桌面端基于 **WASAPI Loopback** 抓取系统混音，**Opus** 编码，通过自研 **UDP 协议（LAS1）** 传输；
手机端用 **AudioTrack** 播放，实时绘制延迟/码率/速度/丢包/抖动曲线，并在本机统计左右声道响度电平。

> **AI 生成声明**：本项目的**全部代码、测试与文档均由 AI 编码助手（DSH，DeepSeek Harness）生成**；
> 人类参与者的角色是提出需求、在实际使用中发现 Bug 并交回 AI 修复。详见 **§10**。

```
┌─────────────────────── Windows 11 桌面端 ───────────────────────┐
│ WASAPI Loopback ─► 48kHz/2ch float ─► Opus(10ms) ─► UDP 47655  │
│   ▲ 可选静音本机扬声器（串流不受影响）                          │
└────────────────────────────┬────────────────────────────────────┘
                             │  UDP（同一局域网，无服务器中转）
┌────────────────────────────▼────────────────────────────────────┐
│ 发现(47654 广播) ─► 握手 ─► 抖动缓冲 ─► Opus 解码 ─► AudioTrack │
│ 后台前台服务 + 实时曲线（位于设置界面上方）                      │
└─────────────────────────────────────────────────────────────────┘
```

## 1. 功能一览

**桌面端（Windows 11）**
- 捕获**系统播放音频**（WASAPI Loopback，事件驱动，10–20 ms 缓冲，可运行于静音状态）
- **可关闭本机扬声器输出**：调用 `IAudioEndpointVolume::SetMute`，采集端不受影响（已实测验证，见 §5）
- 状态面板只显示**本机可直接得到的指标**：客户端、会话状态、当前编码、采集设备、采集+编码延迟、发送码率/包速率/累计流量
- **设置手动提交**：改动只改本机界面，点「保存设置」才生效并同步到手机端；「重置所有设置」一键恢复默认值
- 码率 64–320 kbps、帧长 5/10/20/40 ms、抗丢包冗余开关、端口可配；UDP 广播自动发现（无需输入 IP），设备名显示为 `LanAudioStream@计算机名`；内置 headless 自检模式与协议测试客户端（LoopbackClient.exe）

**手机端（Android 8.0+，APK ~0.8 MB）**
- **自动发现 + 自动连接**，断线指数退避重连（300 ms → 4 s）；连接后两端自动停止周期探测（省电），断线后自动恢复；**后台播放**由前台服务 + 通知栏实时指标 + WakeLock / WifiLock / MulticastLock 支撑
- 实时指标：端到端延迟、抖动、接收码率、实时速度、丢包率、丢包/丢帧数、抖动缓冲、音频输出缓冲、已解码帧、PLC 补偿、播放欠载、时钟同步 RTT
- **实时曲线图**（位于设置界面上方，自绘 Canvas 5 条曲线，无第三方依赖）与**左右声道响度电平条（L / R）**：均按屏幕刷新率绘制、环形缓冲零装箱；电平在手机本地由解码后 PCM 统计，不依赖电脑端回传
- **设置手动保存**：音频格式（Opus / **PCM 无损**）、采样率 48/44.1/32/24/16 kHz、码率、帧长（PCM 上限 10 ms）、声道、抗丢包冗余、静音电脑扬声器、采集缓冲 10–50 ms、静音省流 DTX 等项在**电脑端生效**；断连超时 3–600 s、抖动缓冲策略、断线自动重连、指标刷新率 1–60 Hz、通知栏指标开关在**本机生效**；另有服务器列表（多桌面端时可手动选择连接）

## 2. 目录结构

```
LanAudioStream/
├─ desktop/                        Windows 桌面端（C# / .NET 10 / WinForms，含 LanAudioStream.sln）
│  ├─ src/LanAudioStream.Core/     协议、Opus 编码、UDP 服务端、广播发现（跨平台可测）
│  ├─ src/LanAudioStream.Desktop/  WinForms UI、WASAPI 采集、编码管线、静音控制
│  └─ tools/LanAudioStream.LoopbackClient/  协议一致性 / 自检客户端（模拟手机）
├─ android/                        Android 端（Kotlin + 纯 Java 核心）
│  ├─ app/src/main/java/com/lasaudio/{core,app}/  核心（发现/会话/抖动缓冲/Opus 解码，纯 Java 可在 JVM 上测试）与 app（MainActivity / 前台服务 / AudioTrack / 曲线图）
│  ├─ tools/CoreProbe.java         JVM 端核心逻辑测试器（无需真机即可验证接收链路）
│  └─ keystore/lan-audio.jks       APK 签名密钥（本地生成，不入库）
├─ scripts/                        构建与自检脚本
├─ docs/                           协议 / 构建 / 使用文档 / 变更历史
└─ dist/                           产物目录（exe/apk 不入库，随 Releases 发布；仅 SHA256SUMS.txt 入库）
```

## 3. 快速开始

### 3.1 桌面端

从 **[Releases](https://github.com/XiaoliuNya/LanAudioStream/releases/latest)** 下载 `LanAudioStream-1.0.0-win-x64.exe`（自包含单文件，无需安装 .NET），直接双击运行。或从源码自行构建（需 .NET 10 SDK）：

```powershell
scripts\build_desktop.ps1            # 产物输出到 dist\LanAudioStream-1.0.0-win-x64.exe
```
1. 选择“默认播放设备（推荐）”；需要静音本机时勾选 **静音电脑扬声器**，再点 **保存设置**（静音后继续串流）
2. 点击 **▶ 开始串流**
3. 首次运行如弹出 Windows 防火墙提示，请勾选“专用网络”并允许（或运行 `scripts\allow_firewall.ps1`）；之后每次改完音频设置，点 **保存设置** 才会生效并同步到手机端，点 **重置所有设置** 可一键恢复默认值

### 3.2 手机端

从 **[Releases](https://github.com/XiaoliuNya/LanAudioStream/releases/latest)** 下载 `LanAudioStream-1.0.0.apk`，传到手机点击安装（需允许“安装未知来源应用”），或用 adb：

```powershell
adb install -r LanAudioStream-1.0.0.apk
```
1. 手机与电脑连同一个 WiFi，打开 App → 点击 **开始接收**（App 会自动发现桌面端并连接）
2. 音频参数在「音频设置（在电脑上生效）」里修改，**改完点「保存设置」才会下发**；「播放设置（本机）」里的项目立即生效

> 桌面端与手机端无需任何手工配对：桌面端每秒广播一次，手机端收到即自动连接。

## 4. 技术方案

| 环节 | 方案 | 说明 |
|---|---|---|
| 采集 | NAudio 3.1 `WasapiRecorder` + `WithLoopbackCapture()` | 事件驱动共享模式，20 ms 缓冲，直接拿到 `float32/48 kHz/2ch`；非 48k/2ch 时自动重采样 |
| 编码 | Concentus（**纯托管 Opus**） | 48 kHz 立体声、`OPUS_APPLICATION_AUDIO`、Complexity 10、VBR、In-band FEC，编码耗时 ≈0.2–0.4 ms/帧 |
| 传输 | 自研 UDP 协议 LAS1 | 无队头阻塞、无重传；单帧一包（10 ms → 100 包/秒）；可选**同包重发上一帧**实现 0 成本 1 级丢包恢复 |
| 时钟同步 | NTP 式四时间戳（`SYNC_REQ/SYNC_RSP`） | 取最小 RTT 样本估计时钟偏移，从而在手机上算出**真实端到端延迟**（仅手机端显示） |
| 抖动缓冲 | 自适应有序播放队列 | 低延迟 20–180 ms / 音效 60–400 ms，欠载自动加大、链路干净缓慢回落、比目标深时主动剪掉最旧帧；已知丢包用 Opus PLC 合成；发送端空闲时写静音但不计欠载 |
| 发现与保活 | UDP 47654 广播 + 单播应答；手机每 100 ms 回传 `RR` | 手机端 `PROBE`，桌面端 `ANNOUNCE`（含名称、端口、是否正在串流）；桌面端超过「断连超时」（默认 8 s，可设 1–600 s）无回传时**只暂停发送、保留会话**，恢复反馈或手机重连后立即继续 |
| 远程设置 | `SETTINGS`（type=11，TLV） | 只在点「保存设置」时提交 → 接收端校验并应用 → 重发 `HELLO_ACK` 并回推权威状态（连接建立时也推一次） |
| 输出 | `AudioTrack` MODE_STREAM | 默认媒体路径 + 约 80 ms 缓冲（低延迟模式已取消，保留系统音效） |

协议细节见 **[docs/PROTOCOL.md](docs/PROTOCOL.md)**。

## 5. 关键设计验证（本机实测）

### 5.1 “静音本机扬声器后仍能采集”是否成立？

在目标机器（Windows 11 + Senary Audio 默认播放设备）上：先用 `WasapiOut` 播放 440 Hz 正弦，再用 `WasapiLoopbackCapture` 采集并统计 RMS，中途切换系统静音——
RMS **未静音 0.106082 → 系统静音后 0.106054 → 恢复 0.106069**。结论：**端点静音不影响 Loopback 采集**，“关掉本机扬声器继续串流”可原生实现，无需虚拟声卡。

### 5.2 端到端链路实测（本机回环，Released 版）

采集 `Senary Audio` / `48000 Hz / 2 ch / 32 bit Extensible(float)` / 缓冲 20 ms；播放 1000 Hz 测试音 → 采集 → Opus 128 kbps/10 ms → UDP → 接收端解码写 WAV：

- 解码音频分析：**RMS = 0.2017，1000 Hz 幅值 = 0.2836（源信号 0.3）→ 判定“检测到目标频率音频 ✓”**
- 端到端延迟（采集时刻→接收解码时刻，含采集缓冲与编码）：**19–20 ms**；网络抖动 **0.09–0.16 ms**；丢包 **0.00%**；编码耗时 0.2–0.4 ms/帧；C# 接收端与 **Java（APK 内同一份代码）** 解码结果**完全一致**（帧数、RMS、频谱幅值逐位吻合）

### 5.3 验证状态

| 验证项 | 结果 |
|---|---|
| 桌面端构建 / 界面自检 | ✅ GUI 正常创建，headless 可运行；`--dumpui` 在 200% 缩放（2880×1800）下 **OVERLAPS=0 CLIPPED=0** |
| 采集链路与音频正确性 / 干净链路延迟 | ✅ 实测：19–20 ms，抖动 0.1–0.12 ms，丢包 0%；1 kHz 源幅值 0.3 → 解码幅值 0.2836；端到端 ≈100–180ms（含约 80ms 音频输出缓冲），欠载 0 |
| Android 核心代码（JVM 上同一份源码） | ✅ 跑通发现/会话/抖动缓冲/Opus 解码全流程，解码结果与 C# 端**逐位一致**（`scripts/run_core_probe.ps1`） |
| Android 构建与签名 | ✅ 已签名，含 Concentus 解码器（998 个类），无原生库（任意 ABI 可装）；清单类名↔dex 校验含**负向测试** |
| 音频连续性（注入 100ms/700ms 网络卡顿） | ✅ 自适应缓冲 5s 内收敛：20s 内仅 3 次单帧补偿（≈0.15%），丢包 0%；50ms 窗口静音占比 **0.00%** |
| **PCM 无损模式（含曾经崩溃的 10 ms 默认帧长）** | ✅ 实测：`编码=2/48000Hz/10ms/2ch`、1570 kbps、延迟 89–95 ms、丢包 0%、PLC 0、欠载 0；手机逐秒解码 RMS 恒定 |
| 远程设置双向通道 / 两端设置一致性 | ✅ 实测下发 `192kbps/20ms/单声道/关冗余/开DTX` → 桌面日志「已应用」→ 手机收到新 `HELLO_ACK` 与权威状态回推；手动保存路径同此，桌面 11 项 + 手机本机 3 项 |
| 采样率切换 / 连接后停止探测 / 崩溃留痕 | ✅ `24kHz 单声道 20ms` 通过；16s 会话中桌面端仅前 1.5s 收到 3 个探测包，其后为 0；未捕获异常写入 `Android/data/com.lasaudio/files/crash-*.txt` |
| 手机端 UI / 前台服务真机运行 | ⚠️ 本环境无 Android 设备/模拟器，未做真机运行验证 |

## 6. 构建

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup_toolchain.ps1   # 1) 工具链（JDK 17 + Android SDK + Gradle，含国内镜像）
powershell -ExecutionPolicy Bypass -File scripts\build_desktop.ps1     # 2) 桌面端（自包含单文件 exe + 诊断工具）
powershell -ExecutionPolicy Bypass -File scripts\build_android.ps1     # 3) Android APK（release 已签名）
powershell -ExecutionPolicy Bypass -File scripts\run_conformance.ps1   # 4) 端到端自检（采集→编码→传输→解码→频域校验）
```
各脚本的参数与前置条件详见 **[docs/BUILD.md](docs/BUILD.md)**。

### 6.1 自动构建与发布（GitHub Actions）

仓库内置工作流 **[.github/workflows/build.yml](.github/workflows/build.yml)**：

- 推送到 `main` 或提交 PR → 自动在 GitHub 机器上编译桌面端与 APK（用于验证代码可构建）
- 推送形如 `v1.0.1` 的 tag → 额外把产物自动上传到对应 Release，并生成 `SHA256SUMS.txt`

```powershell
git tag v1.0.1
git push origin main --tags
```

- 构建号与文件名来自 tag 名（`-Version` / `-PlasVersion` 参数），APK 内部的 `versionName`/`versionCode` 也随之变化
- APK 签名需在仓库 **Settings → Secrets and variables → Actions** 配置三个 Secret：`LAS_KEYSTORE_BASE64`（密钥文件的 base64）、`LAS_KEYSTORE_PASSWORD`、`LAS_KEYSTORE_ALIAS`；**未配置时工作流仍会产出未签名的 APK**，仅供测试
- 公开仓库使用 Actions **免费**

## 7. 交付产物（Releases）

二进制产物体积较大，**不放入 Git 仓库**，随版本发布在 **[Releases](https://github.com/XiaoliuNya/LanAudioStream/releases/latest)**；`dist/SHA256SUMS.txt` 已入库，可校验下载到的文件是否完整。

| 文件 | 大小 | SHA256 |
|---|---|---|
| `LanAudioStream-1.0.0-win-x64.exe` | 47.6 MB | `E7B7E9D2B769C62A5930ED7768142DAE87D04554A2E6E281D43ABE17BFCBB9EA` |
| `LanAudioStream-1.0.0.apk` |  0.8 MB | `15C8BB4EE3717F0609D4AD89BA5E3F26846F8F2CB6B4188C0C9C0BF8E4266FFA` |
| `LanAudioStream-1.0.0-source.zip` | ≈0.2 MB | 见 `dist/SHA256SUMS.txt`（源码包内含本文件，改 README 会改变自身哈希） |
| `LoopbackClient-1.0.0-win-x64.exe` | 50.0 MB | 见 `dist/SHA256SUMS.txt` |

桌面端 exe 为 **.NET 10 自包含单文件**，双击即用；APK 为 release 构建（`minSdk 26 / targetSdk 35`），已用 `keystore/lan-audio.jks` 签名并通过 `apksigner verify`；源码包含 `desktop/ + android/ + docs/ + scripts/ + README + LICENSE`（按 Git 跟踪内容打包，不含 bin/obj/build 产物与签名密钥）。

## 8. 已知限制

- 手机端与桌面端必须处于**同一可广播的局域网**（部分企业/访客 WiFi 会隔离客户端，此时可改用手机热点或手动连接）。
- 蓝牙耳机/音箱本身有 100–200 ms 固有延迟，非本工具引入；建议用有线耳机评估低延迟效果。
- 桌面端默认播放设备切换后需重新点击“开始串流”（当前未订阅设备变更通知）。
- 单客户端：同一时刻仅一台手机接收（协议可扩展为多播，未实现）。
- 手机端 UI 未做平板/横屏适配（锁定竖屏）。

## 9. 排查日志

开发者模式（写文件日志）已移除，两端都改用界面内的日志区：桌面端在主界面底部日志框，手机端在页面底部「日志」区，涵盖握手/断开、暂停发送与恢复、设置保存、格式与状态变更、异常堆栈等；手机端未捕获异常仍会写入 `Android/data/com.lasaudio/files/crash-*.txt`。详细步骤见 **[docs/USER_GUIDE.md](docs/USER_GUIDE.md)**。

## 10. 关于 AI 参与

**本项目的全部代码、测试、文档与构建脚本均由 AI 编码助手（DSH，DeepSeek Harness）生成。** 人类参与者的角色是：

1. 提出需求与验收标准
2. 在实际使用中发现 Bug / 体验问题
3. 把问题交回 AI 修复，并在真机上复验

因此请注意：

- 提交记录以 `AI-Assisted-by: DSH (DeepSeek Harness)` 标注 AI 协作（仓库内置钩子自动追加，见下）；刻意不使用 `Co-authored-by:`，否则 GitHub 会把其中的邮箱当作真实账号、误关联到同名用户
- 文中所有「实测数据」都是 AI 在本机跑出的结果，人类未逐条复算——请把它们视为**开发者的自测结论**，而非第三方检测报告
- AI 生成的代码可能存在未知缺陷或安全疏漏，用于生产环境前请自行评估（另见 §8）

### 启用提交署名钩子（克隆后执行一次）

```powershell
git config core.hooksPath .githooks
```

之后每次 `git commit` 会自动追加 AI 署名；Linux/macOS 上如提示钩子不可执行，先 `chmod +x .githooks/prepare-commit-msg`。若某次提交与 AI 无关，用 `git commit --no-verify` 跳过。

## 11. 许可证

本项目使用 [MIT 许可证](LICENSE)。

> 各版本迭代与缺陷修复的完整记录见 **[docs/CHANGELOG.md](docs/CHANGELOG.md)**。
