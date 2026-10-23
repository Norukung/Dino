package com.group16.dinoblog

import com.group16.dinoblog.dtn.*
import com.group16.dinoblog.model.*
import com.group16.dinoblog.network.*
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class SmartRelayTcpTest {
    private class Node(private val clock: AtomicLong, limit: Int = DtnConfig.MAX_RELAY_BUFFER_MESSAGES) {
        val id=UUID.randomUUID().toString()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val store=PredictabilityTestStore()
        val log=NetworkLogger()
        val tcp=TcpTransport(id,id.take(8),scope,log)
        val inventory=InventoryManager(id,store,tcp,scope,log,now={clock.get()})
        val engine=DeliveryEngine(id,store,tcp,scope,log,inventory.state,relayBufferLimit=limit,now={clock.get()})
        init{tcp.onInventory=inventory::receive;inventory.start();engine.start()
            tcp.onSessionPacket={peer,session,p->engine.receive(peer,p,session)}}
        fun close(){tcp.stop();scope.cancel()}
    }
    private suspend fun connect(one:Node,two:Node){
        one.tcp.start(true,"127.0.0.1");one.tcp.state.first{it.status.startsWith("Listening")}
        two.tcp.start(false,"127.0.0.1")
        for(n in listOf(one,two)){
            n.inventory.state.first{it.sent&&it.remoteReceived}
            n.engine.predictability.first{it.summarySent&&it.summaryReceived}
        }
    }
    private suspend fun disconnect(one:Node,two:Node){
        one.tcp.stop();two.tcp.stop()
        for(n in listOf(one,two)){
            n.inventory.state.first{it.peerId==null};n.engine.predictability.first{it.peerId==null}
            assertTrue(n.engine.predictability.value.remoteEntries.isEmpty())
        }
    }
    @Test fun poorFirstPeerIsSkippedThenGoodPeerGetsOneCopyAndDeliversOffline():Unit=runBlocking{
        val clock=AtomicLong(1700000000000L);val a=Node(clock);val b=Node(clock);val c=Node(clock);val d=Node(clock);val e=Node(clock)
        try{withTimeout(30000){
            a.store.seed(d.id,0.20,clock.get());b.store.seed(d.id,0.25,clock.get());c.store.seed(d.id,0.85,clock.get());e.store.seed(d.id,0.99,clock.get())
            val m=Message(UUID.randomUUID().toString(),a.id,d.id,"MSG001",clock.get(),86400,MessageStatus.PENDING,copiesRemaining=2)
            a.store.insert(m)
            connect(b,a);a.engine.retryPending()
            assertNull(b.store.get(m.messageId));assertEquals(2,a.store.get(m.messageId)!!.copiesRemaining)
            assertFalse(a.log.logs.value.any{it.direction=="TX"&&it.detail=="MESSAGE"})
            assertEquals(1,a.store.encounters[b.id])
            assertEquals(1,a.log.logs.value.count{it.direction=="TX"&&it.detail=="PREDICTABILITY_SUMMARY"})
            disconnect(a,b)
            connect(c,a)
            a.engine.predictability.first{it.metrics.smartAccepted==1}
            assertEquals(1,a.store.get(m.messageId)!!.copiesRemaining);assertEquals(1,c.store.get(m.messageId)!!.copiesRemaining)
            assertEquals(0,a.store.get(m.messageId)!!.hopCount);assertEquals(1,c.store.get(m.messageId)!!.hopCount)
            assertNull(c.store.receipt(m.messageId)) // Advice/relay ACK is not final proof.
            disconnect(a,c)
            connect(a,e);a.engine.retryPending()
            assertNull(e.store.get(m.messageId));assertEquals(1,a.store.get(m.messageId)!!.copiesRemaining)
            disconnect(a,e)
            // A and B are absent while C meets the actual destination.
            connect(c,d)
            d.store.receipts.first{it.any{r->r.originalMessageId==m.messageId}}
            assertEquals(MessageStatus.RECEIVED,d.store.get(m.messageId)!!.status)
            assertEquals(2,d.store.get(m.messageId)!!.hopCount)
            disconnect(c,d)
            connect(d,c);c.store.receipts.first{it.any{r->r.originalMessageId==m.messageId}};disconnect(d,c)
            connect(c,a);a.store.receipts.first{it.any{r->r.originalMessageId==m.messageId}}
            assertEquals(MessageStatus.DELIVERED,a.store.get(m.messageId)!!.status)
            assertEquals(1,a.store.get(m.messageId)!!.copiesRemaining)
        }}finally{listOf(a,b,c,d,e).forEach{it.close()}}
    }
    @Test fun agedCarrierCanSmartSpraySecondBranchThenDestinationCreatesReceipt():Unit=runBlocking{
        val clock=AtomicLong(1700000000000L);val a=Node(clock);val b=Node(clock);val c=Node(clock);val d=Node(clock)
        try{withTimeout(30000){
            a.store.seed(d.id,0.1,clock.get());b.store.seed(d.id,0.7,clock.get())
            val m=a.engine.queue(d.id,"multi-hop",ttl=604800,hopLimit=3)
            connect(b,a);a.engine.predictability.first{it.metrics.smartAccepted==1}
            assertEquals(2,b.store.get(m.messageId)!!.copiesRemaining);disconnect(a,b)
            clock.addAndGet(28*DtnConfig.AGING_INTERVAL_MS)
            c.store.seed(d.id,0.9,clock.get())
            assertTrue(b.store.predictability(d.id,clock.get())<0.4)
            connect(b,c);b.engine.predictability.first{it.metrics.smartAccepted==1}
            assertEquals(1,b.store.get(m.messageId)!!.copiesRemaining);assertEquals(1,c.store.get(m.messageId)!!.copiesRemaining)
            assertEquals(1,b.store.get(m.messageId)!!.hopCount);assertEquals(2,c.store.get(m.messageId)!!.hopCount)
            disconnect(b,c);connect(c,d)
            d.store.receipts.first{it.any{r->r.originalMessageId==m.messageId}}
            assertEquals(3,d.store.get(m.messageId)!!.hopCount)
            assertEquals(m.messageId,d.store.get(m.messageId)!!.messageId)
            assertEquals(MessageStatus.RECEIVED,d.store.get(m.messageId)!!.status)
        }}finally{listOf(a,b,c,d).forEach{it.close()}}
    }
    @Test fun goodPeerStillRespectsBufferFullAndDirectTrafficBypassesQuota():Unit=runBlocking{
        val clock=AtomicLong(1700000000000L);val a=Node(clock);val b=Node(clock,0);val d=Node(clock)
        try{withTimeout(20000){
            b.store.seed(d.id,0.95,clock.get())
            val m=a.engine.queue(d.id,"reject relay")
            connect(b,a);a.engine.forwarding.first{m.messageId in it.rejectedIds}
            a.store.messages.first{it.any{r->r.messageId==m.messageId&&r.copiesRemaining==4}}
            assertNull(b.store.get(m.messageId));repeat(3){a.engine.retryPending()}
            assertEquals(1,a.log.logs.value.count{it.direction=="TX"&&it.detail=="MESSAGE"})
            val direct=a.engine.queue(b.id,"direct quota exemption")
            b.store.receipts.first{it.any{r->r.originalMessageId==direct.messageId}}
            assertEquals(MessageStatus.RECEIVED,b.store.get(direct.messageId)!!.status)
        }}finally{listOf(a,b,d).forEach{it.close()}}
    }
}
