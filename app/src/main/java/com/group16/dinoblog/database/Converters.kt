package com.group16.dinoblog.database

import androidx.room.TypeConverter
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.MessageContentType

class Converters {
    @TypeConverter fun fromStatus(status: MessageStatus): String = status.name
    @TypeConverter fun toStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
    @TypeConverter fun fromContentType(type: MessageContentType): String = type.name
    @TypeConverter fun toContentType(value: String): MessageContentType = MessageContentType.valueOf(value)
}
