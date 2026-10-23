# Phase 17.1: Named nearby discovery

## Changed files

Android source: `nearby/BleDiscoveryProtocol.kt`,
`nearby/NearbyDiscoveryManager.kt`, `repository/ProfileRepository.kt`,
`DinoblogApplication.kt`, `viewmodel/AppViewModel.kt`,
`ui/PeerDiscoveryScreen.kt`, `ui/ProfileScreens.kt`, `ui/DinoblogApp.kt`,
and `ui/NetworkDebugScreen.kt`. Tests:
`nearby/BleDiscoveryProtocolTest.kt` and
`Phase16NearbyPreferenceTest.kt`. Documentation: `README.md` and this new
report. No new Android source file, permission, DataStore, Room table, migration,
server endpoint, routing rule or recall behavior was added.

## Architecture inspection

Phase 16 already used legacy `AdvertiseData` with DinoBlog's 128-bit service UUID
`9e8c5d36-47b1-4f69-9f20-b44fd8cd4d6a`, protocol version 1, an 8-byte
rotating random token and one capability byte (`SECURE_IDENTITY=1`,
`WIFI_DIRECT=2`). Its 10-byte service data costs 28 bytes as an AD structure;
Android may add 3 Flags bytes, reaching the 31-byte primary advertisement limit.
There was no scan response. It filters by that service data, smooths RSSI, and
deduplicates by token. Tokens rotate every 10 minutes while advertising. Scan
results expire after 15 seconds. Phase 17 keeps the temporary `BluetoothDevice`
endpoint in memory and connects it through GATT handoff, then Wi-Fi Direct or
RFCOMM. The selected discovery token is already tracked by the view model.

Phase 9 stores the app display name and nearby preferences in the existing
`chat_preferences` SharedPreferences file. This checkout has no DataStore.
`DisplayNames.normalize` enforces 1–32 code points and cleans control characters.
Discoverability defaults off. Phase 15 authenticates the HELLO Device ID and
public key before assigning the connected peer name. The existing HELLO_PROOF
signature covers IDs, nonces and key fingerprint, not the HELLO display-name
field itself; **Verified refers to the device key binding, not a legal/person
name guarantee**. Phase 18's REVOKE,
REVOKE_RESULT, tombstones, cleanup and online synchronization are present and
untouched. Android minSdk is 26; targetSdk is 35.

## On-air format and compatibility

The **primary advertisement is unchanged** (protocol version 1):

| Part | Bytes |
| --- | ---: |
| AD length + service-data type | 2 |
| DinoBlog service UUID | 16 |
| Protocol version | 1 |
| Random discovery token | 8 |
| Capability flags | 1 |
| **Primary AD structure** | **28** |
| Optional Android Flags AD structure | 3 |
| **Primary maximum** | **31** |

A scan response uses separate app-specific 128-bit service-data UUID
`9e8c5d36-47b1-4f69-9f20-b44fd8cd4d6b`, so Android's parsed scan record
can hold both token and name. It never uses the system Bluetooth device name.

| Part | Bytes |
| --- | ---: |
| AD length + service-data type | 2 |
| Name service UUID | 16 |
| Sanitized UTF-8 DinoBlog display name | 1–13 |
| **Named scan response** | **19–31** |
| Explicit anonymous marker (`0x00`) instead of a name | 1 |
| **Anonymous scan response** | **19** |

The name byte limit is calculated as `31 - 2 - 16 = 13`, independent of its
character count. A long name is truncated at grapheme-aware boundaries for
BLE only. The stored profile and authenticated HELLO retain the full name.
Malformed, absent, or invalid UTF-8 scan-response data is ignored while the
primary result remains visible as `DinoBlog device • CODE`. Older scanners
still recognize the unchanged version-1 primary packet and ignore the extra
response. New scanners accept older primary packets without a name.

The UI code is the final four hexadecimal characters of the current random
token. It changes when the token rotates. Neither token nor code is a contact
identity. The primary and scan response contain no stable Device ID, public
key, fingerprint, Bluetooth system name, routing data or media.

## UI and privacy

`Show my DinoBlog name nearby` defaults **on**, but `Allow nearby DinoBlog
users to find me` retains its existing default **off** and overrides the name
setting. Disabling name visibility emits the anonymous marker. Changes to the
profile name or visibility restart advertising with the same current token;
normal token rotation remains active. Both settings use the existing
SharedPreferences; there is no Room migration or contact record for BLE names.

An unconnected result shows its advertised name (or generic fallback), the
four-character code, approximate signal category and **Not verified**. Same
names remain separate because rows are keyed by temporary tokens. A separately
delivered scan response updates the same row. The selected row is retained
through a handoff and changes to the Phase 9 alias or authenticated HELLO name
only after Phase 15 reports `AUTHENTICATED`. A differing BLE and HELLO name is
normal untrusted metadata, not a key mismatch. Genuine key mismatch remains
blocked by Phase 15. Names never enter routing, TOFU, receipts, recall, or
online identity logic.

Nearby scanners can read an enabled name before connecting; a stable chosen
name may allow repeated presence to be recognized despite rotating tokens.

## Verification

Run from the project root:

```powershell
.\build.ps1 -Tasks ':app:testDebugUnitTest',':app:assembleDebug',':app:lintDebug',':app:assembleDebugAndroidTest','--offline'
```

The JVM BLE tests cover exact byte budgets, strict UTF-8, malformed input,
Thai, emoji, long names, duplicate names, anonymous fallback and name updates.
The full Android JVM suite also covers prior DTN, transport, security and recall
logic. The Android instrumentation APK compiles, but its tests require an ADB
capable process and a device. Actual BLE advertising, scan-response delivery,
GATT handoff, Wi-Fi Direct and RFCOMM still require physical phones.

On 2026-10-14, `testDebugUnitTest`, `assembleDebug`, `lintDebug`, and
`assembleDebugAndroidTest` passed offline. The JVM result was **167 tests in
30 suites, with zero failures**. Lint reported **zero errors and 24 warnings**.
The backend `npm test` suite passed **10/10**, including signed recall and
online mailbox tests. `test-device.ps1 -CheckOnly` confirmed that ADB cannot
start from this managed process (`Windows profile API: 0x80070002`); no physical
test result is claimed.

### Exact physical test procedure

Install the same debug APK on A, B and C. On all phones enable Bluetooth and
grant DinoBlog's Nearby permissions. Keep DinoBlog foregrounded. In Settings,
enable discovery on B and C; leave it off on A. Use A's Nearby screen and tap
Refresh for each observation. Check Network Debug for advertisement errors and
the primary/scan-response byte counts.

1. Set B's display name to `William`, with name visibility on. A must show
   `William • CODE` and `Not verified` before connecting, with a signal
   category rather than distance. B's full Device ID and key must not appear.
2. Set C's name to `William` and discoverable on. A must show two independent
   William rows with different codes. Repeated scans must update rows without
   adding a row per packet.
3. On B test `วรฤทธิ์`, `Alex 😀`, and a 32-code-point multibyte name. Each
   scan must decode without corruption; the long nearby form may be shortened.
   B's stored name must stay full. Connect and check the full HELLO name.
4. Change B's name from `William` to `Will` while advertising. A must see the
   new name on the existing token without restarting B's app. Wait 10 minutes
   to confirm a new code while the chosen name stays the same.
5. Turn B's name visibility off while discovery stays on. A must eventually
   show `DinoBlog device • CODE`. Turn discovery off: after the old result
   expires, A must see no B advertisement. Turn discovery back on to recover.
6. Connect A to B from its named row. Accept the request on B. Once HELLO
   proof succeeds, the same row must show **Verified**, the authenticated
   full name and the selected Bluetooth or Wi-Fi Direct transport. Repeat
   with a local alias on A; the alias must win. No second named B row should
   appear for the active handoff.
7. For a harmless name mismatch, change B's name between discovery and HELLO.
   Authentication must succeed and the HELLO name must replace the old hint.
   For a real key mismatch, use the disposable Phase 15 key-replacement test;
   authentication must fail regardless of the advertised name.
8. Repeat connection with Auto, forced Wi-Fi Direct and forced Bluetooth.
   Relay a message A → B → C, then reconnect over the other transport and
   check one stable peer ID, a valid receipt and normal copy accounting.
9. With Phase 18 test data, exercise local cancel, REVOKE and REVOKE_RESULT
   through DTN and online sync. Name visibility must not affect authorization
   or cleanup. Also confirm a server message still syncs normally.

For real Android instrumentation tests, run `.\test-device.ps1` from an
ordinary signed-in Windows PowerShell with an authorized phone. This managed
agent account currently cannot start ADB because Windows supplies no user
profile; therefore it cannot claim the physical checks above passed.
