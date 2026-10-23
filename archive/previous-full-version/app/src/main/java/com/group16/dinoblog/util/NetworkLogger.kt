package com.group16.dinoblog.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PacketLog(
    val time: Long,
    val direction: String,
    val detail: String,
    val messageId: String? = null,
    val ttl: Long? = null,
    val status: String? = null,
)

class NetworkLogger {
    private val entries = MutableStateFlow<List<PacketLog>>(emptyList())
    val logs = entries.asStateFlow()
    fun log(direction: String, detail: String, messageId: String? = null, ttl: Long? = null, status: String? = null) {
        entries.update { (listOf(PacketLog(System.currentTimeMillis(), direction, detail, messageId, ttl, status)) + it).take(250) }
    }
    fun clear() { entries.value = emptyList() }
}
