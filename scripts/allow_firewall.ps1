<#
.SYNOPSIS
  为 LanAudioStream 添加 Windows 防火墙入站规则（需要管理员权限）。
.DESCRIPTION
  开放 UDP 47654（设备发现）与 UDP 47655（默认音频数据端口）。
  如果修改过桌面端端口，请同步修改 -StreamPort。
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\allow_firewall.ps1
#>
param(
    [int]$DiscoveryPort = 47654,
    [int]$StreamPort = 47655
)

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "请以管理员身份运行 PowerShell 后重试。" -ForegroundColor Red
    exit 1
}

foreach ($rule in @(
    @{ Name = "LanAudioStream Discovery"; Port = $DiscoveryPort },
    @{ Name = "LanAudioStream Audio";     Port = $StreamPort }
)) {
    $existing = netsh advfirewall firewall show rule name="$($rule.Name)" 2>$null
    if ($LASTEXITCODE -eq 0) {
        netsh advfirewall firewall delete rule name="$($rule.Name)" | Out-Null
    }
    netsh advfirewall firewall add rule name="$($rule.Name)" dir=in action=allow protocol=UDP localport=$($rule.Port) profile=private,domain | Out-Null
    Write-Host "已添加规则 $($rule.Name) (UDP $($rule.Port))" -ForegroundColor Green
}
