# Direct Chat 2.1 verification — 2026-10-06

App version: `2.1-chat`, versionCode `3`, package `com.group16.dinoblog`.

## Executed checks

Command: `./build.ps1`

| Check | Result |
|---|---|
| `:app:assembleDebug` | PASS, no compilation errors |
| `:app:testDebugUnitTest` | PASS, 11 tests, 0 failures, 0 errors |
| `:app:lintDebug` | PASS, 0 errors, 8 warnings about target SDK/tool/dependency versions |
| APK metadata | Version `2.1-chat`, versionCode `3` |

Final Gradle output: **BUILD SUCCESSFUL**.

## Coverage

5 codec tests cover UTF-8 bytes, Unicode/newline framing, fragmented/coalesced TCP data, length validation, truncated input and malformed UTF-8.

6 real localhost TCP tests cover:

- Two-way Hello Dinoblog compatibility.
- Free-text conversation with Thai, emoji, trailing spaces and newlines; matching histories on both phones with opposite sent/received directions.
- Repeated identical messages create distinct chat bubbles.
- Empty/oversized input leaves the connection healthy; failed sends do not create a misleading sent bubble.
- Receipt produces no ACK or handshake response.
- No offline queue or automatic retry; manual connection restart and listener cleanup still work.

Source inspection confirms the draft is held in the ViewModel, cleared only after successful send and retained on failure. UI changes were compiled and linted, not exercised on an Android device.

## Remaining hardware checks

This update has not been run on two physical phones during implementation. Install the new APK on both phones and follow README.md to verify Wi-Fi Direct, keyboard/composer layout, two-way chat, rotation and manual reconnect.

History is session-only (100 latest messages in process memory). SENT means local socket write succeeded; no remote-delivery ACK is implemented. Room, TTL, DTN and offline retry remain outside this version.

APK: `app/build/outputs/apk/debug/app-debug.apk`

Reports: `app/build/reports/tests/testDebugUnitTest/index.html` and `app/build/reports/lint-results-debug.html`.
