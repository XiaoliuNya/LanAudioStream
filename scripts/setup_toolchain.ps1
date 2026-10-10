<#
.SYNOPSIS
  一键准备 Android 构建工具链（JDK 17 + Android SDK + Gradle），并生成 APK 签名密钥。

.DESCRIPTION
  国内网络优先使用镜像下载：
    JDK 17        : 清华 Adoptium 镜像 / 华为 openjdk 镜像 / Adoptium 官方
    Android SDK   : dl.google.com（cmdline-tools / platform-tools / platforms / build-tools）
    Gradle 8.11.1 : 腾讯云镜像 / 官方

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\setup_toolchain.ps1
  powershell -ExecutionPolicy Bypass -File scripts\setup_toolchain.ps1 -Force
#>
param(
    [string]$ToolchainDir = "C:\Users\Yuri\Desktop\_las_toolchain",
    [string]$AndroidPlatform = "android-35",
    [string]$BuildTools = "35.0.0",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
New-Item -ItemType Directory -Force -Path $ToolchainDir | Out-Null

function Download-File {
    param([string[]]$Urls, [string]$Target)
    if ((Test-Path $Target) -and -not $Force -and (Get-Item $Target).Length -gt 50MB) {
        Write-Host "  已存在，跳过: $Target" -ForegroundColor DarkGray
        return
    }
    foreach ($url in $Urls) {
        try {
            Write-Host "  下载 $url" -ForegroundColor Gray
            if (Test-Path $Target) { Remove-Item $Target -Force }
            curl.exe -sL --retry 3 --fail -o $Target $url
            if ((Test-Path $Target) -and (Get-Item $Target).Length -gt 50MB) { return }
        } catch {
            Write-Host "  失败: $($_.Exception.Message)" -ForegroundColor DarkYellow
        }
    }
    throw "下载失败: $Target"
}

function Expand-Zip {
    param([string]$Zip, [string]$Destination)
    $sevenZip = "C:\Program Files\7-Zip\7z.exe"
    if (Test-Path $sevenZip) {
        & $sevenZip x $Zip "-o$Destination" -y | Out-Null
    } else {
        Expand-Archive -Path $Zip -DestinationPath $Destination -Force
    }
}

Write-Host "==> 1/4 下载 JDK 17 / Android cmdline-tools / Gradle" -ForegroundColor Cyan
Download-File -Target (Join-Path $ToolchainDir "jdk17.zip") -Urls @(
    "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip",
    "https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip",
    "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
)
Download-File -Target (Join-Path $ToolchainDir "cmdline-tools.zip") -Urls @(
    "https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip"
)
Download-File -Target (Join-Path $ToolchainDir "gradle.zip") -Urls @(
    "https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip",
    "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip"
)

Write-Host "==> 2/4 解压" -ForegroundColor Cyan
$jdk = Get-ChildItem $ToolchainDir -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName "bin\javac.exe") } | Select-Object -First 1
if (-not $jdk) {
    Expand-Zip (Join-Path $ToolchainDir "jdk17.zip") $ToolchainDir
}
if (-not (Test-Path (Join-Path $ToolchainDir "gradle-8.11.1\bin\gradle.bat"))) {
    Expand-Zip (Join-Path $ToolchainDir "gradle.zip") $ToolchainDir
}
$sdk = Join-Path $ToolchainDir "sdk"
if (-not (Test-Path (Join-Path $sdk "cmdline-tools\latest\bin\sdkmanager.bat"))) {
    $tmp = Join-Path $ToolchainDir "cmdline-tmp"
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Zip (Join-Path $ToolchainDir "cmdline-tools.zip") $tmp
    New-Item -ItemType Directory -Force -Path (Join-Path $sdk "cmdline-tools") | Out-Null
    if (Test-Path (Join-Path $sdk "cmdline-tools\latest")) { Remove-Item (Join-Path $sdk "cmdline-tools\latest") -Recurse -Force }
    Move-Item (Join-Path $tmp "cmdline-tools") (Join-Path $sdk "cmdline-tools\latest")
    Remove-Item $tmp -Recurse -Force
}

$jdk = Get-ChildItem $ToolchainDir -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName "bin\javac.exe") } | Select-Object -First 1
$javaHome = if ($jdk) { $jdk.FullName } else { $env:JAVA_HOME }
if (-not (Test-Path (Join-Path $javaHome "bin\javac.exe"))) { throw "未找到可用的 JDK 17" }

Write-Host "==> 3/4 安装 Android SDK 组件 (platform-tools / platforms;$AndroidPlatform / build-tools;$BuildTools)" -ForegroundColor Cyan
$installBat = Join-Path $sdk "cmdline-tools\latest\bin\install_components.bat"
@"
@echo off
set JAVA_HOME=$javaHome
call "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root=$sdk platform-tools "platforms;$AndroidPlatform" "build-tools;$BuildTools"
exit /b %ERRORLEVEL%
"@ | Set-Content -Path $installBat -Encoding ASCII
cmd /c "`"$installBat`"" | Out-Null
if (-not (Test-Path (Join-Path $sdk "platforms\$AndroidPlatform\android.jar"))) {
    throw "Android SDK 组件安装失败"
}

Write-Host "==> 4/4 生成 APK 签名密钥（若不存在）" -ForegroundColor Cyan
$keystore = Join-Path (Split-Path -Parent $PSScriptRoot) "android\keystore\lan-audio.jks"
if (-not (Test-Path $keystore)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $keystore) | Out-Null
    & (Join-Path $javaHome "bin\keytool.exe") -genkeypair -v -keystore $keystore -storetype PKCS12 `
        -alias lanaudio -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass lanaudio -keypass lanaudio `
        -dname "CN=LanAudioStream, OU=Dev, O=LanAudioStream, L=NA, ST=NA, C=CN" | Out-Null
}

Write-Host ""
Write-Host "工具链就绪：" -ForegroundColor Green
Write-Host "  JDK    : $javaHome"
Write-Host "  SDK    : $sdk"
Write-Host "  Gradle : $(Join-Path $ToolchainDir 'gradle-8.11.1\bin\gradle.bat')"
