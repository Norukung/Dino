package com.group16.dinoblog.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.Peer

@Database(entities = [Message::class, Peer::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class DinoblogDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun peers(): PeerDao
}
