package com.group16.dinoblog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.model.*
import com.group16.dinoblog.network.*
import com.group16.dinoblog.repository.MessageRepository
import com.group16.dinoblog.repository.MessageAdmission
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeliveryReceiptDatabaseTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DinoblogDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun id() = UUID.randomUUID().toString()
    private fun message() = Message(id(), id(), id(), "persistent receipt", 1000, 60, MessageStatus.PENDING, hopCount = 3)
    private class Wire(peer: String) : MessageTransport {
        override val state = MutableStateFlow(SocketState("Connected", peer, sessionId = "one"))
        override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
        val sent = mutableListOf<WirePacket>()
        var onSend: suspend (WirePacket) -> Unit = {}
        override suspend fun send(peerId: String, packet: WirePacket): Boolean { onSend(packet); sent += packet; return true }
    }

    @Test fun migrationFromV2PreservesMessagesPeersAndAddsEmptyReceiptTable(): Unit = runBlocking {
        val name = "receipt-migration-${id()}.db"
        try {
            migration.createDatabase(name, 2).use {
                it.execSQL("""INSERT INTO messages (messageId,senderId,receiverId,payload,timestamp,ttl,status,expiresAt,hopCount,hopLimit,carrierId)
                    VALUES ('old','a','d','keep me',1000,60,'CARRIED',61000,2,8,NULL)""")
                it.execSQL("INSERT INTO peers (deviceId,name,lastSeen) VALUES ('b','B',1000)")
            }
            migration.runMigrationsAndValidate(name, 3, true, DinoblogDatabase.MIGRATION_2_3).close()
            val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                assertEquals("keep me", db.messages().get("old")!!.payload)
                assertEquals(MessageStatus.CARRIED, db.messages().get("old")!!.status)
                assertEquals(2, db.messages().get("old")!!.hopCount)
                assertEquals(1, db.peers().observeAll().first().size)
                assertTrue(db.receipts().observeAll().first().isEmpty())
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun finalMessageAndReceiptCommitBeforeAckSurviveReopenAndStayUnique(): Unit = runBlocking {
        val name = "receipt-restart-${id()}.db"; val m = message(); val peer = id()
        try {
            val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                val repo = MessageRepository(db); val wire = Wire(peer)
                val engine = DeliveryEngine(m.destinationId, repo, wire, this, NetworkLogger()) { 2000 }
                wire.onSend = {
                    assertNotNull(repo.receiptForMessage(m.messageId))
                    assertEquals(MessageStatus.RECEIVED, repo.get(m.messageId)!!.status)
                }
                repeat(2) { engine.receive(peer, WirePacket(PacketType.MESSAGE, message = m), "one") }
                assertEquals(2, wire.sent.size)
                assertEquals(1, repo.receipts.first().size)
                assertEquals(1, repo.messages.first().size)
            } finally { db.close() }
            val reopened = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                val repo = MessageRepository(reopened); val r = repo.receiptForMessage(m.messageId)!!
                assertEquals(DeliveryReceipt.idFor(m.messageId), r.receiptId)
                assertEquals(2000L, r.deliveredAt)
                assertEquals(0, r.hopCount)
                assertTrue(repo.forwardableMessageIds(3000).isEmpty())
                assertTrue(repo.receivedMessageIds(3000).isEmpty())
                assertEquals(listOf(r.receiptId), repo.activeReceiptIds(3000))
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun receiptFirstSuppressesAdmissionAndReceiptConfirmsExistingSourceWithoutDeletingIt(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        try {
            val repo = MessageRepository(db); val m = message(); val r = DeliveryReceipt.create(m, 2000).copy(hopCount = 2)
            assertTrue(repo.saveReceipt(r))
            assertFalse(repo.saveReceipt(r.copy(hopCount = 3)))
            assertEquals(MessageAdmission.SUPPRESSED, repo.commitReceived(m.copy(status = MessageStatus.CARRIED), null))
            assertNull(repo.get(m.messageId))
            val source = message().copy(status = MessageStatus.FORWARDED, hopCount = 0)
            repo.insert(source)
            repo.saveReceipt(DeliveryReceipt.create(source, 2000).copy(hopCount = 3))
            assertEquals(source.copy(status = MessageStatus.DELIVERED), repo.get(source.messageId))
            assertTrue(repo.forwardableMessageIds(3000).isEmpty())
            assertTrue(repo.receivedMessageIds(3000).isEmpty())
            assertEquals(1, repo.messages.first().size)
            assertEquals(r, repo.receipt(r.receiptId))
            assertNotNull(repo.receiptForMessage(m.messageId))
            assertTrue(repo.activeReceiptIds(r.expiresAt).isEmpty())
            assertEquals(MessageAdmission.SUPPRESSED, repo.commitReceived(m.copy(status = MessageStatus.CARRIED), null))
        } finally { db.close() }
    }

    @Test fun receiptInsertFailureRollsBackFinalMessageAndSendsNoAck(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        try {
            db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_receipt BEFORE INSERT ON delivery_receipts
                BEGIN SELECT RAISE(ABORT, 'injected receipt failure'); END""")
            val m = message(); val peer = id(); val wire = Wire(peer); val repo = MessageRepository(db)
            val engine = DeliveryEngine(m.destinationId, repo, wire, this, NetworkLogger()) { 2000 }
            try { engine.receive(peer, WirePacket(PacketType.MESSAGE, message = m), "one"); fail("Expected transaction failure") }
            catch (_: android.database.sqlite.SQLiteException) { }
            assertNull(repo.get(m.messageId)); assertTrue(repo.receipts.first().isEmpty()); assertTrue(wire.sent.isEmpty())
        } finally { db.close() }
    }

    @Test fun relayReceiptSurvivesReopenThenForwardsToSourceWithoutOriginalPayload(): Unit = runBlocking {
        val name = "receipt-relay-restart-${id()}.db"
        val m = message(); val relayId = id()
        val receipt = DeliveryReceipt.create(m, 2000).copy(hopCount = 2)
        try {
            val first = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                val repo = MessageRepository(first)
                repo.insert(m.copy(status = MessageStatus.CARRIED))
                val wire = Wire(m.destinationId)
                val engine = DeliveryEngine(relayId, repo, wire, this, NetworkLogger()) { 3000 }
                engine.receive(m.destinationId, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = receipt), "one")
                assertEquals(MessageStatus.DELIVERED, repo.get(m.messageId)!!.status)
                assertEquals(PacketType.DELIVERY_RECEIPT, wire.sent.single().ackFor)
            } finally { first.close() }

            val reopened = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                val repo = MessageRepository(reopened)
                val wire = Wire(m.sourceId)
                val receiptIds = repo.activeReceiptIds(4000)
                assertEquals(listOf(receipt.receiptId), receiptIds)
                assertTrue(repo.forwardableMessageIds(4000).isEmpty())
                assertTrue(repo.receivedMessageIds(4000).isEmpty())
                val inventory = MutableStateFlow(InventoryState(peerId = m.sourceId, sessionId = "one",
                    localReady = true, remoteReceived = true, sent = true, peerSupportsReceipts = true,
                    localReceiptIds = receiptIds, receiptMissingOnPeer = receiptIds))
                val engine = DeliveryEngine(relayId, repo, wire, this, NetworkLogger(), inventory) { 4000 }
                engine.retryPending()
                assertEquals(PacketType.DELIVERY_RECEIPT, wire.sent.single().type)
                assertEquals(receipt.copy(hopCount = 3), wire.sent.single().receipt)
                assertEquals(ForwardingKind.DIRECT, engine.forwarding.value.lastReceiptSentKind)
                assertEquals(receipt, repo.receipt(receipt.receiptId))
                assertEquals(m.copy(status = MessageStatus.DELIVERED), repo.get(m.messageId))
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun failedDeliveryStatusUpdateRollsBackReceivedReceiptAndSendsNoAck(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        try {
            val m = message().copy(status = MessageStatus.FORWARDED)
            val repo = MessageRepository(db)
            repo.insert(m)
            db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_confirmation BEFORE UPDATE OF status ON messages
                WHEN NEW.status = 'DELIVERED'
                BEGIN SELECT RAISE(ABORT, 'injected delivery status failure'); END""")
            val peer = id(); val wire = Wire(peer)
            val engine = DeliveryEngine(m.sourceId, repo, wire, this, NetworkLogger()) { 3000 }
            val receipt = DeliveryReceipt.create(m, 2000).copy(hopCount = 2)
            try {
                engine.receive(peer, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = receipt), "one")
                fail("Expected transaction failure")
            } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(m, repo.get(m.messageId))
            assertTrue(repo.receipts.first().isEmpty())
            assertTrue(wire.sent.isEmpty())
        } finally { db.close() }
    }
}
