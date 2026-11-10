# Audio/video DTN upgrade

## Checked-out baseline

This change starts from the checked-out Phase 11 Android app: Room schema 8, text and image messages, app-private `MediaEntity` storage, 128 KiB binary `MEDIA_CHUNK` frames, `MEDIA_MANIFEST`/`MEDIA_ACCEPT`, SHA-256, resume bitmap, bounded relay storage, Spray-and-Wait, PRoPHET-inspired routing, relay participation, and delivery receipts. The local chat relay switch is present. There is no active Bluetooth/BLE discovery or cryptographic identity module in this checkout.

## Device names

`WifiDirectManager` reads `WifiP2pDevice.deviceName` from Android's peer list. Nearby displays the full sanitized value before connection; after HELLO, the separate DinoBlog display name appears above the hardware name when the connected Wi-Fi Direct address is known. Missing Android names show `Device name unavailable`. Duplicate hardware names retain separate rows keyed by Wi-Fi Direct address. Routing, Room peer keys, source/destination IDs, and receipts continue to use the persistent UUID from `DeviceIdentity`; changing a hardware name does not change identity. The optional HELLO `deviceName` field is not used as an identity source. This checkout has no Bluetooth or BLE name source.

## Media format and storage

`MessageContentType` now includes TEXT, IMAGE, AUDIO, VIDEO. One generic manifest adds content type, optional duration, and optional dimensions. Legacy image manifests decode as IMAGE. Room migration 8→9 adds these columns to `media` with IMAGE as the default, preserving old text/image rows. The `MediaEntity` bitmap and `messageJson` continue to hold metadata only; bytes stay in app-private `files/dinoblog_media/<UUID>.part` and `.media`. Incoming filenames never determine paths.

| Kind | Accepted MIME types | Maximum |
| --- | --- | ---: |
| Image | `image/jpeg`, `image/png`, `image/webp` | 5 MiB |
| Audio | `audio/mpeg`, `audio/mp4`, `audio/aac`, `audio/ogg`, `audio/wav`, `audio/x-wav`, `audio/3gpp` | 10 MiB |
| Video | `video/mp4`, `video/webm`, `video/3gpp` | 15 MiB |

Audio uses Android's document picker; video uses the Android Photo Picker. Both selections are copied with bounded streaming I/O into app-private files before a message is queued. Android metadata extraction checks duration and, for video, dimensions and a video track. Filename and MIME are treated as untrusted metadata. The chat shows the selected file, duration and size before Send, and allows Cancel. A single lifecycle-managed Media3 ExoPlayer instance per chat provides tap-to-play audio and video, with no autoplay. Playback rechecks the complete file's SHA-256.

The existing 128 KiB binary chunk frame and missing-index resume exchange are reused. No full-media Base64 JSON or Room BLOB is introduced. A receiver stores only a partial `media` row until the complete file has the declared size, SHA-256 and playable metadata. The message and final receipt commit after verification; only then is a bundle ACK sent. Partial media is absent from normal inventory. Relay media across all types shares the existing 50 MiB byte quota and message-count quota; oversized and full-buffer manifests are rejected before chunks. Final-destination delivery bypasses relay scoring and local relay participation. The chat direct-only switch applies to all media through the existing one-hop and one-copy policy.

## Three-device audio carry test

1. Install the same debug APK on A, B, and C. Open DinoBlog on all three, grant nearby permissions, and enable B's relay setting. Record each persistent Device ID in Network Debug. Use a supported audio file smaller than 10 MiB on A.
2. On A, open C's conversation (use C's Device ID in Network Debug if C is not yet in the chat list). Leave **Allow relay through other devices** on. Tap attachment → Audio, choose the file, check the filename/duration/size preview, and tap Send.
3. Connect A to B through Nearby. In Network Debug, record the message ID and media ID. Wait until B reports a complete AUDIO row with all chunks and hash verified, and A shows custody rather than final delivery.
4. Disconnect A completely. Optionally restart B's app and confirm the AUDIO row remains complete. Connect B to C. Verify the same message ID, media ID, original A source ID, destination C ID, MIME and SHA-256 on B and C. C's A conversation should show one audio item. Tap Play; confirm audible playback. C should create one delivery receipt only after completion.
5. For resume, repeat with a second audio file. Disconnect A–B partway through chunks, reconnect, and verify B's `Resume chunks remaining` falls and only missing chunks are requested. Confirm no chat item or receipt appears on B or C while the file is partial.

## Three-device video carry test

1. Repeat the A/B/C preparation with a supported video smaller than 15 MiB. In A's conversation with C, leave relay on, tap attachment → Video, check the preview metadata, and Send.
2. Connect A–B and wait for B's VIDEO row to become complete with a verified hash. Disconnect A and restart B if desired. Connect B–C; verify the original IDs, source/destination and hash, then tap Play in C's A conversation. Check picture and sound where present, and the final delivery receipt.
3. Interrupt a second transfer mid-chunk sequence, reconnect, and verify the missing-index resume behavior. Also select a video larger than 15 MiB: the app should report `Video is too large for nearby DTN delivery.` and leave no queued chat message.
4. Check B's shared relay-media allocation in Network Debug. If the 50 MiB quota is full, the next media manifest must receive BUFFER_FULL before any chunk is sent.

An Android emulator can run database and media repository instrumentation tests. Real Wi-Fi Direct discovery, disconnected A→B→C encounters, Bluetooth (if added later), and playback on target hardware still require physical-device verification.
