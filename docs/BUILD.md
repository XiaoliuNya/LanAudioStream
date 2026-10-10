# 构建与验证

## 0. 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| Windows | 10 1809+ / 11 | 使用 WASAPI Loopback |
| .NET SDK | 8.0+（实测 10.0.201） | 构建桌面端 |
| JDK | 17（实测 Temurin 17.0.20.1） | 构建 Android 端（需完整 JDK，含 `java.instrument`） |
| Android SDK | platform-tools + `platforms;android-35` + `build-tools;35.0.0` | 构建 APK |
| Gradle | 8.9+（实测 8.11.1） | 由脚本自动下载 |
| 7-Zip（可选） | 任意 | 解压更快，缺失时回退 `Expand-Archive` |

一键准备（含国内镜像，约 500 MB）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup_toolchain.ps1
```

默认安装到**仓库同级**的 `_las_toolchain`（仓库位于 `X\LanAudioStream` 时即 `X\_las_toolchain`）：
```
_las_toolchain\
├─ jdk-17.0.20.1+1\        JDK 17
├─ gradle-8.11.1\          Gradle
└─ sdk\                    Android SDK（cmdline-tools/platforms/build-tools/platform-tools）
```
也可以用 `-ToolchainDir` 指定其它位置；构建脚本支持 `-JavaHome / -SdkRoot / -GradleHome`
或标准环境变量 `JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT / GRADLE_HOME`。

---

## 1. 桌面端构建

```powershell
# 自包含单文件 exe（默认，约 48 MB，免装 .NET 运行时）
powershell -ExecutionPolicy Bypass -File scripts\build_desktop.ps1

# 依赖框架的小体积版本（需要目标机安装 .NET 10 Desktop Runtime）
powershell -ExecutionPolicy Bypass -File scripts\build_desktop.ps1 -FrameworkDependent

# 直接使用 dotnet 命令
dotnet publish desktop\src\LanAudioStream.Desktop\LanAudioStream.Desktop.csproj `
    -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
```

产物：

| 文件 | 说明 |
|---|---|
| `dist\LanAudioStream-1.0.0-win-x64.exe` | 桌面端主程序（GUI，可加 `--headless` 走命令行自检） |
| `dist\LoopbackClient-1.0.0-win-x64.exe` | 协议测试客户端：`listen` 模拟手机 / `tone` 播放测试音 / `analyze` 频域校验 |

---

## 2. Android 构建

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build_android.ps1          # release（已签名）
powershell -ExecutionPolicy Bypass -File scripts\build_android.ps1 -Debug   # debug
```

等价的手工命令：

```powershell
$tc = (Resolve-Path "..\_las_toolchain").Path      # 默认位置（仓库同级）
$env:JAVA_HOME = "$tc\jdk-17.0.20.1+1"
"sdk.dir=$($tc -replace '\\','/')/sdk" > android\local.properties
cd android
& "$tc\gradle-8.11.1\bin\gradle.bat" assembleRelease --no-daemon
# 产物：android\app\build\outputs\apk\release\app-release.apk
```

- 签名密钥：`android/keystore/lan-audio.jks`（**本地生成，不入库**；口令为开发用默认值，见 `scripts/setup_toolchain.ps1`，正式发布请改用环境变量或密钥库单独保管）
- 签名口令覆盖：设置环境变量 `LAS_KEYSTORE_PASSWORD`（口令）与 `LAS_KEYSTORE_ALIAS`（别名），`scripts/setup_toolchain.ps1` 生成密钥和 Gradle 签名会共用这两个变量
- 依赖：仅 `io.github.jaredmdobson:concentus:1.0.2`（纯 Java Opus 解码，**无 NDK / 无 ABI 限制**）
- 校验签名：`sdk\build-tools\35.0.0\apksigner.bat verify --print-certs <apk>`

---

## 3. 端到端自检（不需要手机）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run_conformance.ps1
```
流程：启动 headless 桌面端（**临时静音扬声器，结束自动恢复**）→ 播放 1000 Hz 测试音 →
`LoopbackClient listen` 接收解码并写 WAV → `analyze` 做频域校验。

期望输出：
```
[analyze] 48000 Hz 2 ch ... RMS=0.2017 目标频率(1000Hz)幅值=0.2836
[analyze] 结论: 检测到目标频率音频 ✓
[server]  ... e2e=19.x ms 抖动=0.1ms 丢包=0.00% 编码=0.3ms
```

## 4. 验证 Android 核心逻辑（不需要真机）

`android/tools/CoreProbe.java` 直接把 APK 里那份纯 Java 核心代码跑在桌面 JVM 上：

```powershell
$tc    = (Resolve-Path "..\_las_toolchain").Path   # 默认位置（仓库同级）
$JAVAC = "$tc\jdk-17.0.20.1+1\bin\javac.exe"
$JAVA  = "$tc\jdk-17.0.20.1+1\bin\java.exe"
$CP    = "$tc\concentus.jar"   # 或 Gradle 缓存中的 concentus-1.0.2.jar
New-Item -ItemType Directory -Force build-probe | Out-Null
& $JAVAC --release 17 -cp $CP -d build-probe (Get-ChildItem -Recurse android\app\src\main\java\com\lasaudio\core\*.java).FullName android\tools\CoreProbe.java

# A) 用真实抓包验证解码正确性
& $JAVA -cp "build-probe;$CP" com.lasaudio.tools.CoreProbe decode packets.bin decoded.wav
& dist\LoopbackClient-1.0.0-win-x64.exe analyze decoded.wav --freq 1000

# B) 用真实桌面端验证完整接收链路（发现/握手/抖动缓冲/播放）
& $JAVA -cp "build-probe;$CP" com.lasaudio.tools.CoreProbe client --seconds 15 --out received.wav
```

## 5. 真机安装

```powershell
$env:ANDROID_HOME = (Resolve-Path "..\_las_toolchain").Path + "\sdk"   # 默认位置（仓库同级）
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r dist\LanAudioStream-1.0.0.apk
& "$env:ANDROID_HOME\platform-tools\adb.exe" logcat -s "LANAudio"   # 如需排查
```

---

## 6. 常见构建问题

| 现象 | 原因 / 解决 |
|---|---|
| `Module java.instrument may be missing from runtime image` | 使用了精简 JRE 跑 Gradle，请改用完整 JDK 17（脚本会自动挑选） |
| `Package android-35 not found` | `;` 被 cmd 当作分隔符，请用 `scripts\setup_toolchain.ps1` 或给参数加引号 |
| `validateSdkPath ... 语法不正确` | `local.properties` 里 `sdk.dir` 用了单反斜杠；脚本已改成正斜杠 |
| Gradle 报 `-Dorg.gradle.java.home=...` 被当作任务名 | PowerShell 需加引号：`"-Dorg.gradle.java.home=$java"` |
| 下载慢 | 脚本已内置清华/腾讯云镜像；`-Force` 可重新下载 |
