package com.group16.dinoblog

import androidx.room.Room
import androidx.room.withTransaction
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.dtn.*
import com.group16.dinoblog.model.*
import com.group16.dinoblog.repository.MessageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PredictabilityDatabaseTest {
    @get:Rule val migration=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),DinoblogDatabase::class.java)
    private fun id()=UUID.randomUUID().toString()
    @Test fun migrationPreservesBudgetMessagesAndReceiptsWithEmptyRoutingTable():Unit=runBlocking{
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext;val name="predict-migrate-${id()}.db"
        val msg=id();val source=id();val dest=id()
        try{
            migration.createDatabase(name,4).use{db->
                db.execSQL("INSERT INTO messages (messageId,senderId,receiverId,payload,timestamp,ttl,status,expiresAt,hopCount,hopLimit,copiesRemaining) VALUES (?,?,?,'history',1000,60,'DELIVERED',61000,1,8,2)",arrayOf(msg,source,dest))
                db.execSQL("INSERT INTO delivery_receipts (originalMessageId,sourceId,destinationId,deliveredAt,originalExpiresAt,createdByDeviceId) VALUES (?,?,?,2000,61000,?)",arrayOf(msg,source,dest,dest))
                db.execSQL("INSERT INTO peers (deviceId,name,lastSeen) VALUES (?,'Phone',1000)",arrayOf(dest))
            }
            migration.runMigrationsAndValidate(name, 11, true,DinoblogDatabase.MIGRATION_4_5,DinoblogDatabase.MIGRATION_5_6,DinoblogDatabase.MIGRATION_6_7,DinoblogDatabase.MIGRATION_7_8,DinoblogDatabase.MIGRATION_8_9,DinoblogDatabase.MIGRATION_9_10, DinoblogDatabase.MIGRATION_10_11).close()
            val db=Room.databaseBuilder(ctx,DinoblogDatabase::class.java,name).build()
            try{
                assertTrue(db.predictabilities().all().isEmpty());assertEquals(2,db.messages().get(msg)!!.copiesRemaining)
                assertEquals("history",db.messages().get(msg)!!.payload);assertNotNull(db.receipts().get(msg))
                assertEquals(0.0,MessageRepository(db).predictability(dest,2000),0.0)
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM peers").use{it.moveToFirst();assertEquals(1,it.getInt(0))}
            }finally{db.close()}
        }finally{ctx.deleteDatabase(name)}
    }
    @Test fun encounterAndLazyAgingSurviveDatabaseCloseReopen():Unit=runBlocking{
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext;val name="predict-reopen-${id()}.db";val peer=id()
        try{
            val first=Room.databaseBuilder(ctx,DinoblogDatabase::class.java,name).build()
            try{val r=MessageRepository(first);assertEquals(0.75,r.recordEncounter(peer,1000).predictability,1e-12);assertEquals(0.9375,r.recordEncounter(peer,1000).predictability,1e-12)}finally{first.close()}
            val second=Room.databaseBuilder(ctx,DinoblogDatabase::class.java,name).build()
            try{
                val repo=MessageRepository(second);assertEquals(0.9375,repo.predictability(peer,1000),1e-12)
                val later=1000+DtnConfig.AGING_INTERVAL_MS
                assertEquals(0.9375*0.98,repo.predictability(peer,later),1e-12)
                assertEquals(later,second.predictabilities().get(peer)!!.lastUpdatedAt)
                assertEquals(0.9375*0.98,repo.predictability(peer,1000),1e-12)
            }finally{second.close()}
        }finally{ctx.deleteDatabase(name)}
    }
    @Test fun transitivityAndReservationReadCurrentAgedScoreInOneTransaction():Unit=runBlocking{
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext;val db=Room.inMemoryDatabaseBuilder(ctx,DinoblogDatabase::class.java).build()
        try{
            val local=id();val peer=id();val dest=id();val strong=id();val repo=MessageRepository(db)
            db.predictabilities().save(DeliveryPredictability(peer,0.8,2000))
            db.predictabilities().save(DeliveryPredictability(dest,0.05,2000));db.predictabilities().save(DeliveryPredictability(strong,0.7,2000))
            repo.learnTransitive(local,peer,listOf(PredictabilityEntry(dest,0.9),PredictabilityEntry(strong,0.9)),2000)
            assertEquals(0.18,repo.predictability(dest,2000),1e-12);assertEquals(0.7,repo.predictability(strong,2000),1e-12)
            val m=Message(id(),local,dest,"smart",1000,60,MessageStatus.PENDING,copiesRemaining=4);repo.insert(m)
            assertNull(repo.reserveSmartSpray(m.messageId,local,peer,4,0.19,2000));assertEquals(4,repo.get(m.messageId)!!.copiesRemaining)
            val split=repo.reserveSmartSpray(m.messageId,local,peer,4,0.8,2000)!!;assertEquals(2,split.copiesGiven)
            db.predictabilities().save(DeliveryPredictability(dest,0.9,2000))
            assertNull(repo.reserveSmartSpray(m.messageId,local,peer,2,0.8,2000));assertEquals(4,repo.get(m.messageId)!!.copiesRemaining)
            repo.saveReceipt(DeliveryReceipt.create(m,dest,2000))
            assertNull(repo.reserveSmartSpray(m.messageId,local,peer,2,1.0,2000));assertEquals(0,repo.get(m.messageId)!!.hopCount)
            assertTrue(runCatching{repo.learnTransitive(local,peer,listOf(PredictabilityEntry(dest,4.5)),2000)}.isFailure)
            assertEquals(0.9,repo.predictability(dest,2000),1e-12)
        }finally{db.close()}
    }
    @Test fun localTableAndWireSummaryStayBoundedAndRelevantDestinationsArePrioritized():Unit=runBlocking{
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext;val db=Room.inMemoryDatabaseBuilder(ctx,DinoblogDatabase::class.java).build()
        try{
            val local=id();val relevant=id();val repo=MessageRepository(db)
            db.withTransaction{repeat(100){db.predictabilities().save(DeliveryPredictability(id(),0.8,2000))}}
            db.predictabilities().save(DeliveryPredictability(relevant,0.002,2000))
            repo.insert(Message(id(),local,relevant,"relevant",1000,60,MessageStatus.PENDING,copiesRemaining=2))
            val summary=repo.predictabilitySummary(local,emptySet(),2000)
            assertEquals(64,summary.size);assertEquals(relevant,summary.first().deviceId)
            db.withTransaction{repeat(530){db.predictabilities().save(DeliveryPredictability(id(),0.8,2000))}}
            repo.predictabilitySummary(local,emptySet(),2000)
            assertTrue(db.predictabilities().all().size<=DtnConfig.MAX_LOCAL_PREDICTABILITIES)
        }finally{db.close()}
    }
}
