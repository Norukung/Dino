# Phase 3 - message inventory exchange

App: **2.5-inventory**, versionCode **8**. Room schema **2**, wire protocol **2**.

This milestone ends at inventory comparison. Inventory never sends/request messages,
changes message status, increments hops, or writes Room rows. Existing user-originated
direct/selected-carrier sends and their ACK/retry behavior continue independently.
There is no B-to-C forwarding of CARRIED messages, even when C is their destination.

## Inspection and architecture

Phase 3 production code was already present when this review began. It was reused:

| Responsibility | Existing file under `app/src/main/java/com/group16/dinoblog/` |
| --- | --- |
| Packet enum, DTO, validation, JSON and framing | `network/WireProtocol.kt` |
| HELLO, identified peer/session, inventory dispatch | `network/TcpTransport.kt` |
| Session-bound send API | `network/MessageTransport.kt` |
| Room metadata projection/query | `database/MessageDao.kt` |
| Repository validation and ID projection | `repository/MessageRepository.kt`, `repository/InventoryStore.kt` |
| Inventory build, comparison and state lifecycle | `dtn/InventoryManager.kt`, `model/InventoryState.kt` |
| Existing message lifecycle, ACKs, first-hop retry | `dtn/DeliveryEngine.kt` |
| Component wiring | `DinoblogApplication.kt` |
| Network Debug state owner | `viewmodel/AppViewModel.kt` |
| Existing Debug rendering and state collection | `ui/NetworkDebugScreen.kt`, `ui/DinoblogApp.kt` |
| Connected peer and unique session token | `model/NetworkState.kt` |

No additional manager, parallel packet protocol, schema migration, or architecture
rewrite was needed. No production Kotlin files were changed during this review.

## Wire format

```json
{
  "type": "INVENTORY",
  "version": 2,
  "deviceId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  "messageIds": [
    "00000000-0000-4000-8000-000000000001",
    "00000000-0000-4000-8000-000000000002"
  ],
  "packetType": "INVENTORY"
}
```

The existing 4-byte big-endian length prefix wraps this UTF-8 JSON. The legacy `type`
and explicit `packetType` refer to the same enum; conflicting values are rejected.
IDs are UUIDs, so `MSG001` in the specification is a conceptual label, not a valid
wire ID. No payload, message envelope or ACK status is emitted in inventory JSON.

## Eligibility and comparison

`MessageDao.inventoryCandidates(now)` selects only ID metadata, without loading
payloads into Kotlin. Candidates must be PENDING, CARRIED or FORWARDED; be unexpired
(`now < expiresAt`); have valid timestamp/TTL/expiration and payload constraints;
and satisfy `hopCount >= 0`, `hopLimit in 1..255`, `hopCount + 1 < hopLimit`.
The last rule preserves this project's strict **receiving** admission policy: the
next receiving hop must still be below the limit. Inventory does not increment hops.
Repository validation excludes malformed UUIDs, equal source/destination, and invalid
carrier IDs. DELIVERED, RECEIVED and EXPIRED rows are not advertised; deleted rows
are absent from the query.

`InventoryManager` deduplicates and sorts IDs, then advertises once after HELLO has
identified the peer. Every TCP session has a new token, including reconnection to
the same UUID. The manager rereads Room on receipt of an inventory and computes:

- `missingOnPeer = localEligibleIds - remoteIds`
- `missingLocally = remoteIds - localEligibleIds`

These are differences between eligible snapshots, **not proof of delivery or of
absence from all historical Room rows**. A delivered/received row may still exist
without being advertised. Any future forwarding phase must keep durable duplicate
checks; Phase 3 does not request or send differences.

Session state lives in the manager's `StateFlow<InventoryState>`, exposed by
`AppViewModel.inventory`. Disconnect clears peer, remote IDs and both differences;
Room rows remain intact. Late results from an old session cannot populate a new
session. Repeated inventories are set comparisons with no inserts. Debug distinguishes
waiting from an explicitly empty received inventory. Its local list is a timestamped
snapshot; reconnect refreshes both sides.

Logs contain `DTN INVENTORY BUILD`, `SEND`, `RECEIVE` and `COMPARE`. Existing user
messages/retries can still produce MESSAGE logs; inventory itself never triggers them.

The complete snapshot is capped at 1,000 IDs to fit the existing 64 KiB frame. A larger
snapshot reports an error and sends nothing; it is never silently truncated. Pagination
and continuous synchronization are outside this milestone.

## Verification and changed files in this review

See [VERIFICATION.md](VERIFICATION.md) for executed build/test results and limitations.

Modified:

- `app/src/test/java/com/group16/dinoblog/PeerIdentityHandshakeTest.kt`: attach the real
  inventory manager to the existing CARRIED/no-forwarding regression; verify that C
  receives the ID and B computes it as missing, while explicit retry sends no MESSAGE.
- `README.md`: point the current app instructions to Phase 3.
- `VERIFICATION.md`: record current results separately from historical Phase 2 results.

Added:

- `app/src/test/java/com/group16/dinoblog/InventoryTcpTest.kt`: real two-peer TCP exchange,
  duplicate inventory, disconnect clearing, reconnect, stale-session send rejection.
- `app/src/androidTest/java/com/group16/dinoblog/InventoryDatabaseTest.kt`: real Room
  eligibility/boundary checks and read-only comparison/disconnect/reopen persistence.
- `PHASE3_INVENTORY.md`: this architecture and hardware acceptance guide.

Existing `InventoryManagerTest` and `InventoryProtocolTest` already cover comparison,
pre-HELLO waiting, malformed/oversized input, duplicate inventories, failed reads and
slow old-session results. They are included in the full JVM suite.

## Test with two physical Android phones

1. Install `app/build/outputs/apk/debug/app-debug.apk` on both phones **over the existing
   app**, keeping data. Expect `DTN INVENTORY · 2.5`. Use Android 8+ devices supporting
   Wi-Fi Direct. Enable Wi-Fi/Location and grant the app's requested permissions.
2. Use Peers / Scan and connect from one phone. Debug must show TCP Connected and the
   opposite persistent Device ID. An empty database should exchange empty inventories.
3. Check logs for HELLO SEND/RECEIVE before INVENTORY SEND, then RECEIVE/COMPARE.
   An empty inventory should display Empty rather than Waiting. Disconnect: peer and
   remote comparison must clear. Reconnect: HELLO and INVENTORY must run again.
4. For the exact overlapping-ID cases, connect each debuggable app to Android Studio
   **App Inspection / Database Inspector**. With Wi-Fi Direct disconnected, insert
   the fixtures below into `dinoblog.db`. Record B/C's actual Device IDs from Debug.
   Replace `C_DEVICE_UUID` with C's UUID and `B_DEVICE_UUID` with B's UUID. These are
   designated test rows; do not modify existing conversation rows.
5. Run this on **B** (MSG001 and MSG002), with actual UUID substitutions:

```sql
INSERT OR IGNORE INTO messages
(messageId, senderId, receiverId, payload, timestamp, ttl, status, expiresAt, hopCount, hopLimit, carrierId)
VALUES
('00000000-0000-4000-8000-000000000001', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'Phase 3 fixture 1',
 CAST(strftime('%s','now') AS INTEGER)*1000, 86400, 'CARRIED',
 CAST(strftime('%s','now') AS INTEGER)*1000+86400000, 1, 8, NULL),
('00000000-0000-4000-8000-000000000002', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
 'C_DEVICE_UUID', 'Phase 3 fixture 2 addressed to C',
 CAST(strftime('%s','now') AS INTEGER)*1000, 86400, 'CARRIED',
 CAST(strftime('%s','now') AS INTEGER)*1000+86400000, 1, 8, 'B_DEVICE_UUID');
```

6. On **C**, insert only the first VALUES row (MSG001) using the same column list.
   Connect B/C. B must show MSG001 in Remote Inventory and MSG002 in Missing on Peer.
   C must show MSG002 missing from its eligible inventory. **C must not acquire a
   MSG002 Room row**, even though C is its final destination. B's hop/status/payload
   must remain unchanged. Other existing rows may also appear in these lists.
7. For equal inventories, while disconnected delete only the MSG002 fixture from B
   with `DELETE FROM messages WHERE messageId = '00000000-0000-4000-8000-000000000002';`.
   Reconnect; the fixture-specific missing set must now be empty.
8. For expiry, disconnect and set B's MSG001 `timestamp` to
   `CAST(strftime('%s','now') AS INTEGER)*1000-1000`, `ttl` to `1`, and `expiresAt` to
   `CAST(strftime('%s','now') AS INTEGER)*1000` in one UPDATE. Reconnect; B must not
   advertise that ID. The row remains in Room (the existing expiry worker may mark
   it EXPIRED). A CARRIED fixture with hopCount 7 / hopLimit 8 must also be excluded.
9. Disconnect after a comparison: remote IDs/differences clear, stored fixtures remain.
   Reopen the app and reconnect to verify persisted rows and a fresh exchange.
10. Finally send a normal direct chat in each direction and confirm the existing
    RECEIVED/DELIVERED and ACK behavior still works. Remove only the two designated
    fixture UUIDs from the test devices if desired.

Android instrumentation on an attached test device:

```powershell
.\build.ps1 -Tasks @(':app:connectedDebugAndroidTest')
```

No Phase 4 routing, message requests, carrier-to-destination forwarding, replication,
ACK propagation, or cloud synchronization was implemented.
