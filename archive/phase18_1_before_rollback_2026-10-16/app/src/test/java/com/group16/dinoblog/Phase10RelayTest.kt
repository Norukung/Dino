package com.group16.dinoblog

import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.model.InventoryState
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.network.MessageRejectReason
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class Phase10RelayTest {
    private fun id() = UUID.randomUUID().toString()
    private val at = 2_000L
    private fun message(source: String, destination: String, status: MessageStatus = MessageStatus.CARRIED,
        copies: Int = 4) = Message(id(), source, destination, "payload", 1_000L, 60L, status,
        hopCount = if (status == MessageStatus.PENDING) 0 else 1, copiesRemaining = copies)

    private class Wire(peer: String, capability: Boolean? = true) : MessageTransport {
        override val state = MutableStateFlow(SocketState("Connected", peer, sessionId = "s", peerRelayEnabled = capability))
        override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
        val sent = mutableListOf<WirePacket>()
        var onSend: suspend (WirePacket) -> Unit = {}
        var beforeWrite: suspend () -> Unit = {}
        override suspend fun send(peerId: String, packet: WirePacket): Boolean {
            sent += packet
            onSend(packet)
            return true
        }
        override suspend fun sendIfCurrent(peerId: String, sessionId: String, packet: WirePacket,
            eligible: suspend () -> Boolean): Boolean {
            beforeWrite()
            return if (eligible()) sendToSession(peerId, sessionId, packet) else false
        }
    }
    private fun inventory(peer: String, vararg messages: Message) = MutableStateFlow(InventoryState(
        peerId = peer, sessionId = "s", sent = true, localReady = true, remoteReceived = true,
        missingOnPeer = messages.map { it.messageId }))

    @Test fun helloCapabilityIsOptionalAndNeverChangesDeviceIdentity() {
        val peer = id()
        fun read(packet: WirePacket): WirePacket {
            val output = ByteArrayOutputStream()
            WireProtocol.write(DataOutputStream(output), packet)
            return WireProtocol.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        }
        assertEquals(false, read(WirePacket(PacketType.HELLO, deviceId = peer, displayName = "Nate", relayEnabled = false)).relayEnabled)
        val old = read(WirePacket(PacketType.HELLO, deviceId = peer, displayName = "Nate"))
        assertNull(old.relayEnabled)
        assertEquals(peer, old.deviceId)
    }

    @Test fun relayOffRejectsNewForeignPayloadButAcceptsFinalDestination() = runTest {
        val local = id(); val source = id(); val destination = id()
        val store = Phase4TestStore(); val wire = Wire(source)
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(),
            smartRelaySelection = false, relayEnabled = { false }, now = { at })
        val foreign = message(source, destination)
        engine.receive(source, WirePacket(PacketType.MESSAGE, message = foreign.copy(status = MessageStatus.PENDING)))
        assertNull(store.get(foreign.messageId))
        assertEquals(MessageRejectReason.RELAY_DISABLED, wire.sent.last().reason)
        val ownDestination = message(source, local)
        engine.receive(source, WirePacket(PacketType.MESSAGE, message = ownDestination.copy(status = MessageStatus.PENDING)))
        assertEquals(MessageStatus.RECEIVED, store.get(ownDestination.messageId)?.status)
        assertEquals(MessageStatus.DELIVERED, wire.sent.last().ackStatus)
    }

    @Test fun relayOffStillSendsOwnMessagesAndPreviouslyCarriedDirectOnly() = runTest {
        val local = id(); val peer = id(); val destination = id(); val store = Phase4TestStore()
        val own = message(local, destination, MessageStatus.PENDING)
        val carried = message(id(), destination)
        val direct = message(id(), peer, copies = 1)
        listOf(own, carried, direct).forEach { store.insert(it) }
        val wire = Wire(peer)
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(),
            inventory(peer, own, carried, direct), smartRelaySelection = false,
            relayEnabled = { false }, now = { at })
        engine.retryPending()
        assertEquals(setOf(own.messageId, direct.messageId), wire.sent.mapNotNull { it.message?.messageId }.toSet())
        assertEquals(4, store.get(carried.messageId)?.copiesRemaining)
    }

    @Test fun remoteOptOutBlocksSprayButAllowsDirect() = runTest {
        val local = id(); val peer = id(); val own = message(local, id(), MessageStatus.PENDING)
        val direct = message(local, peer, MessageStatus.PENDING)
        val store = Phase4TestStore(); store.insert(own); store.insert(direct)
        val wire = Wire(peer, capability = false)
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(),
            inventory(peer, own, direct), smartRelaySelection = false, now = { at })
        engine.retryPending()
        assertEquals(listOf(direct.messageId), wire.sent.mapNotNull { it.message?.messageId })
        assertEquals(4, store.get(own.messageId)?.copiesRemaining)
    }

    @Test fun definitiveRelayDisabledRejectRestoresTheFullSprayBudget() = runTest {
        val local = id(); val peer = id(); val own = message(local, id(), MessageStatus.PENDING)
        val store = Phase4TestStore(); store.insert(own)
        val wire = Wire(peer)
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(),
            inventory(peer, own), smartRelaySelection = false, now = { at })
        wire.onSend = { packet -> if (packet.type == PacketType.MESSAGE)
            engine.receive(peer, WirePacket(PacketType.MESSAGE_REJECT, messageId = own.messageId,
                reason = MessageRejectReason.RELAY_DISABLED)) }
        engine.retryPending()
        assertEquals(4, store.get(own.messageId)?.copiesRemaining)
        assertEquals("RELAY_DISABLED", engine.forwarding.value.lastRejectReason)
    }

    @Test fun switchingOffAtWriterBoundaryStopsCarriedSprayAndSwitchingOnResumesIt() = runTest {
        val local = id(); val peer = id(); val carried = message(id(), id())
        val store = Phase4TestStore(); store.insert(carried)
        val wire = Wire(peer); var enabled = true
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(),
            inventory(peer, carried), smartRelaySelection = false, relayEnabled = { enabled }, now = { at })
        wire.beforeWrite = { enabled = false }
        engine.retryPending()
        assertTrue(wire.sent.none { it.type == PacketType.MESSAGE })
        assertEquals(4, store.get(carried.messageId)?.copiesRemaining)
        enabled = true; wire.beforeWrite = {}
        engine.retryPending()
        assertEquals(carried.messageId, wire.sent.single { it.type == PacketType.MESSAGE }.message?.messageId)
    }
}
