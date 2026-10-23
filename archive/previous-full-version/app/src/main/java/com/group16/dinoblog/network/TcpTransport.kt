package com.group16.dinoblog.network

import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class TcpTransport(
    private val localId: String,
    private val localName: String,
    private val scope: CoroutineScope,
    private val logger: NetworkLogger,
) : MessageTransport {
    private val mutableState = MutableStateFlow(SocketState())
    override val state = mutableState.asStateFlow()
    override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
    private var connectionJob: Job? = null
    private var endpoint: String? = null
    private var server: ServerSocket? = null
    @Volatile private var active: Session? = null
    @Volatile private var openingSocket: Socket? = null

    private class Session(val socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val writeLock = Mutex()
        @Volatile var peerId: String? = null
    }

    /** Called on the main thread with the actual group-owner IP from Android. */
    fun start(isOwner: Boolean, ownerIp: String) {
        val key = "$isOwner:$ownerIp"
        if (endpoint == key && connectionJob?.isActive == true) return
        stop()
        endpoint = key
        connectionJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (isOwner) {
                        // Only the Wi-Fi Direct group owner listens. Binding specifically to
                        // its P2P address keeps this server off unrelated Wi-Fi interfaces.
                        ServerSocket().use { listener ->
                            synchronized(this@TcpTransport) {
                                if (!isActive) return@launch
                                server = listener
                            }
                            listener.reuseAddress = true
                            listener.bind(InetSocketAddress(ownerIp, WireProtocol.PORT))
                            mutableState.value = SocketState(status = "Listening on ${WireProtocol.PORT}", localIp = ownerIp)
                            while (isActive) {
                                val socket = listener.accept()
                                try { runSession(socket) } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { logger.log("ERROR", "Session closed: ${e.message}") }
                                finally { socket.close() }
                                if (isActive) mutableState.value = SocketState(status = "Waiting for peer", localIp = ownerIp)
                            }
                        }
                    } else {
                        mutableState.value = SocketState(status = "Connecting to $ownerIp")
                        Socket().use { socket ->
                            synchronized(this@TcpTransport) {
                                if (!isActive) return@launch
                                openingSocket = socket
                            }
                            // The owner may not yet be listening; retry while the P2P group exists.
                            socket.connect(InetSocketAddress(ownerIp, WireProtocol.PORT), 5_000)
                            runSession(socket)
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    logger.log("ERROR", "TCP: ${e.message}; retry in 2s")
                }
                if (isActive) {
                    mutableState.value = SocketState(status = "Reconnecting")
                    delay(2_000)
                }
            }
        }
    }

    private suspend fun runSession(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = 10_000
        val session = Session(socket)
        val sessionContext = currentCoroutineContext()
        // Publish the socket under the same monitor as stop(), including during handshake,
        // so lifecycle shutdown interrupts blocking reads/accepts instead of leaking them.
        synchronized(this) {
            if (!sessionContext.isActive) { socket.close(); return }
            active = session
        }
        var heartbeat: Job? = null
        try {
            write(session, WirePacket(PacketType.HELLO, deviceId = localId, deviceName = localName))
            val hello = WireProtocol.read(session.input)
            require(hello.type == PacketType.HELLO && hello.deviceId != localId) { "Expected a different Dinoblog peer" }
            val peerId = requireNotNull(hello.deviceId)
            session.peerId = peerId
            logger.log("RX", "HELLO from ${hello.deviceName} ($peerId)")
            socket.soTimeout = 35_000
            mutableState.value = SocketState("Connected", peerId, hello.deviceName, socket.localAddress.hostAddress, socket.inetAddress.hostAddress)
            heartbeat = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(10_000)
                    try { write(session, WirePacket(PacketType.PING)) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { socket.close(); break }
                }
            }
            while (currentCoroutineContext().isActive) {
                val packet = WireProtocol.read(session.input)
                logger.log("RX", packet.type.name, packet.message?.messageId ?: packet.messageId, packet.message?.ttl, packet.message?.status?.name)
                when (packet.type) {
                    PacketType.PING -> write(session, WirePacket(PacketType.PONG))
                    PacketType.PONG -> Unit
                    PacketType.HELLO -> error("Repeated HELLO")
                    else -> onPacket(peerId, packet)
                }
            }
        } finally {
            heartbeat?.cancel()
            synchronized(this) {
                if (active === session) {
                    active = null
                    mutableState.value = SocketState(status = "Disconnected")
                }
            }
            socket.close()
        }
    }

    private suspend fun write(session: Session, packet: WirePacket) = session.writeLock.withLock {
        WireProtocol.write(session.output, packet)
        logger.log("TX", packet.type.name, packet.message?.messageId ?: packet.messageId, packet.message?.ttl, packet.message?.status?.name)
    }

    override suspend fun send(peerId: String, packet: WirePacket): Boolean = withContext(Dispatchers.IO) {
        val session = active ?: return@withContext false
        if (session.peerId != peerId) return@withContext false
        try {
            write(session, packet)
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            logger.log("ERROR", "Send failed: ${e.message}", packet.message?.messageId ?: packet.messageId)
            runCatching { session.socket.close() }
            false
        }
    }

    @Synchronized
    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        endpoint = null
        runCatching { server?.close() }
        runCatching { openingSocket?.close() }
        runCatching { active?.socket?.close() }
        server = null
        openingSocket = null
        active = null
        mutableState.value = SocketState()
    }
}
