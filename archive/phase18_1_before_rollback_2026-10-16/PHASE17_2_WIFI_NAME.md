# Phase 17.2: Wi-Fi Direct device names in Nearby

## Architecture inspection and scope

The existing `WifiDirectManager` owns `WifiP2pManager`, its channel and exported
framework `BroadcastReceiver`. It calls `discoverPeers()`, responds to
`WIFI_P2P_PEERS_CHANGED_ACTION` with `requestPeers()`, and publishes the returned
`WifiP2pDeviceList` through `NetworkState`. The previous transient peer list
already contained `deviceName`, `deviceAddress`, and integer status; the Nearby
screen showed a generic Wi-Fi card and the raw peer address. `connect(address)`
already forms the P2P group; `TcpTransport` runs TCP and the Phase 15 HELLO and
HELLO_PROOF over that group. Phase 17 has no separate
`NearbyConnectionCoordinator` class: `AppViewModel` coordinates BLE GATT offers,
the one-time handoff ID, Wi-Fi or RFCOMM selection and Auto fallback. Its
selected BLE token/result already keeps the active BLE row across authentication.

Phase 17.1 BLE results contain an ephemeral token, short code, optional app
display name and BLE RSSI. Wi-Fi device names are a different source. Room
`Peer` records use authenticated Device ID and optional local alias; there is no
stored Wi-Fi device name. Phase 14 experiment metrics and the complete Phase 18
LOCAL CANCEL, REVOKE, REVOKE_RESULT, signed recall and online recall code are
outside this change. Android settings are minSdk 26, targetSdk 35 and
compileSdk 36. The manifest and runtime permission helper already use
`NEARBY_WIFI_DEVICES` on Android 13+ and fine/coarse location on older Android.
No new permission, server API, BLE wire change, DataStore change, or Room
migration is needed. This checkout uses SharedPreferences for its existing
profile settings.

## Implementation

`WifiDirectDiscoveryResult` is an in-memory observation with a sanitized
`deviceName`, internal connection address and named status. `WifiDirectManager`
replaces its peer list on each `requestPeers()` callback, deduplicates by the
current P2P endpoint address, and clears it when Wi-Fi is disabled, discovery
restarts, permissions disappear, or the manager stops. Duplicate names remain
separate. A selected peer snapshot is kept only during the current connection
attempt so its card can survive peer-list refresh while the group connects.
No discovery result or endpoint-to-Device-ID map is persisted.

The sanitizer turns control characters, Unicode whitespace and direction
formatting controls into spaces, collapses spaces, trims, and limits output to
48 Unicode code points plus a visual ellipsis. Null or blank names display
`Wi-Fi Direct device`. It never changes the platform's device name. Android's
P2P status is mapped to Available, Connecting, Connected, Failed or
Unavailable. P2P Connected alone still displays **Not verified**.

Nearby keeps BLE and Wi-Fi in separate sections by default. A BLE card shows
the DinoBlog app display name and rotating short code. A Wi-Fi card shows the
Android `WifiP2pDevice.deviceName` before any group, TCP or HELLO connection.
The UI never prints the P2P address or calls `BluetoothDevice.name` for it.
The Wi-Fi card displays availability rather than a fabricated Wi-Fi signal
strength; BLE signal labels explicitly refer to BLE. A Wi-Fi card cannot be
connected while the user has forced Bluetooth. Manual Wi-Fi selection reuses
the existing Wi-Fi Direct transport; Auto handoff and Bluetooth rules stay as
Phase 17 defined.

BLE and Wi-Fi cards combine only if the selected Phase 17 BLE handoff offer
explicitly supplies the same Wi-Fi endpoint as a current or selected Wi-Fi
observation. This temporary association is discarded when the session ends.
Leaving Nearby for Chat preserves the selected card while the authenticated
connection remains active; disconnecting clears it.
Name equality, list position, timing, signal and device model never correlate
cards. After HELLO_PROOF and key binding, the selected card displays the
authenticated session's peer name, or its local alias if present, and
**Verified**. A Wi-Fi device label remains supplementary. A changed BLE or
Wi-Fi name is not a key mismatch; a real Phase 15 key mismatch still blocks
inventory and transfers. No display name or Wi-Fi address is a contact key,
routing key, receipt key or recall authority. The BLE name and HELLO name are
separate fields; the latter is supplied by the authenticated connection, while
the verified state refers to the cryptographic key binding.

The Wi-Fi controls now show a clear missing-permission message. BLE, Bluetooth
and online features continue according to their own permissions. Developer
Network Debug shows peer count, sanitized names and status without raw P2P
addresses. No device names are exported to experiment CSV.

## Changed files

- `app/src/main/java/com/group16/dinoblog/model/WifiDirectDiscoveryResult.kt` (new)
- `app/src/main/java/com/group16/dinoblog/model/NetworkState.kt`
- `app/src/main/java/com/group16/dinoblog/model/Peer.kt`
- `app/src/main/java/com/group16/dinoblog/network/WifiDirectManager.kt`
- `app/src/main/java/com/group16/dinoblog/viewmodel/AppViewModel.kt`
- `app/src/main/java/com/group16/dinoblog/ui/PeerDiscoveryScreen.kt`
- `app/src/main/java/com/group16/dinoblog/ui/DinoblogApp.kt`
- `app/src/main/java/com/group16/dinoblog/ui/ProfileScreens.kt`
- `app/src/main/java/com/group16/dinoblog/ui/NetworkDebugScreen.kt`
- `app/src/test/java/com/group16/dinoblog/WifiDirectDiscoveryResultTest.kt` (new)
- `README.md` and this report

## Automated verification

Run from the project directory:

```powershell
.\build.ps1 -Tasks ':app:assembleDebug',':app:testDebugUnitTest',':app:lintDebug',':app:assembleDebugAndroidTest','--offline'
cd server
npm test
```

Verification on 2026-10-14: Android debug APK and Android test APK assembled;
169 JVM tests in 31 suites passed with zero failures; lint reported zero errors
and 24 existing warnings. The server's 10 tests passed, including signed recall
and mailbox synchronization. The JVM suite includes prior Phase 1-18 tests and
new sanitizer, duplicate-name and explicit handoff-correlation tests. The
Android test APK compilation is not an on-device instrumentation run. The
managed build account cannot start Windows ADB because
Windows returns `0x80070002` for the user-profile path. Run
`.\test-device.ps1 -CheckOnly` in a normal user PowerShell on the test host,
then use authorized physical phones.

## Physical two-phone procedure

1. Install the same current APK on A and B. Grant each app's requested nearby
   permissions, enable Wi-Fi, Wi-Fi Direct and Bluetooth, and open Nearby on
   both. On B enable DinoBlog discoverability and nearby name visibility.
2. On A tap **Scan for peers**. Before connection, confirm B's Android Wi-Fi
   Direct device name appears under **Nearby via Wi-Fi Direct** with Available
   and Not verified, without a MAC address. The BLE section should separately
   show B's DinoBlog display name plus its short code.
3. Set A to Wi-Fi Direct and tap B's Wi-Fi card. Accept the platform invitation
   if shown. During group/TCP setup, check that Connected still says Not
   verified until HELLO_PROOF completes. Then check one selected card displays
   B's authenticated DinoBlog name, Verified, Device: the Android Wi-Fi name,
   and Connected via Wi-Fi Direct. Set a local alias on A and reconnect; the
   alias must take precedence. Check Network Debug authentication state.
4. Send text, image, audio and video within existing Phase 17 size limits.
   Repeat using a BLE card with Wi-Fi Direct selected; when the handoff offer's
   endpoint matches the Wi-Fi observation, check one combined card. Repeat with
   forced Bluetooth and Auto; confirm their Phase 17 transport behavior. Send
   an online message independently. Exercise LOCAL CANCEL, REVOKE,
   REVOKE_RESULT, relay cleanup and online recall using the Phase 18 guide.
5. Change B's Android Wi-Fi Direct device name if the device's public settings
   allow it; refresh discovery and reconnect. The same cryptographic identity
   must not raise KEY_MISMATCH. Change B's DinoBlog profile name separately and
   verify BLE and post-authenticated display behavior. Disable B's Wi-Fi Direct
   or leave range, refresh A, and confirm the unselected Wi-Fi row disappears.
6. Deny A's Nearby Wi-Fi permission, refresh, and confirm a clear Wi-Fi message
   while permitted BLE, Bluetooth and online paths remain usable. Test blank,
   newline and long platform names where the device permits configuration;
   JVM tests cover names unavailable through the device settings.

## Three-phone duplicate-name and spoof test

1. Give B and C the same Android Wi-Fi Direct name, such as `Galaxy A55`, while
   keeping independent DinoBlog Device IDs and keys. Scan on A. Confirm two
   separate Wi-Fi cards with the same visible name. Give B and C the same
   DinoBlog display name as well; confirm distinct BLE short codes and no
   automatic BLE/Wi-Fi merge.
2. Connect A to B, authenticate, and note B's trusted Device ID in Developer
   Debug. Disconnect and connect A to C. It must authenticate as C's separate
   Device ID, despite the spoofed names. Neither name may bypass HELLO_PROOF.
3. For the stronger key-mismatch check, use a controlled test build/device that
   claims a previously pinned Device ID with a different key. Confirm
   `KEY_MISMATCH`, no INVENTORY or media transfer, and no signed REVOKE
   authorization. Restore the original test identity afterward.

These radio, spoof and permission scenarios need physical Android devices;
automated JVM and APK builds alone cannot establish their runtime outcome.
