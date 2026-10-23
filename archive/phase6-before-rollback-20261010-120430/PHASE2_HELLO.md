# Dinoblog 2.4 - DTN Phase 2: peer identity / HELLO

Version `2.4-peer-identity`, versionCode `7`. This release stops at peer identity. It does **not** implement Phase 3 or forwarding of carried messages.

## Existing architecture reused

- `network/TcpTransport.kt` owns TCP server/client sockets, reconnects, sessions and packet dispatch. It already sent HELLO; this update makes handshake state, logs and teardown explicit.
- `network/WireProtocol.kt` owns framing, JSON parsing, the existing `WirePacket` / `PacketType` abstraction and validation. HELLO, MESSAGE, ACK, PING and PONG use this one protocol.
- `util/DeviceIdentity.kt` owns the persistent installation UUID in SharedPreferences. `AppContainer` passes that ID into `TcpTransport`; it is not taken from an IP, MAC or device name.
- `model/NetworkState.kt` defines `SocketState`; `TcpTransport.state` is the read-only StateFlow, exposed unchanged by `AppViewModel.socket`. Wi-Fi Direct still has its separate `NetworkState`.
- The existing Room, DeliveryEngine, ACK/retry/deduplication, expiry/hop checks, direct chat and carrier selection are reused without modification.

## HELLO packet format and parsing

Every frame is still a 4-byte big-endian byte length followed by UTF-8 JSON on TCP port 8988. This is not newline-delimited JSON.

Example actual outgoing envelope (with a sample persistent UUID and device name):

```json
{
  "type": "HELLO",
  "version": 2,
  "deviceId": "11111111-1111-4111-8111-111111111111",
  "deviceName": "Phone B",
  "message": null,
  "messageId": null,
  "ackStatus": "DELIVERED",
  "packetType": "HELLO"
}
```

`message`, `messageId` and `ackStatus` above are existing serializer defaults. They have no delivery/ACK meaning in a HELLO; packet type determines handling. `type` is retained for existing protocol-v2 peers, while `packetType` is the explicit discriminator requested for Phase 2. Both fields must agree when both are present. MESSAGE and ACK still use the same existing envelope and processing.

The parser also accepts a minimal HELLO:

```json
{
  "packetType": "HELLO",
  "deviceId": "22222222-2222-4222-8222-222222222222"
}
```

Missing `version` defaults to existing protocol version 2. `deviceName` is optional metadata, not identity. Old `type`-only packets remain accepted. Missing/unknown/conflicting packet types, invalid UUIDs and incompatible versions are rejected. Labels such as `DEVICE-B` in diagrams are not literal valid UUIDs.

`WireProtocol.read()` reads the entire bounded frame, parses JSON, normalizes `packetType` into the existing `type` field if needed, then deserializes and validates a `WirePacket`. `TcpTransport.runSession()` requires the first incoming packet to be HELLO from a different identity. MESSAGE/ACK cannot bypass that step. Subsequent HELLO packets close the session rather than silently replacing the peer identity.

## Automatic trigger and connection state

After `accept()` or successful `connect()`, the existing `runSession()` immediately writes local HELLO, then reads remote HELLO. Both sides send first, so neither waits for a button or for the other side to speak first.

`SocketState.connectedPeerDeviceId` is a read-only alias of the existing `peerId`, not a second mutable/global ID. Existing chat and retry code continues using the same state. IPs remain separate `localIp` / `remoteIp` properties.

| State | connectedPeerDeviceId | Debug identity label |
|---|---|---|
| No TCP session, listening, connecting or reconnecting | null | None |
| TCP established, valid HELLO not received | null | Waiting for HELLO... |
| HELLO validated | remote persistent UUID | remote persistent UUID |
| Session stopped, EOF, timeout or socket error detected | null | None |

`awaitingHello` distinguishes a usable TCP socket awaiting identity from a disconnected socket. The existing `connected` property means HELLO-identified, preserving the gate for normal chat/retry. The UI shows `CONNECTED`, `TCP CONNECTED / WAITING FOR HELLO`, or `DISCONNECTED`, with the detailed TCP status also retained.

All teardown paths share `clearSession()`: explicit `stop()`, EOF/exception cleanup, write failure and heartbeat failure. It clears runtime peer ID and waiting state and logs the previous peer. Session-identity checks prevent cleanup of an old socket from clearing a newer one; cancelled coroutine checks prevent old connection-status updates or late HELLO from publishing stale state. Known peers saved in Room can remain in the conversation directory; those are not the currently connected peer.

Logs:

```text
DTN HELLO SEND
localDeviceId=<B UUID>

DTN HELLO RECEIVE
remoteDeviceId=<C UUID>

DTN PEER IDENTIFIED
connectedPeerDeviceId=<C UUID>

DTN PEER DISCONNECTED
previousPeer=<C UUID>
```

## Exact two-phone test procedure

1. Use two Wi-Fi Direct-capable Android 8.0/API 26+ phones, called B and C. Install `app/build/outputs/apk/debug/app-debug.apk` on both with the same signing key as the earlier build. Update in place; do not uninstall/clear storage. Confirm header `PEER IDENTITY · 2.4`.
2. Enable Wi-Fi and Location. Disable hotspot for the test. Allow Nearby devices (Android 13+) or Location/Precise location (older Android). No Internet is needed; mobile data can be off. Keep both apps open in the foreground.
3. On each phone open **Device**, record its persistent **Device ID**, and confirm the IDs differ. Call those values `B_UUID` and `C_UUID`; do not substitute the Wi-Fi Direct address, IP or friendly name.
4. Open **Debug** before connecting. **Connected Peer Device ID** should be **None**.
5. On both phones open **Peers > Scan for peers**. On B tap **Connect** for C and accept the invitation on C. Android chooses the group owner automatically; that role does not change identity semantics.
6. Return to **Debug** on both phones. No send/HELLO button is needed. You may briefly see **Waiting for HELLO...**; on a healthy connection it is usually too fast to observe.
7. Expected on B: **Local Device ID = B_UUID**, **Connection = CONNECTED**, **Connected Peer Device ID = C_UUID**. Expected on C: **Local Device ID = C_UUID**, **Connection = CONNECTED**, **Connected Peer Device ID = B_UUID**. Compare full UUIDs.
8. Inspect each phone's session log for `DTN HELLO SEND`, `DTN HELLO RECEIVE` and `DTN PEER IDENTIFIED` with those exact IDs. This verifies an automatic exchange rather than merely detecting an IP connection.
9. On **Peers**, press **Disconnect** to end the Wi-Fi Direct group. Check Debug on both phones: **Connected Peer Device ID = None** (runtime value null), and `DTN PEER DISCONNECTED` names the previous peer. Connection state may briefly show waiting/reconnecting while Android reports the group change, but no old peer ID should remain after socket closure is detected.
10. Reconnect and repeat steps 7-9. Restart either app without clearing its storage: its **local** Device ID must remain unchanged, but it must not claim a connected remote ID before a fresh HELLO.
11. Regression: connect again, choose the connected peer in **Chat**, leave carrier mode unchecked, and send a message each way. Direct messages should retain normal ACK/status behavior. Disconnect, queue a direct message and reconnect to verify existing pending retry.

### Confirm that carried data is NOT forwarded

If B already has a `CARRIED` message addressed to C, note its message ID and use a long unexpired TTL. Connect B/C. B should identify C, but the bundle must remain `CARRIED`, its hop count must not increase, C must not receive that bundle, and no outgoing MESSAGE log for that message ID should appear. Existing origin-owned pending messages may still retry; that is preserved direct messaging, not relay forwarding.

If only two clean installations are available and neither already has a carried row for the other's UUID, the full physical A-to-B-store-then-B-to-C scenario requires a third original identity. Do not clear storage to simulate it. The automated test `real HELLO identifies C but never sends B carried bundle for C` seeds an in-memory carried bundle, establishes a real localhost TCP HELLO with C, invokes the unchanged retry engine and verifies that no message is transmitted. Room persistence itself is covered by the existing Android instrumentation tests. No production data-injection feature was added for testing.

## Build, verification and limitations

```powershell
.\build.ps1
```

Builds the app, runs JVM tests/lint and compiles the Android test APK. With an authorized Android device attached:

```powershell
.\build.ps1 -Tasks @(':app:connectedDebugAndroidTest')
```

See `VERIFICATION.md` for executed results. JVM tests use real loopback sockets for HELLO/EOF/reconnect, but do not prove Wi-Fi Direct works on a specific OEM phone. No physical device was attached during this implementation, so the two-phone acceptance procedure remains to be run on hardware.

- Initial HELLO read timeout remains 10 seconds. Established sessions retain a 10-second heartbeat and 35-second read timeout. Silent radio loss cannot be detected instantaneously; identity clears when Android/socket EOF/error/timeout detects it. Explicit Disconnect clears local identity immediately.
- Networking remains foreground-only. An interrupted Wi-Fi Direct group may require manual scan/connect again.
- Identity is a UUID assertion, not authentication. There is no encryption or protection against a malicious peer impersonating an ID. Use trusted devices.
- Session logs are bounded and in memory. Clearing logs does not clear Room history or installation identity.
- No inventory exchange, routing tables, Epidemic/PRoPHET/MaxProp, cloud/backend, multi-hop ACK, carried-message forwarding or Phase 3 behavior was added.

## Files changed

Modified:

- `app/src/main/java/com/group16/dinoblog/network/TcpTransport.kt`
- `app/src/main/java/com/group16/dinoblog/network/WireProtocol.kt`
- `app/src/main/java/com/group16/dinoblog/model/NetworkState.kt`
- `app/src/main/java/com/group16/dinoblog/ui/NetworkDebugScreen.kt`
- `app/src/main/java/com/group16/dinoblog/ui/DinoblogApp.kt` (version label only)
- `app/src/main/java/com/group16/dinoblog/ui/PeerDiscoveryScreen.kt` (version label only)
- `app/src/test/java/com/group16/dinoblog/TcpTransportTest.kt`
- `app/src/test/java/com/group16/dinoblog/WireProtocolTest.kt`
- `app/build.gradle.kts` (versionCode/versionName only)
- `README.md`
- `STORE_CARRY.md` (current-version pointer only)
- `VERIFICATION.md`

Added:

- `app/src/test/java/com/group16/dinoblog/PeerIdentityHandshakeTest.kt`
- `PHASE2_HELLO.md`

No production networking manager was added. Room schema/version/migration, `Message.kt`, `DeviceIdentity.kt`, `WifiDirectManager.kt`, `DeliveryEngine.kt`, repositories, ViewModel, MainActivity and ChatScreen are unchanged.
