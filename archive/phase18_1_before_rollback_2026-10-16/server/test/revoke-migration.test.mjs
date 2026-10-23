import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';

const folder = mkdtempSync(join(tmpdir(), 'dinoblog-revoke-migration-'));
const path = join(folder, 'old.sqlite');
const id = randomUUID();
const oldMessageId = randomUUID();
const now = Date.now();
const old = new DatabaseSync(path);
old.exec(`CREATE TABLE devices (device_id TEXT PRIMARY KEY, display_name TEXT NOT NULL,
 token_hash TEXT NOT NULL, registered_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL);
 CREATE TABLE messages (message_id TEXT PRIMARY KEY, source_id TEXT NOT NULL,
 destination_id TEXT NOT NULL, content_type TEXT NOT NULL, payload TEXT NOT NULL, media_id TEXT,
 created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, server_received_at INTEGER NOT NULL);
 CREATE TABLE delivery_receipts (original_message_id TEXT PRIMARY KEY, source_id TEXT NOT NULL,
 destination_id TEXT NOT NULL, delivered_at INTEGER NOT NULL, original_expires_at INTEGER NOT NULL,
 created_by_device_id TEXT NOT NULL, server_received_at INTEGER NOT NULL);`);
old.prepare('INSERT INTO devices VALUES (?,?,?,?,?)').run(id, 'Old profile', 'token', 1000, 1000);
old.prepare('INSERT INTO messages VALUES (?,?,?,?,?,?,?,?,?)').run(oldMessageId, id, randomUUID(),
  'TEXT', 'Legacy message', null, now, now + 60000, now);
old.close();
process.env.DATABASE_PATH = path;
process.env.MEDIA_STORAGE_PATH = join(folder, 'media');
const { server, db } = await import('../src/index.ts');

test('additive server migration retains old devices and adds recall tables', () => {
  assert.equal(db.prepare('SELECT display_name FROM devices WHERE device_id=?').get(id).display_name, 'Old profile');
  assert.equal(db.prepare('SELECT COUNT(*) AS count FROM message_revocations').get().count, 0);
  assert.equal(db.prepare('SELECT COUNT(*) AS count FROM revoke_results').get().count, 0);
  assert.equal(db.prepare('PRAGMA user_version').get().user_version, 19);
  assert.equal(db.prepare("PRAGMA table_info(messages)").all().find(column => column.name === 'relay_policy').dflt_value,
    "'ALLOW_RELAY'");
  assert.equal(db.prepare('SELECT relay_policy FROM messages WHERE message_id=?').get(oldMessageId).relay_policy,
    'ALLOW_RELAY');
});

test.after(() => { server.close(); db.close(); rmSync(folder, { recursive: true, force: true }); });
