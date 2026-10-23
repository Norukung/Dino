package com.group16.dinoblog.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NearbyDiscoveryState(
    val supported: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val scannerAvailable: Boolean = false,
    val advertiserAvailable: Boolean = false,
    val scanPermission: Boolean = false,
    val advertisePermission: Boolean = false,
    val scanning: Boolean = false,
    val advertising: Boolean = false,
    val currentToken: String? = null,
    val tokenCreatedAt: Long? = null,
    val nameBroadcastEnabled: Boolean = false,
    val currentNearbyName: String? = null,
    val nearbyNameBytes: Int = 0,
    val primaryAdvertisementBytes: Int = BleDiscoveryProtocol.MAX_LEGACY_ADVERTISEMENT_BYTES,
    val scanResponseBytes: Int = 0,
    val results: List<NearbyDiscoveryResult> = emptyList(),
    val lastScanError: String? = null,
    val lastAdvertiseError: String? = null,
)

/** Owns only BLE presence. No packet, contact, Room, routing or identity operations live here. */
class NearbyDiscoveryManager(private val context: Context, private val logger: NetworkLogger,
    private val displayName: () -> String = { "DinoBlog User" },
    private val showName: () -> Boolean = { true }) {
    private val handler = Handler(Looper.getMainLooper())
    private val serviceUuid = ParcelUuid(BleDiscoveryProtocol.SERVICE_UUID)
    private val nameServiceUuid = ParcelUuid(BleDiscoveryProtocol.NAME_SERVICE_UUID)
    private val tracker = NearbyResultTracker()
    // Transport endpoints are transient and never exposed in NearbyDiscoveryResult or persisted.
    private val endpoints = mutableMapOf<String, BluetoothDevice>()
    private val pendingNames = mutableMapOf<BluetoothDevice, ByteArray>()
    private var selectedSessionEndpoint: BluetoothDevice? = null
    private var selectedSessionToken: String? = null
    private val bluetoothManager get() = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter
    private val mutableState = MutableStateFlow(NearbyDiscoveryState())
    val state = mutableState.asStateFlow()
    private var foreground = false
    private var visible = false
    private var discoverable = false
    private var token = BleDiscoveryProtocol.newToken()
    private var tokenCreatedAt = System.currentTimeMillis()
    private val recentOwnTokens = ArrayDeque<String>().apply { add(BleDiscoveryProtocol.run { token.toHex() }) }
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private val stopScan = Runnable { stopScanning() }
    private val expireResults = object : Runnable {
        override fun run() {
            if (!visible) return
            val expired = tracker.expire(System.currentTimeMillis())
            if (expired > 0) {
                val live = tracker.snapshot().mapTo(mutableSetOf()) { it.discoveryToken }
                endpoints.keys.retainAll(live)
                publishResults()
                logger.log("BLE", "BLE DISCOVERY RESULT EXPIRED count=$expired")
            }
            handler.postDelayed(this, 1_000L)
        }
    }
    private val rotateToken = object : Runnable {
        override fun run() {
            if (!foreground || !discoverable) return
            stopAdvertising()
            startAdvertising()
        }
    }

    init { refreshStatus() }

    fun refreshStatus() {
        val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && adapter != null
        val canCheck = BlePermissions.hasConnect(context)
        val enabled = if (supported && canCheck) runCatching { adapter?.isEnabled == true }.getOrDefault(false) else false
        val scanner = if (enabled) runCatching { adapter?.bluetoothLeScanner != null }.getOrDefault(false) else false
        val advertiser = if (enabled) runCatching { adapter?.bluetoothLeAdvertiser != null }.getOrDefault(false) else false
        mutableState.value = mutableState.value.copy(supported = supported, bluetoothEnabled = enabled,
            scannerAvailable = scanner, advertiserAvailable = advertiser,
            scanPermission = BlePermissions.hasScan(context), advertisePermission = BlePermissions.hasAdvertise(context))
    }

    fun onBluetoothStateChanged() {
        refreshStatus()
        if (!mutableState.value.bluetoothEnabled) {
            stopScanning()
            stopAdvertising()
        } else if (foreground) {
            if (discoverable) startAdvertising()
            if (visible) refreshScan()
        }
    }

    fun startForeground(discoverableEnabled: Boolean) {
        foreground = true
        discoverable = discoverableEnabled
        refreshStatus()
        if (discoverable) startAdvertising()
        if (visible) {
            handler.removeCallbacks(expireResults)
            handler.post(expireResults)
            refreshScan()
        }
    }

    fun stopForeground() {
        foreground = false
        stopScanning()
        handler.removeCallbacks(expireResults)
        stopAdvertising()
    }

    fun setDiscoverable(enabled: Boolean) {
        discoverable = enabled
        if (enabled && foreground) startAdvertising() else stopAdvertising()
    }

    /** Profile changes update scan response while preserving the current ephemeral token. */
    fun refreshAdvertisement() {
        if (!foreground || !discoverable) return
        stopAdvertising(rotate = false)
        startAdvertising()
    }

    fun startVisible() {
        visible = true
        handler.removeCallbacks(expireResults)
        handler.post(expireResults)
        refreshScan()
    }

    fun stopVisible() {
        visible = false
        stopScanning()
        handler.removeCallbacks(expireResults)
        tracker.clear()
        endpoints.clear()
        pendingNames.clear()
        clearSelectedSession()
        publishResults()
    }

    /** Suppress a rotated duplicate from the same temporary endpoint during this handoff only. */
    fun selectSession(discoveryToken: String) {
        selectedSessionEndpoint = endpointFor(discoveryToken)
        selectedSessionToken = discoveryToken.takeIf { selectedSessionEndpoint != null }
        val duplicateTokens = endpoints.filter { (token, device) ->
            token != discoveryToken && device == selectedSessionEndpoint
        }.keys.toList()
        duplicateTokens.forEach { endpoints.remove(it); tracker.remove(it) }
        if (duplicateTokens.isNotEmpty()) publishResults()
    }

    fun clearSelectedSession() {
        selectedSessionEndpoint = null
        selectedSessionToken = null
    }

    @SuppressLint("MissingPermission") // BlePermissions.hasScan above, with revoked-permission exceptions caught below.
    fun refreshScan() {
        stopScanning()
        refreshStatus()
        val current = mutableState.value
        if (!visible || !foreground) return
        if (!current.supported) { logger.log("BLE", "BLE DISCOVERY UNSUPPORTED"); return }
        if (!current.scanPermission) { logger.log("BLE", "BLE DISCOVERY PERMISSION DENIED scan"); return }
        if (!current.bluetoothEnabled || !current.scannerAvailable) return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handler.post { if (scanCallback === this) observe(result) }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                handler.post { if (scanCallback === this) results.forEach(::observe) }
            }
            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    if (scanCallback === this) {
                        mutableState.value = mutableState.value.copy(lastScanError = "Scan failed ($errorCode)")
                        logger.log("BLE", "BLE DISCOVERY SCAN FAILED code=$errorCode")
                        stopScanning()
                    }
                }
            }
        }
        val filter = ScanFilter.Builder().setServiceData(serviceUuid, byteArrayOf(1), byteArrayOf(0xff.toByte())).build()
        val nameFilter = ScanFilter.Builder().setServiceData(nameServiceUuid, byteArrayOf(0), byteArrayOf(0)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            adapter?.bluetoothLeScanner?.startScan(listOf(filter, nameFilter), settings, callback)
            scanCallback = callback
            mutableState.value = mutableState.value.copy(scanning = true, lastScanError = null)
            handler.postDelayed(stopScan, BleDiscoveryProtocol.SCAN_MS)
            logger.log("BLE", "BLE DISCOVERY SCAN START")
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(lastScanError = error.javaClass.simpleName)
            logger.log("BLE", "BLE DISCOVERY SCAN FAILED ${error.javaClass.simpleName}")
        }
    }

    private fun observe(result: ScanResult) {
        val record = result.scanRecord
        val nameData = record?.getServiceData(nameServiceUuid)
        val primary = record?.getServiceData(serviceUuid)
        if (primary == null && nameData != null) {
            val token = endpoints.entries.firstOrNull { it.value == result.device }?.key
            if (token != null) {
                tracker.updateName(token, nameData, System.currentTimeMillis())
                publishResults()
            } else if (pendingNames.size < 64) pendingNames[result.device] = nameData
            return
        }
        val ad = BleDiscoveryProtocol.decode(primary, nameData ?: pendingNames.remove(result.device))
        if (ad == null) { logger.log("BLE", "BLE DISCOVERY MALFORMED_PACKET"); return }
        if (ad.token in recentOwnTokens) return
        if (selectedSessionEndpoint == result.device && selectedSessionToken != ad.token) return
        val old = mutableState.value.results.any { it.discoveryToken == ad.token }
        tracker.observe(ad, result.rssi, System.currentTimeMillis())
        endpoints[ad.token] = result.device
        publishResults()
        logger.log("BLE", "BLE DISCOVERY RESULT ${if (old) "UPDATED " else ""}token=${ad.token} rssi=${result.rssi} named=${ad.nearbyDisplayName != null}")
    }

    private fun publishResults() { mutableState.value = mutableState.value.copy(results = tracker.snapshot()) }

    /** Only valid while the unexpired BLE result is visible in this process. */
    fun endpointFor(discoveryToken: String): BluetoothDevice? =
        if (tracker.snapshot().any { it.discoveryToken == discoveryToken }) endpoints[discoveryToken] else null

    @SuppressLint("MissingPermission") // Permission can be revoked mid-scan; stop is guarded by runCatching.
    private fun stopScanning() {
        handler.removeCallbacks(stopScan)
        val callback = scanCallback ?: return
        scanCallback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        mutableState.value = mutableState.value.copy(scanning = false)
        logger.log("BLE", "BLE DISCOVERY SCAN STOP")
    }

    private fun startAdvertising() {
        if (advertiseCallback != null) return
        refreshStatus()
        val current = mutableState.value
        if (!foreground || !discoverable) return
        if (!current.supported) { logger.log("BLE", "BLE DISCOVERY UNSUPPORTED"); return }
        if (!current.advertisePermission) { logger.log("BLE", "BLE DISCOVERY PERMISSION DENIED advertise"); return }
        if (!current.bluetoothEnabled) return
        if (!current.advertiserAvailable) {
            mutableState.value = current.copy(lastAdvertiseError = "BLE advertising is unavailable on this device")
            return
        }
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                handler.post { if (advertiseCallback === this) {
                    mutableState.value = mutableState.value.copy(advertising = true, lastAdvertiseError = null)
                    logger.log("BLE", "BLE ADVERTISE START")
                } }
            }
            override fun onStartFailure(errorCode: Int) {
                handler.post { if (advertiseCallback === this) {
                    advertiseCallback = null
                    handler.removeCallbacks(rotateToken)
                    mutableState.value = mutableState.value.copy(advertising = false, lastAdvertiseError = "Advertising failed ($errorCode)")
                    logger.log("BLE", "BLE ADVERTISE FAILED code=$errorCode")
                } }
            }
        }
        val data = AdvertiseData.Builder().addServiceData(serviceUuid, BleDiscoveryProtocol.encode(token))
            .setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build()
        val namePayload = BleDiscoveryProtocol.encodeName(displayName(), showName())
        val response = AdvertiseData.Builder().addServiceData(nameServiceUuid, namePayload)
            .setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build()
        // Primary service data remains anonymous; the optional scan response is an unverified name hint.
        val settings = AdvertiseSettings.Builder().setConnectable(true)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()
        try {
            adapter?.bluetoothLeAdvertiser?.startAdvertising(settings, data, response, callback)
            advertiseCallback = callback
            mutableState.value = mutableState.value.copy(currentToken = BleDiscoveryProtocol.run { token.toHex() }, tokenCreatedAt = tokenCreatedAt,
                nameBroadcastEnabled = showName(),
                currentNearbyName = BleDiscoveryProtocol.decodeName(namePayload),
                nearbyNameBytes = if (showName()) namePayload.size else 0,
                scanResponseBytes = BleDiscoveryProtocol.SCAN_RESPONSE_OVERHEAD_BYTES + namePayload.size,
                lastAdvertiseError = null)
            handler.removeCallbacks(rotateToken)
            handler.postDelayed(rotateToken, BleDiscoveryProtocol.ROTATION_MS)
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(lastAdvertiseError = error.javaClass.simpleName)
            logger.log("BLE", "BLE ADVERTISE FAILED ${error.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission") // Permission can be revoked mid-advertisement; stop is guarded by runCatching.
    private fun stopAdvertising(rotate: Boolean = true) {
        handler.removeCallbacks(rotateToken)
        val callback = advertiseCallback ?: return
        advertiseCallback = null
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        mutableState.value = mutableState.value.copy(advertising = false, currentToken = null, tokenCreatedAt = null,
            currentNearbyName = null, nearbyNameBytes = 0, scanResponseBytes = 0)
        if (rotate) {
            token = BleDiscoveryProtocol.newToken()
            tokenCreatedAt = System.currentTimeMillis()
            recentOwnTokens.addLast(BleDiscoveryProtocol.run { token.toHex() })
            while (recentOwnTokens.size > 3) recentOwnTokens.removeFirst()
            logger.log("BLE", "BLE DISCOVERY TOKEN ROTATED")
        }
        logger.log("BLE", "BLE ADVERTISE STOP")
    }
}
