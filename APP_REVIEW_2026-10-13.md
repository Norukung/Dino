# Dinoblog application review — 2026-10-13

Reviewed the current native Kotlin/Jetpack Compose Android application (3.0,
Room schema 5). This is a source review plus automated verification, not a claim
that every behavior has been exercised on Android hardware.

## Findings and changes

1. **Fixed: cancelled routing-summary send could block smart relaying for the
   rest of a connection.** `DeliveryEngine.sendPredictability` set
   `summarySending=true` before the socket write but only reset it after a normal
   return. A coroutine cancelled during the write could leave it true, preventing
   a retry. Cleanup now runs in `finally`, guarded by the original peer/session.
   The new `cancelledSummaryWriteCanRetryInTheSameSession` test failed before the
   fix and passes after it. It also checks that retrying does not count another
   encounter. Duplicate summaries remain idempotent at the receiver.
2. **Fixed: stale/misleading UI guidance.** Peers no longer instructs users to
   install version 2.7. Chat now explains that relay forwarding requires a
   meaningfully better carrier and that newly installed devices need encounter
   history. No routing algorithm or protocol was replaced.
3. **Existing scale limit remains:** `InventoryManager.build` refuses a complete
   inventory above 1,000 combined message and receipt IDs. Receipts are retained
   indefinitely, so a long-running installation can eventually stop encounter
   synchronization at this limit. The Debug screen reports the inventory error.
   No IDs are silently discarded. Pagination or a separately designed safe
   retention policy is needed before large deployments; this review does not
   delete tombstones or change that protocol.
4. **Build environment fix:** forcing all build tasks exposed an access-denied
   error in AGP's default debug-keystore creation lock, including with a fresh
   daemon. The helper now supplies an explicit copy of the existing debug key
   through `dinoblog.debugKeystore`; Gradle keeps normal signing verification.
   It checks byte equality before reuse and never generates a replacement for
   an existing key. Direct Gradle/Android Studio builds retain their default
   behavior unless that property is supplied.

## Areas inspected

| Area | Review and available verification |
| --- | --- |
| Startup/lifecycle/permissions | Application container, identity persistence, manifest, foreground start/stop, runtime permissions, backup exclusion reviewed. Actual Android startup pending. |
| Wi-Fi Direct | Discovery, connect/group callbacks, disconnect and lifecycle generation guards reviewed. Physical peer discovery still requires phones. |
| TCP and packet protocol | HELLO/session identity, bounded frames, serialized writes, reconnect, ACK/retry and metadata validation reviewed; JVM transport tests pass. |
| Storage | Entities, DAO queries, transactions and additive migrations 1–5 reviewed. Real Room instrumentation compiles but has not run here. |
| DTN routing | Inventory gates, receipt ordering/tombstones, stale sessions, copy reservation, duplicate handling, buffer rejection and smart score checks reviewed; regression suites pass. |
| Predictability | Direct encounter, lazy aging, transitivity, bounded summaries, destination-specific margin and session cleanup covered by unit/TCP tests. |
| UI/ViewModel | Device, Peers, Chat and Debug screens, input validation, saved history and source statuses reviewed in code. Visual/keyboard/rotation testing pending. |
| Build/static checks | Debug application and instrumentation APKs compile. Lint reports no errors. |

Networking is deliberately foreground-only. An unsupported Wi-Fi Direct device
can still display the UI, but it cannot demonstrate actual nearby DTN encounters.
Emulator UI checks do not replace the multi-phone acceptance procedure in
`PHASE8_SMART_RELAYS.md`.

## Verification

Successful command:

```powershell
.\build.ps1 -Tasks ':app:assembleDebug',':app:testDebugUnitTest',':app:lintDebug',':app:assembleDebugAndroidTest','--offline','--no-daemon','--rerun-tasks'
```

- **127 JVM tests passed**, 18 suites, 0 failures, 0 errors.
- **Lint: 0 errors, 2 warnings**: existing `OldTargetApi` (targetSdk 35) and
  `UseKtx` in `AppViewModel`. Neither is a compilation error; changing the target
  SDK should accompany platform behavior testing.
- Updated app APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Instrumentation APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- Earlier incremental build passed, but forced signing validation exposed the
  lock problem described above; a fresh daemon alone did not fix it.
- APK signature verification passes. Before/after SHA-256 certificate digest is
  identical: `4af79e64defb869c3bf57f83918bea9d903000a33423cf574117565415a493e3`.
- No Android instrumentation, real SQLite migration/reopen, Wi-Fi Direct phone
  encounter, or visual UI execution is claimed from this review.

## Emulator attempt and launch helper

Flutter is installed, but this project has no `pubspec.yaml` and is a native
Android application. Android Emulator runs its APK directly; no Flutter rewrite
is needed.

Installed AVD: `Medium_Phone`. Emulator acceleration check reports WHPX usable.
An isolated workspace AVD was attempted so the existing AVD's data was preserved.
It failed repeatedly trying to write a feature-flags lock under the Windows
profile (access denied). The two emulator processes started by this review were
stopped. No app was installed or launched in that attempt.

ADB also fails before device discovery: Windows `SHGetFolderPathW` returns
`0x80070002` with no profile, and native ADB reports `Cannot mkdir '\.android':
Permission denied`. This environment cannot complete the emulator launch.
Changing app code or converting to Flutter would not resolve this Windows issue.

Open a **normal PowerShell window from the Windows Start menu**, then run:

```powershell
Set-Location C:\Users\tawan\Desktop\DinoBlog
.\run-emulator.ps1 -SkipBuild
```

The helper checks ADB, opens or reuses `Medium_Phone`, waits for boot, installs
the existing APK with `-r` (preserving app data), and starts `MainActivity`.
It does not uninstall/wipe app data. Accept any app permission prompt in the
emulator. For another configured AVD, pass `-Avd NAME`; omit `-SkipBuild` to
build first using the existing offline cache.

PowerShell syntax validation passed. Executing the helper in this session
correctly stopped at the ADB failure above. Its successful Android launch path
is still unverified. Once an Android target is available, use
`.\test-device.ps1 -Serial SERIAL` for instrumentation.

Changed: `DeliveryEngine.kt`, `SmartRelayEngineTest.kt`, `PeerDiscoveryScreen.kt`,
`ChatScreen.kt`, `app/build.gradle.kts`, `build.ps1`, `README.md`, `VERIFICATION.md`.
Added: this report and `run-emulator.ps1`. Database schema and wire protocol unchanged.
