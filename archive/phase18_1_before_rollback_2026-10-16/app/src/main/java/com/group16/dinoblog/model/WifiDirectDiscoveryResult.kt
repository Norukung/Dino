package com.group16.dinoblog.model

/** Transient Android Wi-Fi Direct observation. The address is only a connection endpoint. */
data class WifiDirectDiscoveryResult(
    val deviceName: String,
    val addressInternal: String,
    val status: WifiDirectPeerStatus,
)

enum class WifiDirectPeerStatus(val label: String) {
    AVAILABLE("Available"),
    INVITED("Connecting…"),
    CONNECTED("Connected"),
    FAILED("Failed"),
    UNAVAILABLE("Unavailable"),
}

/** Sanitizes a platform-controlled name for display, never for identity or persistence. */
object WifiDirectDeviceNames {
    const val FALLBACK = "Wi-Fi Direct device"
    private const val MAX_CODE_POINTS = 48

    fun sanitize(raw: String?): String {
        if (raw == null) return FALLBACK
        val cleaned = buildString {
            raw.codePoints().forEach { codePoint ->
                if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint) ||
                    Character.isSpaceChar(codePoint) || codePoint in 0x202a..0x202e ||
                    codePoint in 0x2066..0x2069 || codePoint == 0x200e || codePoint == 0x200f)
                    append(' ')
                else appendCodePoint(codePoint)
            }
        }.trim().replace(Regex(" +"), " ")
        if (cleaned.isEmpty()) return FALLBACK
        if (cleaned.codePointCount(0, cleaned.length) <= MAX_CODE_POINTS) return cleaned
        return buildString {
            cleaned.codePoints().limit(MAX_CODE_POINTS.toLong()).forEach { appendCodePoint(it) }
            append('…')
        }
    }
}

/** Correlation requires the selected BLE handoff's explicit Wi-Fi endpoint, never a name. */
object NearbyWifiCorrelation {
    fun forHandoff(bleToken: String?, wifiAddress: String?, current: List<WifiDirectDiscoveryResult>,
        selectedSnapshot: WifiDirectDiscoveryResult?): WifiDirectDiscoveryResult? {
        if (bleToken == null || wifiAddress == null) return null
        return current.firstOrNull { it.addressInternal == wifiAddress }
            ?: selectedSnapshot?.takeIf { it.addressInternal == wifiAddress }
    }
}
