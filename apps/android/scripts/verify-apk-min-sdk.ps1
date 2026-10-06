param(
    [string]$ApkPath = (Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk'),
    [string]$AaptPath = (Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\aapt.exe'),
    [int]$MaximumMinSdk = 23
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "APK not found: $ApkPath"
}
if (-not (Test-Path -LiteralPath $AaptPath -PathType Leaf)) {
    throw "Android aapt not found: $AaptPath. Set ANDROID_HOME or pass -AaptPath."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$resolvedApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
$apkArgument = [System.IO.Path]::GetRelativePath($repoRoot, $resolvedApkPath)
if ([System.IO.Path]::IsPathRooted($apkArgument) -or $apkArgument.StartsWith('..')) {
    $apkArgument = $resolvedApkPath
}

Push-Location $repoRoot
try {
    $badging = & $AaptPath dump badging $apkArgument
    $aaptExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($aaptExitCode -ne 0) {
    throw "aapt could not inspect APK: $ApkPath"
}

$sdkLine = $badging | Where-Object { $_ -match "^sdkVersion:'\d+'$" }
if ($sdkLine -notmatch "^sdkVersion:'(?<api>\d+)'$") {
    throw 'APK manifest does not contain one valid sdkVersion entry.'
}

$actualMinSdk = [int]$Matches.api
if ($actualMinSdk -gt $MaximumMinSdk) {
    Write-Error "APK minSdk $actualMinSdk exceeds required maximum $MaximumMinSdk."
    exit 1
}

Write-Output "PASS: APK minSdk $actualMinSdk is compatible with required API $MaximumMinSdk floor."
