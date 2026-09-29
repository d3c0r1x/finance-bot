$ErrorActionPreference = "Continue"

$Lan = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254*" -and $_.InterfaceAlias -notlike "*tun*" } |
    Select-Object InterfaceAlias, IPAddress, PrefixLength

Write-Host "LAN addresses:"
$Lan | Format-Table -AutoSize

try {
    $Public = (Invoke-WebRequest -UseBasicParsing -Uri "https://api.ipify.org" -TimeoutSec 10).Content.Trim()
    Write-Host "Public IPv4: $Public"
    Write-Host "Compare this with WAN IPv4 in router settings."
    Write-Host "If router WAN IPv4 differs, provider likely uses CG-NAT and direct internet access to this PC will not work."
} catch {
    Write-Host "Could not read public IPv4. Check internet connection."
}

try {
    $Health = (Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:8000/api/v1/health" -TimeoutSec 5).Content
    Write-Host "Local backend health: $Health"
} catch {
    Write-Host "Backend not reachable on http://127.0.0.1:8000. Start it with start_mobile_server.bat."
}
