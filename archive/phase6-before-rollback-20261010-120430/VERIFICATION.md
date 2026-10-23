# Phase 6 Delivery Receipts 2.8 verification - 2026-10-07

Current application: **2.8-delivery-receipts**, versionCode **11**. Room schema is
**3**, with additive migration **2 -> 3** registered alongside **1 -> 2**. Existing
messages/peers entity definitions compare identically in exported schemas 2 and 3.
The same version-2 wire envelope adds receipt packets and optional receipt inventory
capability; no networking stack or original message table was replaced.

Continuation review: production Phase 6 was already implemented and was retained.
The review added four boundary regression tests in `DeliveryReceiptTest.kt`. Device
test preparation adds two real-Room cases in `DeliveryReceiptDatabaseTest.kt`, a
profile-aware `test-device.ps1` launcher, `DEVICE_TESTING.md`, and workspace build
defaults in `build.ps1`. No application code, database version, or application version
change was necessary. Exported schema comparison again confirms
that both existing `messages` and `peers` entities are identical in schema 2 and 3.

Latest full build: **BUILD SUCCESSFUL in 21s** (79 tasks, 8 executed / 71 up-to-date),
using plain `.\build.ps1` with its new workspace defaults. The Android test APK was
rebuilt with the two additional Room tests. The unchanged host tests were UP-TO-DATE;
their last executed run passed all 99 tests in the preceding 52-second audit build.

| Check | Result |
| --- | --- |
| `:app:assembleDebug` | PASS; APK metadata confirms 2.8-delivery-receipts / versionCode 11 |
| `:app:testDebugUnitTest` | **99 passed**, 0 failures/errors/skips; all 77 Phase 1-5 tests plus 22 Phase 6 tests |
| `:app:lintDebug` | PASS, 0 errors and 17 existing warnings |
| `:app:assembleDebugAndroidTest` | PASS; 17 Android/Room test methods compile, including 6 Phase 6 Room tests |
| Android Room instrumentation | NOT EXECUTED: ADB cannot start in the current environment |
| Physical multi-phone Wi-Fi Direct | NOT EXECUTED; exact procedure in the Phase 6 report |

| JVM suite | Passed |
| --- | ---: |
| DeliveryEngineTest | 21 |
| DeliveryReceiptTest | 20 |
| DeliveryReceiptTcpTest | 2 |
| DestinationForwardingTest | 11 |
| EpidemicRoutingTest | 12 |
| EpidemicTcpTest | 2 |
| InventoryManagerTest | 10 |
| InventoryProtocolTest | 3 |
| InventoryTcpTest | 1 |
| PeerIdentityHandshakeTest | 6 |
| StoreCarryForwardTcpTest | 2 |
| TcpTransportTest | 1 |
| WireProtocolTest | 8 |

The two real-TCP tests send three original messages over separate A/B, B/C and
C/D connections, with another B/E payload branch. Only D creates receipts. Receipts
return over D/E, E/B and B/A, confirming delivery at the original source without a
reverse route or end-to-end connection. They also verify D/C receipt branching,
immutable path depths, retained original copies, no receipt echo, fresh-session
inventory, lost receipt ACK recovery, and an empty F receiving only receipts from
informed B with no original MESSAGE transfer. The tests use actual localhost TCP
and the production engine/protocol/inventory manager, with an in-memory transactional
store rather than Android Room.

The 20 focused tests cover atomic-final-receipt contract and ACK ordering, stable
creation on duplicates, relays never generating receipts, simulated storage failure,
source/carrier DELIVERED update, suppression before receive and during admission,
expired knowledge retention, inclusive hop limit, immutable branches, four-class
routing priority, in-flight/retry guards, typed ACK isolation, no echo, disconnect
cleanup, stale receipt queries, receipt capability compatibility, malformed receipt
rejection, combined inventory frame bounds and duplicate re-ACK after expiry.

The four newly executed cases specifically verify same-peer/new-session lookup and
ACK isolation despite fresh inventory, expiry during a suspended receipt lookup,
peer knowledge arriving during lookup, and duplicate final MESSAGE after engine
recreation without renewing receipt timestamps/lifetime or changing stored branch
depth. Engine recreation uses the same in-memory store and is not presented as an
executed Android persistence test.

Six compiled Phase 6 Room tests cover v2 -> v3 migration preserving messages/peers,
final message/receipt commit before ACK and on-disk reopen, receipt-first admission
and source status update without deletion, and injected SQLite receipt-insert failure
rolling back the final payload with no ACK. Two added cases verify that a relay's
receipt survives database close/reopen and forwards directly to its original source
without the suppressed payload, and that a failed delivery-status update rolls back
the received receipt and sends no ACK. Existing Android regression expectations
now account for final receivers advertising receipt IDs. These are compiled tests,
not claims of executed Android database behavior.

An initial lost-ACK TCP test timed out because its fault injector discarded receipt
ACKs from every later peer, including F. Restricting that injector to the intended
B/A encounter resolved the test; the final complete suite above passes.

Native `adb devices -l` (and even `adb version`) exits with code 1 in the agent account:

```text
Cannot mkdir '\.android': Permission denied
```

Root cause is now confirmed: `SHGetFolderPathW(CSIDL_PROFILE)` returns `0x80070002`
(profile not found), with an empty path, for `MSI\CodexSandboxOffline`. Windows ADB
uses that API and therefore constructs the incorrect root-level `.android` path;
the SDK preference environment overrides do not change its native profile lookup.
The user reports a connected phone, but this restricted account cannot enumerate it.

`test-device.ps1 -CheckOnly` was syntax-checked and executed here: it detects this
specific profile failure and gives actionable instructions before invoking ADB.
Its real-device success path and Android test execution await running it from the
normal signed-in desktop PowerShell account. See [DEVICE_TESTING.md](DEVICE_TESTING.md).
No registry, machine environment, account, ACL, or sandbox permissions were changed.

Build command:

```powershell
$env:GRADLE_USER_HOME = Join-Path $PWD '.tools/gradle-home'
$env:ANDROID_USER_HOME = Join-Path $PWD '.tools/android-home'
.\build.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest', '-Pkotlin.compiler.execution.strategy=in-process')
```

Artifacts: `app/build/outputs/apk/debug/app-debug.apk`,
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`,
`app/build/reports/tests/testDebugUnitTest/index.html`,
`app/build/reports/lint-results-debug.html`. Latest build log:
`.tools/phase6-device-preparation-build.log`; last executed JVM log: `.tools/phase6-audit-final.log`.
The earlier `.tools/phase6-build-final.log` records the initial 95-test implementation.

[PHASE6_DELIVERY_RECEIPTS.md](PHASE6_DELIVERY_RECEIPTS.md) contains the full file list,
migration, JSON format, receipt persistence/inventory/ACK/routing/suppression design,
exact protocol flow and physical five-phone acceptance instructions. Work stops at
Phase 6; no advanced routing, replica deletion or Phase 7 feature is added.

## Historical Phase 5 verification

# Phase 5 Epidemic Routing 2.7 verification - 2026-10-07

Current application: **2.7-epidemic-routing**, versionCode **10**. Room schema **2**
and wire protocol **2** are unchanged. Phase 5 code was already present at the start
of this review and was reused. Four additional regressions and UI wording corrections
were added; no replacement routing stack or database was introduced.

Final build: **BUILD SUCCESSFUL in 1m 14s** (79 tasks, 19 executed / 60 up-to-date).
The JVM test task executed during this final build; it was not an up-to-date-only result.

| Check | Result |
| --- | --- |
| `:app:assembleDebug` | PASS, no compilation errors; APK metadata confirms 2.7-epidemic-routing / 10 |
| `:app:testDebugUnitTest` | **77 tests passed**, 0 failures/errors/skips |
| `:app:lintDebug` | PASS, 0 errors and 17 existing warnings |
| `:app:assembleDebugAndroidTest` | PASS, 11 Android/Room test methods compile |
| Android instrumentation | NOT EXECUTED: ADB cannot start in this environment |
| Physical four-phone Wi-Fi Direct | NOT EXECUTED; follow the acceptance procedure linked below |

JVM suite counts:

| Suite | Passed |
| --- | ---: |
| DeliveryEngineTest | 21 |
| DestinationForwardingTest | 11 |
| EpidemicRoutingTest | 12 |
| EpidemicTcpTest | 2 |
| InventoryManagerTest | 10 |
| InventoryProtocolTest | 3 |
| InventoryTcpTest | 1 |
| PeerIdentityHandshakeTest | 6 |
| StoreCarryForwardTcpTest | 2 |
| TcpTransportTest | 1 |
| WireProtocolTest | 8 |

Real localhost TCP tests exercise three messages over separate A/B, B/C and C/D
encounters. A is closed before B/C and B is closed before C/D. C is not the final
destination during B/C. The tests verify stable original metadata, CARRIED relays,
final RECEIVED rows, no echo, no duplicate rows, final ACK, disconnect cleanup and
a separate B/E branch receiving depth 2 while B retains depth 1. A second run drops
relay ACK handling and verifies inventory prevents retransmission after reconnect.
These use an in-memory MessageStore, not Android SQLite or physical radios.

Focused tests cover DIRECT-before-RELAY ordering, known/in-flight rejection,
inventory/HELLO/session gating, expiry, exact hop boundaries, immutable branch
depths, stale Room reads, idempotent receive and ACK semantics. The four cases
added in this pass specifically prove failed relay retry after fresh inventory,
FORWARDED-origin replication to another peer at the same outgoing depth, no revival
or false delivery after a late relay ACK, and no routing of locally final content.

Compiled Android tests include Room schema migration, on-disk reopening, duplicate
guards, final ACK SQL guards, relay replication without sender hop mutation and
inventory advertisement of a copy at its hop limit. Runtime results remain pending.

The current `adb devices -l` attempt exited with code 1:

```text
Cannot mkdir '\.android': Permission denied
```

This prevents device enumeration and instrumentation here; it does not establish
whether phones are physically attached. No hardware success is inferred.

Command used:

```powershell
$env:GRADLE_USER_HOME = Join-Path $PWD '.tools/gradle-home'
$env:ANDROID_USER_HOME = Join-Path $PWD '.tools/android-home'
.\build.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest', '-Pkotlin.compiler.execution.strategy=in-process')
```

Artifacts: `app/build/outputs/apk/debug/app-debug.apk`,
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`,
`app/build/reports/tests/testDebugUnitTest/index.html`, and
`app/build/reports/lint-results-debug.html`. Log: `.tools/phase5-build-final.log`.

See [PHASE5_EPIDEMIC.md](PHASE5_EPIDEMIC.md) for the inspected architecture, modified
and new files, all forwarding/ACK/storage/session semantics, exact protocol sequence,
and physical four-phone acceptance steps. Phase 6 is not implemented.

## Historical Phase 4 verification

# Phase 4 Store-Carry-Forward 2.6 verification - 2026-10-07

Current application: **2.6-store-carry-forward**, versionCode **9**. Room schema **2**
and wire protocol **2** are unchanged.

Final full build: **BUILD SUCCESSFUL in 1m 9s** (79 tasks, 22 executed / 57 up-to-date).

- `:app:assembleDebug`: PASS, no compilation errors. APK metadata confirms versionCode
  9 and versionName 2.6-store-carry-forward.
- `:app:testDebugUnitTest`: **63 tests passed**, 0 failures/errors/skips.
- `:app:lintDebug`: PASS, 0 errors and 17 existing warnings.
- `:app:assembleDebugAndroidTest`: PASS; all **10** Android/Room test methods compile.
- Physical three-phone Wi-Fi Direct and Android instrumentation: **NOT EXECUTED**.
  ADB startup in this execution environment was blocked by its unwritable
  `\.android` location during the preceding Phase 3 verification. No working device
  connection or configured emulator is available to this run. No hardware result
  is inferred from successful localhost sockets or compilation.

JVM suite counts:

| Suite | Passed |
| --- | ---: |
| DeliveryEngineTest | 21 |
| DestinationForwardingTest | 11 |
| InventoryManagerTest | 10 |
| InventoryProtocolTest | 3 |
| InventoryTcpTest | 1 |
| PeerIdentityHandshakeTest | 6 |
| StoreCarryForwardTcpTest | 2 |
| TcpTransportTest | 1 |
| WireProtocolTest | 8 |

The two new real-TCP tests run A-to-B custody first, shut down A completely, then
connect B-to-C and automatically deliver multiple original UUIDs with hop 2. They
verify C RECEIVED/B DELIVERED after normal ACKs, preservation of source/payload/time
fields, a selectable original A conversation, and retention of a D-addressed bundle
on B. One deliberately discards C's final ACK: B retains CARRIED, while reconnecting
C advertises its RECEIVED IDs and B emits no new MESSAGE for those IDs. A remains
FORWARDED; no delivery receipt propagates back to A.

Focused forwarding tests cover gating on HELLO plus sent/received inventory, mixed
destinations, remote-known IDs, invalid status, expiry including time advancing during
a Room lookup, strict hop admission, multiple candidates, attempt/peer/session ACK
guards, duplicate ACKs, receiver commit-before-ACK, storage failure, rejection of
arbitrary relay forwarding, same-hop retries, duplicate alternate path/expired receipts,
old-session ACK rejection, slow reads crossing reconnect, and persistent custody after
failed sends. Existing Phase 1-3 direct, custody, handshake, inventory and TCP regressions
all pass. Phase 3's no-forward tests remain explicitly scoped to no delivery coordinator
or no completed inventory, rather than disabling the new production feature.

New Room instrumentation tests compile coverage for on-disk B custody/reopen followed
by B-to-C delivery into actual Room, receipt ID querying, duplicate suppression, and
the new guarded atomic delivery update. They await execution on an Android device.

Command used (workspace caches/signing setup retained from Phase 3):

```powershell
$env:GRADLE_USER_HOME = Join-Path $PWD '.tools/gradle-home'
$env:ANDROID_USER_HOME = Join-Path $PWD '.tools/android-home'
.\build.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest', '-Pkotlin.compiler.execution.strategy=in-process')
```

Artifacts: `app/build/outputs/apk/debug/app-debug.apk`,
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`,
`app/build/reports/tests/testDebugUnitTest/index.html`, and
`app/build/reports/lint-results-debug.html`. Build log: `.tools/phase4-build-final.log`.

See [PHASE4_FORWARDING.md](PHASE4_FORWARDING.md) for the complete modified/added file
list, forwarding/ACK/session design, limitations, final protocol flow and exact
14-step three-phone demo. No Phase 5 or epidemic routing was implemented.

## Historical Phase 3 verification

# Phase 3 Inventory 2.5 verification - 2026-10-06

Current application: **2.5-inventory**, versionCode **8**. Room schema and wire
protocol remain **2**. Phase 3 production code was already present and was reviewed
and reused; this pass adds integration/database coverage and updates documentation.

Executed `build.ps1` with workspace-local Gradle/Android caches: **BUILD SUCCESSFUL
in 56s**.

- `:app:assembleDebug`: PASS; APK metadata confirms versionCode 8 / 2.5-inventory.
- `:app:testDebugUnitTest`: **50 tests passed**, 0 failures/errors/skips.
  DeliveryEngineTest 21, WireProtocolTest 8, TcpTransportTest 1,
  PeerIdentityHandshakeTest 6, InventoryManagerTest 10, InventoryProtocolTest 3,
  InventoryTcpTest 1.
- `:app:lintDebug`: PASS, 0 errors and 17 non-blocking warnings.
- `:app:assembleDebugAndroidTest`: PASS; eight Android/Room test methods compile.
- Android instrumentation and physical two-phone tests: **NOT EXECUTED**. The local
  ADB executable exits with `Cannot mkdir '\.android': Permission denied` in this
  restricted execution environment. Redirect attempts did not resolve ADB startup;
  this is not evidence that no physical devices are attached. No configured local
  AVD was found.

Real localhost TCP coverage now includes both InventoryManagers exchanging overlapping
inventories, duplicate receipt, disconnect clearing, same-peer reconnect with a fresh
session, and rejection of sends bound to an old session. Another real-socket test
attaches both DeliveryEngine and InventoryManager to B: B advertises a CARRIED bundle
addressed to C, receives C's empty inventory and reports the bundle missing, then an
explicit retry produces no MESSAGE bytes. The stored bundle stays unchanged.

Deterministic manager tests cover no send before HELLO, set comparison and empty
results, no response/forward triggered by receipt, reconnect, slow reads after
disconnect, identity mismatch, failed storage reads and overlarge inventories.
Protocol tests verify UUID validation, ID-only JSON, round trips and frame bounds.

The two new Android tests cover actual Room eligibility and expiry/hop boundaries,
invalid rows, repeated comparison without writes, disconnect clearing, and persisted
rows across database reopen. These compile but require Android execution before
claiming that runtime Room behavior has passed. Loopback TCP does not validate
physical Wi-Fi Direct discovery/radio behavior.

Build environment notes: the default Gradle home resolved to unwritable `C:\.gradle`.
Existing dependency caches were copied into ignored `.tools/gradle-home`, and the
existing debug signing keystore was copied into ignored `.tools/android-home` so APK
signing remains compatible with installed builds. No new signing identity was created.
The Kotlin daemon could not write its usual user directory, so Gradle successfully
used its compiler fallback. For this workspace the same tasks can be run with:

```powershell
$env:GRADLE_USER_HOME = Join-Path $PWD '.tools/gradle-home'
$env:ANDROID_USER_HOME = Join-Path $PWD '.tools/android-home'
.\build.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest', '-Pkotlin.compiler.execution.strategy=in-process')
```

See [PHASE3_INVENTORY.md](PHASE3_INVENTORY.md) for the precise modified/added file list,
final JSON, eligibility policy, snapshot limitations and two-phone fixture procedure.
No carried-message forwarding or Phase 4 implementation was added.

## Historical Phase 2 verification

# Peer Identity / HELLO 2.4 verification - 2026-10-06

Current application: `2.4-peer-identity`, versionCode `7`. Room schema is unchanged at version `2`. Wire protocol version remains `2` with backward-compatible `packetType`/legacy `type` handling.

Executed `./build.ps1`: **BUILD SUCCESSFUL in 24s**.

- `:app:assembleDebug`: PASS, no compilation errors; APK metadata confirms versionCode 7 / 2.4-peer-identity.
- `:app:testDebugUnitTest`: **36 tests passed**, 0 failures/errors (DeliveryEngineTest 21, WireProtocolTest 8, TcpTransportTest 1, PeerIdentityHandshakeTest 6).
- `:app:lintDebug`: PASS, 0 errors and 17 non-blocking warnings.
- `:app:assembleDebugAndroidTest`: PASS; the existing six Android/Room instrumentation tests compile.
- `adb devices`: no attached devices/emulators. Android instrumentation and physical two-phone Wi-Fi Direct tests were **not executed**.

Verified using real localhost TCP: both transports automatically exchange their local UUID and identify the opposite UUID; both clear identity on disconnect; reconnect works; a new UUID on the same IP replaces the previous peer; stop during HELLO clears waiting/identity; invalid/self/non-HELLO/repeated-HELLO packets cannot leave stale identity; a silent peer hits the production 10-second HELLO timeout. Tests also exercise the exact Debug state labels, required log contents, minimal packetType-only HELLO, legacy type-only MESSAGE/ACK/HELLO and conflicting-discriminator rejection.

A new real-socket test attaches the unchanged DeliveryEngine to B with an in-memory CARRIED bundle addressed to C, completes HELLO from C, retries and confirms no bundle bytes are sent. Existing Room persistence tests remain compiled separately; loopback tests are not a substitute for physical Wi-Fi Direct or real Room instrumentation.

Known limitations: foreground networking, OEM Wi-Fi Direct behavior pending hardware validation, unauthenticated UUID assertions, and delayed detection of silent radio loss until socket/system timeout. No Phase 3, inventory, routing or carried-message forwarding was added. See [PHASE2_HELLO.md](PHASE2_HELLO.md) for the full modified/added file list and exact hardware acceptance procedure.

## Previous 2.3.1 results (historical)

# Store & Carry 2.3.1 verification - 2026-10-06

Current application: `2.3.1-store-carry`, versionCode `6`. Room schema and wire protocol remain at version `2`; neither was changed in this update.

Executed `./build.ps1`: **BUILD SUCCESSFUL**.

- `:app:assembleDebug`: PASS; APK metadata confirms versionCode 6 and versionName 2.3.1-store-carry.
- `:app:testDebugUnitTest`: **28 tests passed**, zero failures/errors (DeliveryEngineTest 21, WireProtocolTest 6, TcpTransportTest 1).
- `:app:lintDebug`: PASS, 0 errors and 17 warnings.
- `:app:assembleDebugAndroidTest`: PASS; six Android instrumentation tests compile.
- `adb devices`: no devices attached; Android instrumentation and two-phone physical tests **not executed**.

New/updated tests cover rejection at exact hop limit and exact expiration, over-budget input, just-before-expiration acceptance, preventing a send whose incremented hop is already inadmissible, acceptance without an optional carrier hint, and full DTN RECEIVE fields. Existing direct messages, custody ACK, duplicate suppression, restart/retry, expiry and no-B-to-C-forwarding regressions pass. The added Android test runs DeliveryEngine against actual Room, checks commit-before-ACK, rejects new expired/exhausted rows, deduplicates repeated receives, then closes/reopens the database and verifies the carried bundle; it is compiled but awaits an Android device.

For an existing UUID, re-ACK after expiration still confirms past storage and performs no new insert, expiration renewal or forwarding. This preserves existing lost-ACK handling. New storage uses the strict `hopCount < hopLimit` and `now < expiresAt` rules. A hop-limit-1 message now cannot be newly admitted at B; older 2.3 documentation allowing this is superseded.

See [STORE_CARRY.md](STORE_CARRY.md) for exact two-phone tests and the full list of nine modified files. No changes to identity/model/Room schema, Wi-Fi Direct, TCP transport, wire framing, chat layout or retry/ACK mechanisms were needed.

## Previous 2.3 results (historical)

# Store & Carry 2.3 verification - 2026-10-06

Current application: `2.3-store-carry`, versionCode `5`, package `com.group16.dinoblog`, Room schema `2`, wire protocol `2`.

Executed `./build.ps1` after implementation:

- `:app:assembleDebug`: PASS, APK generated, no compilation errors.
- `:app:testDebugUnitTest`: PASS, **26 tests**, zero failures/errors.
- `:app:lintDebug`: PASS, 0 errors and 17 non-blocking warnings.
- `:app:assembleDebugAndroidTest`: PASS, instrumentation test APK compiled.
- `adb devices`: no devices attached. Android instrumentation tests and physical Wi-Fi Direct tests were **not run**.

JVM coverage: DeliveryEngineTest 19, WireProtocolTest 6, TcpTransportTest 1. New cases exercise A-to-B custody for C, storage-before-custody-ACK, lost ACK with deduplication and stable hop count, correct-peer/status ACK guards, no forwarding after engine restart or simulated C connection, hop exhaustion, expiry, conflicting IDs/content, failed storage, persisted carrier choice, and validation/round-trip serialization of DTN fields. Existing direct-chat regression tests still pass. The socket regression uses real localhost TCP; delivery tests use an in-memory MessageStore and deterministic time, not Android Room.

One new test initially assumed only one send when startup retry and peer-change retry ran together before an ACK. It was corrected to finish startup while the unrelated peer was connected, then trigger the carrier connection explicitly. The final complete build and all 26 tests pass; no production retry checks were removed to make it pass.

Five Android instrumentation tests compile: the two previous DAO tests, plus migration validation against exported schema v1/v2, carried-message on-disk close/reopen and deduplication, and custody ACK/peer-selection SQL guards. These require a connected Android device/emulator to execute:

```powershell
.\build.ps1 -Tasks @(':app:connectedDebugAndroidTest')
```

Room migration is additive and registered in the production builder; no destructive migration is enabled. Hardware verification of upgrade-in-place, UI layout, Wi-Fi Direct and force-stop persistence remains outstanding. Follow [STORE_CARRY.md](STORE_CARRY.md) for exact two-phone acceptance steps; C may be an absent placeholder UUID.

Artifacts: `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/lint-results-debug.html`.

## Historical verification for 2.2 (not current results)

# Offline Chat 2.2 verification - 2026-10-06

Version `2.2-offline-chat`, versionCode `4`, applicationId `com.group16.dinoblog`.

## Executed checks

Command: `./build.ps1`

| Check | Result |
|---|---|
| `:app:assembleDebug` | PASS — APK built, no compilation errors |
| `:app:testDebugUnitTest` | PASS — 18 tests, 0 failures, 0 errors |
| `:app:lintDebug` | PASS — 0 errors, 17 warnings |
| `:app:assembleDebugAndroidTest` | PASS — Android test APK compiled |
| Room schema compatibility | Version 1 schema hash exactly matches the archived first version |
| APK metadata | `2.2-offline-chat` / versionCode 4 |
| `adb devices` | No devices attached |

Lint warnings are dependency/Gradle version recommendations, target SDK and a KTX style suggestion. No lint baseline or disabled error checks were introduced.

## Coverage

- **DeliveryEngineTest: 12 tests.** Pending persistence through engine recreation, retry on correct-peer connection, correct-peer ACK, duplicate suppression, storage before ACK, storage failure without ACK, TTL expiry, expired unseen-message rejection, saved duplicate re-ACK after expiry, no forwarding to unrelated peers, UUID conflict rejection, lost-ACK round trip with two engines, exact Unicode/newline/whitespace preservation and blank rejection, late ACK after sender expiry. Some tests cover more than one behavior.
- **WireProtocolTest: 5 tests.** UTF-8/newlines with one-byte fragmentation and coalesced packets, frame bounds, invalid IDs/version/payload, truncated frame and TTL overflow.
- **TcpTransportTest: 1 test.** Real localhost server/client HELLO, message, ACK and reconnect.

Delivery tests use a fake MessageStore and deterministic time. The TCP test uses real host sockets, not Wi-Fi Direct hardware.

## Room compatibility and Android tests

The application reuses `dinoblog.db` and Room schema version 1 with no destructive migration. The newly generated schema and `archive/previous-full-version/app/schemas/com.group16.dinoblog.database.DinoblogDatabase/1.json` both have SHA-256:

`EE33AFBE541DAF90EB3E9CFFFE909F60954474F922405260421394E47A881BA6`

Two Android instrumentation tests are compiled: actual SQLite deduplication/ACK/TTL queries, and on-disk database close/reopen preserving pending history. They were **not executed**, because no Android device/emulator was attached. Run `./build.ps1 -Tasks @(':app:connectedDebugAndroidTest')` with a device to execute them.

## Hardware checks remaining

Install the 2.2 APK on both phones and follow README.md to verify Wi-Fi Direct discovery, permission prompts, group formation, keyboard/UI, full A ↔ B chat, offline/reconnect delivery and persistence across real app restarts. No physical-phone success is claimed from the JVM tests.

Networking remains foreground-only. A lost Wi-Fi Direct group requires user reconnection; TCP and queued-message retries then resume automatically. Messages from versions 2.0/2.1 were memory-only and cannot be recovered after their process exits. Existing version-1 Room history can be reused if app data was retained.

## Artifacts

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`

Earlier source and documentation snapshots remain in `archive/`; they are outside Android source sets.
