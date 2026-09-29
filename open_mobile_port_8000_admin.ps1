$ErrorActionPreference = "Stop"

$Name = "FinPulse Mobile API 8000"
$Existing = Get-NetFirewallRule -DisplayName $Name -ErrorAction SilentlyContinue
if (-not $Existing) {
    New-NetFirewallRule -DisplayName $Name -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000 | Out-Null
}

Write-Host "Firewall rule ready: TCP 8000 inbound."
