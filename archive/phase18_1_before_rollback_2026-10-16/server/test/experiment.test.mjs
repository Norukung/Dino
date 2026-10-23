import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { createIdentity, registerIdentity, signMessage } from './security-fixture.mjs';

const folder = mkdtempSync(join(tmpdir(), 'dinoblog-experiment-'));
process.env.NODE_ENV = 'test';
process.env.DATABASE_PATH = join(folder, 'mailbox.sqlite');
process.env.MEDIA_STORAGE_PATH = join(folder, 'media');
const { server, db, cleanup } = await import('../src/index.ts');
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;

async function register() {
  const id = randomUUID();
  const identity = createIdentity();
  const response = await registerIdentity(base, id, identity);
  assert.equal(response.status, 201);
  return { id, token: response.data.token, identity };
}

test('experiment IDs are authenticated metadata and metrics are owner-only', async () => {
  const a = await register(), d = await register();
  const experimentId = randomUUID();
  const messageId = randomUUID(), now = Date.now();
  const message = signMessage({ messageId, sourceId: a.id, destinationId: d.id,
    contentType: 'TEXT', payload: 'Private content', createdAt: now, expiresAt: now + 86_400_000 }, a.identity);
  const post = await fetch(`${base}/api/v1/messages`, { method: 'POST',
    headers: { Authorization: `Bearer ${a.token}`, 'Content-Type': 'application/json',
      'X-DinoBlog-Experiment-ID': experimentId }, body: JSON.stringify(message) });
  assert.equal(post.status, 201);
  const badSource = await fetch(`${base}/api/v1/messages`, { method: 'POST',
    headers: { Authorization: `Bearer ${d.token}`, 'Content-Type': 'application/json',
      'X-DinoBlog-Experiment-ID': experimentId }, body: JSON.stringify({ ...message, messageId: randomUUID() }) });
  assert.equal(badSource.status, 403);
  const own = await fetch(`${base}/api/v1/experiments/metrics?experimentId=${experimentId}`,
    { headers: { Authorization: `Bearer ${a.token}` } });
  assert.equal(own.status, 200);
  const data = await own.json();
  assert.equal(data.metrics.find(metric => metric.eventType === 'MESSAGE_ACCEPTED').count, 1);
  assert.equal(JSON.stringify(data).includes('Private content'), false);
  const other = await fetch(`${base}/api/v1/experiments/metrics?experimentId=${experimentId}`,
    { headers: { Authorization: `Bearer ${d.token}` } });
  assert.deepEqual((await other.json()).metrics, []);
  const unauthenticated = await fetch(`${base}/api/v1/experiments/metrics?experimentId=${experimentId}`);
  assert.equal(unauthenticated.status, 401);
  db.prepare('UPDATE experiment_server_events SET event_time=? WHERE experiment_id=?')
    .run(Date.now() - 31 * 86_400_000, experimentId);
  cleanup();
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM experiment_server_events WHERE experiment_id=?')
    .get(experimentId).n, 0);
});

test.after(async () => {
  await new Promise(resolve => server.close(resolve));
  db.close();
  rmSync(folder, { recursive: true, force: true });
});
