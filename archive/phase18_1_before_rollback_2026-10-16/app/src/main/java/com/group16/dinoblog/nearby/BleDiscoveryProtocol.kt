package com.group16.dinoblog.nearby

import java.security.SecureRandom
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import com.group16.dinoblog.model.DisplayNames

/** Anonymous, versioned service data. It must never contain a stable application identity. */
object BleDiscoveryProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("9e8c5d36-47b1-4f69-9f20-b44fd8cd4d6a")
    /** Separate service-data key so Android's ScanRecord retains both token and name. */
    val NAME_SERVICE_UUID: UUID = UUID.fromString("9e8c5d36-47b1-4f69-9f20-b44fd8cd4d6b")
    const val VERSION = 1
    const val TOKEN_BYTES = 8
    const val SERVICE_DATA_BYTES = 10
    // Service-data AD structure: length + type + UUID + anonymous payload.
    const val ADVERTISEMENT_DATA_BYTES = 28
    // Older stacks may also include the three-byte system Flags AD structure.
    const val MAX_LEGACY_ADVERTISEMENT_BYTES = 31
    const val SCAN_RESPONSE_OVERHEAD_BYTES = 18 // AD length, type, 128-bit UUID
    const val MAX_NEARBY_NAME_BYTES = MAX_LEGACY_ADVERTISEMENT_BYTES - SCAN_RESPONSE_OVERHEAD_BYTES
    const val ROTATION_MS = 10 * 60 * 1000L
    const val SCAN_MS = 12_000L
    const val RESULT_EXPIRY_MS = 15_000L
    const val FLAG_SECURE_IDENTITY = 1
    const val FLAG_WIFI_DIRECT = 2
    const val FLAGS = FLAG_SECURE_IDENTITY or FLAG_WIFI_DIRECT
    private val random = SecureRandom()

    fun newToken(): ByteArray = ByteArray(TOKEN_BYTES).also(random::nextBytes)

    fun encode(token: ByteArray, flags: Int = FLAGS): ByteArray {
        require(token.size == TOKEN_BYTES && flags in 0..255)
        return byteArrayOf(VERSION.toByte()) + token + flags.toByte()
    }

    fun decode(data: ByteArray?, nameData: ByteArray? = null): DiscoveryAdvertisement? {
        if (data == null || data.size != SERVICE_DATA_BYTES || data[0].toInt() != VERSION) return null
        val flags = data[9].toInt() and 0xff
        if (flags and FLAGS.inv() != 0) return null
        return DiscoveryAdvertisement(data.copyOfRange(1, 9).toHex(), VERSION, flags,
            decodeName(nameData), nameData != null)
    }

    /** A single NUL byte signals anonymous presence; otherwise this is strict UTF-8. */
    fun encodeName(displayName: String, visible: Boolean): ByteArray {
        if (!visible) return byteArrayOf(0)
        val safe = DisplayNames.normalize(displayName) ?: DisplayNames.FALLBACK
        val text = truncateUtf8ToByteLimit(safe, MAX_NEARBY_NAME_BYTES)
        return (text.ifEmpty { DisplayNames.FALLBACK }).toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeName(data: ByteArray?): String? {
        if (data == null || data.size == 1 && data[0] == 0.toByte()) return null
        if (data.size !in 1..MAX_NEARBY_NAME_BYTES) return null
        return try {
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString()
            text.takeIf { DisplayNames.normalize(it) == it }
        } catch (_: CharacterCodingException) { null }
    }

    fun truncateUtf8ToByteLimit(text: String, maxBytes: Int): String {
        require(maxBytes >= 0)
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        val clusters = mutableListOf<String>()
        val cluster = StringBuilder()
        var start = boundaries.first()
        var end = boundaries.next()
        while (end != BreakIterator.DONE) {
            val piece = text.substring(start, end)
            if (cluster.isNotEmpty() && !joinsPrevious(cluster, piece)) {
                clusters += cluster.toString()
                cluster.clear()
            }
            cluster.append(piece)
            start = end
            end = boundaries.next()
        }
        if (cluster.isNotEmpty()) clusters += cluster.toString()
        val result = StringBuilder()
        for (part in clusters) {
            if ((result.toString() + part).toByteArray(StandardCharsets.UTF_8).size > maxBytes) break
            result.append(part)
        }
        return result.toString().trim()
    }

    private fun joinsPrevious(previous: StringBuilder, next: String): Boolean {
        val first = next.codePointAt(0)
        val type = Character.getType(first)
        return previous.codePointBefore(previous.length) == 0x200d || first == 0x200d ||
            type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() || first in 0xfe00..0xfe0f || first in 0x1f3fb..0x1f3ff
    }

    fun shortCode(token: String): String = token.takeLast(4).uppercase(Locale.ROOT)

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

data class DiscoveryAdvertisement(val token: String, val version: Int, val flags: Int,
    val nearbyDisplayName: String? = null, val nameUpdatePresent: Boolean = false)

enum class SignalLevel { STRONG, MEDIUM, WEAK }

data class NearbyDiscoveryResult(
    val discoveryToken: String,
    val rssi: Int,
    val smoothedRssi: Double,
    val signalLevel: SignalLevel,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val protocolVersion: Int,
    val capabilityFlags: Int,
    val nearbyDisplayName: String? = null,
    val shortCode: String = BleDiscoveryProtocol.shortCode(discoveryToken),
)

/** In-memory scan results. Tokens are neither contacts nor authenticated identities. */
class NearbyResultTracker {
    private val results = mutableMapOf<String, NearbyDiscoveryResult>()
    fun observe(ad: DiscoveryAdvertisement, rssi: Int, now: Long): NearbyDiscoveryResult {
        val old = results[ad.token]
        val smooth = if (old == null) rssi.toDouble() else 0.25 * rssi + 0.75 * old.smoothedRssi
        val result = NearbyDiscoveryResult(ad.token, rssi, smooth, level(smooth, old?.signalLevel),
            old?.firstSeenAt ?: now, now, ad.version, ad.flags,
            if (ad.nameUpdatePresent) ad.nearbyDisplayName else old?.nearbyDisplayName)
        results[ad.token] = result
        return result
    }

    /** A separately delivered scan response may update the same temporary row. */
    fun updateName(token: String, nameData: ByteArray, now: Long): NearbyDiscoveryResult? {
        val old = results[token] ?: return null
        return old.copy(nearbyDisplayName = BleDiscoveryProtocol.decodeName(nameData), lastSeenAt = now)
            .also { results[token] = it }
    }

    fun expire(now: Long): Int {
        val before = results.size
        results.entries.removeAll { now - it.value.lastSeenAt >= BleDiscoveryProtocol.RESULT_EXPIRY_MS }
        return before - results.size
    }

    fun snapshot(): List<NearbyDiscoveryResult> = results.values.sortedWith(
        compareByDescending<NearbyDiscoveryResult> { it.smoothedRssi }.thenByDescending { it.lastSeenAt }.thenBy { it.discoveryToken })

    fun clear() = results.clear()
    fun remove(token: String) { results.remove(token) }

    private fun level(rssi: Double, previous: SignalLevel?): SignalLevel = when (previous) {
        SignalLevel.STRONG -> if (rssi >= -63) SignalLevel.STRONG else if (rssi >= -75) SignalLevel.MEDIUM else SignalLevel.WEAK
        SignalLevel.MEDIUM -> if (rssi >= -57) SignalLevel.STRONG else if (rssi < -78) SignalLevel.WEAK else SignalLevel.MEDIUM
        SignalLevel.WEAK -> if (rssi >= -57) SignalLevel.STRONG else if (rssi >= -72) SignalLevel.MEDIUM else SignalLevel.WEAK
        null -> when { rssi >= -60 -> SignalLevel.STRONG; rssi >= -75 -> SignalLevel.MEDIUM; else -> SignalLevel.WEAK }
    }
}
