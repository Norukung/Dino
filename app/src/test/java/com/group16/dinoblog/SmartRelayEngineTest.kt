package com.group16.dinoblog

import com.group16.dinoblog.dtn.*
import com.group16.dinoblog.model.*
import com.group16.dinoblog.network.*
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SmartRelayEngineTest {
    private fun id() = UUID.randomUUID().toString()
    private val at = 2000L
    private fun row(copies: Int = 2, destination: String = id()) = Message(id(), id(), destination, "data", 1000, 60,
        MessageStatus.CARRIED, hopCount = 1, copiesRemaining = copies)
    private class Wire(peer: String) : MessageTransport {
        override val state = MutableStateFlow(SocketState("Connected", peer, sessionId = "s"))
        override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
        val sent = mutableListOf<WirePacket>()
        var onSend: suspend (WirePacket) -> Unit = {}
        var beforeWrite: suspend () -> Unit = {}
        var succeeds = true
        override suspend fun send(peerId: String, packet: WirePacket): Boolean { sent += packet; onSend(packet); return succeeds }
        override suspend fun sendIfCurrent(peerId: String, sessionId: String, packet: WirePacket, eligible: suspend () -> Boolean): Boolean {
            beforeWrite()
            if (!eligible()) return false
            return sendToSession(peerId, sessionId, packet)
        }
    }
    private fun inventory(peer: String, vararg ids: String) = MutableStateFlow(InventoryState(peerId = peer,
        sessionId = "s", sent = true, remoteReceived = true, localReady = true, missingOnPeer = ids.toList()))
    private suspend fun summary(engine: DeliveryEngine, peer: String, vararg entries: PredictabilityEntry, session: String = "s") {
        engine.receive(peer, WirePacket(PacketType.PREDICTABILITY_SUMMARY, deviceId = peer, entries = entries.toList()), session)
    }

    @Test fun oncePerSessionEncounterSummaryAndTransitivityAreNotReactiveLoops(): Unit = runTest {
        val local = id(); val peer = id(); val dest = id(); val store = PredictabilityTestStore(); val wire = Wire(peer)
        val inv = inventory(peer)
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), inv, now = { at })
        engine.retryPending()
        assertEquals(0.75, store.predictability(peer, at), 1e-12)
        val entries = arrayOf(PredictabilityEntry(dest,0.9))
        repeat(3) { summary(engine, peer, *entries); engine.retryPending() }
        assertEquals(1,store.encounters[peer]); assertEquals(1,store.transitiveUpdates)
        assertEquals(1,wire.sent.count { it.type == PacketType.PREDICTABILITY_SUMMARY })
        assertEquals(0.16875,store.predictability(dest,at),1e-12)
        wire.state.value = SocketState(); engine.retryPending()
        assertTrue(engine.predictability.value.remoteEntries.isEmpty())
        wire.state.value = SocketState("Connected",peer,sessionId="new"); inv.value=inv.value.copy(sessionId="new")
        engine.retryPending()
        assertEquals(2,store.encounters[peer]); assertEquals(0.9375,store.predictability(peer,at),1e-12)
        assertFalse(engine.predictability.value.summaryReceived)
    }
    @Test fun betterPeerGetsBinaryBranchButPoorMarginalAndUnknownPeersKeepBudget(): Unit = runTest {
        for ((localScore,peerScore,expectedSend) in listOf(Triple(0.2,0.8,true),Triple(0.7,0.2,false),Triple(0.4,0.45,false),Triple(0.0,0.0,false))) {
            val local=id();val peer=id();val m=row();val store=PredictabilityTestStore();store.insert(m);store.seed(m.destinationId,localScore,at)
            val wire=Wire(peer);val engine=DeliveryEngine(local,store,wire,backgroundScope,NetworkLogger(),inventory(peer,m.messageId),now={at})
            summary(engine,peer,PredictabilityEntry(m.destinationId,peerScore));engine.retryPending()
            assertEquals(if(expectedSend)1 else 0,wire.sent.count{it.type==PacketType.MESSAGE})
            assertEquals(if(expectedSend)1 else 2,store.get(m.messageId)!!.copiesRemaining)
            if(expectedSend){assertEquals(ForwardingKind.SMART_SPRAY,engine.forwarding.value.lastSentKind);assertEquals(1,wire.sent.last{it.type==PacketType.MESSAGE}.message!!.copiesRemaining)}
        }
    }
    @Test fun decisionsAreDestinationSpecificAndSameRuleAppliesAtSourceAndRelay(): Unit = runTest {
        for(source in listOf(true,false)) {
            val local=id();val peer=id();val good=row().copy(sourceId=if(source)local else id(),status=if(source)MessageStatus.PENDING else MessageStatus.CARRIED)
            val poor=row();val marginal=row();val store=PredictabilityTestStore()
            for(m in listOf(good,poor,marginal))store.insert(m)
            store.seed(good.destinationId,0.2,at);store.seed(poor.destinationId,0.7,at);store.seed(marginal.destinationId,0.65,at)
            val wire=Wire(peer);val inv=inventory(peer,good.messageId,poor.messageId,marginal.messageId)
            val engine=DeliveryEngine(local,store,wire,backgroundScope,NetworkLogger(),inv,now={at})
            summary(engine,peer,PredictabilityEntry(good.destinationId,0.9),PredictabilityEntry(poor.destinationId,0.1),PredictabilityEntry(marginal.destinationId,0.7))
            engine.retryPending()
            assertEquals(listOf(good.messageId),wire.sent.mapNotNull{it.message?.messageId})
            assertEquals(2,store.get(poor.messageId)!!.copiesRemaining);assertEquals(2,store.get(marginal.messageId)!!.copiesRemaining)
            // A high score is advice only, never a receipt or end-to-end status.
            engine.receive(peer,WirePacket(PacketType.ACK,messageId=good.messageId,ackStatus=MessageStatus.CARRIED))
            assertNull(store.receipt(good.messageId));assertNotEquals(MessageStatus.DELIVERED,store.get(good.messageId)!!.status)
            assertEquals(1,engine.predictability.value.metrics.smartAccepted)
        }
    }
    @Test fun directIgnoresMissingSummaryAndOneCopyIgnoresExcellentRelay(): Unit = runTest {
        val local=id();val peer=id();val direct=row(1,peer);val wait=row(1);val store=PredictabilityTestStore()
        store.insert(direct);store.insert(wait)
        val wire=Wire(peer);val engine=DeliveryEngine(local,store,wire,backgroundScope,NetworkLogger(),inventory(peer,direct.messageId,wait.messageId),now={at})
        engine.retryPending()
        assertEquals(listOf(direct.messageId),wire.sent.mapNotNull{it.message?.messageId})
        summary(engine,peer,PredictabilityEntry(wait.destinationId,0.99));engine.retryPending()
        assertFalse(wire.sent.any{it.message?.messageId==wait.messageId});assertEquals(1,store.get(wait.messageId)!!.copiesRemaining)
    }
    @Test fun noSummaryOrPreviousSessionSummaryCanAuthorizeSmartSpray(): Unit = runTest {
        val local=id();val oldPeer=id();val next=id();val m=row();val store=PredictabilityTestStore();store.insert(m)
        val wire=Wire(oldPeer);val inv=inventory(oldPeer,m.messageId)
        val engine=DeliveryEngine(local,store,wire,backgroundScope,NetworkLogger(),inv,now={at})
        engine.retryPending();assertFalse(wire.sent.any{it.type==PacketType.MESSAGE})
        // Store the old peer summary but move connection before any send can use it.
        summary(engine,oldPeer,PredictabilityEntry(m.destinationId,0.9))
        wire.state.value=SocketState("Connected",next,sessionId="new");inv.value=inv.value.copy(peerId=next,sessionId="new")
        engine.retryPending()
        assertTrue(engine.predictability.value.remoteEntries.isEmpty());assertFalse(wire.sent.any{it.type==PacketType.MESSAGE})
        assertEquals(2,store.get(m.messageId)!!.copiesRemaining)
        summary(engine,oldPeer,PredictabilityEntry(m.destinationId,1.0))
        assertFalse(engine.predictability.value.summaryReceived)
    }
    @Test fun receiptExpiryHopLimitAndKnownPeerOverridePerfectScore(): Unit = runTest {
        val local=id();val peer=id();val delivered=row(4);val expired=row(4).copy(ttl=1,expiresAt=2000)
        val blocked=row(4).copy(hopLimit=1);val known=row(4);val store=PredictabilityTestStore()
        val ms=listOf(delivered,expired,blocked,known);ms.forEach{store.insert(it)}
        store.saveReceipt(DeliveryReceipt.create(delivered,delivered.destinationId,1500))
        val wire=Wire(peer);val inv=inventory(peer,*ms.map{it.messageId}.toTypedArray());inv.value=inv.value.copy(remoteMessageIds=listOf(known.messageId))
        val engine=DeliveryEngine(local,store,wire,backgroundScope,NetworkLogger(),inv,now={at})
        summary(engine,peer,*ms.map{PredictabilityEntry(it.destinationId,1.0)}.toTypedArray());engine.retryPending()
        assertFalse(wire.sent.any{it.type==PacketType.MESSAGE});ms.forEach{assertEquals(4,store.get(it.messageId)!!.copiesRemaining)}
    }
    @Test fun actualWriterRechecksScoreBeforeSpendingCopies(): Unit = runTest {
        val local=id();val peer=id();val m=row();val store=PredictabilityTestStore();store.insert(m);store.seed(m.destinationId,0.2,at)
        val wire=Wire(peer);val engine=DeliveryEngine(local,store,wire,backgroundScope,NetworkLogger(),inventory(peer,m.messageId),now={at})
        summary(engine,peer,PredictabilityEntry(m.destinationId,0.8))
        wire.beforeWrite={store.seed(m.destinationId,0.9,at)}
        engine.retryPending();assertFalse(wire.sent.any{it.type==PacketType.MESSAGE});assertEquals(2,store.get(m.messageId)!!.copiesRemaining)
    }
    @Test fun smartBufferRejectRestoresOnceWhileAmbiguousFailureDoesNot(): Unit = runTest {
        for(reject in listOf(true,false)) {
            val local=id();val peer=id();val m=row();val store=PredictabilityTestStore();store.insert(m)
            val wire=Wire(peer);val engine=DeliveryEngine(local,store,wire,backgroundScope,NetworkLogger(),inventory(peer,m.messageId),now={at})
            summary(engine,peer,PredictabilityEntry(m.destinationId,0.95))
            if(reject)wire.onSend={p->if(p.type==PacketType.MESSAGE)engine.receive(peer,WirePacket(PacketType.MESSAGE_REJECT,messageId=m.messageId,reason=MessageRejectReason.BUFFER_FULL))}
            else wire.onSend={p->if(p.type==PacketType.MESSAGE)wire.succeeds=false}
            engine.retryPending()
            assertEquals(if(reject)2 else 1,store.get(m.messageId)!!.copiesRemaining)
            if(reject){repeat(3){engine.retryPending()};assertEquals(1,wire.sent.count{it.type==PacketType.MESSAGE});assertEquals(0,engine.predictability.value.metrics.smartAccepted)}
        }
    }
    @Test fun invalidSummariesAreIgnoredWithoutPoisoningOrCompletingSession(): Unit = runTest {
        val local=id();val peer=id();val dest=id();val store=PredictabilityTestStore();val wire=Wire(peer)
        val engine=DeliveryEngine(local,store,wire,backgroundScope,NetworkLogger(),inventory(peer),now={at})
        for(score in listOf(-1.0,4.5,Double.NaN,Double.POSITIVE_INFINITY))summary(engine,peer,PredictabilityEntry(dest,score))
        summary(engine,peer,PredictabilityEntry("",0.9))
        summary(engine,peer,*List(65){PredictabilityEntry(id(),0.9)}.toTypedArray())
        summary(engine,peer,PredictabilityEntry(dest,0.8),PredictabilityEntry(dest,0.9))
        assertEquals(0.0,store.predictability(dest,at),0.0);assertFalse(engine.predictability.value.summaryReceived)
        summary(engine,peer,PredictabilityEntry(dest,0.8));engine.retryPending();assertTrue(engine.predictability.value.summaryReceived)
    }
    @Test fun slowSummaryReadCannotLeakOldSessionAdviceOrPacketToNewPeer(): Unit = runTest {
        val old=id();val next=id();val store=PredictabilityTestStore();val wire=Wire(old);val inv=inventory(old)
        val engine=DeliveryEngine(id(),store,wire,backgroundScope,NetworkLogger(),inv,now={at})
        val gate=CompletableDeferred<Unit>();store.beforeSummary={gate.await()}
        val work=launch{engine.retryPending()};runCurrent()
        wire.state.value=SocketState("Connected",next,sessionId="new");inv.value=inv.value.copy(peerId=next,sessionId="new")
        gate.complete(Unit);work.join()
        assertTrue(wire.sent.isEmpty());engine.retryPending()
        assertEquals(next,engine.predictability.value.peerId);assertFalse(engine.predictability.value.summaryReceived)
    }
    @Test fun summaryWireRoundTripAndInvalidFramesCannotPoisonRouting() {
        val p=WirePacket(PacketType.PREDICTABILITY_SUMMARY,deviceId=id(),entries=listOf(PredictabilityEntry(id(),0.82)))
        val bytes=ByteArrayOutputStream();WireProtocol.write(DataOutputStream(bytes),p)
        assertEquals(p,WireProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))))
        val raw=String(WireProtocol.encode(p),Charsets.UTF_8).replace("0.82","4.5").toByteArray()
        val invalid=ByteArrayOutputStream();DataOutputStream(invalid).apply{writeInt(raw.size);write(raw)}
        assertTrue(runCatching{WireProtocol.read(DataInputStream(ByteArrayInputStream(invalid.toByteArray())))}.isFailure)
    }
    @Test fun lostAckStillRetriesSameSmartBranchWithoutAnotherBudgetSplit(): Unit = runTest {
        val peer=id();val local=id();val m=row();val store=PredictabilityTestStore();store.insert(m)
        val wire=Wire(peer);var clock=at
        val engine=DeliveryEngine(local,store,wire,backgroundScope,NetworkLogger(),inventory(peer,m.messageId),now={clock})
        summary(engine,peer,PredictabilityEntry(m.destinationId,0.9));engine.retryPending()
        val first=wire.sent.single{it.type==PacketType.MESSAGE}
        assertEquals(1,store.get(m.messageId)!!.copiesRemaining)
        repeat(3){engine.retryPending()};assertEquals(1,wire.sent.count{it.type==PacketType.MESSAGE})
        clock+=5000;engine.retryPending()
        assertEquals(listOf(first,first),wire.sent.filter{it.type==PacketType.MESSAGE})
        assertEquals(1,store.get(m.messageId)!!.copiesRemaining)
        engine.receive(peer,WirePacket(PacketType.ACK,messageId=m.messageId,ackStatus=MessageStatus.CARRIED))
        clock+=5000;engine.retryPending();assertEquals(2,wire.sent.count{it.type==PacketType.MESSAGE})
    }

    @Test fun cancelledSummaryWriteCanRetryInTheSameSession(): Unit = runTest {
        val peer = id()
        val store = PredictabilityTestStore()
        val wire = Wire(peer)
        val engine = DeliveryEngine(id(), store, wire, backgroundScope, NetworkLogger(),
            inventory(peer), now = { at })
        val writing = CompletableDeferred<Unit>()
        wire.onSend = { packet ->
            if (packet.type == PacketType.PREDICTABILITY_SUMMARY) {
                writing.complete(Unit)
                awaitCancellation()
            }
        }
        val first = launch { engine.retryPending() }
        writing.await()
        first.cancelAndJoin()
        assertFalse(engine.predictability.value.summarySent)

        wire.onSend = {}
        engine.retryPending()
        assertTrue(engine.predictability.value.summarySent)
        assertEquals(1, store.encounters[peer])
        assertEquals(2, wire.sent.count { it.type == PacketType.PREDICTABILITY_SUMMARY })
    }

}
