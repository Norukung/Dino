package com.group16.dinoblog

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {
    @Test fun deduplicationAckGuardsAndExpirationUseActualSqlite() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        try {
            val dao = db.messages()
            val message = Message(UUID.randomUUID().toString(), "a", "b", "hello", 1_000, 1, MessageStatus.PENDING)
            assertTrue(dao.insert(message) != -1L)
            assertEquals(-1L, dao.insert(message))
            assertEquals(0, dao.markDelivered(message.messageId, "a", "wrong-peer"))
            assertEquals(1, dao.pending("a", "b").size)
            assertEquals(1, dao.expire(2_000))
            assertEquals(MessageStatus.EXPIRED, dao.get(message.messageId)!!.status)
            assertEquals(1, dao.markDelivered(message.messageId, "a", "b"))
            assertEquals(MessageStatus.DELIVERED, dao.get(message.messageId)!!.status)
            assertEquals(0, dao.expire(3_000))
        } finally { db.close() }
    }
}
