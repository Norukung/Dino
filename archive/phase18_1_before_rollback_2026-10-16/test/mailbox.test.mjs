import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID, randomBytes, createHash, sign } from 'node:crypto';
import { WebSocket } from 'ws';
import { createIdentity, registerIdentity, bindIdentity, signMessage, signReceipt, canonical, digest } from './security-fixture.mjs';

const folder = mkdtempSync(join(tmpdir(), 'dinoblog-server-'));
process.env.NODE_ENV = 'test';
process.env.DATABASE_PATH = join(folder, 'mailbox.sqlite');
process.env.MEDIA_STORAGE_PATH = join(folder, 'media');
const { server, db, cleanup } = await import('../src/index.ts');
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
const identities = new Map();
const signedMessages = new Map();
const signedReceipts = new Map();

async function request(path, token, method = 'GET', value, extra = {}) {
  if (path === '/api/v1/messages' && value?.sourceId && value.signatureVersion == null) {
    const cacheKey = JSON.stringify(value);
    const media = value.contentType === 'TEXT' ? null :
      (db.prepare('SELECT mime_type AS mimeType,file_size AS fileSize,sha256 FROM media WHERE media_id=?').get(value.mediaId) ||
       db.prepare('SELECT mime_type AS mimeType,file_size AS fileSize,sha256 FROM media_uploads WHERE media_id=?').get(value.mediaId) ||
       { mimeType: value.contentType === 'IMAGE' ? 'image/jpeg' : value.contentType === 'VIDEO' ? 'video/mp4' : 'audio/mp4',
         fileSize: 1, sha256: '0'.repeat(64) });
    value = signedMessages.get(cacheKey) || signMessage(value, identities.get(value.sourceId), media);
    signedMessages.set(cacheKey, value);
  }
  if (path === '/api/v1/receipts' && value?.destinationId && value.signatureVersion == null) {
    const cacheKey = JSON.stringify(value);
    value = signedReceipts.get(cacheKey) || signReceipt(value, identities.get(value.destinationId));
    signedReceipts.set(cacheKey, value);
  }
  const response = await fetch(base + path, {
    method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(value && !(value instanceof Uint8Array) ? { 'Content-Type': 'application/json' } : {}), ...extra },
    body: value instanceof Uint8Array ? value : value == null ? undefined : JSON.stringify(value),
  });
  return { status: response.status, data: await response.json() };
}
async function register() {
  const id = randomUUID();
  const identity = createIdentity();
  const response = await registerIdentity(base, id, identity);
  assert.equal(response.status, 201);
  identities.set(id, identity);
  return { id, token: response.data.token };
}

test('mailbox identity, cursor, idempotency, receipt, expiry, and media authorization', async () => {
  const a = await register(), d = await register(), x = await register();
  assert.notEqual(db.prepare('SELECT token_hash FROM devices WHERE device_id=?').get(a.id).token_hash, a.token);
  const now = Date.now();
  const message = { messageId: randomUUID(), sourceId: a.id, destinationId: d.id,
    contentType: 'TEXT', payload: 'Hello', createdAt: now, expiresAt: now + 86_400_000 };
  assert.equal((await request('/api/v1/messages', a.token, 'POST', message)).data.status, 'SERVER_ACCEPTED');
  const duplicate = await request('/api/v1/messages', a.token, 'POST', message);
  assert.equal(duplicate.status, 200);
  assert.equal(duplicate.data.duplicate, true);
  assert.equal((await request('/api/v1/messages', a.token, 'POST', { ...message, payload: 'Changed' })).data.error, 'MESSAGE_ID_CONFLICT');
  assert.equal((await request('/api/v1/messages', x.token, 'POST', message)).data.error, 'SOURCE_MISMATCH');
  assert.equal((await request('/api/v1/messages', a.token, 'POST', { ...message, messageId: randomUUID(), destinationId: randomUUID() })).data.error,
    'DESTINATION_NOT_REGISTERED');
  assert.equal((await request('/api/v1/messages', a.token, 'POST', { ...message, messageId: randomUUID(),
    createdAt: now - 120_000, expiresAt: now - 60_000 })).data.error, 'EXPIRED');
  const oldMessage = { ...message, messageId: randomUUID(), createdAt: now - 120_000,
    expiresAt: now - 60_000 };
  const signedOld = signMessage(oldMessage, identities.get(a.id));
  signedMessages.set(JSON.stringify(oldMessage), signedOld);
  db.prepare(`INSERT INTO messages(message_id,source_id,destination_id,content_type,payload,media_id,
    created_at,expires_at,server_received_at,hop_limit,signature_version,origin_public_key,
    origin_public_key_fingerprint,content_digest,origin_signature)
    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`).run(oldMessage.messageId,
    oldMessage.sourceId, oldMessage.destinationId, oldMessage.contentType, oldMessage.payload,
    null, oldMessage.createdAt, oldMessage.expiresAt, oldMessage.createdAt, signedOld.hopLimit,
    signedOld.signatureVersion, signedOld.originPublicKey, signedOld.originPublicKeyFingerprint,
    signedOld.contentDigest, signedOld.originSignature);
  assert.equal((await request('/api/v1/messages', a.token, 'POST', oldMessage)).data.duplicate, true);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM messages WHERE message_id=?').get(message.messageId).n, 1);
  const sync = await request('/api/v1/sync?cursor=0', d.token);
  assert.equal(sync.data.messages.length, 1);
  assert.equal(sync.data.messages[0].messageId, message.messageId);
  assert.equal(Number.isSafeInteger(sync.data.messages[0].serverReceivedAt), true);
  assert.equal((await request(`/api/v1/sync?cursor=${sync.data.nextCursor}`, d.token)).data.messages.length, 0);
  assert.equal((await request('/api/v1/sync?cursor=0', a.token)).data.deliveryReceipts.length, 0);
  const receipt = { originalMessageId: message.messageId, sourceId: a.id, destinationId: d.id,
    deliveredAt: now + 1, originalExpiresAt: message.expiresAt, createdByDeviceId: d.id };
  assert.equal((await request('/api/v1/receipts', d.token, 'POST', receipt)).status, 201);
  assert.equal((await request('/api/v1/receipts', d.token, 'POST', receipt)).data.duplicate, true);
  assert.equal((await request('/api/v1/sync?cursor=0', a.token)).data.deliveryReceipts.length, 1);
  assert.equal((await request('/api/v1/receipts/lookup', x.token, 'POST',
    { messageIds: [message.messageId] })).data.deliveryReceipts[0].originalMessageId, message.messageId);

  const imageId = randomUUID(), mediaId = randomUUID();
  const bytes = new TextEncoder().encode('image payload for transport test');
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  assert.equal((await request(`/api/v1/media/${mediaId}`, a.token, 'PUT', bytes,
    { 'Content-Type': 'image/jpeg', 'X-Message-Id': imageId, 'X-Sha256': '0'.repeat(64) })).data.error,
    'MEDIA_HASH_MISMATCH');
  const stored = await request(`/api/v1/media/${mediaId}`, a.token, 'PUT', bytes,
    { 'Content-Type': 'image/jpeg', 'X-Message-Id': imageId, 'X-Sha256': sha256 });
  assert.equal(stored.status, 201);
  const image = { ...message, messageId: imageId, contentType: 'IMAGE', payload: '', mediaId };
  assert.equal((await request('/api/v1/messages', a.token, 'POST', { ...image, messageId: randomUUID(), mediaId: randomUUID() })).data.error,
    'MEDIA_NOT_READY');
  assert.equal((await request('/api/v1/messages', a.token, 'POST', image)).status, 201);
  assert.equal((await fetch(`${base}/api/v1/media/${mediaId}`, { headers: { Authorization: `Bearer ${x.token}` } })).status, 403);
  const download = await fetch(`${base}/api/v1/media/${mediaId}`, { headers: { Authorization: `Bearer ${d.token}` } });
  assert.equal(download.status, 200);
  assert.equal(createHash('sha256').update(Buffer.from(await download.arrayBuffer())).digest('hex'), sha256);
  db.prepare('UPDATE messages SET expires_at=? WHERE message_id=?').run(Date.now() - 1, imageId);
  const expiredSync = await request('/api/v1/sync?cursor=0', d.token);
  assert.equal(expiredSync.data.messages.some(item => item.messageId === imageId), false);
  db.prepare('UPDATE messages SET expires_at=? WHERE message_id=?').run(Date.now() - 86_400_001, imageId);
  cleanup();
  assert.equal(db.prepare('SELECT 1 FROM messages WHERE message_id=?').get(imageId), undefined);
  assert.equal(db.prepare('SELECT 1 FROM media WHERE media_id=?').get(mediaId), undefined);
});

test('authenticated WebSocket announces durable mailbox events', async () => {
  const a = await register(), d = await register();
  const socket = new WebSocket(base.replace('http:', 'ws:') + '/api/v1/ws', {
    headers: { Authorization: `Bearer ${d.token}` },
  });
  const events = [];
  let received;
  const twoEvents = new Promise((resolve, reject) => {
    received = resolve;
    setTimeout(() => reject(new Error('WebSocket notification timed out')), 2000).unref();
  });
  socket.on('message', data => {
    events.push(JSON.parse(data.toString()));
    if (events.length >= 2) received();
  });
  await new Promise((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject); });
  const now = Date.now();
  const message = { messageId: randomUUID(), sourceId: a.id, destinationId: d.id,
    contentType: 'TEXT', payload: 'Push hint', createdAt: now, expiresAt: now + 60_000 };
  assert.equal((await request('/api/v1/messages', a.token, 'POST', message)).status, 201);
  await twoEvents;
  assert.ok(events.length >= 2);
  assert.ok(events.every(event => event.type === 'SYNC_AVAILABLE'));
  assert.equal((await request('/api/v1/sync?cursor=0', d.token)).data.messages.some(item => item.messageId === message.messageId), true);
  socket.close();
});

test('audio and video chunk sessions resume, verify, authorize range reads and clean stale partials', async () => {
  const a = await register(), d = await register(), stranger = await register();
  for (const [mediaType, mimeType] of [['AUDIO', 'audio/mp4'], ['VIDEO', 'video/mp4']]) {
    const mediaId = randomUUID(), messageId = randomUUID();
    const bytes = Buffer.alloc(128 * 1024 + 17, mediaType === 'AUDIO' ? 65 : 86);
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    const manifest = { mediaId, messageId, mediaType, mimeType, fileSize: bytes.length, sha256,
      durationMs: 3200, ...(mediaType === 'VIDEO' ? { width: 640, height: 480 } : {}) };
    assert.equal((await request('/api/v1/media/upload-session', stranger.token, 'POST',
      { ...manifest, fileSize: 51 * 1024 * 1024 })).data.error, 'INVALID_MEDIA');
    assert.equal((await request('/api/v1/media/upload-session', a.token, 'POST', manifest)).data.receivedBytes, 0);
    assert.equal((await request('/api/v1/messages', a.token, 'POST', { messageId, sourceId: a.id,
      destinationId: d.id, contentType: mediaType, payload: '', mediaId,
      createdAt: Date.now(), expiresAt: Date.now() + 86_400_000 })).data.error, 'MEDIA_NOT_READY');
    const first = await request(`/api/v1/media/${mediaId}/chunks?offset=0`, a.token, 'PUT',
      bytes.subarray(0, 128 * 1024), { 'Content-Type': mimeType });
    assert.equal(first.data.receivedBytes, 128 * 1024);
    assert.equal((await request(`/api/v1/media/${mediaId}/chunks?offset=${128 * 1024}`,
      stranger.token, 'PUT', bytes.subarray(128 * 1024), { 'Content-Type': mimeType })).status, 404);
    assert.equal((await request('/api/v1/media/upload-session', a.token, 'POST', manifest)).data.receivedBytes, 128 * 1024);
    assert.equal((await request('/api/v1/media/upload-session', stranger.token, 'POST', manifest)).data.error, 'MEDIA_ID_CONFLICT');
    assert.equal((await request(`/api/v1/media/${mediaId}/chunks?offset=0`, a.token, 'PUT',
      bytes.subarray(0, 128 * 1024), { 'Content-Type': mimeType })).data.receivedBytes, 128 * 1024);
    assert.equal((await request(`/api/v1/media/${mediaId}/chunks?offset=${128 * 1024}`, a.token, 'PUT',
      bytes.subarray(128 * 1024), { 'Content-Type': mimeType })).data.status, 'MEDIA_STORED');
    assert.equal((await request('/api/v1/media/upload-session', a.token, 'POST', manifest)).data.status, 'MEDIA_STORED');
    const now = Date.now();
    assert.equal((await request('/api/v1/messages', a.token, 'POST', { messageId, sourceId: a.id,
      destinationId: d.id, contentType: mediaType, payload: '', mediaId,
      createdAt: now, expiresAt: now + 86_400_000 })).status, 201);
    assert.equal((await fetch(`${base}/api/v1/media/${mediaId}`, { headers: {
      Authorization: `Bearer ${stranger.token}`, Range: 'bytes=0-16' } })).status, 403);
    const ranged = await fetch(`${base}/api/v1/media/${mediaId}`, { headers: {
      Authorization: `Bearer ${d.token}`, Range: 'bytes=0-16' } });
    assert.equal(ranged.status, 206);
    assert.deepEqual(Buffer.from(await ranged.arrayBuffer()), bytes.subarray(0, 17));
    const sync = await request('/api/v1/sync?cursor=0', d.token);
    assert.equal(sync.data.messages.find(m => m.messageId === messageId).media.mediaType, mediaType);
  }
  const orphan = { mediaId: randomUUID(), messageId: randomUUID(), mediaType: 'AUDIO',
    mimeType: 'audio/mpeg', fileSize: 3, sha256: createHash('sha256').update('abc').digest('hex') };
  assert.equal((await request('/api/v1/media/upload-session', a.token, 'POST', orphan)).status, 201);
  db.prepare('UPDATE media_uploads SET updated_at=? WHERE media_id=?').run(Date.now() - 25 * 3600_000, orphan.mediaId);
  cleanup();
  assert.equal(db.prepare('SELECT 1 FROM media_uploads WHERE media_id=?').get(orphan.mediaId), undefined);
  const bad = { mediaId: randomUUID(), messageId: randomUUID(), mediaType: 'AUDIO',
    mimeType: 'audio/mpeg', fileSize: 3, sha256: '0'.repeat(64) };
  assert.equal((await request('/api/v1/media/upload-session', a.token, 'POST', bad)).status, 201);
  assert.equal((await request(`/api/v1/media/${bad.mediaId}/chunks?offset=0`, a.token,
    'PUT', new TextEncoder().encode('abc'), { 'Content-Type': bad.mimeType })).data.error, 'MEDIA_HASH_MISMATCH');
  assert.equal(db.prepare('SELECT 1 FROM media WHERE media_id=?').get(bad.mediaId), undefined);
});

test('identity binding blocks replacement, tampering, source spoofing and forged receipts', async () => {
  const a = await register(), d = await register(), x = await register();
  const replacement = createIdentity();
  const challengeResponse = await request('/api/v1/devices/key-challenge', a.token, 'POST');
  const challenge = challengeResponse.data.challenge;
  const proofSignature = sign('sha256', canonical('DINOBLOG_KEY_BINDING', '1', a.id,
    challenge, replacement.fingerprint), replacement.privateKey).toString('base64url');
  const change = await request('/api/v1/devices/key-binding', a.token, 'POST', {
    publicKey: replacement.publicKey, publicKeyFingerprint: replacement.fingerprint,
    challenge, signatureVersion: 1, proofSignature });
  assert.equal(change.data.error, 'IDENTITY_KEY_MISMATCH');
  const key = await request(`/api/v1/devices/${a.id}/key`, d.token);
  assert.equal(key.data.publicKeyFingerprint, identities.get(a.id).fingerprint);

  const now = Date.now();
  const original = signMessage({ messageId: randomUUID(), sourceId: a.id, destinationId: d.id,
    contentType: 'TEXT', payload: 'authentic', createdAt: now, expiresAt: now + 60_000 }, identities.get(a.id));
  assert.equal((await request('/api/v1/messages', a.token, 'POST', { ...original, payload: 'tampered' })).data.error,
    'MESSAGE_AUTHENTICATION_FAILURE');
  assert.equal((await request('/api/v1/messages', x.token, 'POST', original)).data.error, 'SOURCE_MISMATCH');
  assert.equal((await request('/api/v1/messages', a.token, 'POST', original)).status, 201);
  const receipt = { originalMessageId: original.messageId, sourceId: a.id, destinationId: d.id,
    deliveredAt: now + 1, originalExpiresAt: original.expiresAt, createdByDeviceId: d.id };
  assert.equal((await request('/api/v1/receipts', d.token, 'POST', signReceipt(receipt, identities.get(x.id)))).data.error,
    'RECEIPT_AUTHENTICATION_FAILURE');
  assert.equal((await request('/api/v1/receipts', x.token, 'POST', signReceipt(receipt, identities.get(d.id)))).data.error,
    'INVALID_RECEIPT');
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM delivery_receipts WHERE original_message_id=?').get(original.messageId).n, 0);
});

test('new registration requires fresh key proof and existing token can bind a legacy record', async () => {
  const id = randomUUID();
  const challenge = await request('/api/v1/devices/registration-challenge', null, 'POST', { deviceId: id });
  assert.equal(challenge.status, 200);
  const unproved = await request('/api/v1/devices/register', null, 'POST',
    { deviceId: id, displayName: 'Unproved', challenge: challenge.data.challenge });
  assert.equal(unproved.data.error, 'INVALID_KEY_PROOF');
  assert.equal(db.prepare('SELECT 1 FROM devices WHERE device_id=?').get(id), undefined);
  const proposed = createIdentity(), attacker = createIdentity();
  const newChallenge = await request('/api/v1/devices/registration-challenge', null, 'POST', { deviceId: id });
  const wrongSignature = sign('sha256', canonical('DINOBLOG_DEVICE_REGISTRATION', '1', id,
    newChallenge.data.challenge, proposed.fingerprint), attacker.privateKey).toString('base64url');
  const forged = { deviceId: id, displayName: 'Forged', challenge: newChallenge.data.challenge,
    signatureVersion: 1, publicKey: proposed.publicKey,
    publicKeyFingerprint: proposed.fingerprint, proofSignature: wrongSignature };
  assert.equal((await request('/api/v1/devices/register', null, 'POST', forged)).data.error, 'INVALID_KEY_PROOF');
  const correctButReplayed = { ...forged, proofSignature: sign('sha256',
    canonical('DINOBLOG_DEVICE_REGISTRATION', '1', id, newChallenge.data.challenge, proposed.fingerprint),
    proposed.privateKey).toString('base64url') };
  assert.equal((await request('/api/v1/devices/register', null, 'POST', correctButReplayed)).data.error, 'INVALID_KEY_PROOF');
  const identity = createIdentity();
  const registered = await registerIdentity(base, id, identity);
  assert.equal(registered.status, 201);
  assert.equal(db.prepare('SELECT public_key_fingerprint FROM devices WHERE device_id=?').get(id)
    .public_key_fingerprint, identity.fingerprint);
  assert.equal((await request('/api/v1/devices/register', null, 'POST',
    { deviceId: id, displayName: 'Takeover' })).status, 401);

  const legacyId = randomUUID(), legacyToken = randomBytes(32).toString('base64url');
  db.prepare(`INSERT INTO devices(device_id,display_name,token_hash,registered_at,last_seen_at)
    VALUES (?,?,?,?,?)`).run(legacyId, 'Existing', digest(legacyToken), Date.now(), Date.now());
  assert.equal((await request('/api/v1/devices/register', legacyToken, 'POST',
    { deviceId: legacyId, displayName: 'Existing' })).status, 200);
  const legacyIdentity = createIdentity();
  await bindIdentity(base, legacyId, legacyToken, legacyIdentity);
  assert.equal(db.prepare('SELECT public_key_fingerprint FROM devices WHERE device_id=?').get(legacyId)
    .public_key_fingerprint, legacyIdentity.fingerprint);
});

test('canonical signatures use the Android version one fixtures', () => {
  const a = '11111111-1111-4111-8111-111111111111';
  const d = '22222222-2222-4222-8222-222222222222';
  const id = '33333333-3333-4333-8333-333333333333';
  const mediaId = '44444444-4444-4444-8444-444444444444';
  const fp = 'a'.repeat(64);
  assert.equal(digest(canonical('DINOBLOG_HELLO_PROOF', '1', a, d, 'AAABBB', 'CCCDDD', fp)),
    '1e59cda72943940297e2e67f939540cf5f7cade076c7442156b59b39d3a25de4');
  assert.equal(digest(canonical('DINOBLOG_DEVICE_REGISTRATION', '1', a, 'AAABBB', fp)),
    'dd3077f09c9c220c2795eac442bb566ad690c4f52c44e89fc607acabc3af4ae8');
  assert.equal(digest(canonical('DINOBLOG_MESSAGE', '1', id, a, d, 'TEXT', '1000', '61000', '8', digest('Hi'), '', '', '0', '')),
    '4c499f3bf69e8a20de475c93d40b37e1d2b8b879537d05cb758bd32f201bb16a');
  assert.equal(digest(canonical('DINOBLOG_MESSAGE', '1', id, a, d, 'IMAGE', '1000', '61000', '8', fp,
    mediaId, 'image/jpeg', '123', fp)), '86a01db9a9c6666e2e35232a70c378a1c3f4e32917e171f68899f012e0316e2b');
  assert.equal(digest(canonical('DINOBLOG_DELIVERY_RECEIPT', '1', id, a, d, d, '2000', '61000')),
    '74344c0e23cacbc00bc2bffcb232a3fa897deefaf49ac36a37f157b55eb99b4e');
  assert.equal(digest(canonical('DINOBLOG_MESSAGE', '2', mediaId, a, id, 'TEXT', '1000', '61000',
    '8', 'DIRECT_ONLY', digest('Hi'), '', '', '0', '')),
    '35f7041b9589a15ea46886c6e7e4611a686d5dc434757430926b258fd9425579');
});

test('signed sender relay policy survives mailbox sync and cannot be expanded or changed on retry', async () => {
  const a = await register(), d = await register(), b = await register();
  const at = Date.now();
  const unsigned = { messageId: randomUUID(), sourceId: a.id, destinationId: d.id,
    contentType: 'TEXT', payload: 'Direct policy', createdAt: at, expiresAt: at + 60_000,
    relayPolicy: 'DIRECT_ONLY', signatureVersion: 2 };
  const signed = signMessage(unsigned, identities.get(a.id));
  assert.equal((await request('/api/v1/messages', a.token, 'POST', signed)).status, 201);
  assert.equal(db.prepare('SELECT relay_policy FROM messages WHERE message_id=?').get(signed.messageId).relay_policy,
    'DIRECT_ONLY');
  assert.equal((await request('/api/v1/messages', a.token, 'POST', signed)).data.duplicate, true);
  assert.equal((await request('/api/v1/messages', a.token, 'POST', { ...signed,
    relayPolicy: 'ALLOW_RELAY' })).data.error, 'MESSAGE_AUTHENTICATION_FAILURE');
  assert.equal((await request('/api/v1/messages', a.token, 'POST', signMessage({ ...unsigned,
    relayPolicy: 'ALLOW_RELAY' }, identities.get(a.id)))).data.error, 'MESSAGE_ID_CONFLICT');
  assert.equal((await request('/api/v1/messages', b.token, 'POST', signed)).data.error, 'SOURCE_MISMATCH');
  const sync = await request('/api/v1/sync?cursor=0', d.token);
  assert.equal(sync.data.messages.find(m => m.messageId === signed.messageId)?.relayPolicy, 'DIRECT_ONLY');
  assert.equal(sync.data.messages.find(m => m.messageId === signed.messageId)?.signatureVersion, 2);
});

test.after(async () => {
  await new Promise(resolve => server.close(resolve));
  db.close();
  rmSync(folder, { recursive: true, force: true });
});
