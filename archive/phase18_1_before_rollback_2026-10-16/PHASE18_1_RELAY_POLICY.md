# Phase 18.1 — per-message relay permission

## Changed files

| Area | Files |
| --- | --- |
| Android model and persistence | `app/src/main/java/com/group16/dinoblog/model/Message.kt`, `database/Converters.kt`, `database/DinoblogDatabase.kt`, `database/MessageDao.kt`, `DinoblogApplication.kt`, generated Room `14.json` |
| Android signing and transport | `security/CanonicalSignatures.kt`, `security/CryptoIdentityManager.kt`, `network/WireProtocol.kt`, `dtn/DeliveryEngine.kt`, `repository/MediaStore.kt`, `repository/MediaRepository.kt`, `online/TransportCoordinator.kt` |
| Android UX and version | `repository/ProfileRepository.kt`, `viewmodel/AppViewModel.kt`, `ui/ChatScreen.kt`, `ui/ProfileScreens.kt`, `ui/DinoblogApp.kt`, `app/build.gradle.kts` |
| Android tests | `app/src/test/java/com/group16/dinoblog/Phase11EngineTest.kt`, `app/src/androidTest/java/com/group16/dinoblog/Phase11MediaTest.kt`, `app/src/androidTest/java/com/group16/dinoblog/Phase18MigrationTest.kt` |
| Server and docs | `server/src/index.ts`, `server/test/security-fixture.mjs`, `server/test/mailbox.test.mjs`, `server/test/revoke-migration.test.mjs`, `server/README.md`, `README.md` |

New files are `app/src/main/java/com/group16/dinoblog/model/RelayPolicyRules.kt`, `app/src/test/java/com/group16/dinoblog/Phase18_1RelayPolicyTest.kt`, and this guide. No server migration file is needed because the existing server performs additive startup migrations in `server/src/index.ts`.

## Behavior and trust boundary

Each new outgoing TEXT, IMAGE, AUDIO, or VIDEO message stores one immutable `RelayPolicy`:

| Policy | Permitted original-payload paths |
| --- | --- |
| `ALLOW_RELAY` | Existing direct, authenticated DTN relay, and source-owned server paths, subject to all existing eligibility checks. |
| `DIRECT_ONLY` | Original source → final destination over authenticated Wi-Fi Direct or Bluetooth, or original source → DinoBlog server → destination. It waits locally when neither path is available. |

The sender's policy and the receiving carrier's existing `relayEnabled` consent are independent. Third-party carriage needs both `ALLOW_RELAY` and `relayEnabled=true`, plus the existing buffer, expiry, receipt, recall, copy, and PRoPHET checks. `DIRECT_ONLY` does not change BLE names, Wi-Fi device names, authenticated Device IDs, media limits, transport choice, or server routing. It reduces third-party payload exposure; it does **not** provide end-to-end encryption. The server can still read payloads.

Settings → Message Delivery selects the default, initially **Allow nearby relay**. The compact Delivery menu in the composer can override it for one new message without changing Settings. After a successful queue, the composer returns to the current default. Long-press an outgoing bubble to inspect its policy. A pending direct-only message with neither the destination connected nor the server online reads **Waiting for recipient**. The profile repository uses the existing `chat_preferences` SharedPreferences store, so the new `default_relay_policy` key is stored there; this project has no DataStore profile store.

## Persistence, wire format, and signature

- Android Room schema **14** adds `messages.relayPolicy TEXT NOT NULL DEFAULT 'ALLOW_RELAY'` via `MIGRATION_13_14`; the generated schema is `app/schemas/com.group16.dinoblog.database.DinoblogDatabase/14.json`. Existing rows preserve historical relay behavior. The earlier 12→13 recall migration remains registered. No destructive migration is used.
- The server's additive SQLite migration adds `messages.relay_policy TEXT NOT NULL DEFAULT 'ALLOW_RELAY'` and sets `user_version=19`. Old rows remain `ALLOW_RELAY`; the original source authorization, signed revoke tables, media tables, and sync cursor are unchanged.
- New messages use **message signature version 2**. The canonical sequence is the existing length-prefixed UTF-8 `DINOBLOG_MESSAGE`, `2`, `messageId`, `sourceId`, `destinationId`, `contentType`, `createdAt`, `expiresAt`, `hopLimit`, then **`relayPolicy`**, payload SHA-256, media ID, MIME type, size, and media SHA-256. Mutable `hopCount`, `copiesRemaining`, status, and carrier are excluded. Android and server share a deterministic version-two hash fixture. A changed policy invalidates the source signature.
- Historical version-one signatures retain their exact old canonical sequence and are accepted only with `ALLOW_RELAY`. The new client reads missing wire policy as `ALLOW_RELAY`. New version-two messages require an upgraded recipient; older APKs only know version one.
- The server verifies version-two canonical bytes before insert, requires the authenticated uploader to equal `sourceId`, stores the signed policy, and includes it unchanged in sync. Same `messageId` with different policy is `MESSAGE_ID_CONFLICT`, even if re-signed. The Android repository's duplicate checks also reject immutable-content conflicts. No normal UI allows editing a sent policy.

## Peer delivery order

The existing authenticated HELLO establishes the connected peer's stable Device ID first. Receipt/revoke/expiry suppression remains in the existing send path. For an eligible original payload, final-destination delivery has priority. A third-party candidate with `DIRECT_ONLY` is rejected as `SKIP_RELAY_POLICY` before PRoPHET or copy reservation; this is recorded as a routing decision, not a failed transfer. `ALLOW_RELAY` continues through `relayEnabled`, media capability, copy budget, buffer, and the unchanged routing algorithm. The Room spray reservation query also requires `ALLOW_RELAY`.

On receipt over a peer transport, `DIRECT_ONLY` original payload is accepted only when `localDeviceId == destinationId` **and** the authenticated connected peer equals `sourceId`. Thus A→B (B is a relay) and B→D (B holds A's message) both return `RELAY_POLICY_DIRECT_ONLY` without a custody ACK. IMAGE/AUDIO/VIDEO manifests are rejected before part-file allocation; chunks recheck the same rule before writing. A→D directly still accepts normal chunks, hash validation, resume, and delivery receipts across Wi-Fi Direct or Bluetooth. Online sync uses the separately authenticated server path, not the peer-only rule.

Signed Delivery Receipts, REVOKE, and REVOKE_RESULT remain control metadata and may propagate under their existing rules even for a direct-only original message. Phase 18 authorization still uses Device ID and signatures, never the relay policy as a substitute for identity.

Experiment events include `MESSAGE_RELAY_POLICY` on creation and `ROUTING_DECISION` with `SKIP_RELAY_POLICY` for a blocked third-party candidate. The event CSV can distinguish policy skips from transfer failures; existing experiment summary columns and routing formulas are unchanged.

## Verification in this workspace

| Check | Result |
| --- | --- |
| Android `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleDebugAndroidTest` | Passed; 173 JVM tests in 32 suites, 0 failures. Android test APK compiled. |
| Node `npm run build`, `npm test` | Passed; 11 tests, 0 failures. Includes v2 canonical fixture, server authorization, retry conflict, sync, legacy schema migration, and Phase 18 recall regression. |
| Android unit cases | Cover signed policy tamper, v1 compatibility, routing skip/direct path, authenticated peer acceptance, immutable bundle comparison, and TEXT receiver rejection. |
| Android instrumentation cases | Compiled for 12→14 migration and direct-only media manifest/chunk admission; not executed here. |
| Physical radio/media/online cases | Not executed in this managed shell: native Windows ADB stops at profile API `0x80070002`. Run `./test-device.ps1` in a normal user PowerShell with phones connected. |

The existing Phase 1–18 JVM suite passed. Phase 15 signature fixtures, Phase 17 routing and transport tests, and Phase 18 recall server tests remain green. Physical Phase 17.1/17.2 named discovery and Phase 18 recall propagation were not reverified on phones here.

The available automated tests cannot establish physical radio behavior, actual cross-transport media resume, or a real three-device encounter. Use the following device procedure before field release.

## Exact physical three-device procedure

Use three phones **A** (sender), **B** (optional relay), and **D** (destination), all on this 4.7 APK with separate persistent Device IDs. Preserve each phone's existing install data. Start the DinoBlog server if testing online, then use Network Debug on A to configure its URL. Enable B's carrier setting `relayEnabled`; make A/B/D mutually discoverable as needed. Confirm HELLO shows their real authenticated IDs; nearby BLE and Wi-Fi names are only discovery hints. Start with mobile Internet and server access off on all three phones. Use Network Debug / Experiment export to inspect `ROUTING_DECISION`, `TRANSFER_STARTED`, `MESSAGE_CARRIED`, receipts, and recall events.

1. **Direct only, third party:** On A set the composer to Direct only and send TEXT to D while only B is nearby. Connect A↔B over Wi-Fi Direct, then repeat over Bluetooth. Expect `SKIP_RELAY_POLICY`, no payload row or media part file on B, no copy split, and **Waiting for recipient** on A. Disconnect A. An intentionally modified B that offers A's valid direct-only payload to D must receive `RELAY_POLICY_DIRECT_ONLY`; D must not ACK or store it.
2. **Direct only, destination:** Bring A and D together; authenticate A↔D over Wi-Fi Direct, then send another direct-only TEXT over Bluetooth. Both must deliver with signed receipt. Repeat with IMAGE, AUDIO, and VIDEO within existing size limits; interrupt an IMAGE after partial receipt over Bluetooth and reconnect A↔D over Wi-Fi Direct. It should resume the same media/message IDs, verify SHA-256, and finish without B.
3. **Direct only, Internet:** Keep D out of nearby range and reconnect A to the server. Queue a new direct-only message or let A upload its queued source-owned one. D syncs from the server with the same `messageId`, signature, and `relayPolicy`. B must fail to upload A's message under B's server credentials. Verify duplicate direct and server arrival yields one chat item.
4. **Allow relay:** Restore offline conditions. On A send a new ALLOW_RELAY message to D; meet B with `relayEnabled=true`, then separate A and meet B↔D. Existing PRoPHET/Spray eligibility should permit carriage and D should produce a receipt. Repeat with B's `relayEnabled=false`; B must not accept original payload. Repeat a media message and inspect manifest/chunk behavior.
5. **Security and control:** Change the `relayPolicy` wire value of a signed test message without re-signing: source signature verification must fail. Re-sign with the same `messageId` but changed policy: server must return conflict. Test a valid delivery receipt D→B→A and a Phase 18 REVOKE/REVOKE_RESULT through B for both policies; control propagation must continue. A cryptographic key mismatch must still block inventory and transfer regardless of nearby names.
6. **Regression:** Check named BLE discovery, Wi-Fi device-name discovery, AUTO/Wi-Fi/Bluetooth connection selection, existing online delivery, TEXT/IMAGE/AUDIO/VIDEO, partial media resume, local cancel, and relay cleanup. Export the experiment CSV and confirm policy skips are recorded as routing decisions with no failed transfer increment.

On a regular Windows user PowerShell, `./test-device.ps1` installs and runs the available Android instrumentation suite. For the manual three-phone encounters, install `app/build/outputs/apk/debug/app-debug.apk` on each phone with the same signing key used by its existing installation. The Android test APK is `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
