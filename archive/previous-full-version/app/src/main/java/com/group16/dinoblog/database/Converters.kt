package com.group16.dinoblog.database

import androidx.room.TypeConverter
import com.group16.dinoblog.model.MessageStatus

class Converters {
    @TypeConverter fun fromStatus(status: MessageStatus): String = status.name
    @TypeConverter fun toStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
}
