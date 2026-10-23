package com.group16.dinoblog.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

data class DiscoveredPeer(val name: String, val address: String, val status: String)

@Entity(tableName = "peers")
data class Peer(
    @PrimaryKey val deviceId: String,
    val name: String,
    val lastSeen: Long,
    @ColumnInfo(defaultValue = "NULL") val localAlias: String? = null,
) {
    val displayName: String get() = localAlias?.takeIf(String::isNotBlank)
        ?: name.takeIf(String::isNotBlank) ?: "DinoBlog User"
}

