import { createHash, generateKeyPairSync, sign } from 'node:crypto';

export const digest = value => createHash('sha256').update(value).digest('hex');
export function canonical(...fields) {
  return Buffer.concat(fields.map(field => {
    const bytes = Buffer.from(String(field), 'utf8');
    const prefix = Buffer.alloc(4); prefix.writeUInt32BE(bytes.length);
    return Buffer.concat([prefix, bytes]);
  }));
}
export function createIdentity() {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const encoded = publicKey.export({ format: 'der', type: 'spki' });
  return { privateKey, publicKey: encoded.toString('base64url'), fingerprint: digest(encoded) };
}
function signature(identity, data) { return sign('sha256', data, identity.privateKey).toString('base64url'); }
export async function registerIdentity(base, id, identity, displayName = 'Tester') {
  const headers = { 'Content-Type': 'application/json' };
  const issued = await fetch(base + '/api/v1/devices/registration-challenge', { method: 'POST', headers,
    body: JSON.stringify({ deviceId: id }) });
  if (!issued.ok) throw Error(`Registration challenge failed: ${JSON.stringify(await issued.json())}`);
  const { challenge } = await issued.json();
  const proofSignature = signature(identity, canonical('DINOBLOG_DEVICE_REGISTRATION', '1',
    id, challenge, identity.fingerprint));
  const response = await fetch(base + '/api/v1/devices/register', { method: 'POST', headers,
    body: JSON.stringify({ deviceId: id, displayName, challenge, signatureVersion: 1,
      publicKey: identity.publicKey, publicKeyFingerprint: identity.fingerprint, proofSignature }) });
  return { status: response.status, data: await response.json() };
}
export async function bindIdentity(base, id, token, identity) {
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const challengeResponse = await fetch(base + '/api/v1/devices/key-challenge', { method: 'POST', headers });
  const { challenge } = await challengeResponse.json();
  const proofSignature = signature(identity, canonical('DINOBLOG_KEY_BINDING', '1', id, challenge, identity.fingerprint));
  const response = await fetch(base + '/api/v1/devices/key-binding', { method: 'POST', headers,
    body: JSON.stringify({ publicKey: identity.publicKey, publicKeyFingerprint: identity.fingerprint,
      challenge, signatureVersion: 1, proofSignature }) });
  if (!response.ok) throw Error(`Key binding failed: ${JSON.stringify(await response.json())}`);
}
export function signMessage(message, identity, media = null) {
  const isText = message.contentType === 'TEXT';
  const contentDigest = isText ? digest(message.payload) : media.sha256;
  const version = message.signatureVersion || 1;
  const signed = { ...message, hopLimit: message.hopLimit || 8, signatureVersion: version,
    ...(version === 2 ? { relayPolicy: message.relayPolicy || 'ALLOW_RELAY' } : {}),
    originPublicKey: identity.publicKey, originPublicKeyFingerprint: identity.fingerprint,
    contentDigest, signedMediaMimeType: isText ? null : media.mimeType,
    signedMediaFileSize: isText ? null : media.fileSize,
    signedMediaSha256: isText ? null : media.sha256 };
  const bytes = canonical('DINOBLOG_MESSAGE', String(version), signed.messageId, signed.sourceId,
    signed.destinationId, signed.contentType, signed.createdAt, signed.expiresAt,
    signed.hopLimit, ...(version === 2 ? [signed.relayPolicy] : []), contentDigest, isText ? '' : signed.mediaId,
    isText ? '' : media.mimeType, isText ? '0' : media.fileSize,
    isText ? '' : media.sha256);
  return { ...signed, originSignature: signature(identity, bytes) };
}
export function signReceipt(receipt, identity) {
  const signed = { ...receipt, signatureVersion: 1, destinationPublicKey: identity.publicKey,
    destinationPublicKeyFingerprint: identity.fingerprint };
  const bytes = canonical('DINOBLOG_DELIVERY_RECEIPT', '1', receipt.originalMessageId,
    receipt.sourceId, receipt.destinationId, receipt.createdByDeviceId,
    receipt.deliveredAt, receipt.originalExpiresAt);
  return { ...signed, destinationSignature: signature(identity, bytes) };
}

export function signRevoke(revoke, identity) {
  const signed = { ...revoke, signatureVersion: 1, sourcePublicKey: identity.publicKey,
    sourcePublicKeyFingerprint: identity.fingerprint };
  return { ...signed, sourceSignature: signature(identity, canonical('DINOBLOG_REVOKE', '1',
    revoke.revokeId, revoke.originalMessageId, revoke.originalSourceId,
    revoke.originalDestinationId, revoke.originalExpiresAt, revoke.createdAt,
    revoke.retentionUntil)) };
}

export function signRevokeResult(result, identity) {
  const signed = { ...result, signatureVersion: 1, destinationPublicKey: identity.publicKey,
    destinationPublicKeyFingerprint: identity.fingerprint };
  return { ...signed, destinationSignature: signature(identity, canonical('DINOBLOG_REVOKE_RESULT', '1',
    result.resultId, result.revokeId, result.originalMessageId, result.originalSourceId,
    result.originalDestinationId, result.result, result.processedAt)) };
}
