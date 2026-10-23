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
    val awaitingHello: Boolean = false,
    /** Runtime token, new for every identified TCP session (even the same peer/IP). */
    val sessionId: String? = null,
    /** Null means no identified peer; legacy HELLO is interpreted as capable. */
    val peerRelayEnabled: Boolean? = null,
) {
    // One source of truth: retain peerId for existing chat/retry consumers.
    val connectedPeerDeviceId: String? get() = peerId
    val connected: Boolean get() = peerId != null
    val peerIdentityLabel: String get() = connectedPeerDeviceId
        ?: if (awaitingHello) "Waiting for HELLO..." else "None"
}
