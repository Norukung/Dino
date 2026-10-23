# Verification — 2026-10-06

## Build result

Command: `./build.ps1`

Environment: Windows, Eclipse Temurin JDK 17.0.20.1, Gradle 8.13, AGP 8.11.1, Kotlin 2.1.20, compile SDK 36, target SDK 35, min SDK 26.

| Check | Result |
|---|---|
| `:app:assembleDebug` | PASS — debug APK generated |
| `:app:testDebugUnitTest` | PASS — 15 tests, 0 failures, 0 errors |
| `:app:lintDebug` | PASS — 0 errors, 16 warnings |
| `:app:assembleDebugAndroidTest` | PASS — instrumentation test APK compiled |

Final Gradle run: **BUILD SUCCESSFUL**. Lint warnings concern pinned dependency/Gradle versions and targeting API 35 instead of the latest Android API. No lint baseline or global error suppression is used. The three local MissingPermission annotations in WifiDirectManager document the shared runtime permission guard and SecurityException handling that lint cannot follow through a higher-order function.

## Automated coverage

| Suite | Tests | What it verifies |
|---|---:|---|
| DeliveryEngineTest | 9 | Offline persistence through engine recreation, reconnect retry, correct-peer ACK, deduplication, commit before ACK, disk failure without ACK, TTL expiry, expired duplicate re-ACK, no forwarding to third parties, UUID collision rejection |
| WireProtocolTest | 5 | Unicode/newlines and consecutive frames fragmented into single bytes, frame size bounds, payload/UUID/version validation, truncated frames, TTL arithmetic bounds |
| TcpTransportTest | 1 | Real localhost TCP sockets: two-sided HELLO, message, ACK, disconnect and reconnect |

The delivery tests use a fake MessageStore and deterministic time. They verify delivery logic, not Android Room persistence. The protocol and TCP tests run on the host JVM. The loopback test does not exercise Android Wi-Fi Direct.

## Artifacts

- App APK: `app/build/outputs/apk/debug/app-debug.apk`
- Instrumentation APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- Unit test report: `app/build/reports/tests/testDebugUnitTest/index.html`
- Lint report: `app/build/reports/lint-results-debug.html`
- Exported Room schema: `app/schemas/com.group16.dinoblog.database.DinoblogDatabase/1.json`

## Still requires Android devices

`adb devices` reported no connected devices. Therefore the following are **not yet verified on hardware**:

- Actual Wi-Fi Direct discovery, invitation acceptance, group formation, socket routing and A ↔ B delivery.
- Runtime permission prompts, UI rendering, rotation/background/resume and OEM-specific Wi-Fi behavior.
- Persistence across real app/process/phone restarts.
- `:app:connectedDebugAndroidTest`: Room/SQLite DAO instrumentation test is compiled but has not been executed.

Follow the two-phone checklist in README.md before presenting the project demo. Keep both apps in the foreground; when the Wi-Fi Direct group is removed, reconnect it from the Peers screen. TCP retry and message retry then run automatically.
