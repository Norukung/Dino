import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { createIdentity, registerIdentity, signMessage, signReceipt,
  signRevoke, signRevokeResult } from './security-fixture.mjs';

const folder = mkdtempSync(join(tmpdir(), 'dinoblog-revoke-'));
process.env.DATABASE_PATH = join(folder, 'mailbox.sqlite');
process.env.MEDIA_STORAGE_PATH = join(folder, 'media');
const { server, db, cleanup } = await import('../src/index.ts');
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;

async function call(path, token, value, method = 'POST') {
  const response = await fetch(base + path, { method,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: value == null ? undefined : JSON.stringify(value) });
  return { status: response.status, data: await response.json() };
}
async function device() {
  const id = randomUUID(); const key = createIdentity();
  const response = await registerIdentity(base, id, key);
  assert.equal(response.status, 201);
  return { id, key, token: response.data.token };
}

test('signed revoke suppresses mailbox, is idempotent, and destination result is authoritative', async () => {
  const a = await device(), d = await device(), x = await device();
  const at = Date.now();
  const m = signMessage({ messageId: randomUUID(), sourceId: a.id, destinationId: d.id,
    contentType: 'TEXT', payload: 'Stop this', createdAt: at, expiresAt: at + 86_400_000 }, a.key);
  assert.equal((await call('/api/v1/messages', a.token, m)).status, 201);
  const r = signRevoke({ revokeId: randomUUID(), originalMessageId: m.messageId,
    originalSourceId: a.id, originalDestinationId: d.id,
    originalExpiresAt: m.expiresAt, createdAt: at + 1,
    retentionUntil: m.expiresAt + 86_400_000 }, a.key);
  assert.equal((await call(`/api/v1/messages/${m.messageId}/revoke`, x.token, r)).status, 403);
  assert.equal((await call(`/api/v1/messages/${m.messageId}/revoke`, a.token,
    { ...r, sourceSignature: signRevoke(r, x.key).sourceSignature })).status, 403);
  assert.equal((await call(`/api/v1/messages/${m.messageId}/revoke`, a.token,
    { ...r, originalDestinationId: x.id })).status, 403);
  const tamperedId = randomUUID();
  assert.equal((await call(`/api/v1/messages/${tamperedId}/revoke`, a.token,
    { ...r, originalMessageId: tamperedId })).status, 403);
  assert.equal((await call(`/api/v1/messages/${m.messageId}/revoke`, a.token, r)).status, 201);
  assert.equal((await call(`/api/v1/messages/${m.messageId}/revoke`, a.token, r)).data.duplicate, true);
  assert.equal(db.prepare('SELECT COUNT(*) AS count FROM message_revocations').get().count, 1);
  const sync = await call('/api/v1/sync?cursor=0', d.token, null, 'GET');
  assert.equal(sync.data.messages.length, 0);
  assert.equal(sync.data.revokes[0].revokeId, r.revokeId);
  const result = signRevokeResult({ resultId: randomUUID(), revokeId: r.revokeId,
    originalMessageId: m.messageId, originalSourceId: a.id, originalDestinationId: d.id,
    result: 'CANCELLED_BEFORE_DELIVERY', processedAt: at + 2 }, d.key);
  assert.equal((await call('/api/v1/revoke-results', x.token, result)).status, 403);
  assert.equal((await call('/api/v1/revoke-results', d.token,
    { ...result, result: 'ALREADY_DELIVERED' })).status, 403);
  assert.equal((await call('/api/v1/revoke-results', d.token, result)).status, 201);
  assert.equal((await call('/api/v1/revoke-results', d.token, result)).data.duplicate, true);
  const sourceSync = await call('/api/v1/sync?cursor=0', a.token, null, 'GET');
  assert.equal(sourceSync.data.revokeResults[0].result, 'CANCELLED_BEFORE_DELIVERY');
  const receipt = signReceipt({ originalMessageId: m.messageId, sourceId: a.id,
    destinationId: d.id, deliveredAt: at + 3, originalExpiresAt: m.expiresAt,
    createdByDeviceId: d.id }, d.key);
  assert.equal((await call('/api/v1/receipts', d.token, receipt)).data.error, 'MESSAGE_REVOKED');

  const next = signMessage({ ...m, messageId: randomUUID(), payload: 'Never upload' }, a.key);
  const before = signRevoke({ ...r, revokeId: randomUUID(), originalMessageId: next.messageId }, a.key);
  assert.equal((await call(`/api/v1/messages/${next.messageId}/revoke`, a.token, before)).status, 201);
  assert.equal((await call('/api/v1/messages', a.token, next)).data.error, 'MESSAGE_REVOKED');
  assert.equal((await call('/api/v1/revokes/lookup', x.token,
    { messageIds: [next.messageId] })).data.revokes[0].revokeId, before.revokeId);
  db.prepare('UPDATE message_revocations SET retention_until=? WHERE original_message_id=?')
    .run(Date.now() - 1, next.messageId);
  cleanup();
  assert.equal(db.prepare('SELECT 1 FROM message_revocations WHERE original_message_id=?')
    .get(next.messageId), undefined);
});

test('receipt truth survives a later revoke', async () => {
  const a = await device(), d = await device();
  const at = Date.now();
  const m = signMessage({ messageId: randomUUID(), sourceId: a.id, destinationId: d.id,
    contentType: 'TEXT', payload: 'Already there', createdAt: at, expiresAt: at + 86_400_000 }, a.key);
  assert.equal((await call('/api/v1/messages', a.token, m)).status, 201);
  const receipt = signReceipt({ originalMessageId: m.messageId, sourceId: a.id,
    destinationId: d.id, deliveredAt: at + 1, originalExpiresAt: m.expiresAt,
    createdByDeviceId: d.id }, d.key);
  assert.equal((await call('/api/v1/receipts', d.token, receipt)).status, 201);
  const revoke = signRevoke({ revokeId: randomUUID(), originalMessageId: m.messageId,
    originalSourceId: a.id, originalDestinationId: d.id,
    originalExpiresAt: m.expiresAt, createdAt: at + 2,
    retentionUntil: m.expiresAt + 86_400_000 }, a.key);
  assert.equal((await call(`/api/v1/messages/${m.messageId}/revoke`, a.token, revoke)).data.alreadyDelivered, true);
  const falseResult = signRevokeResult({ resultId: randomUUID(), revokeId: revoke.revokeId,
    originalMessageId: m.messageId, originalSourceId: a.id, originalDestinationId: d.id,
    result: 'CANCELLED_BEFORE_DELIVERY', processedAt: at + 3 }, d.key);
  assert.equal((await call('/api/v1/revoke-results', d.token, falseResult)).data.error, 'ALREADY_DELIVERED');
  const trueResult = signRevokeResult({ ...falseResult, resultId: randomUUID(),
    result: 'ALREADY_DELIVERED' }, d.key);
  assert.equal((await call('/api/v1/revoke-results', d.token, trueResult)).status, 201);
});

test.after(async () => {
  await new Promise(resolve => server.close(resolve));
  db.close(); rmSync(folder, { recursive: true, force: true });
});
