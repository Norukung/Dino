# Phase 5: inventory-driven epidemic routing

Current application: **2.7-epidemic-routing**, versionCode **10**. Room schema **2**
and wire protocol **2** remain unchanged. No new database, table, migration,
transport, packet type, delivery-receipt propagation or replica deletion is added.

The workspace already contained the Phase 5 implementation and tests when reviewed.
This completion pass preserves them, adds four missing regression scenarios,
clarifies the existing chat labels, and brings the documentation up to date.
Build evidence and hardware limitations are recorded in [VERIFICATION.md](VERIFICATION.md).

## Files and architecture inspection

Paths below are relative to `app/src/main/java/com/group16/dinoblog/` unless stated.

| Responsibility | Existing implementation reused |
| --- | --- |
| MESSAGE serialization and parsing | `network/WireProtocol.kt`: encode/write/read/validate; length-prefixed UTF-8 JSON, same MESSAGE envelope |
| ACK generation | `dtn/DeliveryEngine.kt`: receive(MESSAGE), only after durable insertion or verified existing duplicate |
| ACK processing | `DeliveryEngine.receive(ACK)` and `confirmForwarded` |
| Retry and in-flight state | `DeliveryEngine.retryPending`, retryLock, attempts map and existing five-second cadence |
| Connected identity and HELLO completion | `network/TcpTransport.kt`: runSession validates HELLO before publishing peerId/sessionId; `model/NetworkState.kt` exposes connectedPeerDeviceId |
| Remote inventory and missingOnPeer | `dtn/InventoryManager.kt`: receive/updateLocal/compare; `model/InventoryState.kt` |
| Candidate selection and automatic start | `DeliveryEngine.classify`, routingRejection, forwardMissing; start observes inventory and schedules retries |
| Persisted message lookup | `repository/MessageRepository.kt` delegates get/inventoryCandidates to `database/MessageDao.kt` |
| Relay storage | receive(MESSAGE) inserts the received copy as CARRIED |
| Final storage and display | receive(MESSAGE) inserts RECEIVED, the existing final-receive state; `ui/ChatScreen.kt` shows only conversations involving the local destination/source |
| Branch hop count | forwardMissing makes an outgoing copy at stored hopCount + 1; DAO ACK updates never increment the sender row |
| Disconnect cleanup | TcpTransport.clearSession/stop; InventoryManager session collector; DeliveryEngine.syncForwardingSession |
| App wiring | `DinoblogApplication.kt`: one Room repository, one TcpTransport, existing InventoryManager and DeliveryEngine; onSessionPacket retains reader session identity |
| Debug state | `model/ForwardingState.kt`, `ui/NetworkDebugScreen.kt`, existing ViewModel state flows |

Modified during this completion pass:

- `app/src/main/java/com/group16/dinoblog/ui/ChatScreen.kt`: describe automatic
  carrier forwarding, remove fixed A/B/C role labels and misleading direct-only title.
  Existing controls, conversation filtering and ViewModel calls are retained.
- `app/src/test/java/com/group16/dinoblog/EpidemicRoutingTest.kt`: add failed relay
  reconnect, FORWARDED origin branching, late relay ACK after expiry, and local-final
  no-forward tests; extend its fake transport with failure injection.
- `README.md` and `VERIFICATION.md`: current Phase 5 entry points and evidence.

New file: `PHASE5_EPIDEMIC.md` (this report).

Existing Phase 5 files reviewed and reused include DeliveryEngine, InventoryManager,
MessageDao/Repository/Store, ForwardingState, Debug UI, application wiring and
`app/build.gradle.kts`; the existing `EpidemicTcpTest`, `EpidemicRoutingTest`,
`DestinationForwardingTest`, `Phase4TestStore` and `ForwardingDatabaseTest` were
already present. They were not recreated as a second implementation.

## Routing, reliability and persistence

1. A current identified TCP session must have successfully sent its local INVENTORY
   and received the peer's INVENTORY. Until then, the production app sends no queued
   MESSAGE. The inventory-less legacy test path remains separate only as the
   pre-existing optional mode of the same engine; production supplies inventory.
2. The engine loads persisted rows named in `missingOnPeer`. It does not broadcast
   all Room rows. `classify` rejects local final destinations, expired or invalid
   metadata, exhausted hop budgets and non-routing statuses. PENDING, CARRIED and
   FORWARDED remain eligible.
3. Destination equal to peer means DIRECT; another destination means RELAY. DIRECT
   sorts first, then RELAY, with createdAt/messageId as deterministic tie breakers.
   Eligibility and the current inventory/session are rechecked after Room reads.
4. Session peer-known IDs combine inventory knowledge, received MESSAGE IDs and
   successful matched ACKs. A received ID is recorded before Room insertion emits
   updates, preventing immediate echo against an old inventory snapshot. A later
   inventory cannot erase proven session knowledge. INVENTORY itself is not a
   delivery ACK.
5. Attempts are registered before socket writes. A reactive refresh cannot launch
   another attempt inside the five-second retry interval. The existing retry loop
   can retransmit the same UUID and outgoing hop after timeout. A TCP write alone
   does not mark the peer as having the copy.
6. ACK must match an attempted message, peer, session and expected status. A relay
   peer returns CARRIED: source PENDING becomes FORWARDED; existing CARRIED/FORWARDED
   copies remain available. The sender's row is retained. A final peer returns
   DELIVERED: the immediate sender records DELIVERED. This does not notify previous
   carriers or globally delete their replicas.
7. Receivers validate existing MESSAGE packets and immutable bundle identity.
   Room uses messageId as primary key with INSERT IGNORE. Duplicate alternate paths
   do not overwrite the first row, change its depth, or create another chat bubble.
   Verified duplicates are re-ACKed, including previously stored content whose TTL
   has subsequently elapsed. Unseen expired content is rejected without storage/ACK.
8. Relays insert CARRIED and do not show another person's payload in a conversation.
   The final receiver inserts **RECEIVED**, the project's existing final-delivery
   state, and sends ACK(DELIVERED). The original author becomes selectable in its
   conversation list. RECEIVED is intentionally preserved rather than renamed;
   DELIVERED on the immediate sender means its final-destination ACK arrived.
9. Outgoing hopCount is local stored hopCount + 1. B retains depth 1 after sending
   to C and E; both receive depth 2. Before every attempt, stored depth must be less
   than hopLimit and current time less than expiresAt. Receiving exactly hopLimit
   is permitted and advertised as known, but cannot propagate further. HELLO,
   inventory, retries of the same copy and ACKs do not increase stored path depth.
10. `retryLock` serializes forwarding evaluation. TcpTransport's existing per-session
    write mutex protects complete length-prefixed frames, including concurrent ACK,
    heartbeat and inventory writes. `sendToSession` binds writes to the captured
    connection; slow queries cannot send old-peer work through a new peer's socket.
11. Disconnect resets remote inventory/comparison, peer-known IDs, attempts and
    forwarding debug fields. Room rows persist. Reconnect requires fresh HELLO and
    INVENTORY, even for the same UUID. Lost ACK recovery follows actual peer storage:
    advertised copies are skipped; absent copies can be retried.

## Final protocol sequence

`MSG001` below is a readable alias for the actual UUID generated once by A.
Use the same APK on all devices.

```text
Encounter 1: A <-> B; C and D offline
TCP CONNECT
A -> B HELLO(A)               B -> A HELLO(B)
A <-> B INVENTORY
A sees MSG001 missing at B; destination D != peer B => RELAY
A -> B MESSAGE(MSG001, source=A, destination=D, hopCount=1)
B commits CARRIED, hop=1
B -> A ACK(MSG001, CARRIED)
A retains FORWARDED, hop=0
DISCONNECT; A is gone

Encounter 2: B <-> C; A and D offline
TCP CONNECT
B <-> C HELLO, then INVENTORY
B sees MSG001 missing at C; destination D != peer C => RELAY
B -> C MESSAGE(MSG001, source=A, destination=D, hopCount=2)
C commits CARRIED, hop=2; no final-recipient chat bubble
C -> B ACK(MSG001, CARRIED)
B retains CARRIED, hop=1
DISCONNECT; B is gone

Encounter 3: C <-> D; A and B offline
TCP CONNECT
C <-> D HELLO, then INVENTORY
C sees MSG001 missing at D; destination D == peer D => DIRECT
C -> D MESSAGE(MSG001, source=A, destination=D, hopCount=3)
D commits RECEIVED (existing final-delivery state), hop=3
D displays "Hello D" once in A's conversation
D -> C ACK(MSG001, DELIVERED)
C retains its row as DELIVERED, hop=2
```

All immutable metadata, including messageId, sourceId, destinationId, payload,
createdAt, expiresAt, TTL, hopLimit and optional original carrier hint, stays intact.
There is no external server, Internet connection, simultaneous end-to-end link,
backward delivery receipt, tombstone or global cleanup.

## Exact physical-device acceptance procedure

These instructions are **pending hardware execution**; JVM TCP tests use actual
localhost sockets with an in-memory store, not Wi-Fi Direct or Android Room.

1. Install `app/build/outputs/apk/debug/app-debug.apk` on four Android 8+ phones
   (optionally a fifth E for branching). Upgrade in place to retain identity/history.
   With working ADB: `adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Record each phone's Local Device ID from Home/Debug and name them A/B/C/D on paper.
   Expect version 2.7 epidemic routing. These IDs are UUIDs, not literal DEVICE-A.
   Disable mobile data; keep Wi-Fi and required Location/Nearby Devices permissions
   enabled. Keep Dinoblog in the foreground on the two phones in each encounter.
3. With every phone disconnected, on A open Chat, paste D's UUID, leave the optional
   connected-carrier checkbox off, set TTL to 86400 seconds, enter `Hello D`, and
   send. It must queue as PENDING while D is unreachable. Record the full generated
   messageId in Debug as MSG001. The default hopLimit is 8.
4. Keep C/D disconnected. Open Peers on A and B, scan, select the other phone, and
   accept any Android connection dialog. Wait for connected peer UUID, HELLO and
   both INVENTORY snapshots. No Forward/Retry button is needed.
5. Verify A logs RELAY with destination D and peer B. B Debug must show MSG001,
   original source A/destination D, CARRIED and hop=1. A retains FORWARDED/hop=0
   after ACK. B must not display `Hello D` as a message addressed to B.
6. Use Disconnect to remove the A/B Wi-Fi Direct group. Force-stop A from Android
   app settings and leave it offline. Verify B's remote inventory, peer-known and
   awaiting-ACK session fields clear while its stored row remains.
7. Keep D offline. Connect only B and C using Peers. Verify C's UUID and fresh
   INVENTORY. In B Debug/logs, MSG001 is missing and selected RELAY despite
   destination D != peer C. C receives CARRIED/hop=2 with identical UUID and content;
   B retains CARRIED/hop=1 after ACK. No final chat bubble appears at C.
8. Disconnect B/C. To test persistence, force-stop and reopen C; its row must remain.
   Force-stop B and keep A/B offline.
9. Connect only C/D. Verify fresh HELLO/INVENTORY and C's DIRECT send. D stores
   MSG001 at hop=3 and RECEIVED, sends ACK(DELIVERED), and shows exactly one `Hello D`
   bubble after selecting original author A in Choose peer. C becomes DELIVERED
   while retaining hop=2. A and B have received no delivery receipt.
10. Disconnect and reconnect C/D. Inspect logs from the new connection: no new
    MESSAGE for MSG001, no second Room row, no duplicate bubble. D advertises its
    received UUID and never relays that final content to a new missing peer.

Additional required checks (use fresh UUIDs created by the UI for each scenario):

| Case | Procedure and expected result |
| --- | --- |
| Peer already has copy | Reconnect B/C after step 7; C inventory includes MSG001, so B emits no MESSAGE |
| Mixed direct and relay priority | With B disconnected, queue one for C and two for D/E. Preload C with a fourth C-addressed message using an earlier B/C encounter. Reconnect B/C: the new C message sends first as DIRECT; D/E messages follow as RELAY; the known fourth is skipped |
| Expiry | Create a message on A with TTL 5 seconds while disconnected. Wait over 5 seconds, then connect A/B: EXPIRED and no MESSAGE for that UUID |
| Hop limit | Connect A/B, choose the connected-carrier option, destination D, hopLimit 1. B may store hop=1 but cannot pass it to C later. For a chain reaching D, use hopLimit >= 3 |
| Branching | After B/C step 7 and before stopping B, connect B to an empty E. E receives hop=2; B stays hop=1. C's earlier copy stays hop=2 |
| No echo | Once B/C inventory exchange completes, create a new D-addressed message on C. B stores it as a relay. B's stale missing snapshot may contain it, but peer-known IDs must prevent B -> C MESSAGE echo |
| Multiple transfers | Queue several messages before connecting. Every eligible UUID arrives once per database; frames parse correctly, direct messages lead, no independent duplicate send occurs while awaiting ACK |
| Failed transfer | Disconnect before receiver storage, reconnect, and inspect inventory: missing content is retried and sender retains its row. Automated failure injection makes this timing deterministic |
| Lost ACK | Interrupt the link after receiver storage but before sender confirmation. Reconnect: receiver advertises the UUID and sender does not resend. `EpidemicTcpTest` deterministically drops relay ACK handling for this case |
| Session isolation | Disconnect C, then connect B/E. C-specific known/in-flight/inventory state must not suppress an E-missing copy. Each connection gets fresh HELLO/INVENTORY |

For deterministic ACK-loss/failure boundary coverage use the automated tests; manual
radio timing alone cannot prove exactly where a packet or ACK was interrupted.

Run Android Room instrumentation when a working connected device is available:

```powershell
$env:GRADLE_USER_HOME = Join-Path $PWD '.tools/gradle-home'
$env:ANDROID_USER_HOME = Join-Path $PWD '.tools/android-home'
.\build.ps1 -Tasks @(':app:connectedDebugAndroidTest', '-Pkotlin.compiler.execution.strategy=in-process')
```

Stop at Phase 5. No Phase 6 routing algorithm, delivery-receipt propagation or
replica cleanup is included.
