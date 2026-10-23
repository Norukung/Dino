package com.group16.dinoblog.nearby

import com.group16.dinoblog.repository.NearbyConnectionMethod
import java.nio.ByteBuffer
import java.util.UUID

/** Small untrusted BLE control messages; no application identity or DTN data. */
object BleHandoffProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("b6d14b57-5f5a-4937-8e49-76b47966e3ab")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("cc6ed60a-9114-4224-8cc1-5e9246fb60a7")
    const val TIMEOUT_MS = 45_000L
    private const val VERSION = 1
    private const val PENDING = 0
    private const val ACCEPTED = 1
    private const val REJECTED = 2

    data class Request(val sessionId: UUID, val method: NearbyConnectionMethod)
    data class Offer(val accepted: Boolean, val pending: Boolean, val transport: NearbyConnectionMethod?,
        val wifiAddress: String?, val bluetoothSupported: Boolean)

    fun encodeRequest(request: Request): ByteArray = ByteBuffer.allocate(18)
        .put(VERSION.toByte()).putLong(request.sessionId.mostSignificantBits)
        .putLong(request.sessionId.leastSignificantBits).put(methodByte(request.method)).array()

    fun decodeRequest(bytes: ByteArray?): Request? {
        if (bytes == null || bytes.size != 18 || bytes[0].toInt() != VERSION) return null
        val buffer = ByteBuffer.wrap(bytes)
        buffer.get()
        val id = UUID(buffer.long, buffer.long)
        val method = decodeMethod(buffer.get()) ?: return null
        return Request(id, method)
    }

    fun pending(): ByteArray = response(PENDING, null, null, false)
    fun reject(): ByteArray = response(REJECTED, null, null, false)
    fun accept(transport: NearbyConnectionMethod, wifiAddress: String?, bluetoothSupported: Boolean): ByteArray =
        response(ACCEPTED, transport, wifiAddress, bluetoothSupported)

    private fun response(status: Int, transport: NearbyConnectionMethod?, address: String?, bluetooth: Boolean): ByteArray {
        val mac = address?.split(':')?.takeIf { it.size == 6 }?.map { it.toIntOrNull(16) }
        val bytes = ByteArray(10)
        bytes[0] = VERSION.toByte(); bytes[1] = status.toByte()
        bytes[2] = transport?.let(::methodByte) ?: 0
        bytes[3] = if (bluetooth) 1 else 0
        if (mac != null && mac.all { it != null && it in 0..255 })
            mac.forEachIndexed { index, part -> bytes[4 + index] = part!!.toByte() }
        return bytes
    }

    fun decodeOffer(bytes: ByteArray?): Offer? {
        if (bytes == null || bytes.size != 10 || bytes[0].toInt() != VERSION || bytes[1].toInt() !in 0..2 ||
            bytes[3].toInt() !in 0..1) return null
        val status = bytes[1].toInt()
        val method = if (status == ACCEPTED) decodeMethod(bytes[2])?.takeIf { it != NearbyConnectionMethod.AUTO } else null
        if (status == ACCEPTED && method == null) return null
        val mac = bytes.copyOfRange(4, 10)
        val address = if (method == NearbyConnectionMethod.WIFI_DIRECT && mac.any { it.toInt() != 0 })
            mac.joinToString(":") { "%02x".format(it.toInt() and 0xff) } else null
        if (method == NearbyConnectionMethod.WIFI_DIRECT && address == null) return null
        return Offer(status == ACCEPTED, status == PENDING, method, address, bytes[3].toInt() == 1)
    }

    /** AUTO prefers Wi-Fi when both sides permit it and a P2P endpoint is known. */
    fun select(local: NearbyConnectionMethod, remote: NearbyConnectionMethod,
        wifiAvailable: Boolean): NearbyConnectionMethod? = when {
        local == NearbyConnectionMethod.WIFI_DIRECT && remote == NearbyConnectionMethod.BLUETOOTH -> null
        local == NearbyConnectionMethod.BLUETOOTH && remote == NearbyConnectionMethod.WIFI_DIRECT -> null
        local == NearbyConnectionMethod.BLUETOOTH || remote == NearbyConnectionMethod.BLUETOOTH -> NearbyConnectionMethod.BLUETOOTH
        wifiAvailable -> NearbyConnectionMethod.WIFI_DIRECT
        local == NearbyConnectionMethod.WIFI_DIRECT || remote == NearbyConnectionMethod.WIFI_DIRECT -> null
        else -> NearbyConnectionMethod.BLUETOOTH
    }

    /** An untrusted GATT offer cannot override a locally forced transport. */
    fun acceptsOffer(preference: NearbyConnectionMethod, offer: Offer): Boolean =
        offer.accepted && when (offer.transport) {
            NearbyConnectionMethod.WIFI_DIRECT ->
                preference != NearbyConnectionMethod.BLUETOOTH && offer.wifiAddress != null
            NearbyConnectionMethod.BLUETOOTH ->
                preference != NearbyConnectionMethod.WIFI_DIRECT && offer.bluetoothSupported
            else -> false
        }

    private fun methodByte(method: NearbyConnectionMethod): Byte = when (method) {
        NearbyConnectionMethod.AUTO -> 0
        NearbyConnectionMethod.WIFI_DIRECT -> 1
        NearbyConnectionMethod.BLUETOOTH -> 2
    }
    private fun decodeMethod(value: Byte): NearbyConnectionMethod? = when (value.toInt()) {
        0 -> NearbyConnectionMethod.AUTO
        1 -> NearbyConnectionMethod.WIFI_DIRECT
        2 -> NearbyConnectionMethod.BLUETOOTH
        else -> null
    }
}
