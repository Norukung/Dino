package com.group16.dinoblog.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import com.group16.dinoblog.experiment.ExperimentManager
import com.group16.dinoblog.network.TcpTransport
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class BluetoothConnectionState(
    val listening: Boolean = false,
    val connecting: Boolean = false,
    val incomingRequest: Boolean = false,
    val lastFailure: String? = null,
)

/** The byte stream is handed to the existing authenticated DinoBlog session. */
class BluetoothPeerTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val protocol: TcpTransport,
    private val logger: NetworkLogger,
    private val metrics: ExperimentManager? = null,
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("a6616d04-c6b5-4f7d-8d53-fc07952e9a4e")
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val ACCEPT_TIMEOUT_MS = 30_000L
        private const val PREAPPROVED_TIMEOUT_MS = 60_000L
    }

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mutableState = MutableStateFlow(BluetoothConnectionState())
    val state = mutableState.asStateFlow()
    private var listener: BluetoothServerSocket? = null
    private var listenerJob: Job? = null
    private var clientJob: Job? = null
    private var opening: BluetoothSocket? = null
    private var pending: BluetoothSocket? = null
    private var approval: CompletableDeferred<Boolean>? = null
    private var approvedEndpoint: Pair<String, Long>? = null

    init { protocol.onAuthenticationFailure = { type ->
        if (type == "BLUETOOTH") mutableState.value = mutableState.value.copy(
            lastFailure = "Could not verify this DinoBlog device")
    } }

    /** One GATT-accepted endpoint may open one RFCOMM stream during this handoff. */
    @Synchronized fun allowNextIncoming(device: BluetoothDevice) {
        if (!BlePermissions.hasConnect(context)) return
        runCatching { approvedEndpoint = device.address to (System.currentTimeMillis() + PREAPPROVED_TIMEOUT_MS) }
    }

    @SuppressLint("MissingPermission") // Guarded by hasConnect; revocation is caught.
    @Synchronized fun startListening() {
        if (listenerJob?.isActive == true || !BlePermissions.hasConnect(context)) return
        if (runCatching { adapter?.isEnabled }.getOrNull() != true) return
        listenerJob = scope.launch(Dispatchers.IO) {
            try {
                // Insecure RFCOMM avoids platform pairing dialogs. HELLO_PROOF remains mandatory.
                val server = adapter?.listenUsingInsecureRfcommWithServiceRecord("DinoBlog", SERVICE_UUID)
                    ?: error("Bluetooth unavailable")
                synchronized(this@BluetoothPeerTransport) { listener = server }
                mutableState.value = mutableState.value.copy(listening = true, lastFailure = null)
                while (isActive) {
                    val socket = server.accept()
                    if (protocol.state.value.connected || pending != null) { socket.close(); continue }
                    val preapproved = synchronized(this@BluetoothPeerTransport) {
                        val allowed = approvedEndpoint?.let { it.first == socket.remoteDevice.address &&
                            System.currentTimeMillis() < it.second } == true
                        if (allowed) approvedEndpoint = null
                        allowed
                    }
                    if (preapproved) { protocol.acceptBluetooth(BluetoothConnection(socket)); continue }
                    val decision = CompletableDeferred<Boolean>()
                    synchronized(this@BluetoothPeerTransport) { pending = socket; approval = decision }
                    mutableState.value = mutableState.value.copy(incomingRequest = true)
                    val accepted = withTimeoutOrNull(ACCEPT_TIMEOUT_MS) { decision.await() } == true
                    synchronized(this@BluetoothPeerTransport) { pending = null; approval = null }
                    mutableState.value = mutableState.value.copy(incomingRequest = false)
                    if (accepted && isActive) protocol.acceptBluetooth(BluetoothConnection(socket))
                    else socket.close()
                }
            } catch (error: Exception) {
                if (isActive) {
                    mutableState.value = mutableState.value.copy(lastFailure = error.message?.take(100) ?: "Bluetooth listener failed")
                    logger.log("ERROR", "Bluetooth listener: ${error.javaClass.simpleName}")
                }
            } finally {
                synchronized(this@BluetoothPeerTransport) { listener?.close(); listener = null }
                mutableState.value = mutableState.value.copy(listening = false, incomingRequest = false)
            }
        }
    }

    fun acceptIncoming() { approval?.complete(true) }
    fun declineIncoming() { approval?.complete(false) }

    @SuppressLint("MissingPermission") // Guarded by hasConnect; revocation is caught.
    @Synchronized fun connect(device: BluetoothDevice) {
        if (clientJob?.isActive == true) return
        if (!BlePermissions.hasConnect(context)) {
            mutableState.value = mutableState.value.copy(lastFailure = "Allow Bluetooth connection permission")
            return
        }
        if (runCatching { adapter?.isEnabled }.getOrNull() != true) {
            mutableState.value = mutableState.value.copy(lastFailure = "Turn on Bluetooth to connect")
            return
        }
        if (protocol.state.value.connected) return
        clientJob = scope.launch(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            var deadline: Job? = null
            val startedAt = if (metrics != null) SystemClock.elapsedRealtime() else 0L
            mutableState.value = mutableState.value.copy(connecting = true, lastFailure = null)
            try {
                socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
                synchronized(this@BluetoothPeerTransport) { opening = socket }
                deadline = launch { delay(CONNECT_TIMEOUT_MS); socket?.close() }
                adapter?.cancelDiscovery()
                socket.connect()
                deadline.cancel()
                metrics?.record("PEER_CONNECTION_SETUP", transport = "BLUETOOTH",
                    durationMs = SystemClock.elapsedRealtime() - startedAt)
                if (!isActive) { socket.close(); return@launch }
                protocol.acceptBluetooth(BluetoothConnection(socket))
                socket = null // Protocol now owns the socket.
            } catch (error: Exception) {
                metrics?.record("PEER_CONNECTION_FAILED", transport = "BLUETOOTH", reason = error.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(lastFailure = error.message?.take(100) ?: "Bluetooth connection failed")
                logger.log("ERROR", "Bluetooth connect: ${error.javaClass.simpleName}")
            } finally {
                deadline?.cancel()
                socket?.close()
                synchronized(this@BluetoothPeerTransport) { opening = null; clientJob = null }
                mutableState.value = mutableState.value.copy(connecting = false)
            }
        }
    }

    @Synchronized fun stop() {
        clientJob?.cancel()
        listenerJob?.cancel()
        approval?.complete(false)
        runCatching { opening?.close() }
        runCatching { pending?.close() }
        runCatching { listener?.close() }
        opening = null
        pending = null
        approvedEndpoint = null
        listener = null
        mutableState.value = BluetoothConnectionState()
    }

    private class BluetoothConnection(private val socket: BluetoothSocket) : TcpTransport.PeerConnection {
        override val input: InputStream = socket.inputStream
        override val output: OutputStream = socket.outputStream
        override val transportType = "BLUETOOTH"
        override val localEndpoint: String? = null
        override val remoteEndpoint: String? = null
        override fun setReadTimeout(milliseconds: Int) = Unit // Handshake deadline closes the socket.
        override fun close() = socket.close()
    }
}
