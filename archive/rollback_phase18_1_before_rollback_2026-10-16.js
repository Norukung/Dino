const fs = require('fs');

function edit(path, mutate) {
  const original = fs.readFileSync(path, 'utf8');
  let value = original.replace(/\r\n/g, '\n');
  const api = {
    replace(before, after = '') {
      const count = value.split(before).length - 1;
      if (count !== 1) throw Error(`${path}: expected one occurrence, found ${count}: ${before.slice(0, 80)}`);
      value = value.replace(before, after);
    },
    all(before, after, expected) {
      const count = value.split(before).length - 1;
      if (count !== expected) throw Error(`${path}: expected ${expected} occurrences, found ${count}: ${before.slice(0, 80)}`);
      value = value.split(before).join(after);
    },
    regex(pattern, after = '') {
      const matches = [...value.matchAll(new RegExp(pattern.source, pattern.flags.includes('g') ? pattern.flags : pattern.flags + 'g'))];
      if (matches.length !== 1) throw Error(`${path}: expected one regex occurrence, found ${matches.length}: ${pattern}`);
      value = value.replace(pattern, after);
    },
  };
  mutate(api);
  fs.writeFileSync(path, original.includes('\r\n') ? value.replace(/\n/g, '\r\n') : value);
}

const app = 'app/src/main/java/com/group16/dinoblog/';
const test = 'app/src/';
edit('app/build.gradle.kts', x => {
  x.replace('versionCode = 23', 'versionCode = 22');
  x.replace('versionName = "4.7-relay-permission"', 'versionName = "4.6-recall"');
});
edit(app + 'model/Message.kt', x => {
  x.replace('@Serializable\nenum class RelayPolicy { ALLOW_RELAY, DIRECT_ONLY }\n\n');
  x.replace('    /** Immutable source consent; old rows and old signed messages allow the historic relay behavior. */\n    @ColumnInfo(defaultValue = "\'ALLOW_RELAY\'") val relayPolicy: RelayPolicy = RelayPolicy.ALLOW_RELAY,\n');
});
edit(app + 'database/DinoblogDatabase.kt', x => {
  x.replace('version = 14', 'version = 13');
  x.regex(/        val MIGRATION_13_14 = object : Migration\(13, 14\) \{\n[\s\S]*?        \}\n(?=        val MIGRATION_12_13)/);
});
edit(app + 'DinoblogApplication.kt', x => x.replace(', DinoblogDatabase.MIGRATION_13_14'));
edit(app + 'database/Converters.kt', x => {
  x.replace('import com.group16.dinoblog.model.RelayPolicy\n');
  x.replace('    @TypeConverter fun fromRelayPolicy(value: RelayPolicy): String = value.name\n    @TypeConverter fun toRelayPolicy(value: String): RelayPolicy = RelayPolicy.valueOf(value)\n');
});
edit(app + 'database/MessageDao.kt', x => x.replace("        AND relayPolicy = 'ALLOW_RELAY' AND status", '        AND status'));
edit(app + 'security/CanonicalSignatures.kt', x => {
  x.replace('import com.group16.dinoblog.model.RelayPolicy\n');
  x.replace('const val MESSAGE_VERSION = 2', 'const val MESSAGE_VERSION = 1');
  x.replace('        require(version == VERSION || version == MESSAGE_VERSION)\n        require(version != VERSION || message.relayPolicy == RelayPolicy.ALLOW_RELAY)\n', '        require(version == VERSION)\n');
  x.replace('            *if (version == MESSAGE_VERSION) arrayOf(message.relayPolicy.name) else emptyArray(), digest,\n', '            digest,\n');
});
edit(app + 'security/CryptoIdentityManager.kt', x => {
  x.replace('        require(message.signatureVersion in setOf(1, CanonicalSignatures.MESSAGE_VERSION)) { "LEGACY_UNSIGNED_MESSAGE" }\n        require(message.signatureVersion != 1 || message.relayPolicy == com.group16.dinoblog.model.RelayPolicy.ALLOW_RELAY) {\n            "Invalid legacy relay policy"\n        }\n', '        require(message.signatureVersion == 1) { "LEGACY_UNSIGNED_MESSAGE" }\n');
});
edit(app + 'network/WireProtocol.kt', x => {
  x.replace('import com.group16.dinoblog.model.RelayPolicy\n');
  x.replace('RELAY_DISABLED, RELAY_POLICY_DIRECT_ONLY,', 'RELAY_DISABLED,');
  x.replace('                require(m.signatureVersion != 1 || m.relayPolicy == RelayPolicy.ALLOW_RELAY) { "Invalid legacy relay policy" }\n');
});
edit(app + 'repository/ProfileRepository.kt', x => {
  x.replace('import com.group16.dinoblog.model.RelayPolicy\n');
  x.replace('    val showNearbyDisplayName: Boolean = true,\n    val defaultRelayPolicy: RelayPolicy = RelayPolicy.ALLOW_RELAY)', '    val showNearbyDisplayName: Boolean = true)');
  x.replace('        preferences.getBoolean("show_nearby_display_name", true),\n        runCatching { RelayPolicy.valueOf(preferences.getString("default_relay_policy", "ALLOW_RELAY") ?: "ALLOW_RELAY") }\n            .getOrDefault(RelayPolicy.ALLOW_RELAY),\n', '        preferences.getBoolean("show_nearby_display_name", true),\n');
  x.regex(/    fun setDefaultRelayPolicy\(policy: RelayPolicy\): Boolean \{\n[\s\S]*?    \}\n\n/);
});
edit(app + 'repository/MediaStore.kt', x => {
  x.replace('import com.group16.dinoblog.model.RelayPolicy\n');
  x.replace('ttl: Long, hopLimit: Int, copies: Int, relayPolicy: RelayPolicy = RelayPolicy.ALLOW_RELAY): Message', 'ttl: Long, hopLimit: Int, copies: Int): Message');
});
edit(app + 'repository/MediaRepository.kt', x => {
  x.replace('import com.group16.dinoblog.model.RelayPolicy\n');
  x.replace('import com.group16.dinoblog.model.RelayPolicyRules\n');
  x.replace('at: Long, ttl: Long, hopLimit: Int, copies: Int, relayPolicy: RelayPolicy): Message', 'at: Long, ttl: Long, hopLimit: Int, copies: Int): Message');
  x.replace('            contentType = prepared.manifest.mediaType, mediaId = prepared.manifest.mediaId,\n            relayPolicy = relayPolicy)', '            contentType = prepared.manifest.mediaType, mediaId = prepared.manifest.mediaId)');
  x.replace('        if (!RelayPolicyRules.mayAcceptFromPeer(message, authenticatedPeerId.orEmpty(), localId))\n            return@withLock MediaOffer.Reject(MessageRejectReason.RELAY_POLICY_DIRECT_ONLY)\n');
  x.replace('        if (!RelayPolicyRules.mayAcceptFromPeer(offeredMessage, authenticatedPeerId.orEmpty(), localId))\n            return@withLock MediaChunkResult.Reject(MessageRejectReason.RELAY_POLICY_DIRECT_ONLY)\n');
});
edit(app + 'dtn/DeliveryEngine.kt', x => {
  x.replace('import com.group16.dinoblog.model.RelayPolicy\n');
  x.replace('import com.group16.dinoblog.model.RelayPolicyRules\n');
  x.replace('        carrierId: String? = null, hopLimit: Int = DtnConfig.DEFAULT_HOP_LIMIT,\n        relayPolicy: RelayPolicy = RelayPolicy.ALLOW_RELAY): Message {', '        carrierId: String? = null, hopLimit: Int = DtnConfig.DEFAULT_HOP_LIMIT): Message {');
  x.replace('    suspend fun queueImage(receiverId: String, prepared: PreparedImage,\n        relayPolicy: RelayPolicy = RelayPolicy.ALLOW_RELAY): Message {', '    suspend fun queueImage(receiverId: String, prepared: PreparedImage): Message {');
  x.replace('            copiesRemaining = DtnConfig.INITIAL_SPRAY_COPIES, relayPolicy = relayPolicy)', '            copiesRemaining = DtnConfig.INITIAL_SPRAY_COPIES)');
  x.all('        metrics?.record("MESSAGE_RELAY_POLICY", message, reason = message.relayPolicy.name)\n', '', 2);
  x.replace('            DtnConfig.INITIAL_SPRAY_COPIES, relayPolicy)', '            DtnConfig.INITIAL_SPRAY_COPIES)');
  x.replace('    suspend fun queueMedia(receiverId: String, prepared: PreparedImage,\n        relayPolicy: RelayPolicy = RelayPolicy.ALLOW_RELAY): Message = queueImage(receiverId, prepared, relayPolicy)', '    suspend fun queueMedia(receiverId: String, prepared: PreparedImage): Message = queueImage(receiverId, prepared)');
  x.replace('        if (!RelayPolicyRules.mayOfferToPeer(message, localId, peer)) {\n            logger.log("DTN", "DTN RELAY SKIP reason=SKIP_RELAY_POLICY", message.messageId)\n            return false\n        }\n');
  x.replace('        if (!RelayPolicyRules.mayAcceptFromPeer(message, peerId, localId)) {\n                    sendMediaReject(peerId, sessionId, message.messageId, MessageRejectReason.RELAY_POLICY_DIRECT_ONLY)\n                    metrics?.record("TRANSFER_REJECTED", message, peerId, transport = "DTN",\n                        reason = "RELAY_POLICY_DIRECT_ONLY")\n                    return\n                }\n');
  x.replace('                if (!RelayPolicyRules.mayAcceptFromPeer(message, peerId, localId)) {\n                    metrics?.record("TRANSFER_REJECTED", message, peerId, transport = "DTN",\n                        reason = "RELAY_POLICY_DIRECT_ONLY")\n                    sendMediaReject(peerId, sessionId, message.messageId, MessageRejectReason.RELAY_POLICY_DIRECT_ONLY)\n                    return\n                }\n');
  x.replace('            if (!RelayPolicyRules.mayOfferToPeer(message, localId, peerId)) return "SKIP_RELAY_POLICY"\n');
  x.replace('selected.signatureVersion !in setOf(1, 2)', 'selected.signatureVersion != 1');
});
edit(app + 'online/TransportCoordinator.kt', x => {
  x.replace('import com.group16.dinoblog.model.RelayPolicy\n');
  x.replace('                    put("relayPolicy", message.relayPolicy.name)\n');
  x.replace('            signedMediaSha256 = row["signedMediaSha256"]?.jsonPrimitive?.content)\n            .copy(relayPolicy = row["relayPolicy"]?.jsonPrimitive?.content?.let(RelayPolicy::valueOf)\n                ?: RelayPolicy.ALLOW_RELAY)', '            signedMediaSha256 = row["signedMediaSha256"]?.jsonPrimitive?.content)');
  x.replace('message.signatureVersion !in setOf(1, 2)', 'message.signatureVersion != 1');
  x.replace('row["signatureVersion"]?.jsonPrimitive?.content !in setOf("1", "2")', 'row["signatureVersion"]?.jsonPrimitive?.content != "1"');
});
edit(app + 'viewmodel/AppViewModel.kt', x => {
  x.replace('import com.group16.dinoblog.model.RelayPolicy\n');
  x.replace('    private val mutableComposerRelayPolicy = MutableStateFlow(app.profile.profile.value.defaultRelayPolicy)\n    val composerRelayPolicy = mutableComposerRelayPolicy.asStateFlow()\n    private var composerPolicyOverridden = false\n');
  x.regex(/    fun setDefaultRelayPolicy\(policy: RelayPolicy\) \{\n[\s\S]*?    fun startNearbyForeground\(\) \{/ , '    fun startNearbyForeground() {');
  x.all('        val relayPolicy = mutableComposerRelayPolicy.value\n', '', 2);
  x.replace('app.delivery.queueMedia(receiverId, selected, relayPolicy)', 'app.delivery.queueMedia(receiverId, selected)');
  x.all('                resetComposerRelayPolicy()\n', '', 2);
  x.replace('hopLimit = DtnConfig.DEFAULT_HOP_LIMIT, relayPolicy = relayPolicy)', 'hopLimit = DtnConfig.DEFAULT_HOP_LIMIT)');
});
edit(app + 'ui/ChatScreen.kt', x => {
  x.replace('import com.group16.dinoblog.model.RelayPolicy\n');
  x.replace('    relayPolicy: RelayPolicy, onRelayPolicy: (RelayPolicy) -> Unit,\n');
  x.replace('    var deliveryMenu by remember { mutableStateOf(false) }\n');
  x.replace('                            else if (outgoing && message.relayPolicy == RelayPolicy.DIRECT_ONLY && !serverOnline &&\n                                socket.peerId != recipient && message.status == MessageStatus.PENDING) "Waiting for recipient"\n');
  x.replace('                                if (outgoing) DropdownMenuItem(\n                                    text = { Text("Delivery: ${if (message.relayPolicy == RelayPolicy.DIRECT_ONLY) "Direct only" else "Nearby relay allowed"}") },\n                                    onClick = {}, enabled = false)\n');
  x.regex(/        Row\(Modifier\.fillMaxWidth\(\)\.padding\(horizontal = 16\.dp\)\) \{\n            TextButton\(onClick = \{ deliveryMenu = true \}[\s\S]*?        \}\n(?=        Row\(Modifier\.fillMaxWidth\(\)\.padding\(16\.dp\), verticalAlignment = Alignment\.Bottom)/);
});
edit(app + 'ui/ProfileScreens.kt', x => {
  x.replace('import com.group16.dinoblog.model.RelayPolicy\n');
  x.replace('    onDefaultRelayPolicy: (RelayPolicy) -> Unit, onNearbyDiscoverable: (Boolean) -> Unit,', '    onNearbyDiscoverable: (Boolean) -> Unit,');
  x.regex(/        InfoCard\("Message Delivery"\) \{\n[\s\S]*?        \}\n(?=        InfoCard\("Nearby Devices"\))/);
  // RadioButton is used by nearby connection method below.
});
edit(app + 'ui/DinoblogApp.kt', x => {
  x.replace('    val composerRelayPolicy by viewModel.composerRelayPolicy.collectAsStateWithLifecycle()\n');
  x.replace('                        composerRelayPolicy,\n                        viewModel::setComposerRelayPolicy,\n');
  x.replace('                        viewModel::setDefaultRelayPolicy, setNearbyDiscoverable,', '                        setNearbyDiscoverable,');
});
edit(test + 'androidTest/java/com/group16/dinoblog/Phase18MigrationTest.kt', x => {
  x.replace('import com.group16.dinoblog.model.RelayPolicy\n');
  x.replace('migration.runMigrationsAndValidate(name, 14, true,\n                DinoblogDatabase.MIGRATION_12_13, DinoblogDatabase.MIGRATION_13_14)', 'migration.runMigrationsAndValidate(name, 13, true,\n                DinoblogDatabase.MIGRATION_12_13)');
  x.replace('.addMigrations(DinoblogDatabase.MIGRATION_12_13, DinoblogDatabase.MIGRATION_13_14)', '.addMigrations(DinoblogDatabase.MIGRATION_12_13)');
  x.replace('                assertEquals(RelayPolicy.ALLOW_RELAY, db.messages().get(id)?.relayPolicy)\n');
});
edit(test + 'test/java/com/group16/dinoblog/Phase11EngineTest.kt', x => {
  x.replace('import com.group16.dinoblog.model.RelayPolicy\n');
  x.replace('            at: Long, ttl: Long, hopLimit: Int, copies: Int,\n            relayPolicy: com.group16.dinoblog.model.RelayPolicy): Message = error("unused")',
    '            at: Long, ttl: Long, hopLimit: Int, copies: Int): Message = error("unused")');
  x.regex(/    @Test fun directOnlyMediaManifestIsRejectedBeforeAllocatingRelayStorage\(\) = runTest \{\n[\s\S]*?    \}\n\n(?=    @Test fun senderOffersManifest)/);
});
edit(test + 'androidTest/java/com/group16/dinoblog/Phase11MediaTest.kt', x => {
  x.replace('import com.group16.dinoblog.model.RelayPolicy\n');
  x.regex(/    @Test fun directOnlyManifestAndPartialChunksRequireOriginalAuthenticatedSource\(\) = runBlocking \{\n[\s\S]*?    \}\n\n(?=    @Test fun receiptCleanup)/);
});

edit('server/src/index.ts', x => {
  x.replace('  signed_media_sha256: \'TEXT\', relay_policy: "TEXT NOT NULL DEFAULT \'ALLOW_RELAY\'" }))',
    "  signed_media_sha256: 'TEXT' }))");
  x.replace("db.exec('PRAGMA user_version=19');", "db.exec('PRAGMA user_version=18');");
  x.replace('  const version = m.signatureVersion;\n', '  const version = 1;\n');
  x.replace('    ...(version === 2 ? [m.relayPolicy] : []), digest,', '    digest,');
  x.replace("  if (![1, 2].includes(m.signatureVersion) ||\n      (m.signatureVersion === 1 && m.relayPolicy != null && m.relayPolicy !== 'ALLOW_RELAY') ||\n      (m.signatureVersion === 2 && !['ALLOW_RELAY', 'DIRECT_ONLY'].includes(m.relayPolicy)) ||",
    '  if (m.signatureVersion !== 1 ||');
  x.replace('    relayPolicy: message.relay_policy,\n');
  x.replace("          old.hop_limit !== m.hopLimit || old.relay_policy !== (m.relayPolicy || 'ALLOW_RELAY') ||\n",
    '          old.hop_limit !== m.hopLimit ||\n');
  x.replace('        created_at,expires_at,server_received_at,hop_limit,relay_policy,signature_version,origin_public_key,\n',
    '        created_at,expires_at,server_received_at,hop_limit,signature_version,origin_public_key,\n');
  x.replace('        signed_media_file_size,signed_media_sha256) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,',
    '        signed_media_file_size,signed_media_sha256) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,');
  x.replace("        m.createdAt, m.expiresAt, Date.now(), m.hopLimit, m.relayPolicy || 'ALLOW_RELAY',\n        m.signatureVersion, m.originPublicKey,",
    '        m.createdAt, m.expiresAt, Date.now(), m.hopLimit,\n        m.signatureVersion, m.originPublicKey,');
});
edit('server/test/security-fixture.mjs', x => {
  x.replace("    ...(version === 2 ? { relayPolicy: message.relayPolicy || 'ALLOW_RELAY' } : {}),\n");
  x.replace("    signed.hopLimit, ...(version === 2 ? [signed.relayPolicy] : []), contentDigest, isText ? '' : signed.mediaId,",
    "    signed.hopLimit, contentDigest, isText ? '' : signed.mediaId,");
});
edit('server/test/mailbox.test.mjs', x => {
  x.regex(/  assert\.equal\(digest\(canonical\('DINOBLOG_MESSAGE', '2', mediaId,[\s\S]*?\);\n/);
  x.regex(/test\('signed sender relay policy survives mailbox sync and cannot be expanded or changed on retry',[\s\S]*?\n\}\);\n\n(?=test\.after)/);
});
edit('server/test/revoke-migration.test.mjs', x => {
  x.replace("assert.equal(db.prepare('PRAGMA user_version').get().user_version, 19);",
    "assert.equal(db.prepare('PRAGMA user_version').get().user_version, 18);");
  x.regex(/  assert\.equal\(db\.prepare\("PRAGMA table_info\(messages\)"\)[\s\S]*?    'ALLOW_RELAY'\);\n/);
});
edit('server/README.md', x => {
  x.regex(/Phase 18\.1 stores the source-signed[\s\S]*?\.\.\/PHASE18_1_RELAY_POLICY\.md\)\.\n\n/);
});
edit('README.md', x => {
  x.regex(/^# DinoBlog 4\.7[^\n]*\n\n[\s\S]*?(?=## Phase 18 and nearby discovery)/);
  x.replace('## Phase 18 and nearby discovery', '# DinoBlog 4.6 - Secure delivery cancellation (Phase 18)\n\n## Phase 18 and nearby discovery');
  x.replace('Current version: **4.7-relay-permission**, versionCode **23**.',
    'Current version: **4.6-recall**, versionCode **22**.');
  x.replace('Room schema 14 adds the message relay policy; schema 13 added persistent recall controls.',
    'Room schema 13 adds persistent recall controls.');
});

for (const path of [
  app + 'model/RelayPolicyRules.kt',
  test + 'test/java/com/group16/dinoblog/Phase18_1RelayPolicyTest.kt',
  'app/schemas/com.group16.dinoblog.database.DinoblogDatabase/14.json',
  'PHASE18_1_RELAY_POLICY.md',
]) fs.unlinkSync(path);
