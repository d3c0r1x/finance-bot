param(
    [Parameter(Position = 0)]
    [ValidateSet("help", "legacy-bot", "legacy-panel")]
    [string]$Mode = "help"
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $RepoRoot

if ($Mode -eq "help") {
    Write-Output "Usage: .\scripts\dev.ps1 legacy-bot|legacy-panel"
    Write-Output "SaaS Docker Compose profiles are not available yet; see docs/DEVELOPMENT.md."
    exit 0
}

$EntryPoint = if ($Mode -eq "legacy-bot") { "bot.py" } else { "panel.py" }
$VenvPython = Join-Path $RepoRoot ".venv\Scripts\python.exe"
if (Test-Path $VenvPython) {
    $PythonExecutable = $VenvPython
} elseif (Get-Command python -ErrorAction SilentlyContinue) {
    $PythonExecutable = "python"
} else {
    throw "Python 3.11 or 3.12 is required on PATH."
}

if (Get-Command infisical -ErrorAction SilentlyContinue) {
    & infisical run --env=dev -- $PythonExecutable $EntryPoint
} else {
    Write-Warning "Infisical not found. Starting with the process environment and ignored local .env file."
    & $PythonExecutable $EntryPoint
}
exit $LASTEXITCODE
