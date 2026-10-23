# Run Dinoblog Android tests on Windows

Actual Room persistence and migrations must also
run on Android. Use the existing connected phone or an Android 8.0/API 26+ emulator.

## Diagnosed ADB startup failure

On 2026-10-07 the agent process runs as `MSI\CodexSandboxOffline`. The native Windows
`SHGetFolderPathW(CSIDL_PROFILE)` call returns `0x80070002` (profile not found) with an
empty path. ADB then tries to create `\.android` and fails with permission denied.
This happens even for `adb version`, before USB device discovery.

Windows ADB obtains its profile path using that API, rather than the
`ANDROID_USER_HOME` variable used by SDK Java tools. Setting `USERPROFILE`,
`ANDROID_USER_HOME`, or `ANDROID_SDK_HOME` in the restricted process did not fix it.
This is an execution-account limitation; it is not evidence of a broken phone,
USB cable, application, or missing profile in the normal Windows user account.

Primary source: [Android ADB profile-path implementation](https://android.googlesource.com/platform/packages/modules/adb/+/1e3df013ae4a44229526a009853d418cd962285c/adb_utils.cpp).
General SDK directory settings: [Android environment variables](https://developer.android.com/tools/variables).

Run the prepared script in a normal PowerShell window opened from the Windows
Start menu, as the usual signed-in user. This moves device testing to a process
with a loaded user profile. Do not create a root-level `.android` directory, modify
registry/profile settings, or loosen system directory permissions to fix this error.

## One-command device test

Keep the phone connected by a USB data cable, unlock it, enable USB debugging,
and accept the phone's **Allow USB debugging** dialog if shown.

```powershell
cd C:\Users\tawan\Desktop\DinoBlog
.\test-device.ps1
```

The script resolves ADB from this project's SDK, checks the real Windows profile,
enumerates devices, checks Android API level, and runs the full existing Android
instrumentation suite for Phase 5. It uses `build.ps1` with the existing
workspace JDK, Gradle cache, and debug signing key. It restores process environment
overrides when it finishes and logs diagnostics to `.tools/device-check-*.log`.
It does not change machine/user environment variables or folder permissions.

For multiple attached devices, choose explicitly:

```powershell
.\test-device.ps1 -Serial SERIAL_FROM_DEVICE_LIST
```

Check the connection without installing/running anything:

```powershell
.\test-device.ps1 -CheckOnly
```

## Interpret the result

- `Windows profile API: 0x80070002`: this is still running in the restricted account;
  run from the ordinary desktop PowerShell window instead.
- `unauthorized`: unlock the phone and accept its USB debugging authorization dialog.
- `offline`: reconnect the cable or wait for the emulator to finish booting.
- No devices: check USB debugging/cable, or start an AVD in Android Studio Device Manager.
- `BUILD SUCCESSFUL`: inspect `app/build/reports/androidTests/connected/debug/index.html`
  and the test counts before claiming Android runtime acceptance.

The script fails rather than claiming success when no device is available or any
Gradle test task fails. The agent can read the generated log/report from this shared
workspace after the command finishes. Android tests on one device validate Room;
the [Phase 5 multi-phone procedure](PHASE5_EPIDEMIC.md)
still separately validates real Wi-Fi Direct encounters and disconnected delivery.
