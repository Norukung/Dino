[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$CheckOnly,
    [switch]$Phase6Only
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$logDirectory = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$logPath = Join-Path $logDirectory ('device-check-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
$previousSerial = $env:ANDROID_SERIAL
$transcriptStarted = $false

function Invoke-Adb {
    param([string[]]$AdbArguments)
    # Windows PowerShell treats native stderr as an error record. Keep diagnostics
    # while checking the actual process exit status, including harmless daemon startup.
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $script:adbPath @AdbArguments 2>&1 | ForEach-Object { "$_" })
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $oldPreference }
    if ($code -ne 0) { throw "ADB failed ($code):`n$($lines -join "`n")" }
    return $lines
}

try {
    Start-Transcript -Path $logPath | Out-Null
    $transcriptStarted = $true
    Write-Host "Dinoblog Android device preflight. Log: $logPath"

    # ADB calls this native API on Windows, ignoring ANDROID_USER_HOME for its key path.
    # Diagnose an unloaded sandbox/service profile before ADB tries to mkdir \.android.
    if (-not ('Dinoblog.WindowsProfile' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
namespace Dinoblog {
    public static class WindowsProfile {
        [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
        public static extern int SHGetFolderPathW(IntPtr hwnd, int folder, IntPtr token,
            uint flags, StringBuilder path);
    }
}
'@
    }
    $profileBuffer = New-Object Text.StringBuilder 260
    $profileResult = [Dinoblog.WindowsProfile]::SHGetFolderPathW([IntPtr]::Zero, 0x28,
        [IntPtr]::Zero, 0, $profileBuffer)
    Write-Host ('Windows profile API: 0x{0:X8}; path: {1}' -f $profileResult, $profileBuffer)
    if ($profileResult -lt 0 -or [string]::IsNullOrWhiteSpace($profileBuffer.ToString())) {
        throw @'
Windows did not provide a user profile to this process. Native Windows ADB cannot
start in this account: changing ANDROID_USER_HOME does not fix its profile lookup.
Open a normal PowerShell window from the Windows Start menu as your usual user,
then run from C:\Users\tawan\Desktop\DinoBlog:
    .\test-device.ps1
Administrator rights and changing folder permissions are not required for this step.
See DEVICE_TESTING.md. No tests were executed by this command.
'@
    }

    $sdkPath = $null
    $localProperties = Join-Path $projectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($sdkLine) {
            $sdkPath = ($sdkLine -replace '^\s*sdk\.dir\s*=\s*', '').Replace('\:', ':').Replace('\\', '\')
        }
    }
    if (-not $sdkPath) { $sdkPath = $env:ANDROID_HOME }
    if (-not $sdkPath) { $sdkPath = Join-Path $profileBuffer.ToString() 'AppData/Local/Android/Sdk' }
    $script:adbPath = Join-Path $sdkPath 'platform-tools/adb.exe'
    if (-not (Test-Path -LiteralPath $script:adbPath)) { throw "ADB not found at $script:adbPath. Set sdk.dir in local.properties." }
    Invoke-Adb -AdbArguments @('version') | ForEach-Object { Write-Host $_ }
    $listing = @(Invoke-Adb -AdbArguments @('devices', '-l'))
    $listing | ForEach-Object { Write-Host $_ }
    $devices = @($listing | ForEach-Object {
        if ($_ -match '^(\S+)\s+(device|offline|unauthorized)(?:\s|$)') {
            [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
        }
    })
    if ($Serial) {
        $selected = @($devices | Where-Object { $_.Serial -eq $Serial })
        if ($selected.Count -ne 1) { throw "Device $Serial is not listed. Check USB debugging and the USB data cable." }
    } else {
        $selected = @($devices | Where-Object { $_.State -eq 'device' })
        if ($selected.Count -gt 1) { throw 'Multiple ready devices. Choose one with .\test-device.ps1 -Serial SERIAL.' }
        if ($selected.Count -eq 0) {
            if ($devices.State -contains 'unauthorized') { throw 'Unlock the phone and accept Allow USB debugging, then run this command again.' }
            if ($devices.State -contains 'offline') { throw 'ADB sees an offline device. Reconnect its USB cable or finish booting the emulator, then retry.' }
            throw 'ADB started, but no Android devices were found. Connect a phone with USB debugging, or start an AVD in Android Studio Device Manager.'
        }
    }
    $device = $selected[0]
    if ($device.State -ne 'device') { throw "Device $($device.Serial) is $($device.State). Unlock and authorize it, or reconnect before retrying." }
    $apiOutput = Invoke-Adb -AdbArguments @('-s', $device.Serial, 'shell', 'getprop', 'ro.build.version.sdk')
    $apiLevel = 0
    if (-not [int]::TryParse(($apiOutput -join '').Trim(), [ref]$apiLevel) -or $apiLevel -lt 26) {
        throw 'Dinoblog requires a ready Android 8.0 (API 26) or newer device.'
    }
    Write-Host "Ready: $($device.Serial), Android API $apiLevel."
    if ($CheckOnly) { Write-Host 'Preflight passed. No APK installed and no tests executed.'; return }

    # Limit Gradle to the selected target. The existing test suite uses separate test DBs.
    $env:ANDROID_SERIAL = $device.Serial
    $tasks = @(':app:connectedDebugAndroidTest')
    if ($Phase6Only) {
        $tasks += '-Pandroid.testInstrumentationRunnerArguments.class=com.group16.dinoblog.DeliveryReceiptDatabaseTest'
    }
    & (Join-Path $projectRoot 'build.ps1') -Tasks $tasks
    Write-Host 'Android instrumentation completed successfully.'
    Write-Host "Report: $(Join-Path $projectRoot 'app/build/reports/androidTests/connected/debug/index.html')"
    Write-Host 'Physical multi-phone Wi-Fi Direct acceptance is separate; follow PHASE6_DELIVERY_RECEIPTS.md.'
} catch {
    Write-Host "FAILED: $($_.Exception.Message)"
    throw
} finally {
    $env:ANDROID_SERIAL = $previousSerial
    if ($transcriptStarted) { Stop-Transcript | Out-Null }
}
