import { createServer } from 'node:http';
import { DatabaseSync } from 'node:sqlite';
import { createHash, randomBytes, timingSafeEqual, createPublicKey, verify as verifySignature } from 'node:crypto';
import { mkdirSync, existsSync, renameSync, unlinkSync, createReadStream, readdirSync, statSync, appendFileSync, openSync, readSync, closeSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { WebSocketServer } from 'ws';

const config = {
  port: Number(process.env.PORT || 8787), host: process.env.HOST || '0.0.0.0',
  dbPath: resolve(process.env.DATABASE_PATH || './data/dinoblog.sqlite'),
  mediaPath: resolve(process.env.MEDIA_STORAGE_PATH || './data/media'),
  graceMs: Number(process.env.DELIVERED_MEDIA_GRACE_HOURS || 24) * 3600_000,
  expiredGraceMs: Number(process.env.EXPIRED_MESSAGE_GRACE_HOURS || 24) * 3600_000,
  receiptRetentionMs: Number(process.env.RECEIPT_RETENTION_DAYS || 7) * 86_400_000,
  experimentRetentionMs: Number(process.env.EXPERIMENT_METRIC_RETENTION_DAYS || 30) * 86_400_000,
  rateLimitPerMinute: Number(process.env.RATE_LIMIT_PER_MINUTE || 1200),
  partialUploadMaxAgeMs: Number(process.env.PARTIAL_UPLOAD_MAX_AGE_HOURS || 24) * 3600_000,
};
const MAX_IMAGE = 5 * 1024 * 1024;
const CHUNK_BYTES = 128 * 1024;
const MEDIA_LIMITS = { IMAGE: MAX_IMAGE, AUDIO: 25 * 1024 * 1024, VIDEO: 50 * 1024 * 1024 };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const HASH = /^[0-9a-f]{64}$/i;
const IMAGE_MIMES = new Set(['image/jpeg', 'image/png', 'image/webp']);
const MEDIA_MIMES = { IMAGE: IMAGE_MIMES,
  AUDIO: new Set(['audio/mp4', 'audio/aac', 'audio/mpeg', 'audio/ogg']),
  VIDEO: new Set(['video/mp4']) };
mkdirSync(dirname(config.dbPath), { recursive: true });
mkdirSync(config.mediaPath, { recursive: true });
const db = new DatabaseSync(config.dbPath);
db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS devices (device_id TEXT PRIMARY KEY, display_name TEXT NOT NULL,
 token_hash TEXT NOT NULL, registered_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS messages (message_id TEXT PRIMARY KEY, source_id TEXT NOT NULL,
 destination_id TEXT NOT NULL, content_type TEXT NOT NULL, payload TEXT NOT NULL, media_id TEXT,
 created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, server_received_at INTEGER NOT NULL,
 FOREIGN KEY(source_id) REFERENCES devices(device_id), FOREIGN KEY(destination_id) REFERENCES devices(device_id));
CREATE TABLE IF NOT EXISTS media (media_id TEXT PRIMARY KEY, message_id TEXT NOT NULL UNIQUE,
 source_id TEXT NOT NULL, mime_type TEXT NOT NULL, file_size INTEGER NOT NULL,
 sha256 TEXT NOT NULL, storage_path TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS media_uploads (media_id TEXT PRIMARY KEY, message_id TEXT NOT NULL UNIQUE,
 source_id TEXT NOT NULL, media_type TEXT NOT NULL, mime_type TEXT NOT NULL,
 file_size INTEGER NOT NULL, sha256 TEXT NOT NULL, duration_ms INTEGER, width INTEGER, height INTEGER,
 storage_path TEXT NOT NULL, bytes_received INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS delivery_receipts (original_message_id TEXT PRIMARY KEY,
 source_id TEXT NOT NULL, destination_id TEXT NOT NULL, delivered_at INTEGER NOT NULL,
 original_expires_at INTEGER NOT NULL, created_by_device_id TEXT NOT NULL,
 server_received_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS message_revocations (original_message_id TEXT PRIMARY KEY,
 revoke_id TEXT NOT NULL UNIQUE, original_source_id TEXT NOT NULL,
 original_destination_id TEXT NOT NULL, original_expires_at INTEGER NOT NULL,
 created_at INTEGER NOT NULL, retention_until INTEGER NOT NULL,
 signature_version INTEGER NOT NULL, source_public_key TEXT NOT NULL,
 source_public_key_fingerprint TEXT NOT NULL, source_signature TEXT NOT NULL,
 server_received_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS revoke_results (original_message_id TEXT PRIMARY KEY,
 result_id TEXT NOT NULL UNIQUE, revoke_id TEXT NOT NULL,
 original_source_id TEXT NOT NULL, original_destination_id TEXT NOT NULL,
 result TEXT NOT NULL, processed_at INTEGER NOT NULL, signature_version INTEGER NOT NULL,
 destination_public_key TEXT NOT NULL, destination_public_key_fingerprint TEXT NOT NULL,
 destination_signature TEXT NOT NULL, server_received_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS sync_events (event_id INTEGER PRIMARY KEY AUTOINCREMENT,
 target_device_id TEXT NOT NULL, event_type TEXT NOT NULL, object_id TEXT NOT NULL,
 created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS index_sync_target ON sync_events(target_device_id,event_id);
CREATE TABLE IF NOT EXISTS experiment_server_events (event_id INTEGER PRIMARY KEY AUTOINCREMENT,
 experiment_id TEXT NOT NULL, device_id TEXT NOT NULL, event_type TEXT NOT NULL,
 object_id TEXT, bytes INTEGER NOT NULL DEFAULT 0, event_time INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS index_experiment_server_events_owner
 ON experiment_server_events(device_id,experiment_id,event_id);
CREATE INDEX IF NOT EXISTS index_experiment_server_events_time ON experiment_server_events(event_time);`);
for (const [name, definition] of Object.entries({ media_type: "TEXT NOT NULL DEFAULT 'IMAGE'",
  duration_ms: 'INTEGER', width: 'INTEGER', height: 'INTEGER' })) {
  if (!db.prepare('PRAGMA table_info(media)').all().some(column => column.name === name))
    db.exec(`ALTER TABLE media ADD COLUMN ${name} ${definition}`);
}
function addColumn(table, name, definition) {
  if (!db.prepare(`PRAGMA table_info(${table})`).all().some(column => column.name === name))
    db.exec(`ALTER TABLE ${table} ADD COLUMN ${name} ${definition}`);
}
for (const [name, definition] of Object.entries({ public_key: 'TEXT', public_key_fingerprint: 'TEXT', key_bound_at: 'INTEGER' }))
  addColumn('devices', name, definition);
for (const [name, definition] of Object.entries({ hop_limit: 'INTEGER', signature_version: 'INTEGER',
  origin_public_key: 'TEXT', origin_public_key_fingerprint: 'TEXT', content_digest: 'TEXT',
  origin_signature: 'TEXT', signed_media_mime_type: 'TEXT', signed_media_file_size: 'INTEGER',
  signed_media_sha256: 'TEXT', relay_policy: "TEXT NOT NULL DEFAULT 'ALLOW_RELAY'" }))
  addColumn('messages', name, definition);
for (const [name, definition] of Object.entries({ signature_version: 'INTEGER', destination_public_key: 'TEXT',
  destination_public_key_fingerprint: 'TEXT', destination_signature: 'TEXT' }))
  addColumn('delivery_receipts', name, definition);
db.exec('PRAGMA user_version=19');

const challenges = new Map();
const registrationChallenges = new Map();
function canonical(...fields) {
  const parts = fields.map(field => {
    const bytes = Buffer.from(String(field), 'utf8');
    const length = Buffer.alloc(4); length.writeUInt32BE(bytes.length);
    return Buffer.concat([length, bytes]);
  });
  return Buffer.concat(parts);
}
function decodeKey(encoded) {
  if (typeof encoded !== 'string' || encoded.length < 100 || encoded.length > 512 || !/^[A-Za-z0-9_-]+$/.test(encoded))
    fail(400, 'INVALID_PUBLIC_KEY');
  const der = Buffer.from(encoded, 'base64url');
  let key;
  try { key = createPublicKey({ key: der, format: 'der', type: 'spki' }); }
  catch { fail(400, 'INVALID_PUBLIC_KEY'); }
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') fail(400, 'INVALID_PUBLIC_KEY');
  if (encoded !== der.toString('base64url') || !key.export({ format: 'der', type: 'spki' }).equals(der))
    fail(400, 'INVALID_PUBLIC_KEY');
  return { key, fingerprint: hash(der) };
}
function signatureValid(publicKey, payload, signature) {
  if (typeof signature !== 'string' || signature.length < 60 || signature.length > 256 || !/^[A-Za-z0-9_-]+$/.test(signature)) return false;
  try { return verifySignature('sha256', payload, decodeKey(publicKey).key, Buffer.from(signature, 'base64url')); }
  catch { return false; }
}
function messageBytes(m) {
  const digest = m.contentType === 'TEXT' ? hash(m.payload) : m.signedMediaSha256;
  const version = m.signatureVersion;
  return canonical('DINOBLOG_MESSAGE', String(version), m.messageId, m.sourceId, m.destinationId,
    m.contentType, m.createdAt, m.expiresAt, m.hopLimit,
    ...(version === 2 ? [m.relayPolicy] : []), digest,
    m.contentType === 'TEXT' ? '' : m.mediaId,
    m.contentType === 'TEXT' ? '' : m.signedMediaMimeType,
    m.contentType === 'TEXT' ? '0' : m.signedMediaFileSize,
    m.contentType === 'TEXT' ? '' : digest);
}
function receiptBytes(r) { return canonical('DINOBLOG_DELIVERY_RECEIPT', '1', r.originalMessageId,
  r.sourceId, r.destinationId, r.createdByDeviceId, r.deliveredAt, r.originalExpiresAt); }
function revokeBytes(r) { return canonical('DINOBLOG_REVOKE', '1', r.revokeId,
  r.originalMessageId, r.originalSourceId, r.originalDestinationId,
  r.originalExpiresAt, r.createdAt, r.retentionUntil); }
function revokeResultBytes(r) { return canonical('DINOBLOG_REVOKE_RESULT', '1', r.resultId,
  r.revokeId, r.originalMessageId, r.originalSourceId, r.originalDestinationId,
  r.result, r.processedAt); }

const hash = value => createHash('sha256').update(value).digest('hex');
const uuid = value => typeof value === 'string' && UUID.test(value);
const fail = (status, code) => { const error = new Error(code); error.status = status; throw error; };
const row = (sql, ...values) => db.prepare(sql).get(...values);
const rows = (sql, ...values) => db.prepare(sql).all(...values);
const run = (sql, ...values) => db.prepare(sql).run(...values);
function noteExperiment(req, deviceId, type, objectId = null, bytes = 0) {
  const id = req.headers['x-dinoblog-experiment-id'];
  if (!uuid(id)) return;
  try {
    run('INSERT INTO experiment_server_events(experiment_id,device_id,event_type,object_id,bytes,event_time) VALUES (?,?,?,?,?,?)',
      id, deviceId, type, objectId, bytes, Date.now());
  } catch (error) { console.warn('experiment metric skipped', error.code || error.name); }
}
function transaction(action) {
  db.exec('BEGIN IMMEDIATE');
  try { const result = action(); db.exec('COMMIT'); return result; }
  catch (error) { db.exec('ROLLBACK'); throw error; }
}
function send(res, status, value) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
}
function authenticate(req) {
  const token = /^Bearer ([A-Za-z0-9_-]{40,100})$/.exec(req.headers.authorization || '')?.[1];
  if (!token) fail(401, 'UNAUTHORIZED');
  const candidate = Buffer.from(hash(token), 'hex');
  const device = rows('SELECT * FROM devices').find(item => timingSafeEqual(candidate, Buffer.from(item.token_hash, 'hex')));
  if (!device) fail(401, 'UNAUTHORIZED');
  run('UPDATE devices SET last_seen_at=? WHERE device_id=?', Date.now(), device.device_id);
  return device.device_id;
}
async function body(req, limit = 64 * 1024) {
  let size = 0; const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) fail(413, 'BODY_TOO_LARGE');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}
async function json(req) {
  try { return JSON.parse((await body(req)).toString('utf8')); }
  catch (error) { if (error.status) throw error; fail(400, 'INVALID_JSON'); }
}
const sockets = new Map();
const requestCounts = new Map();
function rateLimit(req) {
  const id = req.socket.remoteAddress || 'unknown';
  const minute = Math.floor(Date.now() / 60_000);
  const previous = requestCounts.get(id);
  const count = previous?.minute === minute ? previous.count + 1 : 1;
  requestCounts.set(id, { minute, count });
  if (count > config.rateLimitPerMinute) fail(429, 'RATE_LIMITED');
}
function notify(id) {
  for (const socket of sockets.get(id) || []) if (socket.readyState === 1)
    socket.send(JSON.stringify({ type: 'SYNC_AVAILABLE' }));
}
function validateMessage(m) {
  if (!m || !uuid(m.messageId) || !uuid(m.sourceId) || !uuid(m.destinationId) || m.sourceId === m.destinationId)
    fail(400, 'INVALID_MESSAGE');
  if (!['TEXT', 'IMAGE', 'AUDIO', 'VIDEO'].includes(m.contentType) || !Number.isSafeInteger(m.createdAt) ||
      !Number.isSafeInteger(m.expiresAt) || m.createdAt <= 0 || m.expiresAt <= m.createdAt ||
      m.createdAt > Date.now() + 300_000 || m.expiresAt - m.createdAt > 604_800_000 ||
      (m.expiresAt - m.createdAt) % 1000 !== 0)
    fail(400, 'INVALID_MESSAGE');
  if (m.contentType === 'TEXT' && (typeof m.payload !== 'string' || !m.payload.trim() || m.payload.length > 4096 || m.mediaId != null))
    fail(400, 'INVALID_MESSAGE');
  if (m.contentType !== 'TEXT' && (!uuid(m.mediaId) || m.payload !== '')) fail(400, 'INVALID_MESSAGE');
  if (![1, 2].includes(m.signatureVersion) ||
      (m.signatureVersion === 1 && m.relayPolicy != null && m.relayPolicy !== 'ALLOW_RELAY') ||
      (m.signatureVersion === 2 && !['ALLOW_RELAY', 'DIRECT_ONLY'].includes(m.relayPolicy)) ||
      !Number.isInteger(m.hopLimit) || m.hopLimit < 1 || m.hopLimit > 255 ||
      typeof m.originPublicKey !== 'string' || typeof m.originSignature !== 'string' ||
      !HASH.test(m.originPublicKeyFingerprint || '') || !HASH.test(m.contentDigest || ''))
    fail(403, 'MESSAGE_AUTHENTICATION_FAILURE');
  if (m.contentType === 'TEXT') {
    if (m.contentDigest !== hash(m.payload) || m.signedMediaMimeType != null ||
        m.signedMediaFileSize != null || m.signedMediaSha256 != null) fail(403, 'MESSAGE_AUTHENTICATION_FAILURE');
  } else if (!MEDIA_MIMES[m.contentType].has(m.signedMediaMimeType) ||
      !Number.isSafeInteger(m.signedMediaFileSize) || m.signedMediaFileSize < 1 ||
      m.signedMediaFileSize > MEDIA_LIMITS[m.contentType] || !HASH.test(m.signedMediaSha256 || '') ||
      m.contentDigest !== m.signedMediaSha256) fail(403, 'MESSAGE_AUTHENTICATION_FAILURE');
  if (decodeKey(m.originPublicKey).fingerprint !== m.originPublicKeyFingerprint ||
      !signatureValid(m.originPublicKey, messageBytes(m), m.originSignature))
    fail(403, 'MESSAGE_AUTHENTICATION_FAILURE');
}
function messageWire(message) {
  const media = message.media_id ? row('SELECT media_id AS mediaId, message_id AS messageId, mime_type AS mimeType, file_size AS fileSize, sha256, media_type AS mediaType, duration_ms AS durationMs, width, height FROM media WHERE media_id=?', message.media_id) : null;
  return { messageId: message.message_id, sourceId: message.source_id,
    destinationId: message.destination_id, contentType: message.content_type,
    payload: message.payload, mediaId: message.media_id, createdAt: message.created_at,
    expiresAt: message.expires_at, serverReceivedAt: message.server_received_at, media,
    hopLimit: message.hop_limit, signatureVersion: message.signature_version,
    relayPolicy: message.relay_policy,
    originPublicKey: message.origin_public_key,
    originPublicKeyFingerprint: message.origin_public_key_fingerprint,
    contentDigest: message.content_digest, originSignature: message.origin_signature,
    signedMediaMimeType: message.signed_media_mime_type,
    signedMediaFileSize: message.signed_media_file_size, signedMediaSha256: message.signed_media_sha256 };
}
function receiptWire(r) { return { originalMessageId: r.original_message_id, sourceId: r.source_id,
  destinationId: r.destination_id, deliveredAt: r.delivered_at,
  originalExpiresAt: r.original_expires_at, createdByDeviceId: r.created_by_device_id,
  signatureVersion: r.signature_version, destinationPublicKey: r.destination_public_key,
  destinationPublicKeyFingerprint: r.destination_public_key_fingerprint,
  destinationSignature: r.destination_signature }; }
function revokeWire(r) { return { revokeId: r.revoke_id, originalMessageId: r.original_message_id,
  originalSourceId: r.original_source_id, originalDestinationId: r.original_destination_id,
  originalExpiresAt: r.original_expires_at, createdAt: r.created_at,
  retentionUntil: r.retention_until, signatureVersion: r.signature_version,
  sourcePublicKey: r.source_public_key, sourcePublicKeyFingerprint: r.source_public_key_fingerprint,
  sourceSignature: r.source_signature }; }
function revokeResultWire(r) { return { resultId: r.result_id, revokeId: r.revoke_id,
  originalMessageId: r.original_message_id, originalSourceId: r.original_source_id,
  originalDestinationId: r.original_destination_id, result: r.result, processedAt: r.processed_at,
  signatureVersion: r.signature_version, destinationPublicKey: r.destination_public_key,
  destinationPublicKeyFingerprint: r.destination_public_key_fingerprint,
  destinationSignature: r.destination_signature }; }

function validMediaMetadata(m) {
  return m && uuid(m.mediaId) && uuid(m.messageId) && MEDIA_MIMES[m.mediaType]?.has(m.mimeType) &&
    Number.isSafeInteger(m.fileSize) && m.fileSize >= 1 && m.fileSize <= MEDIA_LIMITS[m.mediaType] &&
    HASH.test(m.sha256 || '') &&
    (m.durationMs == null || (Number.isSafeInteger(m.durationMs) && m.durationMs > 0 && m.durationMs <= 86_400_000)) &&
    (m.width == null || (Number.isSafeInteger(m.width) && m.width > 0 && m.width <= 20_000)) &&
    (m.height == null || (Number.isSafeInteger(m.height) && m.height > 0 && m.height <= 20_000));
}
async function hashFile(path) {
  const digest = createHash('sha256');
  for await (const chunk of createReadStream(path)) digest.update(chunk);
  return digest.digest('hex');
}

async function route(req, res) {
  rateLimit(req);
  const url = new URL(req.url || '/', 'http://localhost');
  if (req.method === 'POST' && url.pathname === '/api/v1/devices/registration-challenge') {
    const input = await json(req);
    if (!uuid(input.deviceId)) fail(400, 'INVALID_DEVICE');
    if (row('SELECT 1 FROM devices WHERE device_id=?', input.deviceId)) fail(409, 'DEVICE_ID_CONFLICT');
    const challenge = randomBytes(24).toString('base64url');
    registrationChallenges.set(input.deviceId, { challenge, expiresAt: Date.now() + 120_000 });
    return send(res, 200, { challenge });
  }
  if (req.method === 'POST' && url.pathname === '/api/v1/devices/register') {
    const input = await json(req);
    if (!uuid(input.deviceId) || typeof input.displayName !== 'string' || input.displayName.trim().length > 32)
      fail(400, 'INVALID_DEVICE');
    const existing = row('SELECT * FROM devices WHERE device_id=?', input.deviceId);
    if (existing) {
      if (authenticate(req) !== input.deviceId) fail(403, 'DEVICE_ID_CONFLICT');
      run('UPDATE devices SET display_name=?, last_seen_at=? WHERE device_id=?', input.displayName.trim() || 'DinoBlog User', Date.now(), input.deviceId);
      return send(res, 200, { deviceId: input.deviceId, registered: true });
    }
    const expected = registrationChallenges.get(input.deviceId);
    registrationChallenges.delete(input.deviceId);
    if (!expected || expected.expiresAt < Date.now() || input.challenge !== expected.challenge ||
        input.signatureVersion !== 1 || !HASH.test(input.publicKeyFingerprint || '')) fail(403, 'INVALID_KEY_PROOF');
    const parsed = decodeKey(input.publicKey);
    if (parsed.fingerprint !== input.publicKeyFingerprint ||
        !signatureValid(input.publicKey, canonical('DINOBLOG_DEVICE_REGISTRATION', '1',
          input.deviceId, input.challenge, parsed.fingerprint), input.proofSignature)) fail(403, 'INVALID_KEY_PROOF');
    const token = randomBytes(32).toString('base64url');
    run(`INSERT INTO devices(device_id,display_name,token_hash,registered_at,last_seen_at,
      public_key,public_key_fingerprint,key_bound_at) VALUES (?,?,?,?,?,?,?,?)`,
      input.deviceId, input.displayName.trim() || 'DinoBlog User', hash(token), Date.now(), Date.now(),
      input.publicKey, parsed.fingerprint, Date.now());
    console.info('device registered', input.deviceId);
    return send(res, 201, { deviceId: input.deviceId, token, registered: true });
  }
  if (!url.pathname.startsWith('/api/v1/')) fail(404, 'NOT_FOUND');
  const deviceId = authenticate(req);
  if (req.method === 'GET' && url.pathname === '/api/v1/health') return send(res, 200, { ok: true, deviceId });
  if (req.method === 'GET' && /^\/api\/v1\/devices\/[0-9a-f-]+\/key$/i.test(url.pathname)) {
    const id = url.pathname.split('/')[4];
    if (!uuid(id)) fail(400, 'INVALID_DEVICE');
    const device = row('SELECT public_key,public_key_fingerprint,key_bound_at FROM devices WHERE device_id=?', id);
    if (!device || !device.public_key) fail(404, 'IDENTITY_NOT_BOUND');
    return send(res, 200, { deviceId: id, publicKey: device.public_key,
      publicKeyFingerprint: device.public_key_fingerprint, keyBoundAt: device.key_bound_at });
  }
  if (req.method === 'POST' && url.pathname === '/api/v1/devices/key-challenge') {
    const challenge = randomBytes(24).toString('base64url');
    challenges.set(deviceId, { challenge, expiresAt: Date.now() + 120_000 });
    return send(res, 200, { challenge });
  }
  if (req.method === 'POST' && url.pathname === '/api/v1/devices/key-binding') {
    const input = await json(req);
    const expected = challenges.get(deviceId);
    challenges.delete(deviceId);
    if (!expected || expected.expiresAt < Date.now() || input.challenge !== expected.challenge ||
        input.signatureVersion !== 1 || !HASH.test(input.publicKeyFingerprint || '')) fail(403, 'INVALID_KEY_PROOF');
    const parsed = decodeKey(input.publicKey);
    if (parsed.fingerprint !== input.publicKeyFingerprint ||
        !signatureValid(input.publicKey, canonical('DINOBLOG_KEY_BINDING', '1', deviceId,
          input.challenge, parsed.fingerprint), input.proofSignature)) fail(403, 'INVALID_KEY_PROOF');
    const old = row('SELECT public_key_fingerprint FROM devices WHERE device_id=?', deviceId);
    if (old.public_key_fingerprint && old.public_key_fingerprint !== parsed.fingerprint)
      fail(409, 'IDENTITY_KEY_MISMATCH');
    if (!old.public_key_fingerprint) run('UPDATE devices SET public_key=?,public_key_fingerprint=?,key_bound_at=? WHERE device_id=?',
      input.publicKey, parsed.fingerprint, Date.now(), deviceId);
    return send(res, 200, { deviceId, publicKeyFingerprint: parsed.fingerprint, bound: true });
  }
  if (req.method === 'GET' && url.pathname === '/api/v1/experiments/metrics') {
    const id = url.searchParams.get('experimentId');
    if (!uuid(id)) fail(400, 'INVALID_EXPERIMENT_ID');
    const metrics = rows(`SELECT event_type AS eventType, COUNT(*) AS count, SUM(bytes) AS bytes
      FROM experiment_server_events WHERE device_id=? AND experiment_id=? GROUP BY event_type`, deviceId, id);
    return send(res, 200, { experimentId: id, metrics });
  }
  if (req.method === 'POST' && /^\/api\/v1\/messages\/[0-9a-f-]+\/revoke$/i.test(url.pathname)) {
    const r = await json(req);
    const id = url.pathname.split('/')[4];
    if (r && r.originalSourceId !== deviceId) fail(403, 'REVOKE_SOURCE_FORBIDDEN');
    if (!r || !uuid(id) || r.originalMessageId !== id || !uuid(r.revokeId) ||
        !uuid(r.originalSourceId) || !uuid(r.originalDestinationId) ||
        r.originalSourceId === r.originalDestinationId ||
        !Number.isSafeInteger(r.originalExpiresAt) || !Number.isSafeInteger(r.createdAt) ||
        !Number.isSafeInteger(r.retentionUntil) || r.createdAt <= 0 ||
        r.createdAt > Date.now() + 300_000 || r.retentionUntil !== r.originalExpiresAt + 86_400_000 ||
        r.retentionUntil <= Date.now() || r.originalExpiresAt > Date.now() + 604_800_000 ||
        r.signatureVersion !== 1) fail(400, 'INVALID_REVOKE');
    const source = row('SELECT public_key_fingerprint FROM devices WHERE device_id=?', deviceId);
    if (!source?.public_key_fingerprint || source.public_key_fingerprint !== r.sourcePublicKeyFingerprint ||
        decodeKey(r.sourcePublicKey).fingerprint !== r.sourcePublicKeyFingerprint ||
        !signatureValid(r.sourcePublicKey, revokeBytes(r), r.sourceSignature))
      fail(403, 'REVOKE_AUTHENTICATION_FAILURE');
    const message = row('SELECT * FROM messages WHERE message_id=?', id);
    if (message && (message.source_id !== r.originalSourceId ||
        message.destination_id !== r.originalDestinationId || message.expires_at !== r.originalExpiresAt))
      fail(409, 'REVOKE_MESSAGE_CONFLICT');
    const old = row('SELECT * FROM message_revocations WHERE original_message_id=?', id);
    if (old) {
      if (old.revoke_id !== r.revokeId || old.source_signature !== r.sourceSignature ||
          old.original_source_id !== r.originalSourceId || old.original_destination_id !== r.originalDestinationId)
        fail(409, 'REVOKE_CONFLICT');
      return send(res, 200, { accepted: true, duplicate: true,
        alreadyDelivered: !!row('SELECT 1 FROM delivery_receipts WHERE original_message_id=?', id) });
    }
    transaction(() => {
      run(`INSERT INTO message_revocations(original_message_id,revoke_id,original_source_id,
        original_destination_id,original_expires_at,created_at,retention_until,signature_version,
        source_public_key,source_public_key_fingerprint,source_signature,server_received_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`, id, r.revokeId, r.originalSourceId,
        r.originalDestinationId, r.originalExpiresAt, r.createdAt, r.retentionUntil,
        r.signatureVersion, r.sourcePublicKey, r.sourcePublicKeyFingerprint,
        r.sourceSignature, Date.now());
      run('INSERT INTO sync_events(target_device_id,event_type,object_id,created_at) VALUES (?,?,?,?)',
        r.originalDestinationId, 'REVOKE', id, Date.now());
    });
    noteExperiment(req, deviceId, 'REVOKE_ACCEPTED', id);
    notify(r.originalDestinationId);
    return send(res, 201, { accepted: true, duplicate: false,
      alreadyDelivered: !!row('SELECT 1 FROM delivery_receipts WHERE original_message_id=?', id) });
  }
  if (req.method === 'POST' && url.pathname === '/api/v1/revoke-results') {
    const r = await json(req);
    if (r && r.originalDestinationId !== deviceId) fail(403, 'REVOKE_RESULT_DESTINATION_FORBIDDEN');
    if (!r || !uuid(r.resultId) || !uuid(r.revokeId) || !uuid(r.originalMessageId) ||
        !uuid(r.originalSourceId) ||
        !['CANCELLED_BEFORE_DELIVERY', 'ALREADY_DELIVERED'].includes(r.result) ||
        !Number.isSafeInteger(r.processedAt) || r.processedAt <= 0 ||
        r.processedAt > Date.now() + 300_000 || r.signatureVersion !== 1)
      fail(400, 'INVALID_REVOKE_RESULT');
    const revoke = row('SELECT * FROM message_revocations WHERE original_message_id=?', r.originalMessageId);
    if (!revoke || revoke.revoke_id !== r.revokeId || revoke.original_source_id !== r.originalSourceId ||
        revoke.original_destination_id !== r.originalDestinationId) fail(409, 'REVOKE_RESULT_CONFLICT');
    const destination = row('SELECT public_key_fingerprint FROM devices WHERE device_id=?', deviceId);
    if (!destination?.public_key_fingerprint || destination.public_key_fingerprint !== r.destinationPublicKeyFingerprint ||
        decodeKey(r.destinationPublicKey).fingerprint !== r.destinationPublicKeyFingerprint ||
        !signatureValid(r.destinationPublicKey, revokeResultBytes(r), r.destinationSignature))
      fail(403, 'REVOKE_RESULT_AUTHENTICATION_FAILURE');
    if (r.result === 'CANCELLED_BEFORE_DELIVERY' &&
        row('SELECT 1 FROM delivery_receipts WHERE original_message_id=?', r.originalMessageId))
      fail(409, 'ALREADY_DELIVERED');
    const old = row('SELECT * FROM revoke_results WHERE original_message_id=?', r.originalMessageId);
    if (old) {
      if (old.result_id !== r.resultId || old.destination_signature !== r.destinationSignature)
        fail(409, 'REVOKE_RESULT_CONFLICT');
      return send(res, 200, { accepted: true, duplicate: true });
    }
    transaction(() => {
      run(`INSERT INTO revoke_results(original_message_id,result_id,revoke_id,original_source_id,
        original_destination_id,result,processed_at,signature_version,destination_public_key,
        destination_public_key_fingerprint,destination_signature,server_received_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`, r.originalMessageId, r.resultId, r.revokeId,
        r.originalSourceId, r.originalDestinationId, r.result, r.processedAt,
        r.signatureVersion, r.destinationPublicKey, r.destinationPublicKeyFingerprint,
        r.destinationSignature, Date.now());
      run('INSERT INTO sync_events(target_device_id,event_type,object_id,created_at) VALUES (?,?,?,?)',
        r.originalSourceId, 'REVOKE_RESULT', r.originalMessageId, Date.now());
    });
    notify(r.originalSourceId);
    return send(res, 201, { accepted: true, duplicate: false });
  }
  if (req.method === 'POST' && url.pathname === '/api/v1/messages') {
    const m = await json(req); validateMessage(m);
    if (m.sourceId !== deviceId) fail(403, 'SOURCE_MISMATCH');
    const sourceKey = row('SELECT public_key_fingerprint FROM devices WHERE device_id=?', deviceId);
    if (!sourceKey.public_key_fingerprint || sourceKey.public_key_fingerprint !== m.originPublicKeyFingerprint)
      fail(403, 'IDENTITY_KEY_MISMATCH');
    if (!row('SELECT 1 FROM devices WHERE device_id=?', m.destinationId)) fail(422, 'DESTINATION_NOT_REGISTERED');
    if (row('SELECT 1 FROM message_revocations WHERE original_message_id=?', m.messageId)) fail(409, 'MESSAGE_REVOKED');
    const old = row('SELECT * FROM messages WHERE message_id=?', m.messageId);
    if (old) {
      if (old.source_id !== m.sourceId || old.destination_id !== m.destinationId || old.content_type !== m.contentType ||
          old.payload !== m.payload || old.media_id !== (m.mediaId || null) || old.created_at !== m.createdAt || old.expires_at !== m.expiresAt ||
          old.hop_limit !== m.hopLimit || old.relay_policy !== (m.relayPolicy || 'ALLOW_RELAY') ||
          old.content_digest !== m.contentDigest ||
          old.origin_signature !== m.originSignature || old.origin_public_key_fingerprint !== m.originPublicKeyFingerprint ||
          old.signed_media_mime_type !== (m.signedMediaMimeType || null) ||
          old.signed_media_file_size !== (m.signedMediaFileSize || null) ||
          old.signed_media_sha256 !== (m.signedMediaSha256 || null)) {
        console.info('message conflict rejected', m.messageId);
        fail(409, 'MESSAGE_ID_CONFLICT');
      }
      console.info('duplicate message accepted idempotently', m.messageId);
      noteExperiment(req, deviceId, 'MESSAGE_DUPLICATE_ACCEPTED', m.messageId);
      return send(res, 200, { status: 'SERVER_ACCEPTED', duplicate: true });
    }
    if (m.expiresAt <= Date.now()) fail(422, 'EXPIRED');
    if (m.contentType !== 'TEXT') {
      const media = row('SELECT * FROM media WHERE media_id=?', m.mediaId);
      if (!media || media.message_id !== m.messageId || media.source_id !== deviceId || media.media_type !== m.contentType ||
          media.mime_type !== m.signedMediaMimeType || media.file_size !== m.signedMediaFileSize ||
          media.sha256 !== m.signedMediaSha256) fail(422, 'MEDIA_NOT_READY');
    }
    transaction(() => {
      run(`INSERT INTO messages(message_id,source_id,destination_id,content_type,payload,media_id,
        created_at,expires_at,server_received_at,hop_limit,relay_policy,signature_version,origin_public_key,
        origin_public_key_fingerprint,content_digest,origin_signature,signed_media_mime_type,
        signed_media_file_size,signed_media_sha256) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
        m.messageId, m.sourceId, m.destinationId, m.contentType, m.payload, m.mediaId || null,
        m.createdAt, m.expiresAt, Date.now(), m.hopLimit, m.relayPolicy || 'ALLOW_RELAY',
        m.signatureVersion, m.originPublicKey,
        m.originPublicKeyFingerprint, m.contentDigest, m.originSignature,
        m.signedMediaMimeType || null, m.signedMediaFileSize || null, m.signedMediaSha256 || null);
      run('INSERT INTO sync_events(target_device_id,event_type,object_id,created_at) VALUES (?,?,?,?)',
        m.destinationId, 'MESSAGE', m.messageId, Date.now());
    });
    noteExperiment(req, deviceId, 'MESSAGE_ACCEPTED', m.messageId, Buffer.byteLength(m.payload || '', 'utf8'));
    notify(m.destinationId); console.info('message accepted', m.messageId);
    return send(res, 201, { status: 'SERVER_ACCEPTED', duplicate: false });
  }
  if (req.method === 'POST' && url.pathname === '/api/v1/media/upload-session') {
    const m = await json(req);
    if (m?.messageId && row('SELECT 1 FROM message_revocations WHERE original_message_id=?', m.messageId))
      fail(409, 'MESSAGE_REVOKED');
    if (!validMediaMetadata(m)) fail(400, 'INVALID_MEDIA');
    const complete = row('SELECT * FROM media WHERE media_id=? OR message_id=?', m.mediaId, m.messageId);
    if (complete) {
      if (complete.media_id !== m.mediaId || complete.message_id !== m.messageId ||
          complete.source_id !== deviceId || complete.media_type !== m.mediaType ||
          complete.mime_type !== m.mimeType || complete.file_size !== m.fileSize ||
          complete.sha256 !== m.sha256.toLowerCase() || complete.duration_ms !== (m.durationMs ?? null) ||
          complete.width !== (m.width ?? null) || complete.height !== (m.height ?? null)) fail(409, 'MEDIA_ID_CONFLICT');
      return send(res, 200, { status: 'MEDIA_STORED', receivedBytes: m.fileSize });
    }
    const old = row('SELECT * FROM media_uploads WHERE media_id=? OR message_id=?', m.mediaId, m.messageId);
    if (old) {
      if (old.media_id !== m.mediaId || old.message_id !== m.messageId || old.source_id !== deviceId ||
          old.media_type !== m.mediaType || old.mime_type !== m.mimeType || old.file_size !== m.fileSize ||
          old.sha256 !== m.sha256.toLowerCase() || old.duration_ms !== (m.durationMs ?? null) ||
          old.width !== (m.width ?? null) || old.height !== (m.height ?? null)) fail(409, 'MEDIA_ID_CONFLICT');
      return send(res, 200, { status: 'PARTIAL', receivedBytes: old.bytes_received });
    }
    const path = join(config.mediaPath, `${m.mediaId.toLowerCase()}.upload`);
    if (existsSync(path)) unlinkSync(path);
    transaction(() => run(`INSERT INTO media_uploads VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`,
      m.mediaId, m.messageId, deviceId, m.mediaType, m.mimeType, m.fileSize, m.sha256.toLowerCase(),
      m.durationMs ?? null, m.width ?? null, m.height ?? null, path, 0, Date.now()));
    return send(res, 201, { status: 'PARTIAL', receivedBytes: 0 });
  }
  if (req.method === 'PUT' && /^\/api\/v1\/media\/[0-9a-f-]+\/chunks$/i.test(url.pathname)) {
    const mediaId = url.pathname.split('/')[4];
    const offset = Number(url.searchParams.get('offset'));
    const upload = row('SELECT * FROM media_uploads WHERE media_id=?', mediaId);
    if (!upload || upload.source_id !== deviceId) fail(404, 'UPLOAD_NOT_FOUND');
    if (!Number.isSafeInteger(offset) || offset < 0 || offset >= upload.file_size ||
        offset % CHUNK_BYTES !== 0) fail(400, 'INVALID_OFFSET');
    const bytes = await body(req, CHUNK_BYTES);
    if (bytes.length !== Math.min(CHUNK_BYTES, upload.file_size - offset)) fail(400, 'INVALID_CHUNK');
    const current = row('SELECT * FROM media_uploads WHERE media_id=?', mediaId);
    if (!current || current.source_id !== deviceId) fail(404, 'UPLOAD_NOT_FOUND');
    if (row('SELECT 1 FROM message_revocations WHERE original_message_id=?', current.message_id))
      fail(409, 'MESSAGE_REVOKED');
    if (offset < current.bytes_received) {
      const saved = Buffer.alloc(bytes.length);
      const descriptor = openSync(current.storage_path, 'r');
      try { if (readSync(descriptor, saved, 0, saved.length, offset) !== saved.length) fail(409, 'CHUNK_CONFLICT'); }
      finally { closeSync(descriptor); }
      if (!saved.equals(bytes)) fail(409, 'CHUNK_CONFLICT');
      return send(res, 200, { status: 'PARTIAL', receivedBytes: current.bytes_received });
    }
    if (offset !== current.bytes_received) fail(409, 'OFFSET_CONFLICT');
    appendFileSync(current.storage_path, bytes);
    const received = offset + bytes.length;
    run('UPDATE media_uploads SET bytes_received=?, updated_at=? WHERE media_id=?', received, Date.now(), mediaId);
    if (received < current.file_size) return send(res, 200, { status: 'PARTIAL', receivedBytes: received });
    if (await hashFile(current.storage_path) !== current.sha256) {
      unlinkSync(current.storage_path);
      run('DELETE FROM media_uploads WHERE media_id=?', mediaId);
      fail(422, 'MEDIA_HASH_MISMATCH');
    }
    const finalPath = join(config.mediaPath, `${mediaId.toLowerCase()}.media`);
    renameSync(current.storage_path, finalPath);
    transaction(() => {
      run(`INSERT INTO media(media_id,message_id,source_id,mime_type,file_size,sha256,storage_path,created_at,
        media_type,duration_ms,width,height) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`,
      mediaId, current.message_id, deviceId, current.mime_type, current.file_size, current.sha256,
      finalPath, Date.now(), current.media_type, current.duration_ms, current.width, current.height);
      run('DELETE FROM media_uploads WHERE media_id=?', mediaId);
    });
    noteExperiment(req, deviceId, 'MEDIA_STORED', mediaId, received);
    console.info('media stored', mediaId);
    return send(res, 201, { status: 'MEDIA_STORED', receivedBytes: received });
  }
  if (req.method === 'PUT' && /^\/api\/v1\/media\/[0-9a-f-]+$/i.test(url.pathname)) {
    const mediaId = url.pathname.split('/').pop();
    const messageId = req.headers['x-message-id'];
    const sha256 = req.headers['x-sha256'];
    const mime = req.headers['content-type'];
    const declared = Number(req.headers['content-length']);
    if (!uuid(mediaId) || !uuid(messageId) || !HASH.test(sha256 || '') || !IMAGE_MIMES.has(mime) ||
        !Number.isSafeInteger(declared) || declared < 1 || declared > MAX_IMAGE) fail(400, 'INVALID_MEDIA');
    const bytes = await body(req, MAX_IMAGE);
    if (bytes.length !== declared || hash(bytes) !== sha256.toLowerCase()) fail(422, 'MEDIA_HASH_MISMATCH');
    if (row('SELECT 1 FROM message_revocations WHERE original_message_id=?', messageId))
      fail(409, 'MESSAGE_REVOKED');
    const existing = row('SELECT * FROM media WHERE media_id=? OR message_id=?', mediaId, messageId);
    if (existing) {
      if (existing.media_id !== mediaId || existing.message_id !== messageId || existing.source_id !== deviceId ||
          existing.file_size !== declared || existing.sha256 !== sha256.toLowerCase() || existing.mime_type !== mime)
        fail(409, 'MEDIA_ID_CONFLICT');
      return send(res, 200, { status: 'MEDIA_STORED', duplicate: true });
    }
    const path = join(config.mediaPath, `${mediaId.toLowerCase()}.media`);
    const temporary = `${path}.${randomBytes(8).toString('hex')}.tmp`;
    const { writeFileSync } = await import('node:fs');
    writeFileSync(temporary, bytes, { flag: 'wx' });
    try {
      renameSync(temporary, path);
      run('INSERT INTO media(media_id,message_id,source_id,mime_type,file_size,sha256,storage_path,created_at) VALUES (?,?,?,?,?,?,?,?)', mediaId, messageId, deviceId, mime,
        declared, sha256.toLowerCase(), path, Date.now());
    } catch (error) { if (existsSync(temporary)) unlinkSync(temporary); throw error; }
    noteExperiment(req, deviceId, 'MEDIA_STORED', mediaId, declared);
    console.info('media stored', mediaId);
    return send(res, 201, { status: 'MEDIA_STORED', duplicate: false });
  }
  if (req.method === 'GET' && /^\/api\/v1\/media\/[0-9a-f-]+$/i.test(url.pathname)) {
    const mediaId = url.pathname.split('/').pop();
    const media = row('SELECT * FROM media WHERE media_id=?', mediaId);
    const m = media && row('SELECT * FROM messages WHERE message_id=?', media.message_id);
    if (!m || (m.source_id !== deviceId && m.destination_id !== deviceId)) fail(403, 'MEDIA_FORBIDDEN');
    if (row('SELECT 1 FROM message_revocations WHERE original_message_id=?', m.message_id))
      fail(409, 'MESSAGE_REVOKED');
    if (!existsSync(media.storage_path)) fail(404, 'MEDIA_MISSING');
    const range = /^bytes=(\d+)-(\d+)$/.exec(req.headers.range || '');
    if (req.headers.range && !range) fail(416, 'INVALID_RANGE');
    const start = range ? Number(range[1]) : 0;
    const end = range ? Number(range[2]) : media.file_size - 1;
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end < start || end >= media.file_size ||
        (range && end - start + 1 > CHUNK_BYTES)) fail(416, 'INVALID_RANGE');
    res.writeHead(range ? 206 : 200, { 'Content-Type': media.mime_type, 'Content-Length': end - start + 1,
      ...(range ? { 'Content-Range': `bytes ${start}-${end}/${media.file_size}` } : {}),
      'Accept-Ranges': 'bytes', 'X-Sha256': media.sha256, 'Cache-Control': 'no-store' });
    noteExperiment(req, deviceId, 'MEDIA_DOWNLOADED', mediaId, end - start + 1);
    return createReadStream(media.storage_path, { start, end }).pipe(res);
  }
  if (req.method === 'POST' && url.pathname === '/api/v1/receipts') {
    const r = await json(req);
    const m = r && row('SELECT * FROM messages WHERE message_id=?', r.originalMessageId);
    if (!m || m.destination_id !== deviceId || r.sourceId !== m.source_id ||
        r.destinationId !== deviceId || r.createdByDeviceId !== deviceId ||
        r.originalExpiresAt !== m.expires_at || !Number.isSafeInteger(r.deliveredAt) ||
        r.deliveredAt < m.created_at || r.deliveredAt >= m.expires_at ||
        r.deliveredAt > Date.now() + 300_000) fail(403, 'INVALID_RECEIPT');
    const destinationKey = row('SELECT public_key_fingerprint FROM devices WHERE device_id=?', deviceId);
    if (r.signatureVersion !== 1 || !destinationKey.public_key_fingerprint ||
        destinationKey.public_key_fingerprint !== r.destinationPublicKeyFingerprint ||
        decodeKey(r.destinationPublicKey).fingerprint !== r.destinationPublicKeyFingerprint ||
        !signatureValid(r.destinationPublicKey, receiptBytes(r), r.destinationSignature))
      fail(403, 'RECEIPT_AUTHENTICATION_FAILURE');
    if (row("SELECT 1 FROM revoke_results WHERE original_message_id=? AND result='CANCELLED_BEFORE_DELIVERY'", r.originalMessageId))
      fail(409, 'MESSAGE_REVOKED');
    const old = row('SELECT * FROM delivery_receipts WHERE original_message_id=?', r.originalMessageId);
    if (old) {
      if (old.source_id !== r.sourceId || old.destination_id !== r.destinationId || old.original_expires_at !== r.originalExpiresAt ||
          old.destination_signature !== r.destinationSignature)
        fail(409, 'RECEIPT_CONFLICT');
      return send(res, 200, { accepted: true, duplicate: true });
    }
    transaction(() => {
      run(`INSERT INTO delivery_receipts(original_message_id,source_id,destination_id,delivered_at,
        original_expires_at,created_by_device_id,server_received_at,signature_version,
        destination_public_key,destination_public_key_fingerprint,destination_signature)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)`, r.originalMessageId, r.sourceId,
        r.destinationId, r.deliveredAt, r.originalExpiresAt, r.createdByDeviceId, Date.now(),
        r.signatureVersion, r.destinationPublicKey, r.destinationPublicKeyFingerprint, r.destinationSignature);
      run('INSERT INTO sync_events(target_device_id,event_type,object_id,created_at) VALUES (?,?,?,?)',
        r.sourceId, 'RECEIPT', r.originalMessageId, Date.now());
    });
    notify(r.sourceId); console.info('receipt accepted', r.originalMessageId);
    noteExperiment(req, deviceId, 'RECEIPT_ACCEPTED', r.originalMessageId);
    return send(res, 201, { accepted: true, duplicate: false });
  }
  if (req.method === 'POST' && url.pathname === '/api/v1/receipts/lookup') {
    const input = await json(req);
    if (!Array.isArray(input.messageIds) || input.messageIds.length > 200 || input.messageIds.some(id => !uuid(id)))
      fail(400, 'INVALID_IDS');
    const found = input.messageIds.map(id => row('SELECT * FROM delivery_receipts WHERE original_message_id=?', id))
      .filter(r => r?.signature_version === 1 && r.destination_signature).map(receiptWire);
    return send(res, 200, { deliveryReceipts: found });
  }
  if (req.method === 'POST' && url.pathname === '/api/v1/revokes/lookup') {
    const input = await json(req);
    if (!Array.isArray(input.messageIds) || input.messageIds.length > 200 || input.messageIds.some(id => !uuid(id)))
      fail(400, 'INVALID_IDS');
    const found = input.messageIds.map(id => row('SELECT * FROM message_revocations WHERE original_message_id=?', id))
      .filter(r => r && r.retention_until > Date.now()).map(revokeWire);
    return send(res, 200, { revokes: found });
  }
  if (req.method === 'GET' && url.pathname === '/api/v1/sync') {
    const cursor = Number(url.searchParams.get('cursor') || 0);
    if (!Number.isSafeInteger(cursor) || cursor < 0) fail(400, 'INVALID_CURSOR');
    console.info('sync requested', deviceId);
    noteExperiment(req, deviceId, 'SYNC_REQUESTED');
    const events = rows('SELECT * FROM sync_events WHERE target_device_id=? AND event_id>? ORDER BY event_id LIMIT 100', deviceId, cursor);
    const messages = []; const deliveryReceipts = []; const revokes = []; const revokeResults = [];
    for (const event of events) {
      if (event.event_type === 'MESSAGE') {
        const m = row('SELECT * FROM messages WHERE message_id=?', event.object_id);
        if (m && m.expires_at > Date.now() && [1, 2].includes(m.signature_version) && m.origin_signature &&
            !row('SELECT 1 FROM message_revocations WHERE original_message_id=?', m.message_id))
          messages.push(messageWire(m));
      } else if (event.event_type === 'RECEIPT') {
        const r = row('SELECT * FROM delivery_receipts WHERE original_message_id=?', event.object_id);
        if (r?.signature_version === 1 && r.destination_signature) deliveryReceipts.push(receiptWire(r));
      } else if (event.event_type === 'REVOKE') {
        const r = row('SELECT * FROM message_revocations WHERE original_message_id=?', event.object_id);
        if (r && r.retention_until > Date.now()) revokes.push(revokeWire(r));
      } else if (event.event_type === 'REVOKE_RESULT') {
        const r = row('SELECT * FROM revoke_results WHERE original_message_id=?', event.object_id);
        if (r) revokeResults.push(revokeResultWire(r));
      }
    }
    return send(res, 200, { nextCursor: events.at(-1)?.event_id || cursor, messages, deliveryReceipts,
      revokes, revokeResults,
      hasMore: events.length === 100 });
  }
  fail(404, 'NOT_FOUND');
}

function cleanup() {
  const now = Date.now();
  for (const upload of rows('SELECT * FROM media_uploads WHERE updated_at<?', now - config.partialUploadMaxAgeMs)) {
    if (existsSync(upload.storage_path)) unlinkSync(upload.storage_path);
    run('DELETE FROM media_uploads WHERE media_id=?', upload.media_id);
  }
  for (const m of rows('SELECT * FROM messages WHERE expires_at<=? OR message_id IN (SELECT original_message_id FROM delivery_receipts WHERE server_received_at<=?) OR message_id IN (SELECT original_message_id FROM message_revocations)',
    now, now - config.graceMs)) {
    const media = row('SELECT * FROM media WHERE message_id=?', m.message_id);
    if (media) { if (existsSync(media.storage_path)) unlinkSync(media.storage_path); run('DELETE FROM media WHERE media_id=?', media.media_id); }
    // Keep a small receipt tombstone for duplicate and sync replay safety.
    if (m.expires_at <= now - config.expiredGraceMs) run('DELETE FROM messages WHERE message_id=?', m.message_id);
  }
  for (const media of rows('SELECT * FROM media WHERE message_id NOT IN (SELECT message_id FROM messages) AND created_at<?', now - 3600_000)) {
    if (existsSync(media.storage_path)) unlinkSync(media.storage_path);
    run('DELETE FROM media WHERE media_id=?', media.media_id);
  }
  const knownUploads = new Set(rows('SELECT media_id FROM media_uploads').map(item => item.media_id.toLowerCase()));
  const knownMedia = new Set(rows('SELECT media_id FROM media').map(item => item.media_id.toLowerCase()));
  for (const file of readdirSync(config.mediaPath)) {
    const path = join(config.mediaPath, file);
    if (file.endsWith('.tmp') && statSync(path).mtimeMs < now - 3600_000) unlinkSync(path);
    else if (/^[0-9a-f-]{36}\.upload$/.test(file) && !knownUploads.has(file.slice(0, -7)) &&
        statSync(path).mtimeMs < now - 3600_000) unlinkSync(path);
    else if (/^[0-9a-f-]{36}\.media$/.test(file) && !knownMedia.has(file.slice(0, -6)) &&
        statSync(path).mtimeMs < now - 3600_000) unlinkSync(path);
  }
  run('DELETE FROM sync_events WHERE created_at<?', now - config.receiptRetentionMs);
  run('DELETE FROM delivery_receipts WHERE server_received_at<?', now - config.receiptRetentionMs);
  run('DELETE FROM revoke_results WHERE original_message_id IN (SELECT original_message_id FROM message_revocations WHERE retention_until<?) AND server_received_at<?',
    now, now - config.receiptRetentionMs);
  run('DELETE FROM message_revocations WHERE retention_until<?', now);
  run('DELETE FROM experiment_server_events WHERE event_time<?', now - config.experimentRetentionMs);
  for (const [id, value] of requestCounts) if (value.minute < Math.floor(now / 60_000) - 1) requestCounts.delete(id);
  for (const [id, value] of challenges) if (value.expiresAt < now) challenges.delete(id);
  for (const [id, value] of registrationChallenges) if (value.expiresAt < now) registrationChallenges.delete(id);
}
const server = createServer((req, res) => {
  route(req, res).catch(error => {
    if (!res.headersSent) send(res, error.status || 500, { error: error.status ? error.message : 'INTERNAL_ERROR' });
    if (!error.status) console.error('request failed', error);
  });
});
server.requestTimeout = 30_000;
server.headersTimeout = 15_000;
const wss = new WebSocketServer({ noServer: true, maxPayload: 1024 });
server.on('upgrade', (req, socket, head) => {
  try {
    rateLimit(req);
    if (new URL(req.url || '/', 'http://localhost').pathname !== '/api/v1/ws') fail(404, 'NOT_FOUND');
    const id = authenticate(req);
    wss.handleUpgrade(req, socket, head, client => {
      const set = sockets.get(id) || new Set(); set.add(client); sockets.set(id, set);
      client.on('close', () => { set.delete(client); if (!set.size) sockets.delete(id); });
      client.send(JSON.stringify({ type: 'SYNC_AVAILABLE' }));
    });
  } catch { socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n'); socket.destroy(); }
});
cleanup();
const timer = setInterval(cleanup, 3600_000); timer.unref();
if (process.env.NODE_ENV !== 'test') server.listen(config.port, config.host,
  () => console.info(`DinoBlog mailbox listening on ${config.host}:${config.port}`));
export { server, db, cleanup };
