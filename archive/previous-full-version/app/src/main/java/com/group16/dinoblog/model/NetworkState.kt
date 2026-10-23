package com.group16.dinoblog.model

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
    val peerId: String? = null,
    val peerName: String? = null,
    val localIp: String? = null,
    val remoteIp: String? = null,
)
