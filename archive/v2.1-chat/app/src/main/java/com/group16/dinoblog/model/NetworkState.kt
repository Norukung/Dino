package com.group16.dinoblog.model

data class DiscoveredPeer(val name: String, val address: String, val status: String)

data class NetworkState(
    val supported: Boolean = true,
    val wifiEnabled: Boolean = false,
    val discovering: Boolean = false,
    val peers: List<DiscoveredPeer> = emptyList(),
    val groupFormed: Boolean = false,
    val isGroupOwner: Boolean = false,
    val groupOwnerIp: String? = null,
    val wifiStatus: String = "Waiting for permissions",
    val error: String? = null,
)

data class SocketState(
    val status: String = "Disconnected",
    val connected: Boolean = false,
    val localIp: String? = null,
    val remoteIp: String? = null,
)

/** UI history only; these fields are not added to the plain UTF-8 wire format. */
data class ChatMessage(
    val id: Long,
    val text: String,
    val timestamp: Long,
    val outgoing: Boolean,
)
