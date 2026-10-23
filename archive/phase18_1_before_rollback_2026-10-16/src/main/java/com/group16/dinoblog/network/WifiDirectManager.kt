package com.group16.dinoblog.network

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.group16.dinoblog.model.NetworkState
import com.group16.dinoblog.model.WifiDirectDeviceNames
import com.group16.dinoblog.model.WifiDirectDiscoveryResult
import com.group16.dinoblog.model.WifiDirectPeerStatus
import com.group16.dinoblog.util.NetworkLogger
import com.group16.dinoblog.util.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class WifiDirectManager(
    private val context: Context,
    private val transport: TcpTransport,
    private val logger: NetworkLogger,
) {
    private val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false
    private var generation = 0
    private var groupSuppressed = false
    @Volatile private var localP2pAddress: String? = null
    fun handoffAddress(): String? = if (state.value.wifiEnabled && Permissions.granted(context)) localP2pAddress else null
    private val mutableState = MutableStateFlow(NetworkState(supported = supported,
        wifiPermissionGranted = Permissions.granted(context)))
    val state = mutableState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    mutableState.update { it.copy(wifiEnabled = enabled, wifiStatus = if (enabled) "Wi-Fi Direct ready" else "Wi-Fi Direct disabled",
                        peers = if (enabled) it.peers else emptyList()) }
                    if (!enabled) clearConnection()
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> refreshPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshConnection()
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val discovering = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1) == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                    mutableState.update { it.copy(discovering = discovering) }
                }
            }
        }
    }

    fun start() {
        try { startInternal() }
        catch (error: RuntimeException) {
            // Framework Wi-Fi P2P calls can fail synchronously even after the
            // permission check (for example when the service is restarting).
            // Leave the channel/receiver in a retryable state instead of
            // allowing an automatic foreground start to close the Activity.
            if (registered) runCatching { context.unregisterReceiver(receiver) }
            registered = false
            generation++
            if (Build.VERSION.SDK_INT >= 27) runCatching { channel?.close() }
            channel = null
            runCatching { transport.stopWifi() }
            fail("Wi-Fi Direct unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun startInternal() {
        if (!supported || manager == null) { fail("This device does not support Wi-Fi Direct"); return }
        if (!Permissions.granted(context)) {
            mutableState.update { it.copy(wifiPermissionGranted = false, peers = emptyList()) }
            fail("Nearby Wi-Fi permission is required to find Wi-Fi Direct devices.")
            return
        }
        mutableState.update { it.copy(wifiPermissionGranted = true) }
        if (registered) { refreshPeers(); refreshConnection(); return }
        groupSuppressed = false
        channel = channel ?: manager.initialize(context, Looper.getMainLooper()) {
            channel = null
            stop()
            fail("Wi-Fi Direct channel lost. Tap Refresh / permissions to reconnect.")
        }
        val initializedChannel = channel ?: run { fail("Cannot initialize Wi-Fi Direct"); return }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        // These broadcasts originate from Android's Wi-Fi subsystem, outside our UID.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        val enabled = context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        mutableState.update { it.copy(wifiEnabled = enabled, wifiPermissionGranted = true,
            wifiStatus = if (enabled) "Wi-Fi Direct ready" else "Turn on Wi-Fi", error = null) }
        if (Build.VERSION.SDK_INT >= 29) manager.requestP2pState(initializedChannel) { value ->
            if (registered) mutableState.update { it.copy(wifiEnabled = value == WifiP2pManager.WIFI_P2P_STATE_ENABLED) }
        }
        refreshPeers()
        if (Build.VERSION.SDK_INT >= 29) {
            @SuppressLint("MissingPermission")
            manager.requestDeviceInfo(initializedChannel) { device ->
                if (registered) localP2pAddress = device?.deviceAddress?.takeIf { it.isNotBlank() }
            }
        }
        // Connection broadcasts are not sticky. Query on every foreground entry so an
        // existing group can resume TCP after rotation, backgrounding, or process restart.
        refreshConnection()
    }

    // guarded() checks runtime permission and catches SecurityException on every call.
    // Lint cannot follow that check through a higher-order function.
    @SuppressLint("MissingPermission")
    fun discover() = guarded {
        val locationEnabled = context.getSystemService(LocationManager::class.java)?.let {
            if (Build.VERSION.SDK_INT >= 28) it.isLocationEnabled
            else it.isProviderEnabled(LocationManager.GPS_PROVIDER) || it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } == true
        if (!locationEnabled) { fail("Enable Location in Android settings for peer discovery"); return@guarded }
        groupSuppressed = false
        // A fresh discovery must not keep old devices visible while Android refreshes its list.
        mutableState.update { it.copy(peers = emptyList()) }
        manager?.discoverPeers(channel, action("Discovery started") {
            mutableState.update { it.copy(discovering = true, error = null) }
        })
    }

    @SuppressLint("MissingPermission") // Permission/error handling is in guarded().
    fun connect(address: String) = guarded {
        groupSuppressed = false
        val config = WifiP2pConfig().apply { deviceAddress = address }
        mutableState.update { it.copy(wifiStatus = "Connecting; accept the invitation on the other phone", error = null) }
        manager?.connect(channel, config, action("Connection requested"))
    }

    fun disconnect() {
        groupSuppressed = true
        clearConnection()
        guarded {
            manager?.cancelConnect(channel, action("Cancelled connection request"))
            manager?.removeGroup(channel, action("Group disconnected") {
                mutableState.update { it.copy(wifiStatus = "Disconnected") }
            })
        }
    }

    /** Explicit user action after a failed TCP attempt; does not change the P2P group. */
    fun startTcp() {
        val group = state.value
        val ip = group.groupOwnerIp
        if (!group.groupFormed || ip == null) { fail("Connect a Wi-Fi Direct peer first"); return }
        transport.start(group.isGroupOwner, ip, force = true)
    }

    @SuppressLint("MissingPermission") // Permission/error handling is in guarded().
    private fun refreshPeers() = guarded {
        val token = generation
        manager?.requestPeers(channel) { list ->
            if (!registered || token != generation) return@requestPeers
            val peers = list.deviceList.mapNotNull { device ->
                val address = device.deviceAddress?.trim().orEmpty()
                if (address.isEmpty()) null else WifiDirectDiscoveryResult(
                    WifiDirectDeviceNames.sanitize(device.deviceName), address, peerStatus(device.status))
            }.distinctBy { it.addressInternal }
                .sortedWith(compareBy<WifiDirectDiscoveryResult> { it.deviceName }.thenBy { it.addressInternal })
            mutableState.update { it.copy(peers = peers) }
            logger.log("WIFI", "WIFI_P2P PEERS UPDATED count=${peers.size}")
        }
    }

    private fun refreshConnection() = guarded {
        val token = generation
        manager?.requestConnectionInfo(channel) { info ->
            if (!registered || token != generation || groupSuppressed) return@requestConnectionInfo
            val ip = info.groupOwnerAddress?.hostAddress
            if (info.groupFormed && ip != null) {
                mutableState.update { it.copy(groupFormed = true, isGroupOwner = info.isGroupOwner, groupOwnerIp = ip, wifiStatus = "P2P group connected", error = null) }
                transport.start(info.isGroupOwner, ip)
            } else clearConnection()
        }
    }

    private fun clearConnection() {
        transport.stopWifi()
        mutableState.update { it.copy(groupFormed = false, isGroupOwner = false, groupOwnerIp = null) }
    }

    private inline fun guarded(block: () -> Unit) {
        if (!registered || channel == null) { fail("Tap Refresh / permissions to initialize Wi-Fi Direct"); return }
        if (!Permissions.granted(context)) {
            clearConnection()
            mutableState.update { it.copy(wifiPermissionGranted = false, peers = emptyList()) }
            fail("Nearby Wi-Fi permission is required to find Wi-Fi Direct devices.")
            return
        }
        if (!mutableState.value.wifiPermissionGranted)
            mutableState.update { it.copy(wifiPermissionGranted = true, error = null) }
        try { block() } catch (e: SecurityException) {
            mutableState.update { it.copy(wifiPermissionGranted = false, peers = emptyList()) }
            fail("Nearby Wi-Fi permission is required to find Wi-Fi Direct devices.")
        }
        catch (e: RuntimeException) { fail("Wi-Fi Direct: ${e.message}") }
    }

    private fun action(label: String, success: () -> Unit = {}) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { logger.log("WIFI", label); success() }
        override fun onFailure(reason: Int) {
            val cause = when (reason) {
                WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
                WifiP2pManager.BUSY -> "busy; wait and retry"
                WifiP2pManager.ERROR -> "system error; check Wi-Fi and Location"
                else -> "error $reason"
            }
            fail("$label failed: $cause")
        }
    }

    private fun fail(message: String) {
        mutableState.update { it.copy(error = message) }
        logger.log("ERROR", message)
    }

    fun stop() {
        generation++
        if (registered) {
            if (Permissions.granted(context)) runCatching { manager?.stopPeerDiscovery(channel, null) }
            runCatching { context.unregisterReceiver(receiver) }
        }
        registered = false
        localP2pAddress = null
        // Channel.close() was introduced in API 27. On API 26 retain and reuse
        // the process-owned channel instead of leaking a new one on each resume.
        if (Build.VERSION.SDK_INT >= 27) {
            channel?.close()
            channel = null
        }
        clearConnection()
        mutableState.update { it.copy(discovering = false, peers = emptyList(),
            wifiStatus = "Paused while app is in background") }
    }

    private fun peerStatus(status: Int): WifiDirectPeerStatus = when (status) {
        WifiP2pDevice.AVAILABLE -> WifiDirectPeerStatus.AVAILABLE
        WifiP2pDevice.CONNECTED -> WifiDirectPeerStatus.CONNECTED
        WifiP2pDevice.INVITED -> WifiDirectPeerStatus.INVITED
        WifiP2pDevice.FAILED -> WifiDirectPeerStatus.FAILED
        else -> WifiDirectPeerStatus.UNAVAILABLE
    }
}
