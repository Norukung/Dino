# DinoBlog 3.3 media DTN verification

- Checked-out baseline: Phase 11, Room schema 8, plus chat relay switch. The upgrade uses additive Room schema 9.
- JVM unit tests: 150 passed, 0 failures.
- Debug APK, lint, Android instrumentation APK: built successfully. Lint: 0 errors, 28 warnings.
- New JVM coverage: Wi-Fi device-name presentation, duplicate names and name changes, AUDIO/VIDEO manifest and binary-frame serialization, invalid metadata, and carrier forwarding.
- New Android instrumentation coverage: 8→9 migration preserving TEXT/IMAGE; AUDIO/VIDEO partial resume, hash-failure rejection, oversized rejection, and shared media relay quota.
- Android emulator (API 37): `connectedDebugAndroidTest` passed; 40 tests reported, 39 passed and one opt-in demo setup test skipped. An older Spray-and-Wait test used different random peer IDs for reservation and confirmation; its setup was corrected before the passing run.
- Relay follow-up: an initial source bundle with the four-copy spray budget may deposit one bounded branch at a willing carrier when PRoPHET does not prefer that carrier. Source keeps two copies; later relay choices still use destination scores. A real TCP A→B→C test covers unknown and poorer carrier scores, and a Room test confirms B's CARRIED row stays in inventory and C receives the original message ID. Another Room test confirms a TEXT branch with a lost ACK retries after reconnect with the same reserved copies.
- Follow-up for media stuck at 0%: the receiver accepts playable media when its local duration/dimensions differ from the sender's presentation metadata, provided file size and SHA-256 match. A real WAV transfer test covers this case; a JVM test covers sending all requested VIDEO chunks. Failed verification removes the invalid partial row; stale empty transfers are cleaned up, while partial transfers with valid chunks remain resumable. Media3 receives the MIME type explicitly and reports playback errors.
- Debug APK startup smoke test passed on the same emulator: `MainActivity` launched and the app process remained running.
- Physical Wi-Fi Direct three-phone encounters and audio/video playback on target hardware remain unverified.

See [AUDIO_VIDEO_DTN.md](AUDIO_VIDEO_DTN.md) for media constraints and exact three-device procedures.
