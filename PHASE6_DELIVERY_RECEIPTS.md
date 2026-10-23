# DTN Phase 6 — persistent delivery knowledge

Dinoblog 2.8 extends the existing Phase 5 implementation. One Wi-Fi Direct connection, one TCP transport, one Room database, the existing repository/ViewModel, HELLO, message inventory, ACK cadence, and DIRECT/RELAY routing remain in use. No Phase 7 features were added.

## Files

Modified:

- `README.md`, `test-device.ps1`: current Phase 6 entry point and physical acceptance instructions.
- `app/build.gradle.kts`: versionCode 11, versionName `2.8-delivery-receipts`.
- `app/src/main/java/com/group16/dinoblog/DinoblogApplication.kt`: register additive migration 2→3.
- `database/DinoblogDatabase.kt`: version 3, receipt entity/DAO, migration.
- `database/MessageDao.kt`: receipt-driven status update, retaining payload/history and hopCount.
- `repository/MessageRepository.kt`: atomic receipt/message admission, tombstone queries, reactive receipt inventory, routing suppression.
- `network/WireProtocol.kt`: receipt, receipt ACK, delivery IDs in INVENTORY, validation/serialization.
- `network/MessageTransport.kt`: conditional session send using the existing transport.
- `network/TcpTransport.kt`: eligibility check inside the existing socket write mutex, before writing a complete frame.
- `dtn/InventoryManager.kt`: delivery inventory and set comparison; immediate remote-summary suppression before Room reads.
- `dtn/DeliveryEngine.kt`: destination receipt creation, receipt synchronization/ACK/retry, stale-replica handling, candidate/write-time suppression and session cleanup.
- `model/InventoryState.kt`, `model/ForwardingState.kt`: receipt inventory and session debug fields.
- `viewmodel/AppViewModel.kt`: observe persistent receipts through the existing repository.
- `ui/DinoblogApp.kt`, `ui/NetworkDebugScreen.kt`: receipt debug information, 2.8 label; existing chat status UI retained.
- `app/src/androidTest/java/com/group16/dinoblog/StoreCarryDatabaseTest.kt`: validate the existing 1→2 migration followed by 2→3.

Paths abbreviated above are relative to `app/src/main/java/com/group16/dinoblog/` unless fully qualified.

New:

- `app/src/main/java/com/group16/dinoblog/model/DeliveryReceipt.kt`
- `app/src/main/java/com/group16/dinoblog/database/DeliveryReceiptDao.kt`
- `app/src/main/java/com/group16/dinoblog/repository/ReceiptStore.kt`
- `app/src/test/java/com/group16/dinoblog/ReceiptTestStore.kt`
- `app/src/test/java/com/group16/dinoblog/DeliveryReceiptTest.kt`
- `app/src/test/java/com/group16/dinoblog/DeliveryReceiptTcpTest.kt`
- `app/src/androidTest/java/com/group16/dinoblog/DeliveryReceiptDatabaseTest.kt`
- `app/schemas/com.group16.dinoblog.database.DinoblogDatabase/3.json`
- `PHASE6_DELIVERY_RECEIPTS.md` (this implementation report and device procedure).

## Receipt identity and persistence

Exact Kotlin structure (timestamps in Unix milliseconds):

```kotlin
DeliveryReceipt(
    originalMessageId: String, // Room PRIMARY KEY; original message UUID
    sourceId: String,
    destinationId: String,
    deliveredAt: Long,
    originalExpiresAt: Long,
    createdByDeviceId: String,
)
```

Table: `delivery_receipts`. It has no payload, separate generated receipt UUID, or routing hop counter. Every copy represents the same original message. `createdByDeviceId` must equal `destinationId`; IDs must be UUIDs, source and destination differ, and delivery time precedes original expiry.

The existing database moves from version 2 to 3. `MIGRATION_2_3` only creates the six-column receipt table. It does not drop messages or peers. Both `MIGRATION_1_2` and `MIGRATION_2_3` are registered; no destructive fallback is used. The generated Room v3 schema is included.

Receipts are retained indefinitely in this phase. There is no cleanup operation that could remove them before original expiry. Message rows, payload/history, and hopCount are retained. Receipt processing never creates original message content. Restart/disconnect/reboot leave the Room table intact.

## Destination acceptance and idempotency

`DeliveryEngine.receive` handles the existing MESSAGE packet. It validates wire metadata, requires a traversed hop, checks immutable bundle identity on duplicates, and rejects newly admitted expired/future messages. For a final destination it passes a receipt only when `message.destinationId == localId`. `DeliveryReceipt.create` independently enforces that equality. Relays pass no originating receipt.

`MessageRepository.commitReceived` performs the tombstone check, message admission, and optional final receipt persistence in one Room transaction. The logical delivery event becomes visible only after the message has been successfully stored and the transaction commits. Storage failure produces neither a committed receipt nor a success ACK. Existing destination content remains `RECEIVED` so it appears once in the existing chat for the original source, rather than the carrier.

Room primary-key insertion uses IGNORE. Duplicate receipts with matching immutable metadata retain the first `deliveredAt`; forwarding never modifies it. Conflicting source/destination/expiry metadata is rejected. Duplicate messages are acknowledged again after checking identity, without adding a chat row or a new receipt. Already expired legacy duplicates lacking a receipt are acknowledged as existing storage without inventing a historical delivery timestamp.

## Inventory, synchronization and priority

Existing version-2 INVENTORY now contains:

```json
{
  "packetType": "INVENTORY",
  "type": "INVENTORY",
  "version": 2,
  "deviceId": "<local UUID>",
  "messageIds": ["<message UUID>"],
  "deliveredMessageIds": ["<delivered original message UUID>"]
}
```

The existing protocol defaults absent `deliveredMessageIds` to an empty list. Message inventory behavior remains intact. Install the Phase 6 build on all devices participating in receipt transfer; an older binary does not recognize the new receipt packet types.

`missingReceiptsOnPeer = localDeliveredMessageIds - remoteDeliveredMessageIds`, sorted and deduplicated. The reverse difference is `missingReceiptsLocally`. The existing Room-change observer recomputes these differences after receipt/message changes.

After HELLO and inventory exchange, the engine seeds `peerKnownReceipts`, suppresses IDs reported delivered by the peer, and sends only missing full receipt records. A remote delivery summary disables matching MESSAGE routing immediately, before the inventory handler performs suspending Room reads. Full metadata is obtained from the peer's symmetric receipt anti-entropy; the summary alone does not fabricate a delivery timestamp or destination.

The engine waits for incoming advertised receipt metadata to be persisted and outgoing missing receipts to be storage-ACKed before normal message forwarding. It then retains the existing priority of DIRECT messages followed by RELAY messages. Control/ACK packets continue through the existing TCP writer. New receipts created during a connection trigger the same anti-entropy/retry path; no second queue or transport is introduced.

Inventory's existing complete-frame safety limit is now 1,000 message-plus-receipt entries combined. Oversized inventories are rejected without partial/truncated summaries. Receipts are small records, but indefinite retention may eventually reach this existing bounded-inventory limit; advanced retention/buffer policy is outside Phase 6.

## Receipt packets and reliability

`DELIVERY_RECEIPT` uses the existing WirePacket JSON envelope with a `receipt` object containing the exact fields above. `DELIVERY_RECEIPT_ACK` uses `messageId = originalMessageId`. The explicit packet type prevents an ACK for receipt storage from entering MESSAGE ACK processing.

Normal MESSAGE ACK still acknowledges the current transfer. A relay's `CARRIED` ACK means storage on that relay and cannot create a receipt or mark the source end-to-end delivered. The existing final-destination direct ACK semantics remain compatible. Persistent global delivery knowledge comes from the receipt table.

Receipt ACK is sent only after successful Room persistence, including a valid duplicate receipt. The existing five-second retry cadence and `retryLock` handle receipt transfer; receipt attempts are registered before writing and retried only after five seconds while unconfirmed. Disconnect retains the durable receipt. A later inventory advertises an already-persisted receipt even if its ACK was lost, preventing redundant transfer on reconnect.

`peerKnownReceipts` is seeded from peer receipt inventory and updated when receiving a receipt or its storage ACK. Receiving a receipt proves that sender already has it, recorded before Room emissions; this prevents send-back against an old inventory snapshot. Receipt attempt IDs prevent concurrent repeated enqueues. These sets and receipt last-send/receive fields are session-local and cleared on disconnect, alongside Phase 5 session state.

Every asynchronous receipt send checks the current peer UUID, session token, inventory readiness, and peer-known state. The existing TCP write mutex checks eligibility and socket identity again after waiting, before writing any frame bytes. No partial in-flight packet is aborted. Receipt propagation never changes message hopCount.

## Suppression and source status

The receipt table is authoritative even if a row still says CARRIED/FORWARDED. The repository excludes tombstoned IDs from forwardable inventory. The engine checks both persistent receipts and the current peer's delivered summary during candidate selection and again at the actual writer boundary, for both DIRECT and RELAY traffic.

Learning a receipt updates existing source/relay rows to `DELIVERED`, retaining content and original path depth. Destination rows retain `RECEIVED`. The existing chat observes Room, so A's outgoing status changes without requiring A to reconnect to D. Receipt observers remove affected message IDs from candidate lists and pending attempts; no subsequent copies/retries are sent. A currently written frame finishes safely.

If a stale MESSAGE arrives after a receipt, matching metadata is checked, no new carried row is inserted, the ordinary ACK can be sent, and the receipt remains available for synchronization back to that peer. The atomic admission check also protects a MESSAGE/receipt race. A receipt received before the message is sufficient to prevent resurrection.

Messages without receipts retain the existing Phase 5 eligibility rules, UUID deduplication, peer-known/in-flight guards, expiry and hop-limit checks, DIRECT/RELAY ordering, relay copy retention, and branch-correct hop depths. No cloud, Internet, topology, signatures, or routing probabilities were added.

## Verification

Build command:

```powershell
.\build.ps1 -Tasks ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest', '--offline'
```

**Final verification (2026-10-13):** app APK and instrumentation APK assembled successfully; all **88 JVM tests** passed (11 new Phase 6 tests); lint passed with **0 errors and 2 existing warnings** (`OldTargetApi`, `UseKtx`).

JVM tests use the actual existing TCP transport for disconnected A→B→C→D and returning delivery knowledge, checking source status, preserved hop depths, receipt uniqueness, lost receipt ACK repair on reconnect, session cleanup, and suppression at a new relay E. Focused tests cover destination-only origin, failed persistence/no ACK, duplicate stable events, receipt-before-message tombstones, DIRECT/RELAY suppression, immediate remote-summary suppression, write-time cancellation, only missing receipts, retry/in-flight protection, no echo, mixed receipt/DIRECT/RELAY priority, stale-session writes, inventory comparison and wire validation.

Android instrumentation tests were added for real Room persistence/reopen, history preservation, receipt deduplication, tombstone-before-message handling, atomic destination admission, safe indefinite retention, and migration 2→3 with messages/peers preserved. The existing 1→2 test now also validates 2→3.

Physical/Room instrumentation execution is pending: `test-device.ps1 -CheckOnly` failed because the native Windows profile API returned `0x80070002` with an empty profile path in this managed process. No physical device or emulator test was executed here. The instrumentation APK is compiled; run the test command below from a normal Windows user PowerShell with an authorized Android device. JVM fake persistence is not claimed as proof of actual Room reboot persistence.

## Physical-device demo (A, B, C, D; optional E)

1. Install `app/build/outputs/apk/debug/app-debug.apk` on all participating phones, preserving existing app data. Enable Wi-Fi, required location/nearby-device permissions, and USB debugging if using ADB. Record each persistent Device ID from the Device/Debug screen. Use the same Phase 6 build everywhere. Keep uninvolved phones disconnected at every step.
2. Connect A↔B through Peers/Wi-Fi Direct and wait for HELLO and both inventories. On A, choose D's Device ID as the final chat recipient and send via carrier B. Use a TTL such as 86400 seconds and hopLimit at least 3. Label the body MSG001; the real `messageId` is the generated UUID shown in Debug. Record that UUID. Verify B stores it CARRIED at hop 1, A is FORWARDED at hop 0, and no receipt exists at A/B. Disconnect both.
3. With A/D absent, connect B↔C. Verify C stores that same UUID CARRIED at hop 2, B keeps hop 1, and neither creates a receipt. Disconnect.
4. With A/B absent, connect C↔D. Verify D displays the original source's message once, stores RECEIVED at hop 3, logs its final receipt creation, and sends an ordinary MESSAGE ACK. Verify D's Known delivered IDs contains the original UUID. Disconnect. Receipt synchronization can also complete in this same encounter; that is valid.
5. Connect D↔C again. Confirm delivery IDs in inventory and either transfer/storage ACK of the missing receipt or that C already learned it in step 4. C must persist the receipt and stop selecting the UUID as DIRECT or RELAY. Disconnect.
6. With A/D absent, connect C↔B. Confirm B receives/persists the same receipt and suppresses its retained replica. Disconnect. Force-stop and reopen B; Debug must still show the known delivered UUID. This checks real process-restart retention.
7. With C/D absent, connect B↔A. Verify A receives/persists the same receipt and its original outgoing chat/Debug row becomes DELIVERED, with hop 0 unchanged. No A↔D connection is needed. Disconnect.
8. If a fifth device E is available, connect B↔E. E initially lacks the message/receipt. Verify B sends delivery knowledge first and sends no MESSAGE for the delivered UUID; E may retain just the tombstone, with no original chat payload. Duplicating a stale MESSAGE is covered by the automated tests; a preserved old relay copy can also verify no reactivation on hardware.
9. Reconnect nodes that already know the receipt. Verify no duplicate rows, unchanged deliveredAt, no receipt echo, and no repeated MESSAGE transfer. Send an unrelated message without a receipt to verify epidemic forwarding continues normally.
10. Optionally reboot a node that learned the receipt and reconnect it; the receipt and suppression must remain. Receipts are intentionally not garbage-collected in this phase.

For real Room instrumentation, run in a normal user PowerShell from this project with an authorized device:

```powershell
.\test-device.ps1
```

That existing script builds, installs, and runs the instrumentation suite. For multiple attached devices specify `-Serial <adb-serial>`. See `DEVICE_TESTING.md` for connection troubleshooting.

Expected milestone: message route A→B→C→D; delivery knowledge D→C→B→A through separate encounters; A shows DELIVERED; B/C retain history but stop forwarding; stale payload copies cannot resurrect active routing. Phase 6 stops here.
