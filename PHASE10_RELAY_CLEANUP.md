# Phase 10: relay participation and message cleanup

Version: `3.2-relay-cleanup` (`versionCode 15`). Phase 8 remains the routing baseline.

## Implementation

The existing `ProfileRepository` stores `relay_enabled` in `chat_preferences`, beside the Phase 9 display name and onboarding choice. The default is `true`, including upgrades. Settings and first-run onboarding expose “Help carry messages for others”. The switch is local and takes effect immediately for new transfers. Already-started TCP writes may complete; no socket transaction is interrupted. A remote change is learned on the next HELLO. Wi-Fi Direct, TCP, INVENTORY and delivery receipts always remain active.

HELLO remains a version-2 packet and now has this shape:

```json
{"type":"HELLO","packetType":"HELLO","version":2,"deviceId":"<UUID>","displayName":"Nate","relayEnabled":true}
```

`relayEnabled` is optional when reading. A missing or malformed optional value is treated as the older, relay-capable behavior (`true`). The peer value is held in `SocketState.peerRelayEnabled` only for the identified TCP session and is cleared at disconnect. Device IDs remain the sole routing identities. The flag is omitted from all other packet types.

Before the unchanged Phase 8 score decision, `DeliveryEngine` applies an eligibility gate. DIRECT to the connected destination is allowed regardless of either switch. A new spray to an unrelated peer needs that peer's capability; forwarding a third-party copy also needs the local switch. A locally sourced message can still use a consenting relay when its owner's switch is off. The writer rechecks the local switch and session before starting a new payload write. An older peer without the capability field is assumed capable.

A new foreign payload arriving while the local switch is off is rejected before Room admission. `MessageRepository.commitReceived` checks the switch again inside its transaction to close the UI/network race. The existing `MESSAGE_REJECT` packet now accepts `RELAY_DISABLED` as well as `BUFFER_FULL`; neither is an ACK. An existing matching copy still gets its duplicate ACK. Rejection is sticky for the same bundle in that session, so retry outcomes cannot oscillate. Reconnect/HELLO refreshes both devices' capability state.

Room schema **6 → 7** adds `messages.reservedCopies INTEGER NOT NULL DEFAULT 0`; migration is additive. This field is local bookkeeping and is excluded from MESSAGE serialization. Reserving a Binary Spray branch records the proposed outgoing count in `reservedCopies`, leaving `copiesRemaining` unchanged. On relay storage ACK, one Room update commits the retained count and clears the escrow. On definitive `BUFFER_FULL` or `RELAY_DISABLED`, the escrow is cleared with no lost copies. If socket failure makes custody ambiguous, the durable escrow remains to avoid creating an extra branch; an in-session retry resends the same proposed copy. After process restart an unresolved escrow conservatively prevents another spray until delivery/expiry clears it. This is the intentional uncertainty limit of ACK-based custody, not a changed PRoPHET or Spray formula.

Cleanup uses the existing Room message table and Phase 7 quota. It runs at startup/retry (the existing 5-second app-process cadence), on receipt reception, and before a new relay admission. It first marks expired rows, then removes **relay-only** rows (local ID is neither source nor destination) when a receipt exists, lifetime has expired, or `hopCount >= hopLimit`. The Phase 8 policy blocks **all** future forwarding at that hop boundary, including DIRECT, so that relay payload is permanently unusable. Source and destination chat rows are never reclaimed by this query. Delivery receipts stay in their existing table for tombstone exchange and duplicate suppression. The same active-relay count supplies Phase 7 buffer admission; no second quota or WorkManager was added.

The normal Settings page shows only the relay choice and plain-language storage explanation. Network Debug shows local and current-peer relay capability, acceptance state, rejection reason, buffer use and the existing technical logs. Message TTL remains 24 hours, hop limit 8, initial copies 4, relay buffer 100 messages. `FORWARDING_MARGIN` remains 0.10; all Phase 7/8 values and PRoPHET equations are unchanged.

## Files

Modified: `app/build.gradle.kts`, `README.md`, `ProfileRepository.kt`, `AppViewModel.kt`, `ProfileScreens.kt`, `DinoblogApp.kt`, `NetworkDebugScreen.kt`, `DinoblogApplication.kt`, `NetworkState.kt`, `WireProtocol.kt`, `TcpTransport.kt`, `DeliveryEngine.kt`, `MessageStore.kt`, `MessageRepository.kt`, `Message.kt`, `MessageDao.kt`, `DinoblogDatabase.kt`, and the pre-existing migration/database tests. Generated Room schema: `app/schemas/com.group16.dinoblog.database.DinoblogDatabase/7.json`.

New: `Phase10RelayTest.kt`, `Phase10DatabaseTest.kt`, and this report.

## Verification and device procedure

`build.ps1` passed `assembleDebug`, **137/137 JVM unit tests**, `lintDebug`, and `assembleDebugAndroidTest`. The new tests cover optional HELLO capability, local and remote opt-out, DIRECT exceptions, rejection/copy restoration, Room escrow, cleanup, history protection, and schema migration. Android instrumentation tests are compiled but require an attached device/emulator to execute. In this sandbox `adb devices` cannot start (`Cannot mkdir '\.android': Permission denied`), so migration execution and actual Wi-Fi Direct encounters still need on-device verification.

Use the same version-15 APK on three Android phones A, B and C. In Developer → Network Debug, record each stable Device ID. Use C as the destination and B as a possible carrier. Keep all clocks reasonably synchronized. Phase 8 only sprays when B's predictability for C is sufficiently better, so first let B meet C, disconnect, then connect A to B; verify the scores and `SMART_SPRAY` decision in Network Debug before expecting a spray.

1. **Persistence and HELLO:** On B turn the switch off, force-stop/reopen the app, and verify it stays off. Connect A↔B. Check the peer Device ID is still B's UUID and A's debug page shows peer relay `false`. Reconnect after toggling B on and verify `true`. A Phase 9 HELLO without the field should be treated as capable.
2. **Remote opt-out and DIRECT:** With B off, queue A→C. A should skip B before sending a MESSAGE payload. Then queue A→B; B must receive it and show it in chat. HELLO, INVENTORY and receipt traffic should still work.
3. **Local opt-out and rejection:** Let A↔B establish HELLO while B is on, then toggle B off without reconnecting. Trigger a new A→C transfer when Phase 8 makes B eligible. B should emit `MESSAGE_REJECT/RELAY_DISABLED`, store no new CARRIED row, and A's `copiesRemaining` must stay at its pre-attempt value. Reconnect to refresh A's peer capability afterward.
4. **Own outgoing while off:** Keep B off. Let B create B→C, encounter an eligible consenting A, and check that its own message may `SMART_SPRAY` to A. This choice must not disable B's DTN messaging.
5. **Previously carried copy:** While B is on, get A→C stored on B. Turn B off. B retains the row and must not spray it to a fourth unrelated peer. Connect B directly to C; C receives it, and its receipt later removes B's relay-only payload. C's chat and A's source chat remain visible.
6. **Cleanup and buffer:** For a relay-only row, verify a delivery receipt removes the row while the receipt ID remains. Let another relay row expire; after the next retry/cleanup it must leave inventory and free buffer space. A relay row at `hopCount == hopLimit` is removed because the existing policy allows no further hop. Fill the 100-row buffer to cause `BUFFER_FULL`, reclaim eligible rows, then verify a new eligible relay row can enter.
7. **Regression:** With all switches on, repeat the established Phase 8 better-peer, worse-peer, DIRECT, WAIT and receipt scenarios. Compare local/peer scores and decisions in Network Debug; no PRoPHET formula or copy split changed. Reopen the app and verify messages, receipts, profile, aliases and choice survive.
