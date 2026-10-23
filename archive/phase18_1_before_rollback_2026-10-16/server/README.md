# DinoBlog online mailbox

This existing `server` module runs on Node.js 24 or later. It stores online messages in one SQLite database, stores verified image, audio and video bytes outside the database, and uses authenticated WebSocket frames only to signal that REST sync is available. It is not a DTN peer.

Phase 18 adds signed, idempotent `POST /api/v1/messages/:messageId/revoke` and destination-signed `POST /api/v1/revoke-results`. Authenticated relays can query known tombstones with `POST /api/v1/revokes/lookup`; normal `/api/v1/sync` includes revoke and result events through its existing cursor. The server suppresses revoked mailbox originals, but acceptance of a revoke is only a cancellation request until the final destination signs a result. See [Phase 18 behavior and test procedures](../PHASE18_RECALL.md).

Phase 18.1 stores the source-signed `relayPolicy` (`ALLOW_RELAY` or `DIRECT_ONLY`) with each message and returns it unchanged during sync. The authenticated original source alone may upload its message. The mailbox remains available for `DIRECT_ONLY`; the policy restricts third-party *peer* carriage. Older version-one signed messages and database rows default to `ALLOW_RELAY`. See [Phase 18.1 behavior and tests](../PHASE18_1_RELAY_POLICY.md).

## Local run

From `server/`:

```powershell
npm ci --offline --cache ../.tools/npm-cache
npm run build
npm test
$env:HOST = "0.0.0.0"
$env:PORT = "8787"
npm start
```

`ws.tgz` is included so dependency installation works offline. The default database is `server/data/dinoblog.sqlite` and media files are under `server/data/media`. Both paths can be set with `DATABASE_PATH` and `MEDIA_STORAGE_PATH`. Copy values from `.env.example` into the process environment; the server does not load `.env` automatically. Allow inbound TCP 8787 through the laptop firewall for LAN testing. Do not put the SQLite database or media directory in a web server's public directory.

On each debug Android build, open **Network Debug**, enter `http://LAPTOP_LAN_IP:8787` as the development server URL, and tap **Save server URL**. `localhost` on a phone is the phone, not the laptop. Both phones must open the app and show **Server state: ONLINE** before sending; registration is required for the destination. Copy the destination's Device ID from Network Debug into the sender's destination field. Keep the phones off Wi-Fi Direct to test Internet delivery. Turn one phone's network off after it registers to test mailbox delivery after reconnect. Stop the server to test DTN fallback.

For devices on separate networks, deploy the same process on a reachable host behind an HTTPS reverse proxy with WebSocket upgrade support. Configure `https://your-host` in the Android release build with `-Pdinoblog.serverUrl=https://your-host`, then sign the release APK with the project's production signing key. The proxy must forward `/api/v1/*`, keep the bearer authorization header, and allow 128 KiB binary chunk uploads and preserve Range headers. Use persistent SQLite and media volumes, restricted file permissions, backups, and TLS certificates. Set `HOST`, `PORT`, `DATABASE_PATH`, `MEDIA_STORAGE_PATH`, `DELIVERED_MEDIA_GRACE_HOURS`, `EXPIRED_MESSAGE_GRACE_HOURS`, `RECEIPT_RETENTION_DAYS`, and `RATE_LIMIT_PER_MINUTE` as needed. Release Android builds reject HTTP; debug builds alone permit cleartext for LAN development.

## API

All endpoints except initial registration require `Authorization: Bearer TOKEN`. Registration of an existing Device ID also requires its token.

| Endpoint | Purpose |
| --- | --- |
| `POST /api/v1/devices/register` | Register Device ID or update display name; returns opaque token only for first registration. |
| `GET /api/v1/health` | Verify authenticated server reachability. |
| `POST /api/v1/messages` | Persist TEXT, IMAGE, AUDIO or VIDEO idempotently; media must be complete first. |
| `POST /api/v1/media/upload-session` | Start or resume a media upload; returns `receivedBytes`. |
| `PUT /api/v1/media/:mediaId/chunks?offset=N` | Upload the next binary chunk; final chunk verifies SHA-256. |
| `PUT /api/v1/media/:mediaId` | Upload raw image bytes with `X-Message-Id`, `X-Sha256`, and image `Content-Type`. |
| `GET /api/v1/media/:mediaId` | Download binary media for source or destination; supports `Range: bytes=start-end`. |
| `POST /api/v1/receipts` | Persist a final destination receipt. |
| `POST /api/v1/receipts/lookup` | Look up receipts by held message IDs for relay cleanup. |
| `GET /api/v1/sync?cursor=N` | Read up to 100 ordered message/receipt events and `nextCursor`. |
| `GET /api/v1/ws` | Authenticated WebSocket; sends `SYNC_AVAILABLE` notifications. |

The database tables are `devices`, `messages`, `media`, `media_uploads`, `delivery_receipts`, and `sync_events`. `message_id` and `original_message_id` are unique keys. A message insert and its sync event, and a receipt insert and its sync event, share transactions. The server rejects source spoofing, unknown destinations, expired uploads, conflicting IDs, invalid hash/MIME/size, and unauthorized media downloads. JSON is limited to 64 KiB; images to 5 MiB, audio to 25 MiB and video to 50 MiB; binary chunks to 128 KiB; and requests to 30 seconds. Cleanup runs at startup and hourly: expired messages stop being delivered immediately and their records are removed after a grace period for late receipts; orphan media is removed, delivered media is removed after its grace period, and receipt/sync metadata is retained for the configured number of days.

Registration is possession based: the first client presenting an unused Device ID claims it. There is no account recovery or end-to-end encryption in Phase 12. The server can read message text and media bytes. Protect the host and use HTTPS/WSS outside local debug testing.

## Phase 13 media additions

The existing image endpoint remains for older clients. New clients use upload sessions and 128 KiB binary chunks for IMAGE, AUDIO and VIDEO. A media row appears only after the entire file passes SHA-256 verification, and a message cannot reference a partial upload. Upload sessions store an offset and survive server restarts; the client asks for that offset and resumes with the same `mediaId`. Authorized downloads accept a range of up to 128 KiB, letting Android resume from its persisted `.part` chunk map. `media` gained `media_type`, `duration_ms`, `width`, and `height` columns through an additive startup migration. `media_uploads` stores partial sessions. The server enforces IMAGE <= 5 MiB, AUDIO <= 25 MiB and VIDEO <= 50 MiB. The Android DTN limits are lower for AUDIO (10 MiB) and VIDEO (15 MiB).

## Phase 14 evaluation metadata

An active Android experiment adds `X-DinoBlog-Experiment-ID: <uuid>` to REST requests. After authentication, the server records only event type, authenticated device ID, object ID, byte count, and time in `experiment_server_events`. The protected `GET /api/v1/experiments/metrics?experimentId=<uuid>` returns aggregate counts for the authenticated device only; the header is never an authorization credential. Events older than `EXPERIMENT_METRIC_RETENTION_DAYS` (default 30) are removed by cleanup. The Android CSV ZIP is the primary evaluation export; see [Phase 14 evaluation guide](../PHASE14_EVALUATION.md).

Set `PARTIAL_UPLOAD_MAX_AGE_HOURS` to change the default 24-hour partial-session expiry. Deploy a persistent SQLite volume and media directory; the HTTP reverse proxy must preserve `Authorization` and `Range`. Media uploads are individual 128 KiB requests. The server checks MIME, declared size and SHA-256, and authorizes completed downloads to the source or destination only. It does not inspect or transcode codec bitstreams, and the server can read media contents.
