[CmdletBinding()]
param(
    [string]$Avd = 'Medium_Phone',
    [switch]$SkipBuild,
    [ValidateRange(30, 900)][int]$BootTimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$sdkPath = $env:ANDROID_HOME
$properties = Join-Path $projectRoot 'local.properties'
if (Test-Path -LiteralPath $properties) {
    $sdkLine = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
    if ($sdkLine) { $sdkPath = ($sdkLine -replace '^\s*sdk\.dir\s*=\s*', '').Replace('\:', ':').Replace('\\', '\') }
}
if (-not $sdkPath) { $sdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adbPath = Join-Path $sdkPath 'platform-tools/adb.exe'
$emulatorPath = Join-Path $sdkPath 'emulator/emulator.exe'
foreach ($tool in @($adbPath, $emulatorPath)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Android SDK tool missing: $tool" }
}

function Invoke-Adb {
    param([string[]]$AdbArguments)
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $adbPath @AdbArguments 2>&1 | ForEach-Object { "$_" })
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($code -ne 0) {
        throw "ADB failed ($code): $($output -join [Environment]::NewLine). If Windows cannot locate its profile or create .android, run this script in a normal PowerShell window opened from the Start menu."
    }
    return $output
}

function Find-AvdSerial {
    foreach ($line in @(Invoke-Adb -AdbArguments @('devices'))) {
        if ($line -match '^(emulator-\d+)\s+device\s*$') {
            $candidate = $Matches[1]
            $name = @(Invoke-Adb -AdbArguments @('-s', $candidate, 'emu', 'avd', 'name'))
            if ($name -contains $Avd) { return $candidate }
        }
    }
    return $null
}

# Check ADB before launching anything, so an unusable Windows profile fails cleanly.
Invoke-Adb -AdbArguments @('devices', '-l') | ForEach-Object { Write-Host $_ }
$available = @(& $emulatorPath -list-avds)
if ($LASTEXITCODE -ne 0 -or $available -notcontains $Avd) {
    throw "AVD '$Avd' not found. Available: $($available -join ', '). Create an Android API 26+ AVD in Android Studio Device Manager, or pass -Avd NAME."
}
if (-not $SkipBuild) {
    & (Join-Path $projectRoot 'build.ps1') -Tasks @(':app:assembleDebug', '--offline', '--no-daemon')
}
$apk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'APK missing. Run without -SkipBuild first.' }

$serial = Find-AvdSerial
$process = $null
if (-not $serial) {
    if ($Avd -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Unsupported AVD name. Use letters, numbers, underscores, dots or hyphens.' }
    Write-Host "Opening Android Emulator: $Avd"
    # The emulator window is intentionally visible: this script launches the user's app preview.
    $process = Start-Process -FilePath $emulatorPath -ArgumentList @('-avd', $Avd) -PassThru
}
$deadline = [DateTime]::UtcNow.AddSeconds($BootTimeoutSeconds)
$booted = $false
while ([DateTime]::UtcNow -lt $deadline) {
    if ($process -and $process.HasExited) { throw "Emulator exited with code $($process.ExitCode). Open Android Studio Device Manager to inspect the AVD." }
    if (-not $serial) { $serial = Find-AvdSerial }
    if ($serial) {
        $boot = @(Invoke-Adb -AdbArguments @('-s', $serial, 'shell', 'getprop', 'sys.boot_completed'))
        if (($boot -join '').Trim() -eq '1') { $booted = $true; break }
    }
    Start-Sleep -Seconds 2
}
if (-not $booted) { throw "AVD did not finish booting within $BootTimeoutSeconds seconds. It has been left open for inspection." }
$api = (@(Invoke-Adb -AdbArguments @('-s', $serial, 'shell', 'getprop', 'ro.build.version.sdk')) -join '').Trim()
if ([int]$api -lt 26) { throw "Dinoblog requires API 26+, but this emulator uses API $api." }

# Replace the APK while retaining app data, messages, device identity and routing tables.
Invoke-Adb -AdbArguments @('-s', $serial, 'install', '-r', $apk) | ForEach-Object { Write-Host $_ }
$launch = @(Invoke-Adb -AdbArguments @('-s', $serial, 'shell', 'am', 'start', '-W', '-n', 'com.group16.dinoblog/.MainActivity'))
$launch | ForEach-Object { Write-Host $_ }
if ($launch -match '^Error') { throw 'Android could not launch Dinoblog; see the output above.' }
Write-Host "Dinoblog launched on $serial. Accept the permission prompt in the emulator if shown."
Write-Host 'Use physical phones for Wi-Fi Direct encounter tests. This is the existing native Kotlin/Compose app; Flutter is not required.'
