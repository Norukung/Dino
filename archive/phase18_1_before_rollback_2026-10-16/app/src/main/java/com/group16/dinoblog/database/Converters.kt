package com.group16.dinoblog.database

import androidx.room.TypeConverter
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.RelayPolicy
import com.group16.dinoblog.model.RevokeOutcome

class Converters {
    @TypeConverter fun fromStatus(status: MessageStatus): String = status.name
    @TypeConverter fun toStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
    @TypeConverter fun fromContentType(type: MessageContentType): String = type.name
    @TypeConverter fun toContentType(value: String): MessageContentType = MessageContentType.valueOf(value)
    @TypeConverter fun fromRelayPolicy(value: RelayPolicy): String = value.name
    @TypeConverter fun toRelayPolicy(value: String): RelayPolicy = RelayPolicy.valueOf(value)
    @TypeConverter fun fromRevokeOutcome(value: RevokeOutcome): String = value.name
    @TypeConverter fun toRevokeOutcome(value: String): RevokeOutcome = RevokeOutcome.valueOf(value)
}
