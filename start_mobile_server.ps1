$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = Join-Path $Root ".venv\Scripts\python.exe"
$DataDir = Join-Path $Root "data\mobile"
$SecretFile = Join-Path $DataDir ".jwt-secret"
$HostAddress = $env:FINANCE_API_HOST
$Port = $env:FINANCE_API_PORT

if (-not $HostAddress) { $HostAddress = "0.0.0.0" }
if (-not $Port) { $Port = "8000" }

if (-not (Test-Path $Python)) {
    throw "Python venv not found. Run: python -m venv .venv; .\.venv\Scripts\python.exe -m pip install -r backend\requirements.txt"
}

New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
if (-not (Test-Path $SecretFile)) {
    $bytes = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    [Convert]::ToBase64String($bytes) | Set-Content -LiteralPath $SecretFile -Encoding ascii
}

$env:FINANCE_API_DATA = $DataDir
$env:FINANCE_JWT_SECRET = (Get-Content -LiteralPath $SecretFile -Raw).Trim()

$lan = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254*" -and $_.InterfaceAlias -notlike "*tun*" } |
    Select-Object -First 1 -ExpandProperty IPAddress

if ($lan) {
    Write-Host "Phone URL: http://$($lan):$Port"
}
Write-Host "Backend listening on $HostAddress`:$Port"
Write-Host "Health: http://127.0.0.1:$Port/api/v1/health"

Set-Location $Root
& $Python -m uvicorn backend.app:app --host $HostAddress --port $Port
