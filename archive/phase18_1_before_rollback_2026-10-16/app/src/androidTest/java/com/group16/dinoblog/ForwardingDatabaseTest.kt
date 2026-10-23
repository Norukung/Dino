package com.group16.dinoblog

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.model.InventoryState
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.repository.MessageRepository
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ForwardingDatabaseTest {
    private fun id() = UUID.randomUUID().toString()
    private class Wire(peer: String) : MessageTransport {
        override val state = MutableStateFlow(SocketState("Connected", peer, sessionId = "session"))
        override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
        var onSend: suspend (WirePacket) -> Unit = {}
        override suspend fun send(peerId: String, packet: WirePacket): Boolean { onSend(packet); return true }
    }

    @Test fun persistedCustodyReopensThenDeliversToFinalRoomAndMessageInventoryPreventsDuplicates(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "forward-${id()}.db"
        val a = id(); val b = id(); val c = id()
        val carried = Message(id(), a, c, "Hello C", 1000, 60, MessageStatus.CARRIED, hopCount = 1, carrierId = b)
        try {
            val first = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try { first.messages().insert(carried) } finally { first.close() }
            val dbB = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            val dbC = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
            try {
                val repoB = MessageRepository(dbB)
                val repoC = MessageRepository(dbC)
                val wireB = Wire(c)
                val wireC = Wire(b)
                val inventory = MutableStateFlow(InventoryState(peerId = c, sessionId = "session", sent = true,
                    localReady = true, remoteReceived = true, missingOnPeer = listOf(carried.messageId)))
                val sender = DeliveryEngine(b, repoB, wireB, this, NetworkLogger(), inventory, smartRelaySelection = false) { 2000 }
                val receiver = DeliveryEngine(c, repoC, wireC, this, NetworkLogger(), smartRelaySelection = false) { 2000 }
                wireB.onSend = { receiver.receive(b, it, "session") }
                wireC.onSend = {
                    assertEquals(MessageStatus.RECEIVED, repoC.get(carried.messageId)!!.status)
                    sender.receive(c, it, "session")
                }
                sender.retryPending()
                assertEquals(carried.copy(status = MessageStatus.DELIVERED), repoB.get(carried.messageId))
                assertEquals(carried.copy(status = MessageStatus.RECEIVED, hopCount = 2), repoC.get(carried.messageId))
                assertEquals(listOf(carried.messageId), repoC.receivedMessageIds(2000))
                assertTrue(repoC.forwardableMessageIds(2000).isEmpty())
                receiver.receive(b, WirePacket(PacketType.MESSAGE,
                    message = carried.copy(status = MessageStatus.PENDING, hopCount = 2)), "session")
                assertEquals(1, repoC.messages.first().size)
                assertEquals(1, repoB.get(carried.messageId)!!.hopCount)
            } finally { dbB.close(); dbC.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun finalAckSqlGuardsPeerExpectedHopStatusAndInclusiveBudget(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        try {
            val b = id(); val c = id()
            val row = Message(id(), id(), c, "relay", 1000, 60, MessageStatus.CARRIED, hopCount = 1, carrierId = b)
            val dao = db.messages()
            dao.insert(row)
            assertEquals(0, dao.markRelayDelivered(row.messageId, b, id(), 1))
            assertEquals(0, dao.markRelayDelivered(row.messageId, b, c, 0))
            assertEquals(0, dao.markRelayDelivered(row.messageId, c, c, 1))
            assertEquals(1, dao.markRelayDelivered(row.messageId, b, c, 1))
            assertEquals(0, dao.markRelayDelivered(row.messageId, b, c, 1))
            assertEquals(1, dao.get(row.messageId)!!.hopCount)
            val exhausted = row.copy(messageId = id(), hopLimit = 1)
            dao.insert(exhausted)
            assertEquals(0, dao.markRelayDelivered(exhausted.messageId, b, c, 1))
            assertEquals(exhausted, dao.get(exhausted.messageId))
            val pending = row.copy(messageId = id(), status = MessageStatus.PENDING)
            dao.insert(pending)
            assertEquals(1, dao.markRelayDelivered(pending.messageId, b, c, 1))
            assertEquals(1, dao.get(pending.messageId)!!.hopCount)
        } finally { db.close() }
    }

    @Test fun relayReplicationRetainsPathDepthAndExhaustedKnowledgeSurvivesReopen(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "epidemic-${id()}.db"
        val a = id(); val b = id(); val c = id(); val d = id(); val e = id()
        val carried = Message(id(), a, d, "Hello D", 1000, 60, MessageStatus.CARRIED,
            hopCount = 1, hopLimit = 2, carrierId = b)
        val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
        try {
            val dao = db.messages()
            dao.insert(carried)
            assertEquals(1, dao.markReplicated(carried.messageId, c, 1))
            assertEquals(carried, dao.get(carried.messageId))
            assertEquals(1, dao.markReplicated(carried.messageId, e, 1))
            assertEquals(carried, dao.get(carried.messageId))
            assertEquals(0, dao.markReplicated(carried.messageId, d, 1)) // Final ACK uses a different guard.
            assertEquals(0, dao.markReplicated(carried.messageId, c, 2))
            val source = carried.copy(messageId = id(), status = MessageStatus.PENDING, hopCount = 0)
            dao.insert(source)
            assertEquals(1, dao.markReplicated(source.messageId, c, 0))
            assertEquals(source.copy(status = MessageStatus.FORWARDED), dao.get(source.messageId))
            val received = carried.copy(messageId = id(), hopCount = 2)
            dao.insert(received)
            val repository = MessageRepository(db)
            assertTrue(received.messageId in repository.receivedMessageIds(2000))
            assertFalse(received.messageId in repository.forwardableMessageIds(2000))
            assertTrue(carried.messageId in repository.forwardableMessageIds(2000)) // Outgoing hop equals limit.
        } finally { db.close() }
        try {
            val reopened = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                assertEquals(carried, reopened.messages().get(carried.messageId))
                val repository = MessageRepository(reopened)
                assertEquals(3, repository.messages.first().size)
                repository.expire(61000)
                assertTrue(repository.forwardableMessageIds(61000).isEmpty())
                assertTrue(repository.receivedMessageIds(61000).isEmpty())
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
