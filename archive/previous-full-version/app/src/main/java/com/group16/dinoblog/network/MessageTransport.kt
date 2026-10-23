package com.group16.dinoblog.network

import com.group16.dinoblog.model.SocketState
import kotlinx.coroutines.flow.StateFlow

interface MessageTransport {
    val state: StateFlow<SocketState>
    var onPacket: suspend (String, WirePacket) -> Unit
    suspend fun send(peerId: String, packet: WirePacket): Boolean
}
