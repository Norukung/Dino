# Phase 9: app identity, profile, onboarding, and navigation

## Implementation

- The immutable routing identity is still `DeviceIdentity.id`, stored in the existing `device_identity` SharedPreferences. Editing a DinoBlog name never changes Android's device name or this ID.
- `UserProfile(displayName, imagePath, onboardingCompleted, imageRevision)` is owned by `ProfileRepository`. The existing `chat_preferences` SharedPreferences stores `display_name` and `onboarding_completed`; the image uses the fixed app-private `files/profile.jpg` path. A new preference system or database was not added.
- `DisplayNames.normalize` removes ISO control characters, trims and collapses whitespace, permits Unicode, and accepts 1–32 Unicode code points. Invalid remote names fall back to `DinoBlog User`. Display names are not unique.
- The existing `peers` Room table remains keyed by `deviceId`. It now has nullable `localAlias`; `name` is the latest remote DinoBlog display name and `lastSeen` is retained. UI order is nonempty `localAlias`, then remote `name`, then `DinoBlog User`. Alias writes stay in Room on this phone and never enter a packet. Empty/whitespace-only alias saves are rejected; Remove nickname clears the column.
- Room is version 6. Migration 5→6 adds `localAlias` and replaces old hardware-derived peer names with the neutral fallback. Messages, receipts, predictability records, copy budgets, and routing metadata are left untouched. The database builder registers the migration; destructive migration is not used.
- HELLO now sends compact JSON such as `{"type":"HELLO","version":2,"deviceId":"<UUID>","displayName":"Nate","packetType":"HELLO"}`. `displayName` is optional, and old `deviceName` can still be decoded. Invalid optional name metadata cannot replace the UUID session identity. Profile image bytes and local aliases are never sent.
- A selected picture is decoded, oriented, center cropped to a square, scaled to at most 512×512, compressed as JPEG (quality 82, 68, or 52) to at most 1 MiB, and copied to app-private storage. Selection uses Android Photo Picker without storage permission. Missing/corrupt images show a fallback avatar.
- First launch uses five Compose onboarding steps. Completion and Skip persist the flag and selected or neutral name. The same explanation opens later from Help. Settings offers name and picture editing, removal, app version, and Help.
- The existing single Compose screen selector now drives a Material 3 navigation drawer: Home, Chats, Nearby devices, Settings, Help, and a separated Developer → Network Debug entry. Network Debug retains the DTN telemetry and manual UUID recipient/retry tools. Normal UI uses app names and hides UUIDs, hardware names, TTL/hop/copy inputs, predictability values, and packet details.
- Normal sends use the existing bounded policy: 86,400-second lifetime, hop limit 8, four initial spray copies, 100-message relay buffer, and forwarding margin 0.10. Phase 7 copy splitting and Phase 8 predictability math are unchanged. Carry control, online messaging, and media routing are not included.

## Files

Changed: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `DinoblogApplication.kt`, `AppViewModel.kt`, `DeviceIdentity.kt`, `TcpTransport.kt`, `WireProtocol.kt`, `DeliveryEngine.kt`, `DtnConfig.kt`, `Peer.kt`, `PeerDao.kt`, `DinoblogDatabase.kt`, `MessageRepository.kt`, `DinoblogApp.kt`, `HomeScreen.kt`, `ChatScreen.kt`, `PeerDiscoveryScreen.kt`, `NetworkDebugScreen.kt`, `WireProtocolTest.kt`, `SprayAndWaitDatabaseTest.kt`, `SmartRelayDemoSetupTest.kt`, and `README.md`.

Added: `DisplayNames.kt`, `ProfileRepository.kt`, `ProfileScreens.kt`, `Phase9IdentityTest.kt`, `Phase9HelloTest.kt`, `Phase9MigrationTest.kt`, `Phase9ProfilePersistenceTest.kt`, and the generated Room schema `6.json`.

## Verification

`build.ps1` passed `assembleDebug`, all 131 JVM tests (0 failures), `lintDebug`, and `assembleDebugAndroidTest`. The JVM suite covers the existing Phase 1–8 protocol/routing behavior plus Phase 9 naming and two-sided HELLO exchange. Android test sources compile. The on-device Room migration/profile persistence tests and physical Wi-Fi Direct workflow require a device or emulator with functioning ADB and cached Android test-runner dependencies. In this workspace `connectedDebugAndroidTest` stops before executing tests because the test-runner dependency cannot be downloaded from Google Maven or Maven Central under restricted network access. Thus a real Phase 8 database upgrade and physical-device behavior are still to be verified on a normal development machine.

## Manual test procedure

1. On two phones with existing Phase 8 data, record each Device ID in Network Debug, a saved conversation, receipt count, copy budget, and predictability entry. Install the Phase 9 APK over the old app without clearing app data. Open Network Debug and confirm the IDs and DTN records remain. Existing contact names may show the neutral fallback until the next HELLO.
2. On a fresh install, complete onboarding on A with `Nate` and B with `William`. Restart both apps. Confirm drawer/Settings retain the names and Network Debug shows the same immutable IDs.
3. Connect through Nearby devices. Confirm A shows William and B shows Nate; Network Debug must still show the opposite phone's UUID as `connectedPeerDeviceId`. Confirm the nearby discovery list does not show `SM-A245F`, a MAC address, or another hardware name in normal UI.
4. On A, open B's chat, choose Edit name, and save `P'William`. Confirm A's Home/Chat/Nearby pages use the alias and B has not received it. Change B's own name to `Will`, reconnect, and confirm A still shows `P'William`. Remove A's nickname and confirm it shows `Will`.
5. Set B and a third phone C to the same display name `Alex`. Connect both to A and confirm two distinct conversations and UUIDs in Network Debug; messages do not merge.
6. In Settings, select a large or rotated picture. Confirm the drawer and Settings show the square image after restart. Remove it and confirm the fallback avatar. Check HELLO/other packet capture for absence of image bytes and alias.
7. Reset app data on a test phone and confirm onboarding appears once. Complete it, restart, and confirm it does not reopen. Open How DinoBlog works from the drawer and Settings. Repeat fresh data and choose Skip; confirm the neutral default name.
8. Open/close the drawer, visit every destination, and confirm Network Debug is under Developer. Verify normal Chat/Settings cannot edit TTL, hop limit, copies, margin, or predictability. In Debug, enter a known UUID to open an offline chat and verify its message can still queue.
9. Repeat the existing Phase 1–8 multi-device checks: Wi-Fi Direct discovery, TCP HELLO, INVENTORY, direct send/ACK, delayed delivery, relay-to-relay forwarding, receipt suppression, binary split and WAIT, buffer-full handling, and PRoPHET-inspired SMART_SPRAY. Compare Debug metrics and routing decisions to the Phase 8 baseline.
