# Phase 6: persistent end-to-end delivery receipts

Application **2.8-delivery-receipts**, versionCode **11**. This extends the existing
Wi-Fi Direct/TCP/HELLO/INVENTORY/MESSAGE/ACK pipeline. Room advances from schema 2 to
3 with one additive table. The existing wire envelope remains version 2, with
explicit receipt capability advertised in INVENTORY.

Message route: **A -> B -> C -> D**. Receipt route can independently be
**D -> E -> B -> A**. Only one pair needs to be connected at a time. A eventually
shows DELIVERED, while every informed carrier retains its historical message row
and stops offering or forwarding that message. No reverse route, external server,
global deletion, or Phase 7 feature is introduced.

## Architecture inspected and extended

Continuation review on 2026-10-07: the production Phase 6 implementation described
below was already present. It was inspected against the requested requirements and
retained without adding another networking stack, changing the schema again, or
reimplementing working features. The continuation changes only
`app/src/test/java/com/group16/dinoblog/DeliveryReceiptTest.kt`, this report, and
`VERIFICATION.md`; no new source files were required.

Four additional regression cases exercise boundaries that were not explicitly
covered by the earlier receipt tests:

- A suspended receipt lookup finishes after the **same peer** reconnects and sends
  a fresh inventory. Old-session work sends nothing, and an old ACK cannot confirm
  a new-session attempt.
- A receipt expires while its lookup is suspended. The persistent knowledge remains,
  but the now-expired receipt is neither sent nor marked in flight.
- Remote receipt inventory changes during a suspended lookup. The selected transfer
  is cancelled when the peer now knows the receipt.
- A final destination receives another branch after engine recreation and original
  message expiry. It re-ACKs the stored message while preserving exactly one receipt,
  its original timestamps/lifetime, and the original stored message depth. This host
  test reuses an in-memory store; actual on-disk reopen remains an Android test.

Device-test continuation adds `test-device.ps1` and `DEVICE_TESTING.md`, updates
`build.ps1` to use the existing workspace caches/signing key and in-process Kotlin
compiler by default, and extends `DeliveryReceiptDatabaseTest.kt` with two cases:
relay receipt close/reopen followed by forwarding to the original source, and receipt
transaction rollback if updating the local message status fails. All 17 Android
test methods compile. Native ADB is blocked by the restricted account's missing
Windows profile; the prepared launcher must run from the normal desktop PowerShell
account to execute the Android tests. This is separate from host-test success.

The file inventory below describes the complete Phase 6 implementation, including
files already present before this continuation. Current executed results and the
remaining hardware verification boundary are recorded in `VERIFICATION.md`.

Paths are relative to `app/src/main/java/com/group16/dinoblog/`.

| Existing responsibility | Phase 6 extension |
| --- | --- |
| MESSAGE serialization/parsing: `network/WireProtocol.kt` | Same parser/framing; adds DELIVERY_RECEIPT, receipt inventory IDs and typed ACK target |
| HELLO and packet dispatch: `network/TcpTransport.kt` | Unchanged; receipt packets use existing session-aware packet callback |
| Inventory build/comparison: `dtn/InventoryManager.kt` | Builds both ID lists, compares each separately and refreshes from the same repository changes |
| Phase 5 candidate selection: `dtn/DeliveryEngine.kt` | Checks persistent delivery knowledge before MESSAGE candidates and again before send; adds receipt candidates |
| ACK/retry/in-flight: `DeliveryEngine` | Existing retry lock and five-second cadence; separate receipt attempt/known-ID maps prevent ID/ACK confusion |
| TCP serialization: `TcpTransport.write` | Unchanged per-session mutex protects complete length-prefixed frames for every packet type |
| Final receive: `DeliveryEngine.receive(MESSAGE)` | Uses repository transaction for message plus final-destination receipt; only then sends the existing MESSAGE ACK |
| Room persistence/status: `repository/MessageRepository.kt`, `database/MessageDao.kt` | Transactional receipt insert plus DELIVERED update; receipt-aware inventory exclusion |
| Final UI: existing Room Flow -> ViewModel -> Chat | Existing RECEIVED at destination and DELIVERED at informed source/carriers; no new conversation model |
| Session cleanup: transport, InventoryManager, DeliveryEngine | Existing lifecycle also clears receipt inventories, comparisons, attempts, known IDs and last-transfer fields |
| Debug state: `model/InventoryState.kt`, `model/ForwardingState.kt`, ViewModel/Debug screen | Exposes persistent receipts, suppression, receipt inventories and per-session transfers |

The production MessageRepository implements the small ReceiptStore interface in
addition to its existing interfaces. The same DeliveryEngine handles both kinds
of transfer. Historical tests with a message-only fake store continue to exercise
the Phase 1-5 paths; production always uses the receipt-capable Room repository.

## Modified and new files

Modified production files (under the source root above):

- `DinoblogApplication.kt`: registers migration 2 -> 3 along with 1 -> 2.
- `database/DinoblogDatabase.kt`: registers receipt entity/DAO and additive migration.
- `database/MessageDao.kt`: delivery-known status update and inventory exclusion.
- `repository/MessageRepository.kt`: receipt queries/Flow and atomic transactions.
- `repository/InventoryStore.kt`: receipt ID inventory boundary.
- `network/WireProtocol.kt`: receipt packet/validation/serialization, ACK target,
  combined inventory size bound and legacy capability handling.
- `dtn/InventoryManager.kt`: both inventory sets in the existing session manager.
- `dtn/DeliveryEngine.kt`: final receipt creation, receive/suppression, receipt
  routing priority, ACK/retry, duplicate and session guards.
- `model/InventoryState.kt`, `model/ForwardingState.kt`: receipt debug/session fields.
- `viewmodel/AppViewModel.kt`: existing repository receipt Flow exposed to Debug.
- `ui/NetworkDebugScreen.kt`: receipts, missing IDs, known/in-flight IDs, suppression.
- `ui/DinoblogApp.kt`, `ui/PeerDiscoveryScreen.kt`: Debug wiring and version labels.

Other modified files:

- `app/build.gradle.kts`: versionCode 11 / versionName 2.8-delivery-receipts.
- `app/src/androidTest/java/com/group16/dinoblog/StoreCarryDatabaseTest.kt`: existing
  migration regression now registers the complete upgrade path.
- `app/src/androidTest/java/com/group16/dinoblog/ForwardingDatabaseTest.kt`: final
  receiver now advertises its receipt instead of the original message ID.
- `README.md`, `VERIFICATION.md`: current entry point and executed evidence.

New files:

- `app/src/main/java/com/group16/dinoblog/model/DeliveryReceipt.kt`
- `app/src/main/java/com/group16/dinoblog/database/DeliveryReceiptDao.kt`
- `app/src/main/java/com/group16/dinoblog/repository/ReceiptStore.kt`
- `app/schemas/com.group16.dinoblog.database.DinoblogDatabase/3.json`
- `app/src/test/java/com/group16/dinoblog/ReceiptTestStore.kt`
- `app/src/test/java/com/group16/dinoblog/DeliveryReceiptTest.kt`
- `app/src/test/java/com/group16/dinoblog/DeliveryReceiptTcpTest.kt`
- `app/src/androidTest/java/com/group16/dinoblog/DeliveryReceiptDatabaseTest.kt`
- `PHASE6_DELIVERY_RECEIPTS.md` (this report).

## Persistence and migration

`delivery_receipts` lives in the existing `dinoblog.db`. Its primary key is receiptId,
with a unique index on originalMessageId. Other columns are originalSourceId,
originalDestinationId, deliveredAt, createdAt, expiresAt, hopCount and hopLimit.
There is deliberately no foreign key requiring an original message row: a receipt
can arrive before the payload. Existing messages and peers tables are unchanged;
their exported entity schemas compare identically between schema 2 and 3.

`MIGRATION_2_3` creates only that table/index. Production registers both migrations
so installs can upgrade from schema 1 or 2. No destructive migration or deletion is
used. Existing RECEIVED rows are kept; a duplicate final MESSAGE can create their
missing receipt, but migration does not fabricate historical delivery timestamps.

At a final destination, `commitReceived` checks message/receipt identity and commits
the RECEIVED message and receipt in one Room transaction. Failure to insert the
receipt rolls back the message too, and no MESSAGE ACK is sent. A retry can safely
complete both. This removes a crash gap between saving the payload and its receipt.
The duplicate path reuses an existing receipt and its original deliveredAt/expiry.

`saveReceipt` atomically inserts an unseen receipt and updates a matching local
PENDING/CARRIED/FORWARDED/EXPIRED message to DELIVERED. RECEIVED at the actual
destination stays RECEIVED. No message or receipt row is deleted. A duplicate
receipt with matching immutable metadata produces no repeated status update.
Conflicting UUID/source/destination or receipt metadata is rejected.

## Wire format and stable identity

Receipt ID is Java's name-based UUID of the UTF-8 bytes:

```text
dinoblog:delivery-receipt:v1:<originalMessageId>
```

It is deterministic, persistent and distinct from the original message identity.
Relays preserve every receipt field except outgoing hopCount. Receipt packets use
the existing wrapper with a nested receipt, analogous to MESSAGE's nested message:

```json
{
  "type": "DELIVERY_RECEIPT",
  "version": 2,
  "receipt": {
    "receiptId": "23c2c668-75d8-36c6-bc20-427de68dd708",
    "originalMessageId": "00000000-0000-4000-8000-000000000001",
    "originalSourceId": "00000000-0000-4000-8000-00000000000a",
    "originalDestinationId": "00000000-0000-4000-8000-00000000000d",
    "deliveredAt": 1791300000000,
    "createdAt": 1791300000000,
    "expiresAt": 1791904800000,
    "hopCount": 1,
    "hopLimit": 10
  },
  "packetType": "DELIVERY_RECEIPT"
}
```

D stores its generated receipt at hop 0; the first transmitted copy above is hop 1.
Receipt validation checks stable ID, all original UUIDs, distinct source/destination,
timestamp ordering, bounded lifetime and hop budget. Unknown expired/far-future
receipts are not stored or ACKed. Previously committed identical receipts can be
re-ACKed without refreshing their lifetime or overwriting their stored path depth.
Only the local final MESSAGE receive path generates new authoritative receipts;
other nodes copy received receipts. This phase does not add cryptographic signatures.

INVENTORY is still one ID-only packet:

```json
{
  "type": "INVENTORY",
  "version": 2,
  "deviceId": "00000000-0000-4000-8000-00000000000d",
  "messageIds": [],
  "deliveryReceiptIds": ["23c2c668-75d8-36c6-bc20-427de68dd708"],
  "packetType": "INVENTORY"
}
```

The combined number of message IDs and receipt IDs is bounded at 1,000 so the whole
snapshot stays inside the existing 64 KiB frame. Oversized snapshots fail explicitly;
they are never silently truncated. There is no new pagination or quota system.

An explicit receipt list, including an empty list, advertises receipt support. An
older peer omitting this field is treated as message-only and is not sent unsupported
DELIVERY_RECEIPT packets. Ordinary MESSAGE/ACK traffic keeps its existing shape and
semantics. Install 2.8 on every device for the full receipt demonstration.

A receipt transfer is acknowledged by the **same ACK packet type**, with
`ackFor = DELIVERY_RECEIPT`, `receiptId = <R-ID>`, `messageId = null`, and
`ackStatus = CARRIED`. This ACK means only that the receiving node stored the receipt.
Ordinary MESSAGE ACKs retain `ackFor = MESSAGE` (also the default when absent),
messageId, and the existing CARRIED/DELIVERED status. Explicitly separating ACK
targets prevents a receipt ACK from changing an unrelated message's delivery state.

## Anti-entropy, routing, lifetime and session behavior

After the current session's HELLO and successful bidirectional INVENTORY:

1. Message missing IDs are the existing local-minus-remote set difference.
2. Receipt missing IDs are `localReceiptIds - remoteReceiptIds`, calculated in the
   same InventoryManager. Room changes refresh both local comparisons.
3. Stored receipts whose propagation lifetime is active are advertised, including
   exhausted-hop copies as known IDs. A message with any stored receipt is excluded
   from message inventory, including stored-copy inventory used for duplicate knowledge.
4. Receipt candidates must be persisted, missing on this peer, unexpired, under
   hopLimit, not peer-known, and not awaiting an unexpired retry interval. Inventory,
   identity and session are rechecked after asynchronous Room lookups.
5. DIRECT RECEIPT means originalSourceId equals the connected peer. RELAY RECEIPT
   means any other peer can carry the knowledge. Source receipt delivery does not
   prevent that source from carrying it onward to other uninformed branches.

The same forwarding pass uses this order:

```text
DIRECT RECEIPT -> DIRECT MESSAGE -> RELAY RECEIPT -> RELAY MESSAGE
```

Both receipt classes use the existing five-second retry cadence and retry mutex.
Registering an attempt before the TCP write handles fast ACKs. Only a matching
receipt ACK for that attempt's peer and session adds successful-send knowledge.
Successful socket writes alone do not. The existing per-session TCP writer serializes
all message, receipt, ACK, inventory and heartbeat frames.

Receipt peer-known IDs union the remote inventory, received receipt IDs and confirmed
transfers. Receive records peer knowledge before Room emits changes, preventing
echo against a stale inventory. Repeated inventories cannot erase learned session
knowledge. Lost ACKs retry the same UUID/depth; after reconnect, the receiver's
persistent receipt inventory suppresses an unnecessary resend. Failed transfers
leave the sender's durable receipt available for a future encounter.

Default receipt lifetime is seven days after final delivery; default hopLimit is 10.
`DeliveryEngine` constructor parameters `receiptLifetimeMillis` and `receiptHopLimit`
make these configurable (maximum lifetime 30 days, hopLimit 1..255). Each outgoing
receipt has stored hopCount + 1. The sender's stored hop never changes after a send
or ACK. Receiving at exactly hopLimit is allowed, but further forwarding is blocked.
HELLO, inventory comparisons and retransmission of the same copy do not consume
another stored hop.

Expiration stops receipt advertisement/forwarding and admission of unseen expired
receipts. It does **not** erase already learned delivery knowledge: retained receipts
continue suppressing the original payload. There is no garbage collector or deletion
in this phase. This avoids resurrecting delivered messages after receipt expiry.

Disconnect clears receipt remote inventory, missing comparisons, attempts, known IDs
and last-transfer debug fields together with the existing message session state.
Room messages and receipts survive. Reconnecting to the same peer still requires
fresh HELLO/INVENTORY and a new session token; old queries and ACKs cannot affect a
new peer's forwarding state or write through its socket.

## Suppression and receipt-first handling

Receiving delivery knowledge commits it before ACK, updates a matching local row
to DELIVERED and removes its active MESSAGE retry/candidate state. The existing chat
Flow makes the source's status update visible. Receipts never create chat bubbles.
Every forwarding pass also queries receipt knowledge before MESSAGE selection and
again before send, protecting against a stale message inventory or Room result.

If no original message row exists, the receipt is still stored. A later MESSAGE with
that ID must match original source/destination, then is suppressed without inserting
a routed copy or creating a chat bubble. The repository repeats that check inside
its admission transaction, covering a receipt arriving between receive validation
and insertion. It does **not** falsely send a custody ACK for a copy it did not store.
The existing inventory-driven receipt transfer informs a receipt-capable sender,
which then stops its message retries. Already stored duplicate payloads retain the
existing re-ACK behavior and never create another row.

A packet already written/in flight before receipt knowledge arrives cannot be
retracted; receiver idempotency/suppression handles it. If a node only knows an
expired or hop-exhausted receipt, it still suppresses the payload locally, but cannot
relay that receipt over budget. An uninformed sender can then retry until its normal
message expiry or until delivery knowledge reaches it through another valid path.
Knowledge reaches the source opportunistically, subject to encounters and budgets.

## Final protocol sequence

MSG001/R-MSG001 here are readable aliases for actual UUIDs.
Each separate encounter begins with TCP CONNECT, HELLO in both directions and
INVENTORY containing message IDs plus receipt IDs.

```text
MESSAGE DELIVERY
TIME 1: A <-> B only
  A -> B MESSAGE MSG001, source=A, destination=D, hop=1
  B commits CARRIED; B -> A ACK(MESSAGE, MSG001, CARRIED)
  A retains FORWARDED, hop=0. Disconnect.

TIME 2: B <-> C only; A offline
  B -> C MESSAGE MSG001, same source/destination, hop=2
  C commits CARRIED; C -> B ACK(MESSAGE, MSG001, CARRIED)
  B retains its hop=1 copy. Disconnect.

TIME 3: C <-> D only; A and B offline
  C -> D MESSAGE MSG001, hop=3
  D transaction: commit MSG001 as RECEIVED + R-MSG001 at receipt hop=0
  D displays one message in original A's conversation
  D -> C ACK(MESSAGE, MSG001, DELIVERED)
  Room refresh also offers R-MSG001 in the current session automatically
  D -> C DELIVERY_RECEIPT R-MSG001, receipt hop=1
  C commits receipt, suppresses MSG001
  C -> D ACK(DELIVERY_RECEIPT, R-MSG001, CARRIED)
  Disconnect.

DELIVERY KNOWLEDGE THROUGH AN ALTERNATIVE PATH
TIME 4: D <-> E only
  Fresh HELLO/INVENTORY: E lacks R-MSG001
  D -> E DELIVERY_RECEIPT R-MSG001, receipt hop=1
  E commits receipt, marks any MSG001 copy DELIVERED, stops forwarding it
  E -> D ACK(DELIVERY_RECEIPT, R-MSG001, CARRIED)
  D retains receipt hop=0. Disconnect; D offline.

TIME 5: E <-> B only
  Fresh HELLO/INVENTORY: B lacks R-MSG001
  E -> B DELIVERY_RECEIPT R-MSG001, receipt hop=2
  B commits receipt + MSG001 status DELIVERED; retains original payload/hop=1
  B -> E ACK(DELIVERY_RECEIPT, R-MSG001, CARRIED)
  E retains receipt hop=1. Disconnect; E offline.

TIME 6: B <-> A only
  Fresh HELLO/INVENTORY: A lacks R-MSG001
  Classify DIRECT RECEIPT because originalSource=A
  B -> A DELIVERY_RECEIPT R-MSG001, receipt hop=3
  A commits receipt + MSG001 status DELIVERED; chat updates
  A -> B ACK(DELIVERY_RECEIPT, R-MSG001, CARRIED)
  B retains receipt hop=2. No original payload is deleted.
```

The ordinary reverse encounter sequence D -> C -> B -> A works by exactly the
same receipt rule. No reverse routing table is recorded or required.

## Exact physical-device acceptance procedure

Hardware testing remains separate from the automated host tests. Use five phones
A/B/C/D/E for the alternative-path demo; four suffice for D/C/B/A receipt return.

1. Install `app/build/outputs/apk/debug/app-debug.apk` on every phone using an in-place
   upgrade. With working ADB: `adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk`.
   Do not clear storage. Confirm header **DELIVERY RECEIPTS · 2.8**. Existing history
   and Device IDs must remain after the Room migration.
2. Record each UUID from Device/Debug. Use these actual UUIDs, not literal DEVICE-A.
   Disable mobile data, enable Wi-Fi and required Location/Nearby Devices permissions,
   and keep the two active phones' apps in the foreground. Disconnect all other pairs.
3. On disconnected A, open Chat, paste D's UUID, leave connected-carrier option off,
   set TTL 86400 seconds and send `Hello D`. Record the generated full messageId
   from Debug as MSG001. A must show PENDING while D is unreachable.
4. Connect only A/B: Peers -> Scan for peers -> Connect on one phone; accept Android's
   dialog on the other. Wait for HELLO/INVENTORY. B must store CARRIED/hop=1 and A
   become FORWARDED/hop=0 after MESSAGE ACK. Neither has a receipt yet.
5. Disconnect A/B using Peers -> Disconnect, then force-stop A in Android settings.
   Connect only B/C. C is not D but must receive CARRIED/hop=2. B keeps hop=1.
   Disconnect B/C. Optionally connect B/E now to make another payload branch;
   E must also receive hop=2 while B remains hop=1. Disconnect B/E.
6. Keep A/B/E offline. Connect only C/D. D must show one message in original A's
   conversation, a RECEIVED row at hop=3, and one stored receipt in Debug at hop=0.
   C receives MESSAGE ACK and the new receipt automatically. Verify C's receipt hop=1
   and suppression entry. No manual Forward/Retry action is needed.
7. Disconnect C/D. Force-stop and reopen D. Its message and receipt must still exist
   with exactly the same IDs, deliveredAt and stored hops. Force-stop C.
8. Connect only D/E. E's receipt inventory initially lacks R-MSG001. Verify RELAY
   receipt send, E persistent receipt hop=1 and receipt ACK. If E carried MSG001 from
   step 5, it becomes DELIVERED and remains stored. Otherwise E has only the receipt,
   with no MSG001 chat bubble. D's receipt stays hop=0. Disconnect and force-stop D.
9. Connect only E/B. Verify B learns R-MSG001 at receipt hop=2, retains its original
   MESSAGE row at hop=1 and marks it DELIVERED. MSG001 must disappear from message
   inventory/candidates; its receipt must appear in receipt inventory. Disconnect
   and force-stop E.
10. Reopen A and connect only B/A. Verify DIRECT receipt priority, A's receipt hop=3
    and original MSG001 status DELIVERED in Chat. A's message hop remains 0 and the
    original UUID/source/destination/payload are unchanged. At no point did A/D
    have an end-to-end connection.
11. Disconnect/reconnect B/A. The receipt ID must be present in A's INVENTORY; B
    must not send that receipt again. Both retain one receipt and one local message.
12. Connect informed B to a fresh uninformed phone (or use a fresh test message and
    keep E uninformed until this step). Even with no remote MSG001, B must never
    send MSG001; it sends the receipt instead. Check logs for FINAL_DELIVERY_KNOWN
    and the stored suppression entry. Do not delete existing history to stage this.
13. For the four-phone reverse-return variant, after step 6 connect C/B then B/A
    instead of steps 8-10. Receipt depths are D=0, C=1, B=2, A=3.

Additional deterministic checks and how to reproduce them:

| Requirement | Verification |
| --- | --- |
| Receipt-first/message-later | Unit and actual Room admission tests store a receipt first, then inject the matching MESSAGE; no original row or false MESSAGE ACK |
| Duplicate receipt | Unit/Room tests resend the same receipt at a different path depth; one receipt, unchanged first depth and lifetime, repeated receipt ACK allowed |
| Lost receipt ACK | TCP test deliberately discards only A's receipt ACKs at B; reconnect inventories prevent retransmission and A remains DELIVERED |
| Failed transfer | Unit transport returns failure before storage; sender retains receipt and retries after fresh session inventory |
| Hop limit / expiry | Unit tests admit the last hop, advertise it as known, prevent another send, reject unseen expired receipts and retain expired delivery knowledge |
| Receipt branching | TCP D sends to C and E at hop=1; B sends separate branches at hop=3 while retaining hop=2 |
| Direct priority / TCP safety | Unit test asserts all four priority classes; TCP tests mix several payloads, receipts and ACKs over the existing writer |
| Session cleanup / stale query | Tests disconnect/change peer while receipt queries are suspended and reject old-session work/ACKs |
| Atomic persistence failure | Android test installs a SQLite abort trigger on receipt insert: final MESSAGE insertion rolls back and no ACK is sent |
| Migration/restart | Android tests validate exported v2 -> v3 schema, retain old messages/peers and reopen a database containing final payload/receipt |

Manual link interruption is not precise enough to prove the ACK-loss/storage boundary;
the deterministic tests cover that boundary. To test short receipt budgets on phones,
configure `receiptLifetimeMillis`/`receiptHopLimit` in the existing AppContainer's
DeliveryEngine constructor for a dedicated test APK; normal UI has no receipt editor.

Run all build checks with the workspace JDK/caches:

```powershell
$env:GRADLE_USER_HOME = Join-Path $PWD '.tools/gradle-home'
$env:ANDROID_USER_HOME = Join-Path $PWD '.tools/android-home'
.\build.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest', '-Pkotlin.compiler.execution.strategy=in-process')
```

With a connected Android device, open PowerShell as the normal signed-in Windows
user and run real Room instrumentation using the prepared preflight/launcher:

```powershell
.\test-device.ps1
```

See [DEVICE_TESTING.md](DEVICE_TESTING.md) for the diagnosed ADB profile error,
device selection, and targeted Phase 6 execution. See [VERIFICATION.md](VERIFICATION.md)
for executed results and the current ADB
limitation. Host tests use real TCP but an in-memory transactional store; they are
not a substitute for physical Wi-Fi Direct and Android Room runtime acceptance.
