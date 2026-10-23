# DTN Phase 8 — PRoPHET-inspired smart relay selection

Dinoblog 3.0 keeps Phase 7 Binary Spray-and-Wait and chooses which peer deserves a scarce relay branch. The app uses the existing Wi-Fi Direct/TCP transport, Room database, repository/ViewModel, HELLO/INVENTORY, ACK/retry, receipts, copy reservation and buffer policy. No additional networking stack or later-phase algorithm was added.

## Files

Modified production files under `app/src/main/java/com/group16/dinoblog/`:

- `dtn/DtnConfig.kt`: centralized predictability/aging/margin/storage bounds.
- `dtn/DeliveryEngine.kt`: once-per-session encounter tracking, summary send/receive, destination-specific decisions, writer-boundary score revalidation, metrics and remote-session cleanup.
- `model/ForwardingState.kt`: SMART_SPRAY classification; the existing SPRAY primitive remains for binary-budget policy and baseline comparisons.
- `database/DinoblogDatabase.kt`: schema 5, predictability DAO/entity and additive migration 4→5.
- `database/MessageDao.kt`: payload-free projection of active message destinations for summary prioritization.
- `repository/MessageRepository.kt`: persistent local estimates, lazy aging, transitive updates, bounded summaries/table and transactional smart score-plus-copy reservation.
- `network/WireProtocol.kt`: PREDICTABILITY_SUMMARY envelope, finite-score/identity/size validation.
- `DinoblogApplication.kt`: register migration 4→5.
- `viewmodel/AppViewModel.kt`: expose persistent local table and session routing state.
- `ui/NetworkDebugScreen.kt`, `ui/DinoblogApp.kt`: score/decision/metrics debug display and SMART RELAYS 3.0 label.

Other modified files:

- `app/build.gradle.kts`: versionCode 13, versionName `3.0-smart-relays`.
- `README.md`, `VERIFICATION.md`, `test-device.ps1`: current report/test entry point.
- Baseline JVM tests with DeliveryEngine instances explicitly request `smartRelaySelection=false`: `DeliveryEngineTest.kt`, `DeliveryReceiptTest.kt`, `DeliveryReceiptTcpTest.kt`, `DestinationForwardingTest.kt`, `EpidemicRoutingTest.kt`, `EpidemicTcpTest.kt`, `PeerIdentityHandshakeTest.kt`, `SprayAndWaitTest.kt`, `SprayAndWaitTcpTest.kt`, `StoreCarryForwardTcpTest.kt`.
- Existing Android tests: `ForwardingDatabaseTest.kt`, `StoreCarryDatabaseTest.kt` use the same explicit baseline comparison for previous behavior; `StoreCarryDatabaseTest.kt`, `DeliveryReceiptDatabaseTest.kt`, `SprayAndWaitDatabaseTest.kt` finish migration validation at schema 5.

The internal comparison option has no UI control. The production container always uses its default `true`. Missing score storage/summary in that default mode fails closed for new relay spraying. The baseline remains finite Phase 7 Spray-and-Wait, not epidemic replication. New Phase 8 suites exercise the actual default smart mode and preserve receipt, copy, retry and buffer invariants under it.

New files:

- `app/src/main/java/com/group16/dinoblog/model/DeliveryPredictability.kt` (Room entity and small serialized summary entry).
- `app/src/main/java/com/group16/dinoblog/model/PredictabilityState.kt` (session advice, decisions, metrics).
- `app/src/main/java/com/group16/dinoblog/database/DeliveryPredictabilityDao.kt`.
- `app/src/main/java/com/group16/dinoblog/repository/PredictabilityStore.kt`.
- `app/src/main/java/com/group16/dinoblog/dtn/PredictabilityPolicy.kt` (pure math/decision/summary ordering).
- `app/src/test/java/com/group16/dinoblog/PredictabilityTestStore.kt`.
- `app/src/test/java/com/group16/dinoblog/PredictabilityPolicyTest.kt`.
- `app/src/test/java/com/group16/dinoblog/SmartRelayEngineTest.kt`.
- `app/src/test/java/com/group16/dinoblog/SmartRelayTcpTest.kt`.
- `app/src/androidTest/java/com/group16/dinoblog/PredictabilityDatabaseTest.kt`.
- `app/src/androidTest/java/com/group16/dinoblog/SmartRelayDemoSetupTest.kt` (explicit hardware experiment fixture, skipped by ordinary tests).
- `app/schemas/com.group16.dinoblog.database.DinoblogDatabase/5.json`.
- `PHASE8_SMART_RELAYS.md` (this report).

TCP transport, Wi-Fi Direct, inventory manager and message/receipt entities are unchanged. The new policy lives in the existing engine/repository paths; it does not replace them.

## Persistent model and migration

Exact local Room entity:

```kotlin
DeliveryPredictability(
    destinationDeviceId: String, // primary key
    predictability: Double,
    lastUpdatedAt: Long,        // local Unix milliseconds
)
```

Table `delivery_predictabilities` contains only the local device's estimates. There is no raw encounter log or persisted remote-peer table. `MIGRATION_4_5` creates this empty table and preserves messages, copiesRemaining, peers and delivery receipts. All preceding migrations remain registered; no destructive migration or fake historical score initialization is used.

Rows are upserted through the existing repository in Room transactions. Application/database restart retains them, subject to normal lazy aging. Disconnect clears only remote/session state, not the local table. The generated schema 5 accompanies the migration.

## Configuration and formulas

All constants are in `DtnConfig.kt`:

| Setting | Value | Purpose |
|---|---:|---|
| P_INIT | 0.75 | Direct-encounter reinforcement |
| GAMMA | 0.98 | Decay multiplier per aging interval |
| BETA | 0.25 | Discount on transitive advice |
| AGING_INTERVAL_MS | 3,600,000 | One hour |
| FORWARDING_MARGIN | 0.10 | Minimum destination-score improvement |
| MAX_PREDICTABILITY_ENTRIES | 64 | Maximum entries in one wire summary |
| MAX_LOCAL_PREDICTABILITIES | 512 | Lightweight bounded local table |
| MIN_PREDICTABILITY | 0.001 | Omit/prune negligible scores |

Phase 7 constants remain initial copies=4 and active relay limit=100.

Direct encounter: `Pnew = Pold + (1-Pold)*0.75`, using the aged old value. First contact gives 0.75, a second contact at the same simulated time gives 0.9375.

Lazy aging: `Paged = Pold * 0.98^k`, where `k = max(0, elapsedMilliseconds)/3,600,000`. Fractional intervals preserve elapsed time across repeated reads rather than discarding partial hours. Values are aged when read, updated, compared or prepared for summary; no new background aging timer is added. Timestamp progression is monotonic under clock rollback, so rollback cannot increase a value or apply negative aging.

Transitivity: `P(local,dest) = max(agedCurrent, agedP(local,peer) * peerAdvertisedP(dest) * 0.25)`. For 0.8×0.9×0.25 the candidate is 0.18; a stronger local 0.70 is retained. Peer advice is never copied as the local score. Entries for self/the current peer are excluded from transitive learning.

Local math clamps values to [0,1] and maps non-finite corrupted local values conservatively to zero. Remote metadata is strictly rejected rather than accepting out-of-range values. Simple strongest-value pruning caps local rows at 512 after encounter/transitive/summary operations. Aging occurs before pruning.

## Encounter and summary lifecycle

The existing TCP transport publishes a peer UUID/session only after successful HELLO. `DeliveryEngine.retryPending` then calls `ensureEncounter`; Wi-Fi discovery alone cannot update a score. The existing session identifier and a routing mutex protect one encounter update per session. Successful persistence and its session flag complete together even if a StateFlow collector is cancelled. Later genuine reconnection has a fresh UUID token and may reinforce the peer again.

After inventory and Phase 6 receipt anti-entropy, the engine sends one bounded summary through the existing TCP writer:

```json
{
  "packetType": "PREDICTABILITY_SUMMARY",
  "type": "PREDICTABILITY_SUMMARY",
  "version": 2,
  "deviceId": "<HELLO peer UUID>",
  "entries": [
    { "deviceId": "<destination UUID>", "predictability": 0.82 }
  ]
}
```

The serializer may include unused WirePacket fields at null/default values; no original payload is sent. UUID identity must match HELLO. Entries are unique, finite, within [0,1] and limited to 64; the existing 64 KiB frame bound remains.

Summaries omit self and negligible scores, prioritize destinations relevant to active stored messages, then highest scores with stable UUID tie ordering. Relevant destinations are obtained as ID metadata from SQLite, without loading message bodies into the control packet.

The first valid peer summary triggers one transitive transaction. The engine publishes remote scores as usable only after that transaction finishes and the session is still current. Duplicate summaries do not reinforce encounters, reapply transitivity or replace the first summary. Local score changes never automatically echo another summary. No periodic score synchronization occurs.

Peer summary values are already aged at their sender and age lazily from local receipt time during a long encounter. Unknown/missing entries use 0.0. Disconnect or peer/session change clears remoteEntries, readiness flags, decisions and session statistics. Late Room/summary results are checked against the original peer/session before publishing or sending.

Malformed summary handling cannot poison local scores. The engine ignores invalid direct API packets without completing readiness. Invalid on-wire JSON/score frames are rejected by the existing parser and close that TCP session through its normal exception/reconnect handling; the application remains running. A subsequent valid encounter can exchange a fresh summary.

## Message-specific smart routing and safety

For each original message UUID, receipt/peer inventory/session guards remain authoritative. The pure Phase 7 classifier still enforces local-final content, status, metadata, expiry and hopLimit. The new score policy then chooses:

1. DIRECT when peer == destination, with no score or incoming-summary requirement.
2. WAIT when copiesRemaining == 1, regardless of a relay's excellent score.
3. SMART_SPRAY only with copies>1, a current completed summary exchange, and `peerScore-localScore >= 0.10`.
4. WAIT/KEEP otherwise; budget and hopCount remain unchanged.

A tiny 1e-12 arithmetic tolerance handles floating-point representation at the inclusive margin boundary. It does not turn negligible improvements into meaningful ones.

Scores are looked up by each message's destinationId, not by generic peer reputation. Sources and relays use the same function. A peer can be good toward D and poor toward E. High score is routing advice; it creates no receipt and is never proof that a payload was delivered.

Before actual writing, the existing conditional-send hook rechecks current session, identity, inventory, peer-known/rejected IDs, message validity and tombstones. `reserveSmartSpray` reads the current aged local destination score and performs the existing compare-and-update Binary Spray reservation within one Room transaction. Poor/changed scores cannot spend stale candidates' budgets. The engine checks current advice again at the writer boundary. A queued candidate cancelled after a successful reservation remains conservative, as in Phase 7.

SMART_SPRAY changes who gets the branch, not its binary split. UUID/source/destination/createdAt/expiresAt remain unchanged. HopCount increments only on the outgoing MESSAGE branch; score/summary updates consume neither hops nor copies.

Existing lost-ACK retries preserve the already-reserved outgoing branch and budget. A retained one-copy row may retry that same allocated branch in the same session, but cannot originate another relay branch. Current score/session checks also govern retry. Ambiguous disconnects never restore uncertain budget; explicit BUFFER_FULL uses Phase 7's one-time guarded restoration and sticky session rejection. Receiver buffer capacity, duplicate budget preservation, receipt/tombstone checks and direct-destination quota exemptions remain active.

Receipt synchronization still runs first. A persistent receipt or a peer's delivered summary suppresses the message before predictive candidate evaluation. DIRECT priority remains ahead of SMART_SPRAY. DIRECT may proceed while the peer's routing summary is missing, after the existing inventory/receipt guards; new relay branches cannot.

## Debug and metrics

The existing Debug screen now shows local aged score rows, current remote summary, encounter/summary flags, last direct score and transitive time, destination-specific local/peer scores and decision reasons beside UUID/copies/hops/receipt/buffer state.

Session metrics count successful DIRECT/SMART writer operations, smart custody ACKs, poor-score/margin rejections, WAIT decisions, newly received final messages, MESSAGE writes and receipt writes. Repeated observer decisions are deduplicated by message/reason. Statistics clear on disconnect and are lightweight debug counters, not network-wide analytics or persistent delivery proof.

## Verification

Final local verification (2026-10-13): application APK and instrumentation APK built successfully, **126 JVM tests passed**, lint passed with **0 errors and 2 existing warnings** (`OldTargetApi`, `UseKtx`).

```powershell
.\build.ps1 -Tasks ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest', '--offline'
```

The 104 previous regression tests retain explicit finite Phase 7 baseline behavior. The 22 new Phase 8 tests (7 pure policy, 12 default smart-engine, 3 real TCP) cover encounter reinforcement/deduplication, compositional aging, discounted transitivity, strong-local preservation, normalization, bounded/relevant summaries, margin/unknown/poor peers, destination-specific source/relay behavior, DIRECT/WAIT/receipt/hop/expiry/inventory authority, score changes while queued, stale sessions, malformed frames, lost ACK, BUFFER_FULL restoration/no loop and conservative ambiguous failure.

Real TCP logical-node demos passed:

- A/D=0.20, B/D=0.25, C/D=0.85: A keeps two copies at B, SMART_SPRAYs 1/1 to C, refuses a high-score E after reaching WAIT, C directly delivers to D, and a later receipt reaches A.
- Four-copy A→B→C→D with B's old score aged over 28 simulated hours and seven-day TTL: smart relay branches retain correct UUID, budget and hop depths.
- A high-score zero-capacity relay rejects once; sender restores safely and does not loop, while direct traffic to that same peer still succeeds.

New real Room instrumentation tests compile for migration 4→5, persistence/reopen/aging, transitivity, current-score atomic copy reservations, invalid metadata and bounded table/summary behavior. The explicit hardware demo setup fixture also compiles and is skipped during ordinary instrumentation unless its role/destination arguments are supplied.

Actual Android instrumentation, physical Wi-Fi Direct and reboot tests were **not run** in this managed session. `test-device.ps1 -CheckOnly` again failed because Windows profile lookup returned `0x80070002` with an empty path; native ADB cannot start here. JVM stores are not claimed as actual SQLite/reboot proof. Run the instructions below from a normal Windows user PowerShell with authorized phones.

## Exact physical-device demo

Use A/B/C/D and optionally E. Install the same Phase 8 build on every participating phone. Preserve app data, enable Wi-Fi/required permissions and record each persistent Device ID. Keep uninvolved devices disconnected at every encounter. No Internet/server is used.

Two setup options:

- Learned-score demonstration: C first encounters D successfully, so C/D becomes about 0.75; A and B have not met D, so their missing entries are 0. A declines B, then prefers C. Normal source creation uses four copies; the score-dependent behavior is the same.
- Exact requested experiment: the test-only fixture sets A/D=0.20, B/D=0.25, C/D=0.85 and creates one source A message with two copies. These are explicit synthetic experiment inputs, not historical scores invented during migration or normal app usage. The fixture preserves existing history/receipts, has no production UI control and runs only with explicit arguments.

For the exact experiment, open a normal PowerShell in this project. Set real A/B/C USB serials and D's Device ID, then install both APKs on A/B/C and initialize each role:

```powershell
$phase8Adb = 'C:\Users\tawan\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$phase8Destination = 'REPLACE_WITH_D_DEVICE_UUID'
$phase8Devices = @(
    @{ Role = 'A'; Serial = 'REPLACE_WITH_A_SERIAL' },
    @{ Role = 'B'; Serial = 'REPLACE_WITH_B_SERIAL' },
    @{ Role = 'C'; Serial = 'REPLACE_WITH_C_SERIAL' }
)
foreach ($phase8Device in $phase8Devices) {
    & $phase8Adb -s $phase8Device.Serial install -r app/build/outputs/apk/debug/app-debug.apk
    & $phase8Adb -s $phase8Device.Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    & $phase8Adb -s $phase8Device.Serial shell am force-stop com.group16.dinoblog
    & $phase8Adb -s $phase8Device.Serial shell am instrument -w -e class com.group16.dinoblog.SmartRelayDemoSetupTest -e phase8DemoRole $phase8Device.Role -e phase8DestinationId $phase8Destination com.group16.dinoblog.test/androidx.test.runner.AndroidJUnitRunner
}
```

D needs only the main Phase 8 APK. Do not leave an active peer connection during setup. Run setup once for A, since each explicit A setup creates a new demo UUID. Open A's Debug screen and record the UUID of `MSG001 Phase 8 demo`. Normal source creation remains copies=4; only this fixture's experiment row uses copies=2.

1. A↔B: wait for HELLO, both inventories, receipt processing and routing summaries. Debug should show approximately local 0.20 and peer 0.25 for D (minor elapsed aging is normal), WAIT with insufficient margin, A copies=2/hop=0 and no B payload row. Direct encounter toward B becomes about 0.75, but that is a different destination from D. Disconnect.
2. A↔C with B/D absent: fresh summary shows C/D about 0.85. A selects SMART_SPRAY, keeps 1 and C receives 1. Verify same original UUID/source/destination, A hop=0 and C hop=1. Both are WAIT carriers after the split. A relay ACK must not mark final delivery. Disconnect.
3. If E is available, let E learn a strong D estimate or use the equivalent seeded logical test. Connect A↔E while E is not D and lacks the payload. A keeps its one copy even if E's score is excellent. No new MESSAGE branch. Disconnect. This rule is independently covered by default-engine/TCP tests.
4. C↔D while A/B are offline: DIRECT ignores predictive score and accepts the one-copy message. Verify D stores/displays it once under original source A, hop=2, sends normal ACK and creates the existing persistent delivery receipt. Disconnect. Receipt transfer can also complete in this encounter.
5. D↔C, then C↔A through later disconnected encounters: verify receipt reaches A and original outgoing UUID becomes DELIVERED; no direct A↔D connection is required. Select D's UUID in A's Chat if needed, or verify in global Debug. Remaining routing attempts are suppressed.
6. Force-stop/reopen a node with routing values. Verify its local predictability table persists and remote summary is empty until a new peer/session exchange. Reboot can be tested similarly. Reconnect A/B and verify each genuine session reinforces direct peer score once; repeated packets inside it do not repeatedly increase the value.
7. For buffer acceptance testing, follow Phase 7's small-quota procedure: a high-score relay that is full must return BUFFER_FULL with one-time restoration/no same-session retry loop. Final traffic to that peer must remain exempt. Receipt and expired-payload reclamation rules are unchanged.

For real Room instrumentation (ordinary suite, no demo seeding):

```powershell
.\test-device.ps1 -Serial <adb-serial>
```

See `DEVICE_TESTING.md` for ADB setup. This runs database tests on one authorized Android device; physical multi-phone acceptance is the separate procedure above. No further phase is implemented.
