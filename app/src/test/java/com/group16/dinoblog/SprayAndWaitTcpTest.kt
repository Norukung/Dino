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

class SprayAndWaitTcpTest {
    private class Node(limit: Int = DtnConfig.MAX_RELAY_BUFFER_MESSAGES) {
        val id = UUID.randomUUID().toString()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ReceiptTestStore()
        val log = NetworkLogger()
        val tcp = TcpTransport(id,id.take(8),scope,log)
        val inventory = InventoryManager(id,store,tcp,scope,log)
        val engine = DeliveryEngine(id,store,tcp,scope,log,inventory.state,relayBufferLimit=limit, smartRelaySelection = false)
        init {
            tcp.onInventory=inventory::receive
            inventory.start();engine.start()
            tcp.onSessionPacket={peer,session,packet->engine.receive(peer,packet,session)}
        }
        fun close(){tcp.stop();scope.cancel()}
    }
    private suspend fun connect(one:Node,two:Node){
        one.tcp.start(true,"127.0.0.1");one.tcp.state.first{it.status.startsWith("Listening")}
        two.tcp.start(false,"127.0.0.1")
        one.inventory.state.first{it.sent&&it.remoteReceived};two.inventory.state.first{it.sent&&it.remoteReceived}
    }
    private suspend fun disconnect(one:Node,two:Node){
        one.tcp.stop();two.tcp.stop()
        for(n in listOf(one,two)){
            n.inventory.state.first{it.peerId==null};n.engine.forwarding.first{it.peerId==null}
            assertTrue(n.engine.forwarding.value.awaitingAckIds.isEmpty())
            assertTrue(n.engine.forwarding.value.rejectedIds.isEmpty())
        }
    }
    @Test fun fullFourCopyDemoWaitDirectDeliveryAndFutureReceipts():Unit=runBlocking{
        val a=Node();val b=Node();val c=Node();val d=Node();val e=Node();val f=Node()
        try{withTimeout(40000){
            connect(b,a)
            val m=a.engine.queue(d.id,"MSG001",carrierId=b.id)
            a.store.messages.first{it.any{r->r.messageId==m.messageId&&r.status==MessageStatus.FORWARDED}}
            assertEquals(2,a.store.get(m.messageId)!!.copiesRemaining)
            assertEquals(2,b.store.get(m.messageId)!!.copiesRemaining)
            disconnect(a,b)
            connect(b,c)
            b.engine.forwarding.first{m.messageId in it.peerKnownIds}
            assertEquals(1,b.store.get(m.messageId)!!.copiesRemaining)
            assertEquals(1,c.store.get(m.messageId)!!.copiesRemaining)
            disconnect(b,c)
            connect(a,e)
            a.engine.forwarding.first{m.messageId in it.peerKnownIds}
            assertEquals(1,a.store.get(m.messageId)!!.copiesRemaining)
            assertEquals(1,e.store.get(m.messageId)!!.copiesRemaining)
            disconnect(a,e)
            val carriers=listOf(a,b,c,e)
            assertEquals(4,carriers.sumOf{it.store.get(m.messageId)!!.copiesRemaining})
            assertEquals(listOf(0,1,2,1),carriers.map{it.store.get(m.messageId)!!.hopCount})
            connect(b,f);b.engine.retryPending()
            assertNull(f.store.get(m.messageId))
            assertEquals(listOf(m.messageId),b.engine.forwarding.value.waitIds)
            disconnect(b,f)
            // Only the WAIT carrier C and destination D are connected now.
            connect(c,d)
            d.store.receipts.first{it.any{r->r.originalMessageId==m.messageId}}
            assertEquals(MessageStatus.RECEIVED,d.store.get(m.messageId)!!.status)
            assertEquals(3,d.store.get(m.messageId)!!.hopCount)
            disconnect(c,d)
            connect(d,c);c.store.receipts.first{it.any{r->r.originalMessageId==m.messageId}};disconnect(d,c)
            connect(c,b);b.store.receipts.first{it.any{r->r.originalMessageId==m.messageId}};disconnect(c,b)
            connect(b,a);a.store.receipts.first{it.any{r->r.originalMessageId==m.messageId}};disconnect(b,a)
            connect(a,e);e.store.receipts.first{it.any{r->r.originalMessageId==m.messageId}};disconnect(a,e)
            for(n in carriers){assertEquals(MessageStatus.DELIVERED,n.store.get(m.messageId)!!.status);assertEquals(1,n.store.receipts.value.size)}
            b.log.clear();connect(b,f);f.store.receipts.first{it.any{r->r.originalMessageId==m.messageId}}
            b.engine.retryPending();assertNull(f.store.get(m.messageId))
            assertFalse(b.log.logs.value.any{it.direction=="TX"&&it.detail=="MESSAGE"})
        }}finally{listOf(a,b,c,d,e,f).forEach{it.close()}}
    }

    @Test fun realBufferRejectRestoresBudgetOnceAndFinalDeliveryBypassesFullQuota():Unit=runBlocking{
        val a=Node();val b=Node(0);val d=Node()
        try{withTimeout(20000){
            connect(b,a)
            val m=a.engine.queue(d.id,"relay rejected",carrierId=b.id)
            a.engine.forwarding.first{m.messageId in it.rejectedIds}
            // Restoration happens after marking the rejection; wait for its Room-like update.
            a.store.messages.first{it.any{r->r.messageId==m.messageId&&r.copiesRemaining==4}}
            assertNull(b.store.get(m.messageId))
            val sends=a.log.logs.value.count{it.direction=="TX"&&it.detail=="MESSAGE"}
            repeat(3){a.engine.retryPending()}
            assertEquals(sends,a.log.logs.value.count{it.direction=="TX"&&it.detail=="MESSAGE"})
            val direct=a.engine.queue(b.id,"final recipient")
            b.store.receipts.first{it.any{r->r.originalMessageId==direct.messageId}}
            assertEquals(MessageStatus.RECEIVED,b.store.get(direct.messageId)!!.status)
            assertEquals(0,b.store.relayBufferUsage(b.id,System.currentTimeMillis()))
            disconnect(a,b)
            assertEquals(4,a.store.get(m.messageId)!!.copiesRemaining)
        }}finally{a.close();b.close();d.close()}
    }
}
