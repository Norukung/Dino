package com.group16.dinoblog.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.group16.dinoblog.repository.NearbyConnectionMethod
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class BleHandoffState(
    val serverListening: Boolean = false,
    val requesting: Boolean = false,
    val incomingRequest: Boolean = false,
    val sessionId: String? = null,
    val status: String = "IDLE",
    val lastFailure: String? = null,
)

/** A bounded GATT control exchange. It never reads or writes DTN frames. */
class BleHandoffManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val logger: NetworkLogger,
    private val localMethod: () -> NearbyConnectionMethod,
    private val wifiAddress: () -> String?,
    private val canAccept: () -> Boolean,
    private val onAccepted: (UUID, BluetoothDevice) -> Unit,
) {
    private val manager get() = context.getSystemService(BluetoothManager::class.java)
    private val mutableState = MutableStateFlow(BleHandoffState())
    val state = mutableState.asStateFlow()
    private var server: BluetoothGattServer? = null
    private var request: BleHandoffProtocol.Request? = null
    private var requester: BluetoothDevice? = null
    private var approved: Boolean? = null
    private var expiresAt = 0L
    private var generation = 0
    @Volatile private var clientGatt: BluetoothGatt? = null
    @Volatile private var clientAnswer: CompletableDeferred<BleHandoffProtocol.Offer?>? = null

    val pendingDevice: BluetoothDevice? get() = requester

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (server != null && service.uuid == BleHandoffProtocol.SERVICE_UUID)
                mutableState.value = mutableState.value.copy(serverListening = status == BluetoothGatt.GATT_SUCCESS,
                    lastFailure = if (status == BluetoothGatt.GATT_SUCCESS) null else "BLE handoff service unavailable")
        }
        @SuppressLint("MissingPermission") // Permission is checked when opening GATT; revocation is caught.
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val decoded = if (!preparedWrite && offset == 0 && characteristic.uuid == BleHandoffProtocol.CHARACTERISTIC_UUID)
                BleHandoffProtocol.decodeRequest(value) else null
            val status = synchronized(this@BleHandoffManager) {
                if (decoded == null || !canAccept() || (request != null && System.currentTimeMillis() < expiresAt))
                    BluetoothGatt.GATT_FAILURE
                else {
                    request = decoded; requester = device; approved = null
                    expiresAt = System.currentTimeMillis() + BleHandoffProtocol.TIMEOUT_MS
                    val currentGeneration = ++generation
                    mutableState.value = mutableState.value.copy(incomingRequest = true,
                        sessionId = decoded.sessionId.toString(), status = "NEGOTIATING", lastFailure = null)
                    scope.launch {
                        delay(BleHandoffProtocol.TIMEOUT_MS)
                        synchronized(this@BleHandoffManager) {
                            if (generation == currentGeneration) clearPending()
                        }
                    }
                    BluetoothGatt.GATT_SUCCESS
                }
            }
            if (responseNeeded) runCatching { server?.sendResponse(device, requestId, status, 0, null) }
        }

        @SuppressLint("MissingPermission") // Permission is checked when opening GATT; revocation is caught.
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int,
            offset: Int, characteristic: BluetoothGattCharacteristic) {
            val payload = synchronized(this@BleHandoffManager) {
                val active = request
                if (active == null || !BlePermissions.hasConnect(context) || requester?.address != device.address ||
                    characteristic.uuid != BleHandoffProtocol.CHARACTERISTIC_UUID ||
                    System.currentTimeMillis() >= expiresAt) BleHandoffProtocol.reject()
                else when (approved) {
                    null -> BleHandoffProtocol.pending()
                    false -> BleHandoffProtocol.reject()
                    true -> {
                        val address = wifiAddress()
                        val method = BleHandoffProtocol.select(localMethod(), active.method, address != null)
                        if (method == null) BleHandoffProtocol.reject()
                        else BleHandoffProtocol.accept(method, if (method == NearbyConnectionMethod.WIFI_DIRECT) address else null,
                            localMethod() != NearbyConnectionMethod.WIFI_DIRECT)
                    }
                }
            }
            val status = if (offset in 0..payload.size) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET
            runCatching { server?.sendResponse(device, requestId, status, offset,
                if (status == BluetoothGatt.GATT_SUCCESS) payload.copyOfRange(offset, payload.size) else null) }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) synchronized(this@BleHandoffManager) {
                if (runCatching { requester?.address == device.address }.getOrDefault(false)) clearPending()
            }
        }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT checked; revocation is caught.
    @Synchronized fun startServer() {
        if (server != null || !BlePermissions.hasConnect(context)) return
        try {
            val gatt = manager?.openGattServer(context, serverCallback) ?: return
            val service = BluetoothGattService(BleHandoffProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(BleHandoffProtocol.CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE))
            server = gatt
            if (!gatt.addService(service)) { server = null; gatt.close(); return }
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(lastFailure = error.message?.take(100))
        }
    }

    @SuppressLint("MissingPermission") // Closing is best effort if permission was revoked.
    @Synchronized fun stopServer() {
        generation++
        clearPending()
        runCatching { server?.close() }
        server = null
        mutableState.value = mutableState.value.copy(serverListening = false)
    }

    @SuppressLint("MissingPermission") // Best-effort close after a permission revocation.
    fun cancelRequest() {
        clientAnswer?.complete(null)
        runCatching { clientGatt?.disconnect(); clientGatt?.close() }
        clientGatt = null
        clientAnswer = null
    }

    @Synchronized fun acceptIncoming() {
        val active = request ?: return
        val device = requester ?: return
        if (System.currentTimeMillis() >= expiresAt) { clearPending(); return }
        if (BleHandoffProtocol.select(localMethod(), active.method, wifiAddress() != null) == null) {
            declineIncoming()
            return
        }
        approved = true
        mutableState.value = mutableState.value.copy(incomingRequest = false, status = "ACCEPTED")
        onAccepted(active.sessionId, device)
    }

    @Synchronized fun declineIncoming() {
        approved = false
        mutableState.value = mutableState.value.copy(incomingRequest = false, status = "REJECTED")
    }

    private fun clearPending() {
        request = null; requester = null; approved = null; expiresAt = 0
        mutableState.value = mutableState.value.copy(incomingRequest = false, sessionId = null, status = "IDLE")
    }

    @SuppressLint("MissingPermission") // Caller checks permission; failures close GATT and return null.
    suspend fun request(device: BluetoothDevice, method: NearbyConnectionMethod,
        sessionId: UUID): BleHandoffProtocol.Offer? = withContext(Dispatchers.IO) {
        if (!BlePermissions.hasConnect(context)) return@withContext null
        val answer = CompletableDeferred<BleHandoffProtocol.Offer?>()
        clientAnswer = answer
        val payload = BleHandoffProtocol.encodeRequest(BleHandoffProtocol.Request(sessionId, method))
        var gatt: BluetoothGatt? = null
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED)
                    client.discoverServices()
                else answer.complete(null)
            }
            override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
                val characteristic = client.getService(BleHandoffProtocol.SERVICE_UUID)
                    ?.getCharacteristic(BleHandoffProtocol.CHARACTERISTIC_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) { answer.complete(null); return }
                val started = if (Build.VERSION.SDK_INT >= 33)
                    client.writeCharacteristic(characteristic, payload,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                else {
                    @Suppress("DEPRECATION")
                    characteristic.value = payload
                    @Suppress("DEPRECATION")
                    client.writeCharacteristic(characteristic)
                }
                if (!started) answer.complete(null)
            }
            override fun onCharacteristicWrite(client: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS || !client.readCharacteristic(characteristic)) answer.complete(null)
            }
            override fun onCharacteristicRead(client: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic, status: Int) {
                @Suppress("DEPRECATION")
                val value = characteristic.value
                handleRead(client, characteristic, value, status)
            }
            override fun onCharacteristicRead(client: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                handleRead(client, characteristic, value, status)
            }
            private fun handleRead(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                value: ByteArray?, status: Int) {
                val offer = if (status == BluetoothGatt.GATT_SUCCESS)
                    BleHandoffProtocol.decodeOffer(value) else null
                if (offer?.pending == true && !answer.isCompleted) scope.launch(Dispatchers.IO) {
                    delay(400)
                    if (!answer.isCompleted) runCatching { client.readCharacteristic(characteristic) }
                } else answer.complete(offer)
            }
        }
        mutableState.value = mutableState.value.copy(requesting = true, sessionId = sessionId.toString(),
            status = "NEGOTIATING", lastFailure = null)
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            clientGatt = gatt
            val offer = withTimeoutOrNull(BleHandoffProtocol.TIMEOUT_MS) { answer.await() }
            mutableState.value = mutableState.value.copy(status = if (offer?.accepted == true) "ACCEPTED" else "FAILED",
                lastFailure = if (offer?.accepted == true) null else "Nearby connection request was declined or timed out")
            offer
        } catch (error: Exception) {
            logger.log("ERROR", "BLE handoff: ${error.javaClass.simpleName}")
            mutableState.value = mutableState.value.copy(status = "FAILED", lastFailure = "Nearby handoff failed")
            null
        } finally {
            runCatching { gatt?.disconnect(); gatt?.close() }
            if (clientGatt === gatt) clientGatt = null
            if (clientAnswer === answer) clientAnswer = null
            mutableState.value = mutableState.value.copy(requesting = false)
        }
    }
}
