package com.group16.dinoblog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.dtn.DtnConfig
import com.group16.dinoblog.model.*
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.repository.*
import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SprayAndWaitDatabaseTest {
    @get:Rule val migration=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),DinoblogDatabase::class.java)
    private fun id()=UUID.randomUUID().toString()
    private fun row(copies:Int=4)=Message(id(),id(),id(),"payload",1000,60,MessageStatus.CARRIED,hopCount=1,copiesRemaining=copies)
    private class TestWire(peer:String):MessageTransport {
        override val state=MutableStateFlow(SocketState("Connected",peer,sessionId="one"))
        override var onPacket:suspend (String,WirePacket)->Unit={_,_->}
        val sent=mutableListOf<WirePacket>()
        override suspend fun send(peerId:String,packet:WirePacket):Boolean {
            if(state.value.peerId!=peerId)return false
            sent+=packet
            return true
        }
    }
    @Test fun textRelayReusesPersistedReservationAfterReconnect():Unit=runBlocking{
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(ctx,DinoblogDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try{
            val local=id();val peer=id();val destination=id();val now=System.currentTimeMillis()
            val message=Message(id(),local,destination,"carry me",now,60,MessageStatus.PENDING,
                copiesRemaining=DtnConfig.INITIAL_SPRAY_COPIES)
            val repo=MessageRepository(db);assertTrue(repo.insert(message))
            val wire=TestWire(peer)
            val inventory=MutableStateFlow(InventoryState(peerId=peer,sessionId="one",sent=true,
                localReady=true,remoteReceived=true,missingOnPeer=listOf(message.messageId)))
            val engine=DeliveryEngine(local,repo,wire,scope,NetworkLogger(),inventory,now={now})
            engine.retryPending()
            engine.receive(peer,WirePacket(PacketType.PREDICTABILITY_SUMMARY,deviceId=peer),"one")
            engine.retryPending()
            assertEquals(1,wire.sent.count{it.type==PacketType.MESSAGE})
            assertEquals(2,repo.get(message.messageId)!!.reservedCopies)
            wire.state.value=SocketState();engine.retryPending()
            wire.state.value=SocketState("Connected",peer,sessionId="two")
            inventory.value=inventory.value.copy(sessionId="two")
            engine.retryPending()
            engine.receive(peer,WirePacket(PacketType.PREDICTABILITY_SUMMARY,deviceId=peer),"two")
            engine.retryPending()
            assertEquals(listOf(2,2),wire.sent.filter{it.type==PacketType.MESSAGE}.map{it.message!!.copiesRemaining})
            assertEquals(4,repo.get(message.messageId)!!.copiesRemaining)
            assertEquals(2,repo.get(message.messageId)!!.reservedCopies)
            engine.receive(peer,WirePacket(PacketType.ACK,messageId=message.messageId,ackStatus=MessageStatus.CARRIED),"two")
            assertEquals(2,repo.get(message.messageId)!!.copiesRemaining)
            assertEquals(0,repo.get(message.messageId)!!.reservedCopies)
        }finally{scope.cancel();db.close()}
    }
    @Test fun sourceRelayAndFinalDestinationKeepOneMessageIdInRealRoom():Unit=runBlocking{
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val sourceDb=Room.inMemoryDatabaseBuilder(ctx,DinoblogDatabase::class.java).build()
        val carrierDb=Room.inMemoryDatabaseBuilder(ctx,DinoblogDatabase::class.java).build()
        val destinationDb=Room.inMemoryDatabaseBuilder(ctx,DinoblogDatabase::class.java).build()
        try{
            val source=id();val carrier=id();val destination=id();val now=System.currentTimeMillis()
            val message=Message(id(),source,destination,"through B",now,60,MessageStatus.PENDING,
                copiesRemaining=DtnConfig.INITIAL_SPRAY_COPIES)
            val a=MessageRepository(sourceDb);val b=MessageRepository(carrierDb);val c=MessageRepository(destinationDb)
            assertTrue(a.insert(message))
            val split=a.reserveSpray(message.messageId,source,carrier,4,now)!!
            assertEquals(2,split.copiesGiven)
            val carried=message.copy(status=MessageStatus.CARRIED,hopCount=1,copiesRemaining=2)
            assertTrue(b.commitReceived(carried,null,carrier,now,100))
            assertTrue(a.confirmSpray(message.messageId,carrier,0,2,2))
            assertTrue(message.messageId in b.forwardableMessageIds(now))
            val delivered=carried.copy(status=MessageStatus.RECEIVED,hopCount=2)
            assertTrue(c.commitReceived(delivered,DeliveryReceipt.create(delivered,destination,now+1),destination,now+1,100))
            assertEquals(source,c.get(message.messageId)!!.sourceId)
            assertNotNull(c.receipt(message.messageId))
        }finally{sourceDb.close();carrierDb.close();destinationDb.close()}
    }
    @Test fun migrationMakesAllLegacyRowsWaitWhilePreservingReceiptAndHistory():Unit=runBlocking{
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext;val name="spray-migrate-${id()}.db"
        try{
            migration.createDatabase(name,3).use{db->
                for(status in listOf("PENDING","FORWARDED","CARRIED","RECEIVED","DELIVERED","EXPIRED")) db.execSQL("INSERT INTO messages (messageId,senderId,receiverId,payload,timestamp,ttl,status,expiresAt,hopCount,hopLimit) VALUES (?, 'a','d','history',1000,60,?,61000,1,8)",arrayOf(status,status))
                db.execSQL("INSERT INTO delivery_receipts (originalMessageId,sourceId,destinationId,deliveredAt,originalExpiresAt,createdByDeviceId) VALUES ('DELIVERED','a','d',2000,61000,'d')")
                db.execSQL("INSERT INTO peers (deviceId,name,lastSeen) VALUES ('b','Phone B',1000)")
            }
            migration.runMigrationsAndValidate(name, 9, true,DinoblogDatabase.MIGRATION_3_4, DinoblogDatabase.MIGRATION_4_5, DinoblogDatabase.MIGRATION_5_6, DinoblogDatabase.MIGRATION_6_7, DinoblogDatabase.MIGRATION_7_8, DinoblogDatabase.MIGRATION_8_9).close()
            val db=Room.databaseBuilder(ctx,DinoblogDatabase::class.java,name).build()
            try{
                for(status in MessageStatus.entries){val m=db.messages().get(status.name)!!;assertEquals(1,m.copiesRemaining);assertEquals("history",m.payload);assertEquals(1,m.hopCount)}
                assertNotNull(db.receipts().get("DELIVERED"))
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM peers").use{it.moveToFirst();assertEquals(1,it.getInt(0))}
            }finally{db.close()}
        }finally{ctx.deleteDatabase(name)}
    }
    @Test fun reservationsSurviveReopenAndDuplicateCannotMintBudget():Unit=runBlocking{
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext;val name="spray-reopen-${id()}.db"
        val m=row();val local=id();val peer=id()
        try{
            val first=Room.databaseBuilder(ctx,DinoblogDatabase::class.java,name).build()
            try{val repo=MessageRepository(first);repo.insert(m);val split=repo.reserveSpray(m.messageId,local,peer,4,2000)!!;assertEquals(2,split.copiesGiven);assertEquals(2,split.retained.copiesRemaining)}finally{first.close()}
            val second=Room.databaseBuilder(ctx,DinoblogDatabase::class.java,name).build()
            try{
                val repo=MessageRepository(second);assertEquals(4,repo.get(m.messageId)!!.copiesRemaining)
                assertEquals(2,repo.get(m.messageId)!!.reservedCopies)
                assertNull(repo.reserveSpray(m.messageId,local,id(),4,2000))
                assertTrue(repo.commitReceived(m.copy(copiesRemaining=2),null,local,2000,1))
                assertTrue(repo.confirmSpray(m.messageId,peer,1,2,2))
                assertEquals(2,repo.get(m.messageId)!!.copiesRemaining)
                assertEquals(0,repo.get(m.messageId)!!.reservedCopies)
                val nextPeer=id()
                val split=repo.reserveSpray(m.messageId,local,nextPeer,2,2000)!!;assertEquals(1,split.copiesGiven)
                assertTrue(repo.confirmSpray(m.messageId,nextPeer,1,1,1))
                assertEquals(1,repo.get(m.messageId)!!.copiesRemaining)
                assertEquals(1,repo.get(m.messageId)!!.hopCount)
                repo.saveReceipt(DeliveryReceipt.create(m,m.destinationId,2000))
                assertNull(repo.reserveSpray(m.messageId,local,id(),1,2000))
                assertFalse(repo.restoreSpray(m.messageId,1,1))
            }finally{second.close()}
        }finally{ctx.deleteDatabase(name)}
    }
    @Test fun atomicQuotaRejectsConcurrentRelayButExemptsFinalAndSourceAndReclaimsSafely():Unit=runBlocking{
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(ctx,DinoblogDatabase::class.java).build()
        try{
            val repo=MessageRepository(db);val local=id();val ms=listOf(row(),row())
            val outcomes=ms.map{m->async{runCatching{repo.commitReceived(m,null,local,2000,1)}}}.awaitAll()
            assertEquals(1,outcomes.count{it.isSuccess});assertEquals(1,outcomes.count{it.exceptionOrNull() is RelayBufferFullException})
            assertEquals(1,repo.relayBufferUsage(local,2000))
            val final=row().copy(destinationId=local,status=MessageStatus.RECEIVED)
            val own=row().copy(sourceId=local,status=MessageStatus.PENDING)
            repo.insert(own)
            repo.commitReceived(final,DeliveryReceipt.create(final,local,2000),local,2000,1)
            assertNotNull(repo.get(final.messageId));assertEquals(1,repo.relayBufferUsage(local,2000))
            val carried=ms.first{repo.get(it.messageId)!=null};val receipt=DeliveryReceipt.create(carried,carried.destinationId,2000)
            repo.saveReceipt(receipt)
            val expired=row().copy(ttl=1,expiresAt=2000);repo.insert(expired)
            val incoming=row();assertTrue(repo.commitReceived(incoming,null,local,2000,1))
            assertNull(repo.get(carried.messageId));assertNull(repo.get(expired.messageId))
            assertEquals(receipt,repo.receipt(carried.messageId));assertNotNull(repo.get(own.messageId));assertNotNull(repo.get(final.messageId))
            assertFalse(repo.commitReceived(carried,null,local,2000,1))
            assertEquals(1,repo.relayBufferUsage(local,2000))
        }finally{db.close()}
    }
}
