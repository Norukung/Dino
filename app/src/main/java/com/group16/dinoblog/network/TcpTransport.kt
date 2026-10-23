package com.group16.dinoblog.network

import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.model.DisplayNames
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
import java.util.UUID
import kotlin.coroutines.CoroutineContext

class TcpTransport(
    private val localId: String,
    private val localName: String,
    private val scope: CoroutineScope,
    private val logger: NetworkLogger,
    private val relayEnabledProvider: () -> Boolean = { true },
    private val displayNameProvider: () -> String = { localName },
) : MessageTransport {
    private val mutableState = MutableStateFlow(SocketState())
    override val state = mutableState.asStateFlow()
    override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
    // Preserve the reader's session identity across slow Room operations/reconnects.
    @Volatile var onSessionPacket: (suspend (String, String, WirePacket) -> Unit)? = null
    // Same protocol/reader; only INVENTORY is dispatched to the DTN inventory component.
    var onInventory: suspend (String, String, WirePacket) -> Unit = { _, _, _ -> }
    private var connectionJob: Job? = null
    private var endpoint: String? = null
    private var server: ServerSocket? = null
    @Volatile private var active: Session? = null
    @Volatile private var openingSocket: Socket? = null

    private class Session(val socket: Socket) {
        val id: String = UUID.randomUUID().toString()
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val writeLock = Mutex()
        @Volatile var peerId: String? = null
    }

    /** Called on the main thread with the actual group-owner IP from Android. */
    @Synchronized
    fun start(isOwner: Boolean, ownerIp: String, force: Boolean = false) {
        val key = "$isOwner:$ownerIp"
        if (endpoint == key && connectionJob?.isActive == true && (!force || state.value.connected)) return
        stop()
        endpoint = key
        connectionJob = scope.launch(Dispatchers.IO) {
            val connectionContext = currentCoroutineContext()
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
                            publishState(connectionContext, SocketState(status = "Listening on ${WireProtocol.PORT}", localIp = ownerIp))
                            while (isActive) {
                                val socket = listener.accept()
                                try { runSession(socket) } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { logger.log("ERROR", "Session closed: ${e.message}") }
                                finally { socket.close() }
                                publishState(connectionContext, SocketState(status = "Waiting for peer", localIp = ownerIp))
                            }
                        }
                    } else {
                        publishState(connectionContext, SocketState(status = "Connecting to $ownerIp"))
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
                    publishState(connectionContext, SocketState(status = "Reconnecting"))
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
            mutableState.value = SocketState(status = "TCP connected; waiting for HELLO",
                localIp = socket.localAddress.hostAddress, remoteIp = socket.inetAddress.hostAddress,
                awaitingHello = true)
        }
        var heartbeat: Job? = null
        try {
            write(session, WirePacket(PacketType.HELLO, deviceId = localId, displayName = displayNameProvider(),
                relayEnabled = relayEnabledProvider()))
            val hello = WireProtocol.read(session.input)
            require(hello.type == PacketType.HELLO && hello.deviceId != localId) { "Expected a different Dinoblog peer" }
            val peerId = requireNotNull(hello.deviceId)
            socket.soTimeout = 35_000
            synchronized(this) {
                // A late HELLO from a cancelled connection must never republish
                // an old peer after stop() or a new P2P connection has begun.
                if (active !== session || !sessionContext.isActive) return
                session.peerId = peerId
                logger.log("RX", "DTN HELLO RECEIVE\nremoteDeviceId=$peerId")
                logger.log("STATE", "DTN PEER IDENTIFIED\nconnectedPeerDeviceId=$peerId")
                logger.log("DTN", "DTN PEER CAPABILITY peer=$peerId relayEnabled=${hello.relayEnabled ?: true}")
                mutableState.value = SocketState("Connected", peerId, DisplayNames.normalize(hello.displayName) ?: DisplayNames.FALLBACK, socket.localAddress.hostAddress, socket.inetAddress.hostAddress,
                    sessionId = session.id, peerRelayEnabled = hello.relayEnabled ?: true)
            }
            heartbeat = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(10_000)
                    try { write(session, WirePacket(PacketType.PING)) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { clearSession(session); socket.close(); break }
                }
            }
            while (currentCoroutineContext().isActive) {
                val packet = WireProtocol.read(session.input)
                logger.log("RX", packet.type.name, packet.message?.messageId ?: packet.messageId, packet.message?.ttl, packet.message?.status?.name)
                when (packet.type) {
                    PacketType.PING -> write(session, WirePacket(PacketType.PONG))
                    PacketType.PONG -> Unit
                    PacketType.HELLO -> error("Repeated HELLO")
                    PacketType.INVENTORY -> onInventory(peerId, session.id, packet)
                    else -> {
                        val handler = onSessionPacket
                        if (handler != null) handler(peerId, session.id, packet) else onPacket(peerId, packet)
                    }
                }
            }
        } finally {
            heartbeat?.cancel()
            clearSession(session)
            socket.close()
        }
    }

    private suspend fun write(session: Session, packet: WirePacket,
        eligible: suspend () -> Boolean = { true }): Boolean = session.writeLock.withLock {
        if (active !== session || !eligible() || active !== session) return@withLock false
        WireProtocol.write(session.output, packet)
        if (packet.type == PacketType.HELLO) logger.log("TX", "DTN HELLO SEND\nlocalDeviceId=$localId")
        logger.log("TX", packet.type.name, packet.message?.messageId ?: packet.messageId, packet.message?.ttl, packet.message?.status?.name)
        true
    }

    override suspend fun sendIfCurrent(peerId: String, sessionId: String, packet: WirePacket,
        eligible: suspend () -> Boolean): Boolean = sendPacket(peerId, packet, sessionId, eligible)

    override suspend fun send(peerId: String, packet: WirePacket): Boolean = sendPacket(peerId, packet, null)

    override suspend fun sendToSession(peerId: String, sessionId: String, packet: WirePacket): Boolean =
        sendPacket(peerId, packet, sessionId)

    private suspend fun sendPacket(peerId: String, packet: WirePacket, expectedSessionId: String?, eligible: suspend () -> Boolean = { true }): Boolean = withContext(Dispatchers.IO) {
        val session = active ?: return@withContext false
        if (session.peerId != peerId || (expectedSessionId != null && session.id != expectedSessionId)) return@withContext false
        try {
            write(session, packet) {
                active === session && (expectedSessionId == null || session.id == expectedSessionId) && eligible()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            logger.log("ERROR", "Send failed: ${e.message}", packet.message?.messageId ?: packet.messageId)
            clearSession(session)
            runCatching { session.socket.close() }
            false
        }
    }

    @Synchronized
    private fun publishState(context: CoroutineContext, state: SocketState) {
        // A cancelled connection loop must not overwrite a newer session's identity.
        if (context.isActive) mutableState.value = state
    }

    @Synchronized
    private fun clearSession(session: Session) {
        // Shared by EOF/error, explicit stop and write/heartbeat failures. Identity
        // belongs to a TCP session, never to an IP or the saved Room peer directory.
        if (active !== session) return
        active = null
        mutableState.value = SocketState()
        session.peerId?.let { logger.log("STATE", "DTN PEER DISCONNECTED\npreviousPeer=$it") }
        session.peerId = null
    }

    @Synchronized
    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        endpoint = null
        val session = active
        if (session != null) clearSession(session)
        runCatching { server?.close() }
        runCatching { openingSocket?.close() }
        runCatching { session?.socket?.close() }
        server = null
        openingSocket = null
        active = null
        mutableState.value = SocketState()
    }
}
