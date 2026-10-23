package com.group16.dinoblog.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class Peer(
    @PrimaryKey val deviceId: String,
    val name: String,
    val lastSeen: Long,
)

data class DiscoveredPeer(val name: String, val address: String, val status: String)
