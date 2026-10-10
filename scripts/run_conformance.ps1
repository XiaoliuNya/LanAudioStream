<#
.SYNOPSIS
  端到端自检：本机播放 1kHz 测试音 -> WASAPI Loopback 采集 -> Opus -> UDP -> 协议客户端解码 -> 频域校验。
.DESCRIPTION
  该脚本会临时静音本机扬声器（采集不受影响），结束后自动恢复。
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\run_conformance.ps1
#>
param(
    [int]$Seconds = 14,
    [double]$Frequency = 1000.0
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$desktop = Join-Path $root "desktop"
$app = Join-Path $desktop "src\LanAudioStream.Desktop\bin\Release\net10.0-windows\LanAudioStream.exe"
$tool = Join-Path $desktop "tools\LanAudioStream.LoopbackClient\bin\Release\net10.0-windows\LoopbackClient.exe"
$work = Join-Path $env:TEMP "las-conformance"
New-Item -ItemType Directory -Force -Path $work | Out-Null

Write-Host "==> 构建（Release）" -ForegroundColor Cyan
dotnet build (Join-Path $desktop "src\LanAudioStream.Desktop\LanAudioStream.Desktop.csproj") -c Release -v q --nologo | Out-Null
dotnet build (Join-Path $desktop "tools\LanAudioStream.LoopbackClient\LanAudioStream.LoopbackClient.csproj") -c Release -v q --nologo | Out-Null

Write-Host "==> 启动桌面端 headless 服务（静音扬声器，$($Seconds + 12)s）" -ForegroundColor Cyan
$server = Start-Process -FilePath $app -ArgumentList "--headless", "--seconds", ($Seconds + 12), "--mute" -PassThru -RedirectStandardOutput (Join-Path $work "server.log") -NoNewWindow
Start-Sleep -Seconds 4

Write-Host "==> 播放 $Frequency Hz 测试音" -ForegroundColor Cyan
$tone = Start-Process -FilePath $tool -ArgumentList "tone", "--freq", $Frequency, "--seconds", ($Seconds - 1) -PassThru -NoNewWindow
Start-Sleep -Seconds 1

Write-Host "==> 协议客户端接收并解码" -ForegroundColor Cyan
& $tool listen --seconds $Seconds --out (Join-Path $work "decoded.wav") --dump (Join-Path $work "packets.bin")

Write-Host "==> 校验解码音频" -ForegroundColor Cyan
& $tool analyze (Join-Path $work "decoded.wav") --freq $Frequency
$tone.WaitForExit()
$server.WaitForExit()

Write-Host ""
Write-Host "服务端日志:" -ForegroundColor Cyan
Get-Content (Join-Path $work "server.log") -Encoding UTF8 | Select-Object -Last 14
Write-Host ""
Write-Host "产物: $work" -ForegroundColor Green
