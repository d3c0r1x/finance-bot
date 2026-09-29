$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Script = Join-Path $Root "start_mobile_server.bat"
$TaskName = "FinPulse Mobile Server"

if (-not (Test-Path $Script)) {
    throw "Server launcher not found: $Script"
}

$Action = New-ScheduledTaskAction -Execute $Script -WorkingDirectory $Root
$Trigger = New-ScheduledTaskTrigger -AtLogOn
$Settings = New-ScheduledTaskSettingsSet -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $Trigger -Settings $Settings -Force | Out-Null
Write-Host "Autostart installed: $TaskName"
