param(
    [string]$DeviceSerial = "emulator-5554",
    [string]$ExpectedAvdName = "CodexFinanceBotApi27"
)

$ErrorActionPreference = "Stop"
$appPackage = "com.decorix.finance.debug"
$testClass = "com.decorix.finance.AndroidMutationCheckpointProcessDeathTest"
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
$unityJdk = "C:\Program Files\Unity\Hub\Editor\6000.6.3f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK"

if (-not (Test-Path $adb)) { throw "Android adb was not found under $sdkRoot" }
if (-not $env:JAVA_HOME -and (Test-Path (Join-Path $unityJdk "bin\java.exe"))) {
    $env:JAVA_HOME = $unityJdk
}
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    throw "Set JAVA_HOME to a Java 17 installation before running this check."
}
$env:ANDROID_HOME = $sdkRoot
$env:PATH = "$env:JAVA_HOME\bin;$sdkRoot\platform-tools;$env:PATH"

$connectedDevices = @(& $adb devices -l | Select-String '^\S+\s+device(?:\s|$)')
if ($connectedDevices.Count -ne 1 -or $connectedDevices[0].Line -notmatch "^$([regex]::Escape($DeviceSerial))\s+device\s") {
    throw "This isolated check requires only $DeviceSerial connected; found: $($connectedDevices -join ', ')"
}
$api = (& $adb -s $DeviceSerial shell getprop ro.build.version.sdk).Trim()
if ($api -ne "27") { throw "Expected isolated Android API 27 emulator, found API $api." }
$isEmulator = (& $adb -s $DeviceSerial shell getprop ro.kernel.qemu).Trim()
if ($DeviceSerial -notmatch '^emulator-' -or $isEmulator -ne "1") {
    throw "Refusing to run destructive force-stop check on a non-emulator device ($DeviceSerial, ro.kernel.qemu=$isEmulator)."
}
$avdNameOutput = @(& $adb -s $DeviceSerial emu avd name)
$avdName = if ($avdNameOutput.Count -gt 0) { $avdNameOutput[0].Trim() } else { "" }
if ($LASTEXITCODE -ne 0 -or $avdName -ne $ExpectedAvdName) {
    throw "Refusing force-stop outside the dedicated AVD '$ExpectedAvdName'; connected AVD is '$avdName'."
}

$androidDir = Split-Path -Parent $PSScriptRoot
function Invoke-CheckpointTest([string]$method) {
    $filter = "-Pandroid.testInstrumentationRunnerArguments.class=$testClass#$method"
    Push-Location $androidDir
    try {
        & .\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --console=plain $filter
        if ($LASTEXITCODE -ne 0) { throw "Instrumentation phase $method failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
}

$failure = $null
try {
    Invoke-CheckpointTest "test01SeedSyntheticRepeatAndVoid"

    & $adb -s $DeviceSerial shell am start -W -n "$appPackage/com.decorix.finance.MainActivity"
    if ($LASTEXITCODE -ne 0) { throw "Could not start MainActivity before the force-stop check." }
    $wasRunning = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $targetProcessId = (& $adb -s $DeviceSerial shell pidof $appPackage 2>$null | Out-String).Trim()
        if ($targetProcessId) { $wasRunning = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $wasRunning) { throw "MainActivity did not start before force-stop; no process death was observed." }
    $pidBeforeStop = $targetProcessId

    & $adb -s $DeviceSerial shell am force-stop $appPackage
    if ($LASTEXITCODE -ne 0) { throw "Could not force-stop the isolated test app." }
    $targetProcessId = (& $adb -s $DeviceSerial shell pidof $appPackage 2>$null | Out-String).Trim()
    if ($targetProcessId) { throw "The target process is still running after force-stop: $targetProcessId" }

    & $adb -s $DeviceSerial shell am start -W -n "$appPackage/com.decorix.finance.MainActivity"
    if ($LASTEXITCODE -ne 0) { throw "Could not relaunch MainActivity after force-stop." }
    $started = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $targetProcessId = (& $adb -s $DeviceSerial shell pidof $appPackage 2>$null | Out-String).Trim()
        if ($targetProcessId) { $started = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $started) { throw "MainActivity did not start a fresh target process." }
    $pidAfterLaunch = $targetProcessId

    Invoke-CheckpointTest "test02RecoverExactSnapshotsAfterHostForceStop"
    Write-Host "PASS: encrypted synthetic repeat/void checkpoints survived an OS force-stop and fresh app launch on API 27 (PID $pidBeforeStop -> $pidAfterLaunch)."
} catch {
    $failure = $_.Exception.Message
} finally {
    try {
        Invoke-CheckpointTest "test03CleanupSyntheticState"
    } catch {
        if (-not $failure) { $failure = $_.Exception.Message }
        else { Write-Warning "Test-only encrypted fixture cleanup also failed: $($_.Exception.Message)" }
    }
}

if ($failure) { throw $failure }
