package com.group16.dinoblog.network

import com.group16.dinoblog.model.ChatMessage
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class TcpTransport(private val scope: CoroutineScope, private val logger: NetworkLogger) {
    private val mutableState = MutableStateFlow(SocketState())
    val state = mutableState.asStateFlow()
    private val chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = chatMessages.asStateFlow()
    private var nextMessageId = 0L
    private var endpoint: String? = null
    private var current: Attempt? = null

    private class Attempt {
        var job: Job? = null
        var listener: ServerSocket? = null
        var socket: Socket? = null
        val writeLock = Mutex()
    }

    /** One connection attempt per formed P2P group. force=true is an explicit UI action. */
    @Synchronized
    fun start(isOwner: Boolean, ownerIp: String, force: Boolean = false) {
        val key = "$isOwner:$ownerIp"
        // Repeated Android connection broadcasts must not restart or automatically
        // reattempt a failed socket. The user can explicitly press Start TCP.
        if (endpoint == key && (!force || mutableState.value.connected)) return
        stop()
        endpoint = key
        val attempt = Attempt()
        current = attempt
        mutableState.value = SocketState(status = if (isOwner) "Starting TCP server" else "Connecting to $ownerIp")
        attempt.job = scope.launch(Dispatchers.IO) { connectOnce(attempt, isOwner, ownerIp) }
    }

    private suspend fun connectOnce(attempt: Attempt, isOwner: Boolean, ownerIp: String) {
        try {
            val socket: Socket
            if (isOwner) {
                val listener = ServerSocket()
                synchronized(this) {
                    if (current !== attempt) { listener.close(); return }
                    attempt.listener = listener
                }
                listener.reuseAddress = true
                // Android chooses the group owner. Only that phone binds its P2P
                // IP, and the other phone connects to that actual address, not a
                // hardcoded 192.168.49.1 or an Internet server.
                listener.bind(InetSocketAddress(ownerIp, TextFrameCodec.PORT))
                updateState(attempt, SocketState("Listening on ${TextFrameCodec.PORT}", localIp = ownerIp))
                logger.log("TCP", "Listening at $ownerIp:${TextFrameCodec.PORT}")
                socket = listener.accept()
                synchronized(this) {
                    if (current !== attempt) { socket.close(); return }
                    attempt.socket = socket
                }
            } else {
                socket = Socket()
                synchronized(this) {
                    if (current !== attempt) { socket.close(); return }
                    attempt.socket = socket
                }
                // A single bounded attempt. If the owner is not ready, show the
                // failure and let the user press Start TCP; there is no retry loop.
                socket.connect(InetSocketAddress(ownerIp, TextFrameCodec.PORT), 5_000)
            }
            socket.tcpNoDelay = true
            updateState(attempt, SocketState("Connected", true, socket.localAddress.hostAddress, socket.inetAddress.hostAddress))
            logger.log("TCP", "Connected to ${socket.inetAddress.hostAddress}")
            val input = DataInputStream(socket.getInputStream())
            // Reads block only on Dispatchers.IO. stop() closes the actual socket
            // to unblock read/accept when the P2P group or Activity stops.
            while (currentCoroutineContext().isActive) {
                val text = TextFrameCodec.read(input)
                synchronized(this) {
                    if (current !== attempt) return
                    appendMessage(text, outgoing = false)
                }
                logger.log("RX", text)
                // Receiving only updates StateFlow for Compose. No response is sent.
            }
        } catch (e: CancellationException) { throw e }
        catch (_: EOFException) {
            updateState(attempt, SocketState("Peer disconnected. Tap Start TCP to connect again."))
        } catch (e: Exception) {
            if (updateState(attempt, SocketState("TCP error: ${e.message}. Tap Start TCP."))) {
                logger.log("ERROR", "TCP: ${e.message}")
            }
        } finally {
            synchronized(this) {
                runCatching { attempt.socket?.close() }
                runCatching { attempt.listener?.close() }
                if (current === attempt && mutableState.value.connected) mutableState.value = SocketState()
            }
        }
    }

    suspend fun sendText(text: String): Boolean = withContext(Dispatchers.IO) {
        // Validate before touching the socket so invalid input does not drop a
        // healthy connection. Do not trim; preserve the user's spaces/newlines.
        require(text.isNotBlank()) { "Type a message first" }
        require(text.toByteArray(Charsets.UTF_8).size <= TextFrameCodec.MAX_BYTES) { "Message is too large" }
        val attempt = synchronized(this@TcpTransport) {
            current.takeIf { mutableState.value.connected }
        } ?: return@withContext false
        attempt.writeLock.withLock {
            val socket = synchronized(this@TcpTransport) {
                attempt.socket.takeIf { current === attempt && mutableState.value.connected }
            } ?: return@withLock false
            try {
                TextFrameCodec.write(DataOutputStream(socket.getOutputStream()), text)
                appendMessage(text, outgoing = true)
                logger.log("TX", text)
                // This confirms a local socket write only, not delivery to the other UI.
                true
            } catch (e: Exception) {
                updateState(attempt, SocketState("Send failed: ${e.message}. Tap Start TCP."))
                logger.log("ERROR", "Send failed: ${e.message}")
                runCatching { socket.close() }
                false
            }
        }
    }

    @Synchronized
    private fun appendMessage(text: String, outgoing: Boolean) {
        val message = ChatMessage(++nextMessageId, text, System.currentTimeMillis(), outgoing)
        chatMessages.update { (it + message).takeLast(100) }
    }

    @Synchronized
    private fun updateState(attempt: Attempt, value: SocketState): Boolean {
        if (current !== attempt) return false
        mutableState.value = value
        return true
    }

    @Synchronized
    fun stop() {
        val previous = current
        current = null
        endpoint = null
        previous?.job?.cancel()
        runCatching { previous?.socket?.close() }
        runCatching { previous?.listener?.close() }
        mutableState.value = SocketState()
    }
}
