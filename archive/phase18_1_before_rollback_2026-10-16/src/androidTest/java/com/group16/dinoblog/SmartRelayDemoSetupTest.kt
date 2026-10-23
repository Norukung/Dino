package com.group16.dinoblog

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.model.*
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.repository.MessageRepository
import com.group16.dinoblog.util.DeviceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicit experimental setup only. Ordinary instrumentation skips this test and never touches the app DB. */
@RunWith(AndroidJUnit4::class)
class SmartRelayDemoSetupTest {
    @Test fun seedOnlyWhenExplicitlyRequested(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val role = args.getString("phase8DemoRole")
        assumeTrue("Requires explicit phase8DemoRole A/B/C", role != null)
        require(role in listOf("A", "B", "C"))
        val destination = requireNotNull(args.getString("phase8DestinationId"))
        require(WireProtocol.isUuid(destination))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val local = DeviceIdentity(context).id
        require(local != destination)
        val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, "dinoblog.db")
            .addMigrations(DinoblogDatabase.MIGRATION_1_2, DinoblogDatabase.MIGRATION_2_3,
                DinoblogDatabase.MIGRATION_3_4, DinoblogDatabase.MIGRATION_4_5, DinoblogDatabase.MIGRATION_5_6, DinoblogDatabase.MIGRATION_6_7,
                DinoblogDatabase.MIGRATION_7_8, DinoblogDatabase.MIGRATION_8_9, DinoblogDatabase.MIGRATION_9_10, DinoblogDatabase.MIGRATION_10_11).build()
        try {
            val at = System.currentTimeMillis()
            val score = when (role) { "A" -> 0.20; "B" -> 0.25; else -> 0.85 }
            db.predictabilities().save(DeliveryPredictability(destination, score, at))
            if (role == "A") {
                // This test-only demo branch has two copies; normal source creation still uses DtnConfig.
                val message = Message(UUID.randomUUID().toString(), local, destination, "MSG001 Phase 8 demo", at,
                    86400, MessageStatus.PENDING, copiesRemaining = 2)
                check(MessageRepository(db).insert(message))
                Log.i("DinoblogDemo", "Demo source local=$local destination=$destination messageId=${message.messageId} copies=2")
            }
            Log.i("DinoblogDemo", "Experimental routing state role=$role local=$local destination=$destination score=$score")
        } finally { db.close() }
    }
}
