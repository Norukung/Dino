package com.group16.dinoblog.network

import com.group16.dinoblog.model.SocketState
import kotlinx.coroutines.flow.StateFlow

interface MessageTransport {
    val state: StateFlow<SocketState>
    var onPacket: suspend (String, WirePacket) -> Unit
    suspend fun send(peerId: String, packet: WirePacket): Boolean
    // Session-scoped control packets must never spill into a reconnect to the same ID.
    suspend fun sendToSession(peerId: String, sessionId: String, packet: WirePacket): Boolean {
        if (state.value.peerId != peerId || state.value.sessionId != sessionId) return false
        return send(peerId, packet)
    }
}
