# Phase 4 - store-carry-forward to the final destination

App **2.6-store-carry-forward**, versionCode **9**. Install the same APK on A, B and C.
Room schema **2** and wire protocol **2** are unchanged. This milestone adds automatic
final-destination forwarding. It does not add arbitrary relay replication, routing
metrics, a server, or delivery-receipt propagation back to disconnected A.

## Inspection and implementation map

The existing components were extended, not replaced:

| Responsibility | Existing implementation / Phase 4 extension |
| --- | --- |
| MESSAGE creation and retries | `dtn/DeliveryEngine.kt`: existing `queue` and `retryPending`; new `forwardMissing` runs under the same retry mutex |
| Parsing and packet model | `network/WireProtocol.kt`: unchanged HELLO/INVENTORY/MESSAGE/ACK model, validation, framing |
| Socket writes and lifecycle | `network/TcpTransport.kt`: existing write mutex and session-bound sends; new session-aware MESSAGE/ACK callback |
| ACK handling | `DeliveryEngine.receive` uses original ACK type/status; adds `confirmForwarded` for tracked relay attempts |
| Room lookup and status update | Existing `MessageStore.get` / `MessageDao.get`; new guarded `markRelayDelivered` query |
| Missing IDs | Existing `InventoryManager.state.missingOnPeer`, calculated by set subtraction |
| Peer identity | Existing `SocketState.connectedPeerDeviceId` and unique `sessionId` set only after HELLO |
| Session cleanup | Existing transport/inventory cleanup; `DeliveryEngine.syncForwardingSession` clears relay attempts/debug state |
| Normal chat | Receiver saves original source A in the existing Room peer list; existing Chat filtering displays A-to-C messages |
| Debug state | Existing `AppViewModel`, with `delivery.forwarding` exposed next to `inventory` |

## Forwarding decision

`DeliveryEngine` observes the existing inventory StateFlow and calls `retryPending`.
The regular five-second retry loop also uses the same code. `forwardMissing` runs only
when local inventory was sent, remote inventory was received/compared successfully,
and both snapshots belong to the current identified TCP session.

It iterates **only `missingOnPeer`**, loads each ID using the existing Room `get`, and
requires all of the following:

- `destinationId == connectedPeerDeviceId`.
- Status is CARRIED or FORWARDED. PENDING remains in the existing user-originated
  direct/explicit-carrier path; RECEIVED/DELIVERED rows are never forwarding candidates.
- `now < expiresAt`, valid original metadata, nonnegative hop count, and
  `hopCount + 1 < hopLimit` (the existing strict receiving admission policy).
- Peer/session still match after Room suspension; the latest inventory still lists
  the ID as missing and does not list it in the remote inventory.

Sends use `sendToSession` and the existing per-socket write mutex. Multiple candidates
are sent sequentially. An attempted ID is throttled to the existing five-second retry
cadence; repeated inventory notifications cannot create a tight resend loop.

## Identity, hops and status

The forwarded MESSAGE preserves messageId, sourceId, destinationId, payload, createdAt,
expiresAt, TTL, hopLimit and carrierId. `carrierId` remains the original first-hop hint;
it is not rewritten as a route. The wire status remains PENDING, as required by the
existing MESSAGE schema. Only the outgoing copy's hop count advances before a send.

| Event | A copy | B copy | C copy |
| --- | --- | --- | --- |
| A creates | PENDING, hop 0 | absent | absent |
| A sends to B; custody ACK | FORWARDED, hop 1 | CARRIED, hop 1 | absent |
| B sends to C; awaiting ACK | unchanged | CARRIED, hop 1 | RECEIVED, hop 2 after commit |
| C's final ACK reaches B | unchanged | DELIVERED, hop 2 | RECEIVED, hop 2 |

HELLO, INVENTORY and comparisons never increment a hop. Retry uses the same stored
copy and hence the same outgoing hop count, not one additional hop per TCP attempt.
For the two-hop demo use hopLimit **8** (at least **3** is needed under the strict
`incoming hopCount < hopLimit` policy).

## Receiver, deduplication and ACKs

The original first-hop receiver path is preserved. A relayed MESSAGE with hopCount
at least 2 is accepted **only at its final destination**. A relay cannot send it to
another arbitrary carrier in this phase. Wire validation, expiry and strict hop
admission apply before new storage. Final content is stored as RECEIVED and ACKed
with the existing DELIVERED ACK status after storage succeeds.

The receiver first looks up messageId and relies on Room's primary key / IGNORE
insert as the final duplicate guard. Existing content must match all immutable
bundle fields; hop count and local status may differ along a valid alternate path.
Duplicates do not replace rows or duplicate the chat. Valid already-stored duplicates
can be re-ACKed after expiry, preserving the existing lost-ACK behavior; unseen expired
messages are never admitted.

At B, a final ACK must match an attempted message, final peer and current session.
The Room update additionally checks expected stored hop count and valid lifecycle
status. It atomically changes B to DELIVERED and increments the stored hop once.
Wrong-peer, stale-session, unsolicited and repeated relay ACKs do not change custody.
The existing origin direct ACK/custody ACK handling remains independent.

`TcpTransport` supplies the reader's actual session token to the delivery callback.
ACK responses are also session-bound: a slow Room commit cannot send an old ACK over
a new connection to the same device.

## Lost ACK, reconnect and inventory knowledge

Phase 3 advertised only forwarding offers. Phase 4 also includes **unexpired RECEIVED
message IDs** in the same INVENTORY list, using an ID-only Room query. This is needed
because C must announce that it already has a delivered bundle even though RECEIVED
rows must never be forwarded. Existing forwarding filters still exclude RECEIVED.
No new packet type or payload-bearing inventory was added.

If C commits but B misses the ACK, B retains its CARRIED/FORWARDED copy and original
hop count. In the same session it may retry the same UUID/hop; C re-ACKs without a new
row. On reconnect, C advertises the RECEIVED ID and B's fresh `missingOnPeer` excludes
it, so B sends nothing. B may remain CARRIED until its existing expiry policy runs:
**an inventory is knowledge, not a final-delivery ACK**. This phase does not invent a
receipt-recovery protocol or carry C's ACK back to A.

Disconnect clears the peer/session, remote inventory, missing sets, candidates,
pending relay-attempt map, and last-send/ACK debug values. Room copies are retained.
Reconnect must complete HELLO and a fresh inventory exchange before forwarding.

Inventory still has the existing complete-snapshot limit of 1,000 IDs / 64 KiB.
Oversized inventories report an error and are not truncated or used for forwarding.
Snapshots refresh on connection or receipt, not continuously after every Room write.

## Protocol flow

```text
A -- HELLO / INVENTORY -- B
A -- MESSAGE(id=M, source=A, destination=C, hop=1) --> B
B -- ACK(id=M, ackStatus=CARRIED) ------------------> A
B commits CARRIED; A marks FORWARDED

A disconnects and can power off

B -- HELLO ---------------------------------------- C
B <-- HELLO(deviceId=C) ---------------------------- C
B -- INVENTORY [M] --------------------------------> C
B <-- INVENTORY [] --------------------------------- C
B computes missingOnPeer=[M], checks destination=C and session/TTL/hops
B -- MESSAGE(id=M, source=A, destination=C, hop=2) --> C
C commits RECEIVED and exposes original source A in Conversations
B <-- ACK(id=M, ackStatus=DELIVERED) ---------------- C
B marks DELIVERED, hop=2

Reconnect: C INVENTORY includes M; B does not resend M
```

The MESSAGE and ACK JSON structures are unchanged; both retain their existing `type`,
`packetType` and `version` fields. The forwarded message is inside the existing
`message` envelope, and ACK uses the original `messageId` and `ackStatus`.

## Exact three-phone demo

Preparation: use three Android 8+ phones with Wi-Fi Direct support, install
`app/build/outputs/apk/debug/app-debug.apk` over the current app on **all three**,
and verify **STORE-CARRY-FORWARD · 2.6**. Keep apps in the foreground, enable Wi-Fi
and Location as required by the OS, grant permissions, and use reasonably synchronized
clocks. Internet is not needed. Record A/B/C's persistent UUIDs from Device / Copy
Device ID (or Debug). Use TTL 86400 and hopLimit 8 for the main demo.

1. On A and B, open Peers, scan, and initiate **Connect from one side**. Accept the
   Android connection prompt. Wait for TCP Connected and HELLO identity on both.
2. On A, open Chat. Paste **C's UUID** into the recipient field and enable
   **Store on connected carrier (A -> B for C)**. Confirm Carrier B is B's UUID.
3. Send `Hello C - Phase 4`. Record the full message UUID M in Debug; this is MSG001
   from the specification. A's first-hop send uses M unchanged.
4. On B Debug / Stored messages, verify M: source=A, destination=C, CARRIED, hopCount=1,
   hopLimit=8. On A, custody ACK should produce FORWARDED, not DELIVERED.
5. Disconnect A and B. Close A's app or power A off. Optionally close/reopen B now
   to demonstrate that custody survives process restart. B must still show M in Room.
6. Connect B and C via Peers, using the same discovery/connection procedure.
7. On B Debug, verify Connected Peer Device ID is C's UUID after HELLO.
8. Check HELLO logs followed by INVENTORY SEND/RECEIVE and INVENTORY COMPARE. B's
   local inventory includes M; C's initial inventory does not.
9. Confirm B's `Missing on Peer` includes M. It is a connection snapshot; it may
   remain listed after ACK until a new comparison. It is not a delivery-status field.
10. Without pressing Forward or Retry, observe `DTN FORWARD CANDIDATE` and
    `DTN FORWARD SEND` for M, destination=C. Debug exposes candidates, last forwarded
    ID, waiting ACKs and last final ACK. The candidate list can clear on a later retry
    after delivery, so use logs/Last forwarded to inspect the completed send.
11. On C, verify M is RECEIVED (not CARRIED). Open **Device / Conversations / A's
    UUID** or **Chat / Choose peer / A's UUID**. If A has never connected directly,
    it appears as `Via carrier (...)`. Confirm `Hello C - Phase 4` displays once.
    The conversation belongs to original A, not the currently connected carrier B.
12. Compare Debug on all available copies: UUID M, original source A, destination C,
    payload and timestamps remain unchanged. A was not connected to C.
13. Verify C's hopCount=2. Once final ACK reaches B, B is DELIVERED with hopCount=2;
    A remains FORWARDED with hopCount=1. HELLO/inventory/retry must not inflate hops.
14. Disconnect and reconnect B/C. C's inventory must include M. B must not log another
    MESSAGE send for M, and C must still have a single row/chat entry for M.

Additional acceptance cases:

- **Mixed destinations / several messages:** before step 5, send three messages for
  C and one for a different valid UUID D through B. On B/C contact only C's missing
  messages should arrive; D's message stays CARRIED at B. Reconnect checks already-held IDs.
- **Expiry:** send a separate fixture through B with TTL 10 seconds; disconnect A,
  wait past expiresAt, then connect C. It must not arrive. B retains its EXPIRED history.
- **Hop limit:** use hopLimit 2 for a separate fixture. B can accept hop 1, but cannot
  forward hop 2 under the existing strict admission rule. It stays stored, with no hop
  increment from inventory.
- **Disconnect before completion:** interrupt B/C quickly, check B's stored copy,
  reconnect and wait for fresh HELLO/inventory. Delivery must either retry safely or be
  suppressed if C already stored the ID. Manually timing an ACK loss is nondeterministic;
  the automated real-socket test drops the ACK callback deterministically.

## Verification and file changes

Executed results and environment limitations are in [VERIFICATION.md](VERIFICATION.md).
The automated real-TCP scenarios shut down A before B/C contact, cover multiple
messages and wrong destinations, and test both successful ACK and deliberately lost
ACK with receipt-inventory suppression on reconnect. Room instrumentation tests cover
on-disk custody/reopen, receiver persistence, ID receipt query, and atomic ACK guards.
Hardware Wi-Fi Direct acceptance still needs the three-phone procedure above.

Modified files (paths relative to project root):

- `app/build.gradle.kts`
- `app/src/main/java/com/group16/dinoblog/DinoblogApplication.kt`
- `app/src/main/java/com/group16/dinoblog/database/MessageDao.kt`
- `app/src/main/java/com/group16/dinoblog/dtn/DeliveryEngine.kt`
- `app/src/main/java/com/group16/dinoblog/dtn/InventoryManager.kt`
- `app/src/main/java/com/group16/dinoblog/network/TcpTransport.kt`
- `app/src/main/java/com/group16/dinoblog/repository/InventoryStore.kt`
- `app/src/main/java/com/group16/dinoblog/repository/MessageRepository.kt`
- `app/src/main/java/com/group16/dinoblog/repository/MessageStore.kt`
- `app/src/main/java/com/group16/dinoblog/viewmodel/AppViewModel.kt`
- `app/src/main/java/com/group16/dinoblog/ui/ChatScreen.kt`
- `app/src/main/java/com/group16/dinoblog/ui/DinoblogApp.kt`
- `app/src/main/java/com/group16/dinoblog/ui/NetworkDebugScreen.kt`
- `app/src/main/java/com/group16/dinoblog/ui/PeerDiscoveryScreen.kt`
- `app/src/test/java/com/group16/dinoblog/DeliveryEngineTest.kt`
- `app/src/test/java/com/group16/dinoblog/PeerIdentityHandshakeTest.kt`
- `README.md`
- `VERIFICATION.md`

Added files:

- `app/src/main/java/com/group16/dinoblog/model/ForwardingState.kt`
- `app/src/test/java/com/group16/dinoblog/Phase4TestStore.kt`
- `app/src/test/java/com/group16/dinoblog/DestinationForwardingTest.kt`
- `app/src/test/java/com/group16/dinoblog/StoreCarryForwardTcpTest.kt`
- `app/src/androidTest/java/com/group16/dinoblog/ForwardingDatabaseTest.kt`
- `PHASE4_FORWARDING.md`

No Wi-Fi Direct manager, packet schema, database schema, or routing architecture was
replaced. Work stops at Phase 4.
