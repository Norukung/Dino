package com.group16.dinoblog.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PacketLog(val time: Long, val direction: String, val detail: String)

class NetworkLogger {
    private val entries = MutableStateFlow<List<PacketLog>>(emptyList())
    val logs = entries.asStateFlow()
    fun log(direction: String, detail: String) {
        entries.update { (listOf(PacketLog(System.currentTimeMillis(), direction, detail)) + it).take(100) }
    }
    fun clear() { entries.value = emptyList() }
}
