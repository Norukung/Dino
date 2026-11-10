package com.group16.dinoblog.model

/** Android-provided hardware names are display metadata, never protocol identity. */
object HardwareNames {
    const val UNAVAILABLE = "Device name unavailable"

    fun normalize(value: String?): String? = value?.codePoints()?.toArray()?.let { points ->
        buildString {
            for (point in points) {
                if (Character.isISOControl(point)) append(' ') else appendCodePoint(point)
            }
        }.trim().replace(Regex("\\s+"), " ").takeIf(String::isNotEmpty)
    }
}

object NearbyPeerLabels {
    fun title(peer: DiscoveredPeer, connectedAddress: String?, dinoName: String?): String =
        if (peer.address == connectedAddress && !dinoName.isNullOrBlank()) dinoName else peer.name
}
