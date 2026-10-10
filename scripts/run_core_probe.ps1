<#
.SYNOPSIS
  在桌面 JVM 上运行 Android 端核心代码（发现/握手/抖动缓冲/Opus 解码），无需真机即可验证。

.DESCRIPTION
  A) decode 模式：把 Windows 抓包 dump 解码成 WAV，用于跨语言编解码一致性验证
  B) client 模式：以 APK 内同一份 Java 代码连接真实桌面端，打印实时指标

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\run_core_probe.ps1 -Mode decode -InputFile packets.bin -OutputFile decoded.wav
  powershell -ExecutionPolicy Bypass -File scripts\run_core_probe.ps1 -Mode client -Seconds 15
#>
param(
    [ValidateSet("decode", "client")]
    [string]$Mode = "client",
    [string]$InputFile,
    [string]$OutputFile,
    [int]$Seconds = 15,
    [string]$JavaHome = "",
    [string]$Host_ = "",
    [int]$Port = 47655
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$toolchain = Join-Path (Split-Path -Parent $root) "_las_toolchain"   # 内置工具链（仓库同级）
$buildDir = Join-Path $root "dist\core-probe"

function Find-JavaHome {
    param([string]$Explicit)
    if ($Explicit -and (Test-Path (Join-Path $Explicit "bin\javac.exe"))) { return $Explicit }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) { return $env:JAVA_HOME }
    $jdk = Get-ChildItem $toolchain -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "bin\javac.exe") } | Select-Object -First 1
    if ($jdk) { return $jdk.FullName }
    throw "未找到 JDK 17+，请先运行 scripts\setup_toolchain.ps1"
}

$javaHome = Find-JavaHome $JavaHome
$javac = Join-Path $javaHome "bin\javac.exe"
$java = Join-Path $javaHome "bin\java.exe"

# concentus jar：优先使用工具链目录，其次从 Maven 镜像下载
$jar = Join-Path $toolchain "concentus.jar"
if (-not (Test-Path $jar)) {
    $cached = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\io.github.jaredmdobson\concentus" -Recurse -Filter "concentus-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cached) {
        $jar = $cached.FullName
    } else {
        Write-Host "下载 concentus jar ..." -ForegroundColor Cyan
        curl.exe -sL --retry 3 -o $jar "https://maven.aliyun.com/repository/public/io/github/jaredmdobson/concentus/1.0.2/concentus-1.0.2.jar"
    }
}
Write-Host "JDK  : $javaHome"
Write-Host "jar  : $jar"

New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
$sources = Get-ChildItem -Recurse (Join-Path $root "android\app\src\main\java\com\lasaudio\core\*.java") | ForEach-Object { $_.FullName }
& $javac --release 17 -encoding UTF-8 -nowarn -cp $jar -d $buildDir @sources (Join-Path $root "android\tools\CoreProbe.java")
if ($LASTEXITCODE -ne 0) { throw "javac 编译失败" }

if ($Mode -eq "decode") {
    if (-not $InputFile) { throw "decode 模式需要 -InputFile <packets.bin>" }
    if (-not $OutputFile) { $OutputFile = Join-Path $buildDir "decoded.wav" }
    & $java -cp "$buildDir;$jar" com.lasaudio.tools.CoreProbe decode $InputFile $OutputFile
} else {
    $cpArgs = @("-cp", "$buildDir;$jar", "com.lasaudio.tools.CoreProbe", "client", "--seconds", $Seconds)
    if ($Host_) { $cpArgs += @("--host", $Host_, "--port", $Port) }
    if ($OutputFile) { $cpArgs += @("--out", $OutputFile) }
    & $java @cpArgs
}
