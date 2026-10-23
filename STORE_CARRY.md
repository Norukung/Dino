# Dinoblog 2.3.1 - first Store-Carry milestone

Current APK is **2.4-peer-identity** (header `PEER IDENTITY · 2.4`); see [Phase 2 HELLO](PHASE2_HELLO.md). The Store-Carry behavior/tests below still apply with both phones on 2.4. The file-change list below describes the earlier 2.3.1 milestone, not the latest update. The Debug peer label is now **Connected Peer Device ID**. Carried-message forwarding remains disabled.

Version: `2.3.1-store-carry`, versionCode `6`. Install the same APK on both phones.

## Scope

A creates a bundle for final destination C and explicitly selects its connected peer B as carrier. B durably stores it as `CARRIED`. After B's custody ACK, A records `FORWARDED`, **not** `DELIVERED`. B does not forward to C in this release, even if C connects. No routing algorithm, multi-hop exchange, cloud backend, authentication or background service was added.

Direct A/B chat, free-text bubbles, Room history, TTL, UUID deduplication, ACK and pending retries remain. Wi-Fi Direct discovery, group establishment, TCP connection/reconnection, framing and heartbeat implementation are unchanged. Protocol version 2 and Room schema 2 are unchanged from 2.3; protocol-1 APKs (2.2 and earlier) are incompatible. Use 2.3.1 on both phones for matching hop-admission rules.

## Install and connect two physical phones

1. Use two Wi-Fi Direct capable Android phones running Android 8.0/API 26 or later. Call them A and B; those labels do not determine which one Android chooses as group owner.
2. Build with `./build.ps1`, or install `app/build/outputs/apk/debug/app-debug.apk` on **both** phones. Install over the existing app with the same signing key; do not uninstall or clear storage. The header should show `STORE & CARRY · 2.3.1`.
3. Enable Wi-Fi and Location on both phones; turn off hotspot for this test. Internet/mobile data is not required. To demonstrate offline operation, disable mobile data and disconnect from Internet Wi-Fi while leaving the Wi-Fi radio enabled.
4. Open Dinoblog on both phones. Allow **Nearby devices** on Android 13+; allow **Location/Precise location** on Android 8-12L. Existing manifest/permission logic is unchanged.
5. Keep both apps in the foreground. Open **Peers**, then **Scan for peers** on both. On A, tap **Connect** for B. Accept the Android invitation on B when prompted. Avoid starting simultaneous invitations from both sides.
6. Wait for the P2P group and TCP `Connected`. Check **Debug > Connected Device ID**, not just the Wi-Fi connection status. A and B exchange persistent UUIDs during HELLO; the Wi-Fi Direct address and IP are not Device IDs.

If discovery fails, check permissions/Location, scan again, and disconnect an old group before retrying. A lost P2P group still requires manual scan/connect; TCP retries within an existing group remain automatic. Keep device clocks reasonably synchronized because expiration uses absolute Unix milliseconds.

## Test 1: verify normal direct chat first

1. On A, open **Chat > Choose peer > Connected: B**.
2. Leave **Store on connected carrier (A -> B for C)** unchecked.
3. Send `Direct hello from A`. A should change from `PENDING` to `DELIVERED`; B should see it in the A conversation as `RECEIVED`.
4. On B, choose A and reply. Confirm both directions still work.
5. Disconnect, queue another direct message to the saved peer, then reconnect. The pending message should retry automatically and appear only once on the receiver.

## Test 2: A -> B carrying a message for absent C

No third phone is needed: C is an absent logical destination for this milestone. Use this valid test UUID, ensuring it differs from the Device IDs of A and B:

```text
11111111-1111-4111-8111-111111111111
```

1. Connect A/B as above. On A open **Chat** and check **Store on connected carrier (A -> B for C)**.
2. The screen shows **Carrier B** with the connected phone's UUID. Verify it matches B's **Device > Device ID**.
3. Paste the UUID above into **Final destination C Device ID**. Do not paste B's ID here; B is only the carrier. If you already have C's real Device ID, you may use that instead, but no connection to C is attempted.
4. Leave **Hop limit** at `8` and TTL at `86400`. Enter `Hello C - please carry this on B` and send.
5. A's C conversation should display `PENDING`, then `FORWARDED`, with `hops 1/8`. `FORWARDED` means B acknowledged durable storage, not that C received anything.
6. On B open **Debug**. Find the `DB` log headed `DTN RECEIVE`, with full `A_UUID -> B_UUID`, `messageId=...`, `source=A_UUID`, `destination=C_UUID`, `currentNode=B_UUID`, `hopCount=1`, `hopLimit=8`, and `status=CARRIED`. This log is emitted only after Room commits successfully.
7. Scroll to **Stored messages** on B. Find the same message ID and payload, status `CARRIED`, source A, destination C, carrier B, hop count `1`, hop limit `8`, and creation/expiration times. You can use **Clear logs** to shorten the page; this only clears session logs, not stored messages.
8. B should **not** show this payload as an ordinary incoming A/B chat bubble, because it is addressed to C. The **Device > Saved messages** carried count and the Debug stored-message list expose carried data.
9. On A, Debug should show a custody ACK result and `FORWARDED`. Neither phone should claim `DELIVERED` to C.

Expected records for this one logical message:

| Device | sourceId | destinationId | hopCount | status |
|---|---|---|---|---|
| A before custody ACK | A | C | 0 (outgoing packet carries 1) | PENDING |
| A after custody ACK | A | C | 1 | FORWARDED |
| B after Room commit | A | C | 1 | CARRIED |

## Test 3: carry without a connection, and survive process death

1. Note the carried message ID on B. Use TTL `86400` so it will not expire during this test.
2. Disconnect A/B, then force-stop B from Android **Settings > Apps > Dinoblog > Force stop**, or reboot B. **Do not use Clear storage or uninstall.** Merely navigating away is not a reliable process-death test.
3. Reopen B without reconnecting A. Go to **Debug > Stored messages**. The same UUID, payload, source, destination, hop count and `CARRIED` status must still exist. Session logs may be empty: they intentionally live only in memory.
4. Reconnect A/B, wait at least 10 seconds, and use **Retry now**. There must still be one stored row for that UUID. Each press of Send creates a new UUID, so do not press Send again to test deduplication.
5. A should remain `FORWARDED`; B retains the bundle locally and does not initiate a connection to C. Deterministic lost-ACK/deduplication and simulated C-connection cases are also covered by unit tests; manually disconnecting cannot guarantee that an ACK was actually lost.

## Test 4: hop limit and expiration

- On A in carrier mode, set **Hop limit** to `1` and send a new message for C. The stored origin row is `PENDING` at hop 0, but the outgoing hop would be 1, equal to the limit. A logs `DTN REJECT: receiving hop reaches hopLimit` and does not transmit it; B must have no row for that UUID. It stays pending until expiration. Tests also inject hop `1/1` at B directly and verify no insert and no ACK. Unlike 2.3, arrival at the limit is now rejected for **new storage** as requested. Use limit `2` (or default `8`) for a successful first hop. Zero/invalid limits disable Send. B-to-C forwarding remains disabled regardless of remaining budget.
- Send another carried message with TTL `10` using **TTL: ... edit**, then keep B's app open for at least 15 seconds. B's row becomes `EXPIRED`, remains stored for inspection and is never forwarded. A's `FORWARDED` row is a historical custody receipt, not a live statement about B's current expiration state.
- A previously saved duplicate can be ACKed again after expiration, using its original UUID, because that acknowledges an earlier successful commit rather than a new insert. No lifetime is renewed and no forwarding is allowed. Unseen messages are rejected without storage/ACK at `now >= expiresAt` or `hopCount >= hopLimit`. Retransmissions do not reset `createdAt`/`expiresAt` or inflate `hopCount`.
- Return TTL to `86400` and uncheck carrier mode to resume ordinary direct chat. Choose the connected peer again as recipient.

## Files modified in the 2.3.1 update

Only these nine files were modified in this update (generated build outputs excluded):

1. `app/src/main/java/com/group16/dinoblog/dtn/DeliveryEngine.kt` - strict new-storage/next-hop boundary checks, optional carrier hint, DTN log fields.
2. `app/src/test/java/com/group16/dinoblog/DeliveryEngineTest.kt` - hop/expiry boundaries for direct and carried messages, optional-hint acceptance, complete log assertions; previous direct/retry tests retained.
3. `app/src/androidTest/java/com/group16/dinoblog/StoreCarryDatabaseTest.kt` - real engine-to-Room receive/deduplication/admission/reopen test and updated valid-hop fixture.
4. `app/build.gradle.kts` - versionCode 6 / versionName 2.3.1-store-carry only.
5. `app/src/main/java/com/group16/dinoblog/ui/DinoblogApp.kt` - version text only.
6. `app/src/main/java/com/group16/dinoblog/ui/PeerDiscoveryScreen.kt` - version text only.
7. `README.md` - current version and behavior summary.
8. `STORE_CARRY.md` - updated acceptance steps, boundary semantics and file list.
9. `VERIFICATION.md` - results and testing limitations.

Device identity, model, Room schema/migration/DAO/repository, wire protocol, Wi-Fi Direct, TCP transport, ViewModel and chat UI were inspected and reused without modification in 2.3.1. `DeviceIdentity.kt` already commits a UUID to app-private preferences; it does not derive identity from an IP address. The ID survives app restarts and updates, but uninstall/clear-storage starts a new installation. The existing chat filter includes only source/destination conversations involving this device, so a C-addressed carried message does not appear as an incoming message addressed to B.

## Existing 2.3 architecture (retained)

| File(s), relative to `app/src/main/java/com/group16/dinoblog/` | Change |
|---|---|
| `model/Message.kt` | Canonical DTN fields, hop budget, custody status and optional explicit `carrierId`; compatibility getters preserve existing UI access |
| `database/DinoblogDatabase.kt`, `DinoblogApplication.kt` | Room v1 -> v2 additive migration registered on the existing database builder |
| `database/MessageDao.kt`, `repository/MessageStore.kt`, `repository/MessageRepository.kt` | Select origin-owned pending messages for their chosen next peer, persist custody ACK and expire carried rows |
| `dtn/DeliveryEngine.kt` | Increment hop on the outgoing copy, store non-destination bundles before custody ACK, deduplicate, block hop exhaustion and all relay forwarding |
| `network/WireProtocol.kt` | DTN validation and distinct custody ACK in version-2 envelope; existing TCP framing retained |
| `viewmodel/AppViewModel.kt`, `ui/ChatScreen.kt` | Explicit carrier opt-in and hop limit; direct messaging is still the default |
| `ui/NetworkDebugScreen.kt`, `ui/HomeScreen.kt` | Persistent carried-message inspection and counts |
| `ui/DinoblogApp.kt`, `ui/PeerDiscoveryScreen.kt` | Version labels |

`WifiDirectManager.kt`, `TcpTransport.kt`, `MainActivity.kt`, Android permissions and Room's database filename are unchanged. Tests and the Gradle version/schema-assets settings were updated separately. No unrelated code was rewritten.

## Data and status semantics

The model contains `messageId` (UUID), `sourceId`, `destinationId`, `payload`, `createdAt`, `expiresAt`, `hopCount`, `hopLimit` and `status`. Legacy TTL seconds remain for the existing composer; `expiresAt = createdAt + ttl * 1000`. The optional `carrierId` persists the explicitly chosen first-hop peer for safe retries; it is not a routing table. A valid received first-hop bundle with no carrier hint can also be stored by a non-destination node. An explicit conflicting carrier hint is rejected. The sender UI still uses explicit carrier opt-in so ordinary direct messages are not unexpectedly sent to unrelated peers. Example labels such as `MSG001` and `DEVICE-A` are descriptive only; real IDs in the application are UUIDs.

Existing SQLite columns `senderId`, `receiverId`, `timestamp` are retained via `@ColumnInfo` mappings to `sourceId`, `destinationId`, `createdAt`. This avoids rebuilding the table. New columns are added and expiration is backfilled; historical delivered/received rows get hop count 1, other old rows get 0, and default hop limit is 8. There is **no destructive migration**. See [Android's Room migration guidance](https://developer.android.com/training/data-storage/room/migrating-db-versions).

| Status | Meaning |
|---|---|
| PENDING | Origin committed locally; waiting for the selected peer's ACK |
| CARRIED | Non-destination node committed the bundle to Room |
| FORWARDED | Origin received a custody ACK from the selected carrier; NOT final delivery |
| DELIVERED | Final destination acknowledged a direct message |
| RECEIVED | Existing direct-chat receiver status, retained for compatibility |
| EXPIRED | Pending/carried bundle lifetime elapsed; history remains, transmission stops |

Retries select only locally originated `PENDING` rows. `CARRIED` is excluded both by SQL and delivery-engine checks. A direct message is never opportunistically given to an unrelated peer. A carrier-targeted message retries to that selected carrier only, even if its final destination happens to connect.

The wire copy has hop count `stored hopCount + 1`; the origin keeps its queued count until the appropriate ACK atomically records the completed hop. The receiver stores that incoming count without incrementing again. Only source-to-first-hop messages are accepted; packets claiming to be second-hop forwards are rejected in this release.

Device IDs are identifiers, not authenticated identities. As before, this coursework protocol has no encryption, login or protection against a malicious peer impersonating an ID. Test with trusted devices only.

## Build and automated checks

```powershell
.\build.ps1
```

Runs assembleDebug, JVM tests, lintDebug and assembleDebugAndroidTest with the existing JDK 17 setup. Artifacts:

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- JVM report: `app/build/reports/tests/testDebugUnitTest/index.html`
- Lint report: `app/build/reports/lint-results-debug.html`
- Android test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

With an authorized USB-debugging Android device connected, run:

```powershell
.\build.ps1 -Tasks @(':app:connectedDebugAndroidTest')
```

This executes existing DAO tests plus migration v1 -> v2 validation, carried-row persistence across on-disk database close/reopen, carrier ACK guards and non-forwarding SQL selection. These tests use isolated test databases; they do not clear your app's conversations. Wi-Fi Direct discovery and two-phone behavior still require the manual hardware steps above. See `VERIFICATION.md` for what was actually executed in this workspace.
