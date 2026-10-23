package com.group16.dinoblog.network

import android.os.SystemClock
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.model.DisplayNames
import com.group16.dinoblog.util.NetworkLogger
import com.group16.dinoblog.experiment.ExperimentManager
import com.group16.dinoblog.security.CryptoIdentityManager
import com.group16.dinoblog.security.CanonicalSignatures
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
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
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
    private val metrics: ExperimentManager? = null,
    private val security: CryptoIdentityManager? = null,
    private val serverBinding: suspend (String) -> String? = { null },
    private val afterAuthenticated: suspend (String) -> Unit = {},
    private val wifiAllowed: () -> Boolean = { true },
    private val bluetoothAllowed: () -> Boolean = { true },
    private val displayNameProvider: () -> String = { localName },
) : MessageTransport {
    private val mutableState = MutableStateFlow(SocketState())
    override val state = mutableState.asStateFlow()
    override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
    // Preserve the reader's session identity across slow Room operations/reconnects.
    @Volatile var onSessionPacket: (suspend (String, String, WirePacket) -> Unit)? = null
    // Same protocol/reader; only INVENTORY is dispatched to the DTN inventory component.
    var onInventory: suspend (String, String, WirePacket) -> Unit = { _, _, _ -> }
    var onAuthenticationFailure: (String) -> Unit = {}
    private var connectionJob: Job? = null
    private var endpoint: String? = null
    private var server: ServerSocket? = null
    @Volatile private var active: Session? = null
    @Volatile private var openingSocket: Socket? = null
    @Volatile private var handoff: Pair<String, Long>? = null

    fun prepareHandoff(id: String) {
        require(WireProtocol.isUuid(id))
        handoff = id to (System.currentTimeMillis() + 60_000L)
    }

    private class Session(val connection: PeerConnection) {
        val id: String = UUID.randomUUID().toString()
        val countingInput = CountingInput(connection.input)
        val countingOutput = CountingOutput(connection.output)
        val input = DataInputStream(countingInput)
        val output = DataOutputStream(countingOutput)
        val writeLock = Mutex()
        @Volatile var peerId: String? = null
        @Volatile var connectedAt: Long? = null
        @Volatile var lastReadAt: Long = System.currentTimeMillis()
    }

    /** RFCOMM and TCP share this exact frame reader, writer and authenticated session. */
    interface PeerConnection : Closeable {
        val input: InputStream
        val output: OutputStream
        val transportType: String
        val localEndpoint: String?
        val remoteEndpoint: String?
        fun setReadTimeout(milliseconds: Int)
    }

    private class TcpConnection(private val socket: Socket) : PeerConnection {
        init { socket.tcpNoDelay = true; socket.keepAlive = true }
        override val input = socket.getInputStream()
        override val output = socket.getOutputStream()
        override val transportType = "WIFI_DIRECT"
        override val localEndpoint get() = socket.localAddress.hostAddress
        override val remoteEndpoint get() = socket.inetAddress.hostAddress
        override fun setReadTimeout(milliseconds: Int) { socket.soTimeout = milliseconds }
        override fun close() = socket.close()
    }

    /** Takes ownership of an already connected Bluetooth byte stream. */
    fun acceptBluetooth(connection: PeerConnection) {
        scope.launch(Dispatchers.IO) {
            try { runSession(connection) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { logger.log("ERROR", "Bluetooth session closed: ${error.message}") }
            finally { connection.close() }
        }
    }

    /** Called on the main thread with the actual group-owner IP from Android. */
    @Synchronized
    fun start(isOwner: Boolean, ownerIp: String, force: Boolean = false) {
        if (!wifiAllowed()) { stopWifi(); return }
        val key = "$isOwner:$ownerIp"
        if (endpoint == key && connectionJob?.isActive == true && (!force || state.value.connected)) return
        stopWifi()
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
                                metrics?.record("PEER_CONNECTION_SETUP", transport = "WIFI_DIRECT", durationMs = 0)
                                try { runSession(TcpConnection(socket)) } catch (e: CancellationException) { throw e }
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
                            val startedAt = if (metrics != null) SystemClock.elapsedRealtime() else 0L
                            socket.connect(InetSocketAddress(ownerIp, WireProtocol.PORT), 5_000)
                            metrics?.record("PEER_CONNECTION_SETUP", transport = "WIFI_DIRECT",
                                durationMs = SystemClock.elapsedRealtime() - startedAt)
                            runSession(TcpConnection(socket))
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    logger.log("ERROR", "TCP: ${e.message}; retry in 2s")
                    metrics?.record("PEER_CONNECTION_FAILED", transport = "WIFI_DIRECT", reason = e.javaClass.simpleName)
                }
                if (isActive) {
                    publishState(connectionContext, SocketState(status = "Reconnecting"))
                    delay(2_000)
                }
            }
        }
    }

    private suspend fun runSession(connection: PeerConnection) {
        connection.setReadTimeout(10_000)
        val session = Session(connection)
        val setupStartedAt = if (metrics != null) SystemClock.elapsedRealtime() else 0L
        val expectedHandoff = handoff?.takeIf { it.second > System.currentTimeMillis() }?.first
        val sessionContext = currentCoroutineContext()
        // Publish the socket under the same monitor as stop(), including during handshake,
        // so lifecycle shutdown interrupts blocking reads/accepts instead of leaking them.
        synchronized(this) {
            if (!sessionContext.isActive || active != null ||
                (connection.transportType == "WIFI_DIRECT" && !wifiAllowed()) ||
                (connection.transportType == "BLUETOOTH" && !bluetoothAllowed())) {
                connection.close(); return
            }
            active = session
            mutableState.value = SocketState(status = "Verifying DinoBlog device",
                localIp = connection.localEndpoint, remoteIp = connection.remoteEndpoint,
                awaitingHello = true, transportType = connection.transportType)
        }
        var heartbeat: Job? = null
        val handshakeDeadline = if (connection.transportType == "BLUETOOTH") scope.launch(Dispatchers.IO) {
            delay(20_000)
            if (active === session && session.peerId == null) connection.close()
        } else null
        val idleDeadline = if (connection.transportType == "BLUETOOTH") scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10_000)
                if (active !== session) break
                if (System.currentTimeMillis() - session.lastReadAt > 45_000) {
                    logger.log("ERROR", "Bluetooth peer timed out")
                    connection.close()
                    break
                }
            }
        } else null
        try {
            val ownNonce = security?.nonce()
            if (security != null) mutableState.value = mutableState.value.copy(
                peerAuthState = "AUTHENTICATING", helloNonceState = "LOCAL_CREATED")
            write(session, WirePacket(PacketType.HELLO, deviceId = localId, displayName = displayNameProvider(),
                relayEnabled = relayEnabledProvider(), mediaCapabilities = 7,
                securityProtocolVersion = if (security != null) 1 else null,
                publicKey = security?.publicKey, publicKeyFingerprint = security?.fingerprint,
                helloNonce = ownNonce, handoffSessionId = expectedHandoff))
            val hello = readFrame(session)
            require(hello.type == PacketType.HELLO && hello.deviceId != localId) { "Expected a different Dinoblog peer" }
            if (expectedHandoff != null) require(hello.handoffSessionId == expectedHandoff) { "Handoff session mismatch" }
            val peerId = requireNotNull(hello.deviceId)
            var trustState: String? = null
            if (security != null) {
                require(hello.securityProtocolVersion == 1 && hello.publicKey != null &&
                    hello.publicKeyFingerprint != null && hello.helloNonce != null) { "LEGACY_UNVERIFIED peer blocked" }
                require(hello.helloNonce.length in 24..128 &&
                    security.fingerprintOf(hello.publicKey) == hello.publicKeyFingerprint) { "Invalid HELLO identity" }
                mutableState.value = mutableState.value.copy(helloNonceState = "BOTH_EXCHANGED")
                val proofBytes = CanonicalSignatures.hello(localId, peerId, requireNotNull(ownNonce),
                    hello.helloNonce, security.fingerprint)
                write(session, WirePacket(PacketType.HELLO_PROOF, securityProtocolVersion = 1,
                    deviceId = localId, peerDeviceId = peerId, proofOwnNonce = ownNonce,
                    proofPeerNonce = hello.helloNonce, proofSignature = security.sign(proofBytes)))
                val proof = readFrame(session)
                require(proof.type == PacketType.HELLO_PROOF && proof.deviceId == peerId &&
                    proof.peerDeviceId == localId && proof.proofOwnNonce == hello.helloNonce &&
                    proof.proofPeerNonce == ownNonce &&
                    security.verify(hello.publicKey,
                        CanonicalSignatures.hello(peerId, localId, hello.helloNonce, ownNonce,
                            hello.publicKeyFingerprint), requireNotNull(proof.proofSignature))) { "HELLO_PROOF failed" }
                // Check the server's authenticated binding before any first-use pin.
                val authoritative = serverBinding(peerId)
                if (authoritative != null) require(security.pin(peerId, authoritative, "SERVER") != "KEY_MISMATCH") {
                    "Server identity conflicts with local pin" }
                trustState = security.pin(peerId, hello.publicKey, "TOFU")
                require(trustState != "KEY_MISMATCH") { "KEY_MISMATCH" }
                afterAuthenticated(peerId)
                logger.log("SECURITY", "SECURITY HELLO AUTHENTICATED peer=$peerId")
                metrics?.record("HELLO_AUTHENTICATED", peerId = peerId, transport = connection.transportType)
            }
            connection.setReadTimeout(35_000)
            synchronized(this) {
                // A late HELLO from a cancelled connection must never republish
                // an old peer after stop() or a new P2P connection has begun.
                if (active !== session || !sessionContext.isActive) return
                session.peerId = peerId
                if (expectedHandoff != null && handoff?.first == expectedHandoff) handoff = null
                session.connectedAt = if (metrics != null) SystemClock.elapsedRealtime() else null
                logger.log("RX", "DTN HELLO RECEIVE\nremoteDeviceId=$peerId")
                logger.log("STATE", "DTN PEER IDENTIFIED\nconnectedPeerDeviceId=$peerId")
                logger.log("DTN", "DTN PEER CAPABILITY peer=$peerId relayEnabled=${hello.relayEnabled ?: true}")
                mutableState.value = SocketState("Connected", peerId, DisplayNames.normalize(hello.displayName) ?: DisplayNames.FALLBACK, connection.localEndpoint, connection.remoteEndpoint,
                    sessionId = session.id, peerRelayEnabled = hello.relayEnabled ?: true,
                    peerMediaCapabilities = hello.mediaCapabilities ?: 1,
                    peerAuthState = if (security != null) "AUTHENTICATED" else "LEGACY_UNVERIFIED",
                    peerTrustState = trustState, peerFingerprint = hello.publicKeyFingerprint,
                    helloProofVerified = security != null,
                    helloNonceState = if (security != null) "BOTH_VERIFIED" else "NONE",
                    transportType = connection.transportType)
            }
            metrics?.record("PEER_CONNECTED", peerId = peerId, transport = connection.transportType)
            metrics?.record("PEER_AUTHENTICATED_SETUP", peerId = peerId, transport = connection.transportType,
                durationMs = SystemClock.elapsedRealtime() - setupStartedAt)
            heartbeat = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(10_000)
                    try { write(session, WirePacket(PacketType.PING)) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { clearSession(session); connection.close(); break }
                }
            }
            while (currentCoroutineContext().isActive) {
                val packet = readFrame(session)
                logger.log("RX", packet.type.name, packet.message?.messageId ?: packet.messageId, packet.message?.ttl, packet.message?.status?.name)
                when (packet.type) {
                    PacketType.PING -> write(session, WirePacket(PacketType.PONG))
                    PacketType.PONG -> Unit
                    PacketType.HELLO, PacketType.HELLO_PROOF -> error("Repeated HELLO")
                    PacketType.INVENTORY -> onInventory(peerId, session.id, packet)
                    else -> {
                        val handler = onSessionPacket
                        if (handler != null) handler(peerId, session.id, packet) else onPacket(peerId, packet)
                    }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (security != null && session.peerId == null) {
                security.noteFailure(error.message ?: error.javaClass.simpleName)
                metrics?.record("HELLO_AUTHENTICATION_FAILED", transport = connection.transportType, reason = error.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(peerAuthState = "FAILED")
                onAuthenticationFailure(connection.transportType)
            }
            throw error
        } finally {
            heartbeat?.cancel()
            handshakeDeadline?.cancel()
            idleDeadline?.cancel()
            clearSession(session)
            connection.close()
        }
    }

    private suspend fun write(session: Session, packet: WirePacket,
        eligible: suspend () -> Boolean = { true }): Boolean = session.writeLock.withLock {
        if (active !== session || !eligible() || active !== session) return@withLock false
        val before = session.countingOutput.count
        WireProtocol.write(session.output, packet)
        metrics?.bytes("SENT", session.connection.transportType, packet.type == PacketType.MEDIA_CHUNK,
            session.countingOutput.count - before, packet.type in setOf(PacketType.MESSAGE, PacketType.MEDIA_CHUNK),
            packet.binaryData?.size?.toLong() ?: 0)
        if (packet.type == PacketType.HELLO) logger.log("TX", "DTN HELLO SEND\nlocalDeviceId=$localId")
        logger.log("TX", packet.type.name, packet.message?.messageId ?: packet.messageId, packet.message?.ttl, packet.message?.status?.name)
        true
    }

    private fun readFrame(session: Session): WirePacket {
        val before = session.countingInput.count
        val packet = WireProtocol.read(session.input)
        session.lastReadAt = System.currentTimeMillis()
        metrics?.bytes("RECEIVED", session.connection.transportType, packet.type == PacketType.MEDIA_CHUNK,
            session.countingInput.count - before, packet.type in setOf(PacketType.MESSAGE, PacketType.MEDIA_CHUNK),
            packet.binaryData?.size?.toLong() ?: 0)
        return packet
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
            runCatching { session.connection.close() }
            false
        }
    }

    @Synchronized
    private fun publishState(context: CoroutineContext, state: SocketState) {
        // A cancelled connection loop must not overwrite a newer session's identity.
        if (context.isActive && active?.connection?.transportType != "BLUETOOTH") mutableState.value = state
    }

    @Synchronized
    private fun clearSession(session: Session) {
        // Shared by EOF/error, explicit stop and write/heartbeat failures. Identity
        // belongs to a TCP session, never to an IP or the saved Room peer directory.
        if (active !== session) return
        active = null
        mutableState.value = SocketState()
        session.peerId?.let {
            logger.log("STATE", "DTN PEER DISCONNECTED\npreviousPeer=$it")
            metrics?.record("PEER_DISCONNECTED", peerId = it, transport = session.connection.transportType,
                durationMs = session.connectedAt?.let { started -> SystemClock.elapsedRealtime() - started })
        }
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
        runCatching { session?.connection?.close() }
        server = null
        openingSocket = null
        active = null
        mutableState.value = SocketState()
    }

    /** Wi-Fi lifecycle changes must leave an independent Bluetooth session intact. */
    @Synchronized
    fun stopWifi() {
        connectionJob?.cancel()
        connectionJob = null
        endpoint = null
        runCatching { server?.close() }
        runCatching { openingSocket?.close() }
        server = null
        openingSocket = null
        if (active?.connection?.transportType == "WIFI_DIRECT") {
            val session = active ?: return
            clearSession(session)
            runCatching { session.connection.close() }
        }
    }

    @Synchronized
    fun disconnectBluetooth() {
        val session = active?.takeIf { it.connection.transportType == "BLUETOOTH" } ?: return
        clearSession(session)
        runCatching { session.connection.close() }
    }
}

private class CountingInput(private val delegate: InputStream) : InputStream() {
    var count = 0L
        private set
    override fun read(): Int = delegate.read().also { if (it >= 0) count++ }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        delegate.read(bytes, offset, length).also { if (it > 0) count += it }
    override fun close() = delegate.close()
}

private class CountingOutput(private val delegate: OutputStream) : OutputStream() {
    var count = 0L
        private set
    override fun write(value: Int) { delegate.write(value); count++ }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        delegate.write(bytes, offset, length)
        count += length
    }
    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
