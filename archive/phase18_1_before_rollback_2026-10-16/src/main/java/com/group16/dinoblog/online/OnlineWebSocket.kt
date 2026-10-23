package com.group16.dinoblog.online

import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Small authenticated RFC 6455 reader. WebSocket only signals that REST sync is needed. */
internal class OnlineWebSocket {
    @Volatile private var socket: Socket? = null

    fun close() { socket?.close(); socket = null }

    fun listen(baseUrl: String, token: String, onData: () -> Unit) {
        val base = URI(baseUrl)
        val secure = base.scheme == "https"
        require(secure || base.scheme == "http")
        val host = base.host ?: error("Invalid server host")
        val port = if (base.port > 0) base.port else if (secure) 443 else 80
        val connection = if (secure) {
            (SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket).apply {
                sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                startHandshake()
            }
        } else Socket(host, port)
        socket = connection
        try {
            connection.soTimeout = 60_000
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val key = Base64.getEncoder().encodeToString(nonce)
            val path = (base.rawPath.trimEnd('/') + "/api/v1/ws").ifEmpty { "/api/v1/ws" }
            val hostHeader = if (base.port > 0) "$host:$port" else host
            val request = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n" +
                "Authorization: Bearer $token\r\n\r\n"
            connection.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            val input = connection.getInputStream()
            val status = readLine(input)
            require(status.contains(" 101 ")) { "WebSocket handshake failed" }
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val index = line.indexOf(':')
                if (index > 0) headers[line.substring(0, index).lowercase()] = line.substring(index + 1).trim()
            }
            val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
            require(headers["sec-websocket-accept"] == expected) { "Invalid WebSocket handshake" }
            while (!connection.isClosed) {
                val first = input.read()
                val second = input.read()
                if (first < 0 || second < 0) break
                require(second and 0x80 == 0) { "Masked server frame" }
                var length = second and 0x7f
                if (length == 126) length = (input.read() shl 8) or input.read()
                require(length in 0..1024) { "Oversized WebSocket frame" }
                val body = ByteArray(length)
                var received = 0
                while (received < length) {
                    val n = input.read(body, received, length - received)
                    if (n < 0) break
                    received += n
                }
                if (received != length || first and 0x0f == 8) break
                if (first and 0x0f == 1 && body.decodeToString().contains("SYNC_AVAILABLE")) onData()
            }
        } finally { connection.close(); if (socket === connection) socket = null }
    }

    private fun readLine(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 8192) {
            val char = input.read()
            if (char < 0) error("WebSocket closed during handshake")
            if (char == 10) return bytes.dropLast(if (bytes.lastOrNull() == 13.toByte()) 1 else 0)
                .toByteArray().toString(Charsets.US_ASCII)
            bytes.add(char.toByte())
        }
        error("Oversized WebSocket header")
    }
}
