<#
.SYNOPSIS
  构建 Windows 桌面端（自包含单文件 exe 或依赖框架的 exe）。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\build_desktop.ps1
  powershell -ExecutionPolicy Bypass -File scripts\build_desktop.ps1 -FrameworkDependent
#>
param(
    [string]$Configuration = "Release",
    [string]$Runtime = "win-x64",
    [switch]$FrameworkDependent
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$project = Join-Path $root "desktop\src\LanAudioStream.Desktop\LanAudioStream.Desktop.csproj"
$toolProject = Join-Path $root "desktop\tools\LanAudioStream.LoopbackClient\LanAudioStream.LoopbackClient.csproj"
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$version = "1.0.0"
Write-Host "==> 构建桌面端 ($Configuration / $Runtime)" -ForegroundColor Cyan

$selfContained = if ($FrameworkDependent) { "false" } else { "true" }
$publishDir = Join-Path $dist ("desktop-" + $(if ($FrameworkDependent) { "framedependent" } else { "selfcontained" }))

dotnet publish $project `
    -c $Configuration `
    -r $Runtime `
    --self-contained $selfContained `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:EnableCompressionInSingleFile=true `
    -p:DebugType=none `
    -o $publishDir
if ($LASTEXITCODE -ne 0) { throw "dotnet publish 失败" }

Write-Host "==> 构建诊断工具 LoopbackClient" -ForegroundColor Cyan
$toolPublish = Join-Path $dist "tools"
dotnet publish $toolProject -c $Configuration -r $Runtime --self-contained $selfContained `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true `
    -p:DebugType=none -o $toolPublish
if ($LASTEXITCODE -ne 0) { throw "工具构建失败" }

$exe = Join-Path $publishDir "LanAudioStream.exe"
$target = Join-Path $dist ("LanAudioStream-$version-win-x64" + $(if ($FrameworkDependent) { "-fd" } else { "" }) + ".exe")
Copy-Item $exe $target -Force
Copy-Item (Join-Path $toolPublish "LoopbackClient.exe") (Join-Path $dist "LoopbackClient-$version-win-x64.exe") -Force

Write-Host "==> 界面自检（当前 DPI 下的文字重叠/裁切检查）" -ForegroundColor Cyan
$dumpFile = Join-Path $publishDir "layout-dump.txt"
if (Test-Path $dumpFile) { Remove-Item $dumpFile -Force }
& $exe --dumpui | Out-Null
Start-Sleep -Milliseconds 500
if (Test-Path $dumpFile) {
    $dump = Get-Content $dumpFile -Raw -Encoding UTF8
    $head = ($dump -split "`n")[0]
    Write-Host "  $head"
    if ($dump -match "OVERLAPS=0 CLIPPED=0") {
        Write-Host "  界面布局检查通过 (OVERLAPS=0 CLIPPED=0)" -ForegroundColor Green
    } else {
        $bad = ($dump -split "`n" | Where-Object { $_ -match "OVERLAP|fits=False" } | Select-Object -First 6) -join "`n"
        Write-Host "  界面布局存在重叠/裁切：" -ForegroundColor Yellow
        Write-Host $bad
    }
} else {
    Write-Host "  未生成 layout-dump.txt（跳过）" -ForegroundColor DarkYellow
}

$hash = (Get-FileHash $target -Algorithm SHA256).Hash
Write-Host ""
Write-Host "产物: $target" -ForegroundColor Green
Write-Host "SHA256: $hash" -ForegroundColor Green
Write-Host "(完整 dist 目录: $publishDir)"
