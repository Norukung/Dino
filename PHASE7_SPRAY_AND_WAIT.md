# DTN Phase 7 — Binary Spray-and-Wait and bounded relay storage

Dinoblog 2.9 extends the Phase 6 implementation. MESSAGE identity, Wi-Fi Direct, TCP framing/writer, HELLO, INVENTORY, per-hop ACKs, receipt anti-entropy/tombstones, the existing repository/ViewModel and direct delivery remain in use. Phase 7 replaces automatic epidemic replication with a finite binary budget. No Phase 8 routing was added.

## Changed files

Production files under `app/src/main/java/com/group16/dinoblog/`:

- `model/Message.kt`: persistent branch-local `copiesRemaining`, default 1 for missing/legacy metadata.
- `model/ForwardingState.kt`: DIRECT/SPRAY/WAIT classification and session debug fields.
- `database/DinoblogDatabase.kt`: schema 4 and additive `MIGRATION_3_4`.
- `database/MessageDao.kt`: conditional budget reservation/restoration, relay quota and safe reclamation queries, inventory validation of budgets.
- `repository/MessageStore.kt`: atomic reservation API, reservation result and definitive buffer-full exception.
- `repository/ReceiptStore.kt`: existing atomic admission accepts time/quota parameters.
- `repository/MessageRepository.kt`: transaction-based reservations, quota admission, housekeeping and guarded restoration.
- `network/WireProtocol.kt`: MESSAGE copy budget validation; `MESSAGE_REJECT` / `BUFFER_FULL`.
- `dtn/DeliveryEngine.kt`: finite source initialization, DIRECT/SPRAY/WAIT decisions, reservation at the existing writer boundary, identical-branch retry, sticky rejection and session cleanup.
- `DinoblogApplication.kt`: register 3→4 migration; pass existing logger into the repository.
- `ui/NetworkDebugScreen.kt`, `ui/DinoblogApp.kt`: budget/mode/buffer/rejection debug information and version label. Main chat layout remains unchanged.

Other modified files:

- `app/build.gradle.kts`: versionCode 12, versionName `2.9-spray-and-wait`.
- `README.md`, `VERIFICATION.md`, `test-device.ps1`: current Phase 7 entry point and hardware demo link.
- Existing JVM regression tests: `DeliveryEngineTest.kt`, `Phase4TestStore.kt`, `ReceiptTestStore.kt`, `DestinationForwardingTest.kt`, `EpidemicRoutingTest.kt`, `EpidemicTcpTest.kt`, `StoreCarryForwardTcpTest.kt`, `DeliveryReceiptTest.kt`. Memory stores model durable reservations; assertions now account for branch budgets. The old B→E epidemic branch test now asserts WAIT suppression after B spent its spray budget. UUID, ACK/retry, identity, expiry, direct delivery and hop-depth checks remain.
- Existing Android tests: `StoreCarryDatabaseTest.kt`, `DeliveryReceiptDatabaseTest.kt` now finish migration validation at schema 4.

New files:

- `app/src/main/java/com/group16/dinoblog/dtn/DtnConfig.kt`
- `app/src/test/java/com/group16/dinoblog/SprayAndWaitTest.kt`
- `app/src/test/java/com/group16/dinoblog/SprayAndWaitTcpTest.kt`
- `app/src/androidTest/java/com/group16/dinoblog/SprayAndWaitDatabaseTest.kt`
- `app/schemas/com.group16.dinoblog.database.DinoblogDatabase/4.json`
- `PHASE7_SPRAY_AND_WAIT.md` (this report).

No new database, message model, socket manager, inventory manager, routing engine or network stack was introduced. Phase 6's `sendIfCurrent` hook and TCP write mutex are reused without modifying the transport.

## Configuration and migration

`DtnConfig.kt` defines policy in one place:

```kotlin
INITIAL_SPRAY_COPIES = 4
MAX_RELAY_BUFFER_MESSAGES = 100
MAX_SPRAY_COPIES = 255 // wire validation bound
```

The existing `messages` table gains `copiesRemaining INTEGER NOT NULL DEFAULT 1`. The database advances from version 3 to 4 using `MIGRATION_3_4`; the existing 1→2 and 2→3 migrations remain registered. There is no destructive migration. All historical rows, including old outgoing rows, conservatively enter WAIT with 1 copy. They do not receive new spray budgets on upgrade/restart.

Only `DeliveryEngine.queue` explicitly initializes a newly authored message to `INITIAL_SPRAY_COPIES`. A missing MESSAGE wire field defaults to 1. Install the Phase 7 binary on every participating phone: older epidemic-routing binaries do not enforce the new budget. The existing wire envelope remains protocol version 2 and includes the extra field inside the MESSAGE object; inventory still exchanges IDs rather than budgets.

Each local branch retains one Room row and the original messageId/sourceId/destinationId. Copies are routing permission, not duplicate local database rows. Restart reads the persisted value; it never resets to 4.

## Binary splitting, WAIT and direct delivery

The pure `DeliveryEngine.classify` decision first rejects invalid status/metadata, local-final content, expired messages and exhausted hop limits. Session/inventory/tombstone/peer-known checks remain in the existing coordinator. Eligible messages then classify as:

- DIRECT if the peer is the actual destination, even with copies=1.
- SPRAY if the peer is another carrier and copies>1.
- WAIT if the peer is another carrier and copies=1; no new relay transmission occurs.

A SPRAY branch with L copies gives `floor(L/2)` and keeps `L-floor(L/2)`:

| Before | Sender keeps | Receiver receives |
|---|---:|---:|
| 4 | 2 | 2 |
| 3 | 2 | 1 |
| 2 | 1 | 1 |
| 1 | 1 | No relay send |

DIRECT performs no binary split. The destination's row remains locally final and creates the existing Phase 6 receipt after atomic successful acceptance.

Outgoing hopCount increments by one; the retained row keeps its previous hopCount. For example A hop=0/copies=4 → B hop=1/copies=2, then B hop=1/copies=1 → C hop=2/copies=1. Branch budgets are never globally synchronized. Copies do not override expiry or hopLimit.

## Conservative accounting and retry

`MessageRepository.reserveSpray` runs a Room transaction that loads the current row, checks expected budget, routing validity and the receipt table, calculates the split, and conditionally persists the retained budget. A compare-and-update guard prevents two observers from reserving the same budget twice. Unsupported stores fail closed for relay reservation instead of permitting an unaccounted spray.

`DeliveryEngine.sendCandidate` invokes reservation inside Phase 6's existing conditional TCP send hook, after waiting for the socket writer mutex and before any frame bytes are written. It rechecks peer/session, inventory, peer knowledge/rejection, immutable message identity, expiry, hop limit, routing authority and tombstones. If the row budget changed while queued, the expected-budget guard skips the stale candidate.

The attempt records the immutable outgoing branch, given/kept counts, peer and session before writing, so an immediate ACK/rejection can resolve it. Lost-ACK retries use the same message UUID, outgoing hopCount and copies-to-give. They never perform a second reservation for that attempt. A retained copies=1 row can retry this already-reserved branch in the same session; it cannot originate another relay branch. Retry remains on the existing five-second cadence and serialized coordinator.

On ambiguous write failure, disconnect, process death, or a queued send cancelled after reservation, the local reduction is retained. Session attempts are abandoned; no uncertain budget is recreated. This can sacrifice unused spray opportunities, which is the chosen safety tradeoff. The sender always retains at least one copy and can still deliver directly. A later inventory repairs lost ACK knowledge if the peer did persist the branch.

For definitive BUFFER_FULL only, the sender may restore exactly the given budget using a conditional Room update against the retained count and valid status, with no tombstone. The attempt is removed and its message ID is marked rejected before restoration; duplicate/stale rejection packets cannot restore again. An altered/delivered row prevents restoration. No other failure triggers budget restoration.

## Duplicate handling and receipts

`sameBundle` now treats copiesRemaining, like hopCount and local status, as mutable branch metadata. Duplicate reception checks immutable bundle identity but does not add, replace, or reset the existing local budget. It re-ACKs existing storage. This also handles a retry carrying copies=2 after the receiver has already spent its branch down to copies=1.

Phase 6 receipts remain the authoritative tombstones. Receipt synchronization runs first; both persistent receipts and the peer's delivered summary suppress DIRECT and SPRAY. Learning a receipt clears future message attempts/candidates, updates source/relay state through the existing Room status mechanism, and retains delivery knowledge. Stale payload reception checks the tombstone before even a previous BUFFER_FULL rejection; delivered knowledge always takes precedence. No receipt modifies hopCount or copiesRemaining or recreates message content.

Receipt propagation retains its own inventory, storage ACK/retry and peer-known behavior. It is not limited by the original MESSAGE spray budget or relay payload quota.

## Bounded relay admission and housekeeping

Active relay usage counts rows with all of:

- status CARRIED;
- sourceId != localDeviceId and destinationId != localDeviceId;
- expiresAt > current time;
- no persistent delivery receipt.

User-authored outgoing history, final-recipient messages and receipt rows are excluded. The quota is an active foreign-relay payload limit, not a total application storage limit.

The existing `commitReceived` transaction checks tombstones/duplicates first. For a new foreign relay, it deletes only foreign expired or receipt-suppressed relay payload rows, preserving receipts, own messages and final-recipient history. It then counts active rows and inserts only if space exists. Safe housekeeping commits even if remaining valid active rows still fill the quota. No valid active relay is arbitrarily evicted.

When quota is full, no new row or success ACK is produced. The protocol sends:

```json
{
  "packetType": "MESSAGE_REJECT",
  "type": "MESSAGE_REJECT",
  "version": 2,
  "messageId": "<original UUID>",
  "reason": "BUFFER_FULL"
}
```

The existing serializer may also include unused WirePacket fields at their defaults; rejection never contains a message payload.

A receiver remembers a BUFFER_FULL outcome for that message throughout the current session and rejects retries consistently even if capacity frees later. This prevents an already-rejected retry from being accepted after the sender restored its budget. The sender's session rejection set prevents immediate repeat sends. A future reconnect clears rejection state and re-evaluates fresh inventories. A lost rejection followed by disconnect conservatively leaves the reduction in place.

Final-destination delivery bypasses the relay quota and still receives its normal MESSAGE ACK plus receipt. Real storage failures continue to produce no success ACK. Payload reclamation is opportunistic on relay admission; receipt learning immediately excludes delivered rows from active quota, while tombstones remain persistent indefinitely.

## Session state, ordering and debug

Disconnect clears attempts, peer-known IDs, incoming/outgoing rejection state, candidate lists and receipt session state. Room budgets, messages, source/final history and delivery tombstones remain. An old peer/session query cannot schedule a new-session frame.

Ordering remains control/ACK traffic, delivery receipt anti-entropy, DIRECT, then SPRAY. Existing `retryLock` and TCP frame writer serialize sends; no partial written frame is aborted. WAIT IDs are shown separately in debug.

Debug shows copiesRemaining, routing mode, initial budget, active relay buffer usage/limit, rejected IDs/reason and last spray given/kept values alongside UUID, destination, hop count and delivery knowledge. Logs describe splits, WAIT decisions, rejection, conservative ambiguous failure, duplicate budget preservation and reclaimed expired/delivered payload counts without logging chat bodies.

## Verification and practical limits

Build command:

```powershell
.\build.ps1 -Tasks ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest', '--offline'
```

**Final verification (2026-10-13):** application APK and Android instrumentation APK assembled successfully; all **104 JVM tests** passed (88 regression tests adapted to the new policy plus 16 new Phase 7 tests); lint passed with **0 errors and 2 existing warnings** (`OldTargetApi`, `UseKtx`).

The existing regression suite is run with the new policy; previous epidemic assertions now verify controlled branch budgets and WAIT behavior. Focused tests cover even/odd splits, source initialization, WAIT suppression/direct delivery, inventory-known peers, receipts, expiry/hop limits, duplicate budget preservation, same-branch lost-ACK retry, conservative restart after ambiguous failure, one-time definitive rejection restoration, sticky rejection after lost reject/capacity changes, concurrency, buffer exclusions/reclamation, multiple-message DIRECT priority, stale-session/receipt-before-write cancellation and missing-field wire compatibility.

The actual existing TCP transport demonstrates disconnected A→B, B→C, A→E, WAIT suppression to F, C→D direct delivery and later receipt propagation to all carriers. Another TCP test proves a zero-capacity relay rejects/restores once without retry looping, yet still accepts its own final-recipient message.

New Android instrumentation tests compile against real Room for schema migration 3→4 with history/receipts preserved, durable reservations across database reopen, duplicate budget preservation, concurrent quota admission, destination/source exemptions and tombstone-preserving reclamation. They have not been executed in this managed session. `test-device.ps1 -CheckOnly` returned Windows profile API `0x80070002` and an empty profile path, so native ADB cannot start here. Real Room/device/reboot validation remains pending; JVM stores are not claimed as actual SQLite/reboot proof.

## Exact physical-device procedure

1. Install `app/build/outputs/apk/debug/app-debug.apk` on A/B/C/D/E, preserving app data. Use the same Phase 7 build on all devices and record their persistent Device IDs. Enable Wi-Fi, required Android permissions and USB debugging if running instrumentation. Expect the SPRAY-AND-WAIT 2.9 label. Keep every uninvolved device disconnected at each encounter; no Internet is required.
2. A↔B: wait for HELLO, both inventories and receipt comparison. On A, enter D's Device ID as the final recipient and send via carrier B. Use body MSG001 as a demo label, TTL 86400 and hopLimit at least 3. The real messageId is a generated UUID; record it from Debug. Before send A's new row is copies=4. After ACK verify A copies=2/hop=0/FORWARDED and B copies=2/hop=1/CARRIED, same UUID/source/destination. Disconnect.
3. B↔C with A/D/E absent: C lacks the UUID; B sprays 1 and keeps 1. Verify B copies=1/hop=1 and C copies=1/hop=2, both WAIT. Disconnect.
4. A↔E with B/C/D absent: verify A copies=1/hop=0 and E copies=1/hop=1. Disconnect. A/B/C/E now each retain one budget unit, total 4. Neither B nor C creates a receipt merely from custody ACK.
5. If a sixth device F is available, connect B↔F while F is not D and lacks the message. Verify WAIT in Debug, no MESSAGE transfer, no F payload row, and B budget unchanged. With five phones, use D as the carrier in an equivalent separate demo whose final destination is another recorded offline Device ID; keep this primary MSG001 demo's D as the final recipient.
6. Force-stop/reopen B before delivery and verify copies=1 remains, with no fresh budget. For an additional restart-at-two test, restart B between encounters 2 and 3 instead and verify 2 persists before its 1/1 split. Reboot testing may be performed similarly.
7. C↔D with A/B/E absent: C is WAIT but peer equals destination, so DIRECT is allowed. Verify no budget split, D receives exactly one chat row attributed to original A at hop=3, sends ordinary ACK, and creates the existing receipt/tombstone. Disconnect. Receipt synchronization may complete in this encounter or the next.
8. Connect D↔C, then C↔B, then B↔A, then A↔E, disconnecting between encounters. Verify the receipt UUID reaches every carrier, A's outgoing row is DELIVERED, all carriers stop routing that UUID, and branch hop counts/budgets do not increase. No simultaneous A↔D path is needed.
9. Reconnect carriers that already know the message/receipt: no new replica and no duplicate budget increase. If F is available, B↔F after delivery should exchange the missing receipt but no original MESSAGE. Old replicas cannot reactivate the UUID because the receipt remains even when carrier payloads are later reclaimed.
10. For a quick buffer demo, use a test build with only `MAX_RELAY_BUFFER_MESSAGES` changed from 100 to 2 in `DtnConfig.kt` and install that same build on the test phones. Keep production default 100. Fill B with two nonexpired foreign CARRIED messages addressed to an absent destination. A third sprayed message must receive BUFFER_FULL, leave no B row, restore only that reserved sender budget once, and remain session-rejected on repeated Retry. Send a different message addressed directly to B: it must succeed despite full relay usage. Reconnect after capacity frees to verify fresh-session reconsideration.
11. To free test capacity safely, let one carried message expire or deliver it and propagate its receipt to B. On the next new relay admission, verify eligible foreign payload reclamation and preserved receipt. Do not clear app data to test receipt persistence. Odd L=3 splitting and duplicate/retry injection are covered by focused automated tests; a test build with initial budget 3 can demonstrate a physical 2/1 split.

From a normal Windows user PowerShell with an authorized Android device, run:

```powershell
.\test-device.ps1 -Serial <adb-serial>
```

The existing script invokes `connectedDebugAndroidTest` to build/install/run Room instrumentation. See `DEVICE_TESTING.md` for ADB setup. This runs database tests on one device; multi-phone Wi-Fi Direct acceptance is the separate procedure above.

Final Phase 7 milestone: A starts with 4; A→B produces 2/2; B→C produces 1/1; A→E produces 1/1; all four branches WAIT; C meets D and delivers; later receipts suppress every remaining replica. Phase 7 stops here.
