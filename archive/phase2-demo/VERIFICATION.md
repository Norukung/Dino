# Phase 2 verification — 2026-10-06

Build: **PASS**. APK version `2.0-phase2`, versionCode `2`, package `com.group16.dinoblog`.

## Executed checks

| Check | Result |
|---|---|
| Compile and assemble debug APK | PASS — no compilation errors |
| `:app:testDebugUnitTest` | PASS — 9 tests, 0 failures, 0 errors |
| `:app:lintDebug` | PASS — 0 errors, 8 warnings |
| `debugRuntimeClasspath` dependency inspection | No Room dependencies; no direct JSON serialization dependency/plugin |
| Active source inspection | No database/repository/DTN code, ACK protocol, TTL, pending queue or automatic retry |

The app was rebuilt with `--rerun-tasks`. The initial test run found an incorrect expected byte count in a test (13 instead of 14 for `Hello Dinoblog`); the assertion and documentation were corrected, and the final `build.ps1` run passed all tasks. There was no Kotlin compilation error in Phase 2.

AndroidX may bring serialization-core transitively for its own internals; the TCP protocol does not use it. Lint warnings concern target SDK and dependency/Gradle version updates. No lint baseline was added.

## Test coverage

`TextFrameCodecTest` — 5 tests:

- Exact UTF-8 bytes for `Hello Dinoblog` (14-byte payload after a 4-byte length prefix).
- Unicode, emoji, newline and consecutive messages arriving in single-byte fragments.
- Invalid/oversized byte lengths.
- Truncated frame rejected before display.
- Malformed UTF-8 rejected.

`TcpTransportTest` — 4 tests, using real TCP sockets on localhost:

- A sends `Hello Dinoblog`, B's received StateFlow contains it; B can send back to A; send after disconnect fails.
- A raw socket sends text and the receiver exposes it without returning ACK/handshake bytes.
- A failed client does not automatically reconnect when the server appears; repeating the same start event does not retry; explicit manual start works and no offline message is queued.
- Closing a listening server releases its port so an explicit new connection can start.

## Artifacts

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Tests: `app/build/reports/tests/testDebugUnitTest/index.html`
- Lint: `app/build/reports/lint-results-debug.html`

## Hardware verification still needed

The Phase 2 APK has not been installed/run on two physical phones during this implementation. Host socket tests verify the receive state used by Compose but do not prove rendered UI, Android permissions, Wi-Fi Direct group formation, or OEM behavior.

Install this APK on **both** phones, follow README.md, and confirm B displays `Hello Dinoblog` after A taps **Send Hello Dinoblog**. The screen header must say **PHASE 2 · DIRECT TCP**. If the client connects before the server is ready, wait for **Listening on 8988**, then explicitly tap **Start TCP** on the client.

Prior source files/tests/documentation were preserved in `archive/previous-full-version/` outside Gradle source sets. No previous on-device database was deleted; it is not opened by this version.
