package com.group16.dinoblog.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.group16.dinoblog.DinoblogApplication
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.dtn.DtnConfig
import com.group16.dinoblog.model.DisplayNames
import com.group16.dinoblog.model.WifiDirectDiscoveryResult
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.MediaPolicy
import com.group16.dinoblog.model.RecallState
import com.group16.dinoblog.model.RoutingExperimentMode
import com.group16.dinoblog.model.RelayPolicy
import com.group16.dinoblog.repository.PreparedImage
import com.group16.dinoblog.repository.NearbyConnectionMethod
import com.group16.dinoblog.nearby.BleHandoffProtocol
import com.group16.dinoblog.nearby.NearbyDiscoveryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import java.util.UUID

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = (application as DinoblogApplication).container
    val localId = app.identity.id
    val securityFingerprint: String get() = runCatching { app.security.fingerprint }.getOrElse { "IDENTITY KEY UNAVAILABLE" }
    val lastSecurityError: String? get() = app.security.lastSecurityError
    val lastMessageSignatureVerified: Boolean? get() = app.security.lastMessageSignatureVerified
    val lastReceiptSignatureVerified: Boolean? get() = app.security.lastReceiptSignatureVerified
    val profile = app.profile.profile
    val nearby = app.nearby.state
    val bluetooth = app.bluetooth.state
    val handoff = app.handoff.state
    private val mutableNearbyConnecting = MutableStateFlow(false)
    private var nearbyAttemptJob: Job? = null
    val nearbyConnecting = mutableNearbyConnecting.asStateFlow()
    private val mutableSelectedNearbyToken = MutableStateFlow<String?>(null)
    val selectedNearbyToken = mutableSelectedNearbyToken.asStateFlow()
    private val mutableSelectedNearbyResult = MutableStateFlow<NearbyDiscoveryResult?>(null)
    val selectedNearbyResult = mutableSelectedNearbyResult.asStateFlow()
    private val mutableSelectedWifiAddress = MutableStateFlow<String?>(null)
    val selectedWifiAddress = mutableSelectedWifiAddress.asStateFlow()
    private val mutableSelectedWifiResult = MutableStateFlow<WifiDirectDiscoveryResult?>(null)
    val selectedWifiResult = mutableSelectedWifiResult.asStateFlow()
    val network = app.wifi.state
    val wifiHandoffAddress: String? get() = app.wifi.handoffAddress()
    val socket = app.transport.state
    val inventory = app.inventory.state
    val forwarding = app.delivery.forwarding
    val mediaProgress = combine(app.delivery.mediaProgress, app.coordinator.mediaProgress) { dtn, online ->
        dtn + online
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val predictability = app.delivery.predictability
    val predictabilities = app.repository.predictabilities.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val logs = app.logger.logs
    val messages = app.repository.messages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recallStates = app.recalls.states.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val revokes = app.recalls.revokes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val revokeResults = app.recalls.results.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val media = app.media.media.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val server = app.coordinator.state
    val onlineMessages = app.coordinator.onlineMessages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val experiment = app.experiment.current
    val experimentDashboard = app.experiment.dashboard
    val experimentSessions = app.experiment.sessions
    fun mediaPath(mediaId: String) = app.media.filePath(mediaId)
    val receipts = app.repository.receipts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val peers = app.repository.peers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val peerIdentities = app.peerIdentities.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val preferences = application.getSharedPreferences("chat_preferences", Context.MODE_PRIVATE)
    private val mutableRecipient = MutableStateFlow(preferences.getString("last_recipient", "") ?: "")
    val recipient = mutableRecipient.asStateFlow()
    private val drafts = mutableMapOf<String, String>()
    private val mutableDraft = MutableStateFlow("")
    val draft = mutableDraft.asStateFlow()
    private val mutableComposerRelayPolicy = MutableStateFlow(app.profile.profile.value.defaultRelayPolicy)
    val composerRelayPolicy = mutableComposerRelayPolicy.asStateFlow()
    private var composerPolicyOverridden = false
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private val mutableSending = MutableStateFlow(false)
    val sending = mutableSending.asStateFlow()
    private val mutableSelectedMedia = MutableStateFlow<PreparedImage?>(null)
    val selectedMedia = mutableSelectedMedia.asStateFlow()
    private val mutablePreparingMedia = MutableStateFlow(false)
    val preparingMedia = mutablePreparingMedia.asStateFlow()

    init {
        viewModelScope.launch {
            socket.collect { connection ->
                // Fill the first recipient after HELLO. Do not replace a chosen
                // offline recipient or redirect their draft to a different peer.
                if (mutableRecipient.value.isBlank()) connection.peerId?.let(::selectRecipient)
            }
        }
        viewModelScope.launch {
            var wasConnected = false
            socket.collect { connection ->
                if (wasConnected && !connection.connected) {
                    mutableSelectedNearbyToken.value = null
                    mutableSelectedNearbyResult.value = null
                    clearSelectedWifi()
                    app.nearby.clearSelectedSession()
                }
                wasConnected = connection.connected
            }
        }
        viewModelScope.launch {
            network.collect { wifi ->
                if (wifi.error != null && !wifi.groupFormed && !socket.value.connected &&
                    mutableSelectedNearbyToken.value == null) clearSelectedWifi()
            }
        }
    }

    fun selectRecipient(id: String) {
        if (mutableSending.value) return
        val target = id.trim()
        if (target == mutableRecipient.value) return
        cancelMedia()
        drafts[mutableRecipient.value] = mutableDraft.value
        mutableRecipient.value = target
        mutableDraft.value = drafts[target].orEmpty()
        if (WireProtocol.isUuid(target) && target != localId) {
            preferences.edit().putString("last_recipient", target).apply()
        }
    }

    fun saveDisplayName(name: String): Boolean = app.profile.setDisplayName(name).also {
        if (it) app.nearby.refreshAdvertisement()
    }
    fun completeOnboarding(name: String?, relayEnabled: Boolean): Boolean =
        app.profile.completeOnboarding(name, relayEnabled).also { if (it) app.nearby.refreshAdvertisement() }
    fun setRelayEnabled(enabled: Boolean) {
        if (app.profile.setRelayEnabled(enabled)) {
            app.logger.log("DTN", "DTN RELAY SETTING CHANGED enabled=$enabled")
            retryPending()
        } else mutableNotice.value = "Could not save this setting. Try again."
    }
    fun setDefaultRelayPolicy(policy: RelayPolicy) {
        if (app.profile.setDefaultRelayPolicy(policy)) {
            if (!composerPolicyOverridden) mutableComposerRelayPolicy.value = policy
        } else mutableNotice.value = "Could not save this setting. Try again."
    }
    fun setComposerRelayPolicy(policy: RelayPolicy) {
        if (mutableSending.value) return
        mutableComposerRelayPolicy.value = policy
        composerPolicyOverridden = true
    }
    private fun resetComposerRelayPolicy() {
        composerPolicyOverridden = false
        mutableComposerRelayPolicy.value = app.profile.profile.value.defaultRelayPolicy
    }
    fun startNearbyForeground() {
        val discoverable = app.profile.profile.value.nearbyDiscoverableEnabled
        if (discoverable) app.handoff.startServer()
        app.nearby.startForeground(discoverable)
        if (discoverable) app.bluetooth.startListening()
    }
    fun stopNearbyForeground(closeSession: Boolean) {
        app.nearby.stopForeground()
        if (closeSession) { nearbyAttemptJob?.cancel(); app.handoff.cancelRequest() }
        app.handoff.stopServer()
        app.bluetooth.stop()
        if (closeSession) app.transport.disconnectBluetooth()
    }
    fun openNearby() = app.nearby.startVisible()
    fun closeNearby() {
        app.nearby.stopVisible()
        // The selected row still belongs to an authenticated active connection
        // when the user briefly visits Chat or another screen.
        if (!app.transport.state.value.connected) {
            mutableSelectedNearbyToken.value = null
            mutableSelectedNearbyResult.value = null
            clearSelectedWifi()
        }
    }
    fun refreshNearby() = app.nearby.refreshScan()
    fun refreshNearbyStatus() = app.nearby.refreshStatus()
    fun nearbyBluetoothChanged() = app.nearby.onBluetoothStateChanged()
    fun acceptBluetoothIncoming() = app.bluetooth.acceptIncoming()
    fun declineBluetoothIncoming() = app.bluetooth.declineIncoming()
    fun acceptHandoffIncoming() = app.handoff.acceptIncoming()
    fun declineHandoffIncoming() = app.handoff.declineIncoming()
    fun setNearbyConnectionMethod(method: NearbyConnectionMethod) {
        if (!app.profile.setNearbyConnectionMethod(method)) mutableNotice.value = "Could not save this setting. Try again."
    }
    fun connectNearby(discoveryToken: String) {
        if (app.transport.state.value.connected || mutableNearbyConnecting.value) return
        app.nearby.refreshStatus()
        if (!com.group16.dinoblog.nearby.BlePermissions.hasConnect(getApplication())) {
            mutableNotice.value = "Allow Bluetooth connection permission."
            return
        }
        if (!app.nearby.state.value.bluetoothEnabled) {
            mutableNotice.value = "Turn on Bluetooth to connect."
            return
        }
        val endpoint = app.nearby.endpointFor(discoveryToken)
        if (endpoint == null) { mutableNotice.value = "Nearby result expired. Scan again."; return }
        mutableSelectedNearbyToken.value = discoveryToken
        mutableSelectedNearbyResult.value = app.nearby.state.value.results.firstOrNull { it.discoveryToken == discoveryToken }
        clearSelectedWifi()
        app.nearby.selectSession(discoveryToken)
        val preference = app.profile.profile.value.nearbyConnectionMethod
        nearbyAttemptJob = viewModelScope.launch {
            mutableNearbyConnecting.value = true
            try {
                val id = UUID.randomUUID()
                val offer = app.handoff.request(endpoint, preference, id)
                if (offer == null || !BleHandoffProtocol.acceptsOffer(preference, offer)) {
                    mutableNotice.value = if (preference == NearbyConnectionMethod.WIFI_DIRECT)
                        "Unable to connect using Wi-Fi Direct." else
                        app.handoff.state.value.lastFailure ?: "Nearby connection failed"
                    return@launch
                }
                app.transport.prepareHandoff(id.toString())
                val wifiReady = app.wifi.state.value.let { it.supported && it.wifiEnabled }
                if (offer.transport == NearbyConnectionMethod.WIFI_DIRECT && wifiReady && offer.wifiAddress != null) {
                    selectWifi(offer.wifiAddress)
                    app.wifi.connect(offer.wifiAddress)
                    val wifiConnected = withTimeoutOrNull(20_000) {
                        app.transport.state.first { it.connected && it.transportType == "WIFI_DIRECT" }
                    } != null
                    if (wifiConnected) return@launch
                    app.wifi.disconnect()
                    if (preference == NearbyConnectionMethod.AUTO && offer.bluetoothSupported) {
                        clearSelectedWifi()
                        app.experiment.record("AUTO_FALLBACK", transport = "BLUETOOTH", reason = "WIFI_TIMEOUT")
                        app.bluetooth.connect(endpoint)
                        withTimeoutOrNull(25_000) { app.transport.state.first { it.connected && it.transportType == "BLUETOOTH" } }
                    } else mutableNotice.value = "Unable to connect using Wi-Fi Direct."
                } else if (preference != NearbyConnectionMethod.WIFI_DIRECT && offer.bluetoothSupported) {
                    app.bluetooth.connect(endpoint)
                    withTimeoutOrNull(25_000) { app.transport.state.first { it.connected && it.transportType == "BLUETOOTH" } }
                } else mutableNotice.value = "Unable to connect using Wi-Fi Direct."
            } finally {
                mutableNearbyConnecting.value = false
                if (!app.transport.state.value.connected) {
                    mutableSelectedNearbyToken.value = null
                    mutableSelectedNearbyResult.value = null
                    clearSelectedWifi()
                    app.nearby.clearSelectedSession()
                }
            }
        }
    }
    fun markNearbyPromptShown() { app.profile.markNearbyPromptShown() }
    fun setShowNearbyDisplayName(enabled: Boolean) {
        if (app.profile.setShowNearbyDisplayName(enabled)) {
            app.nearby.refreshAdvertisement()
            app.logger.log("BLE", "BLE NAME VISIBILITY CHANGED enabled=$enabled")
        } else mutableNotice.value = "Could not save this setting. Try again."
    }
    fun setNearbyDiscoverableEnabled(enabled: Boolean) {
        if (enabled) {
            app.nearby.refreshStatus()
            val capability = app.nearby.state.value
            if (!capability.supported || (capability.bluetoothEnabled && !capability.advertiserAvailable)) {
                mutableNotice.value = "This device does not support BLE advertising. Scanning may still work."
                return
            }
        }
        if (app.profile.setNearbyDiscoverableEnabled(enabled)) {
            if (enabled) { app.handoff.startServer(); app.bluetooth.startListening(); app.nearby.setDiscoverable(true) }
            else { app.nearby.setDiscoverable(false); app.handoff.stopServer(); app.bluetooth.stop() }
        }
        else mutableNotice.value = "Could not save this setting. Try again."
    }
    fun savePeerAlias(deviceId: String, raw: String) = viewModelScope.launch {
        val alias = DisplayNames.normalize(raw)
        if (alias == null) { mutableNotice.value = "Name must be 1 to 32 characters"; return@launch }
        if (!WireProtocol.isUuid(deviceId)) return@launch
        app.repository.setPeerAlias(deviceId, alias)
    }
    fun removePeerAlias(deviceId: String) = viewModelScope.launch {
        if (WireProtocol.isUuid(deviceId)) app.repository.setPeerAlias(deviceId, null)
    }
    fun saveProfileImage(uri: Uri) = viewModelScope.launch {
        val saved = withContext(Dispatchers.IO) { app.profile.saveImage(uri) }
        if (!saved) mutableNotice.value = "Could not use that picture. Try another image."
    }
    fun removeProfileImage() = app.profile.removeImage()

    fun selectMedia(uri: Uri, type: MessageContentType) {
        if (mutableSending.value || mutablePreparingMedia.value) return
        val target = mutableRecipient.value
        mutablePreparingMedia.value = true
        viewModelScope.launch {
            try {
                val prepared = app.media.prepare(uri, type)
                if (target != mutableRecipient.value) {
                    app.media.discardPrepared(prepared)
                    return@launch
                }
                mutableSelectedMedia.value?.let { app.media.discardPrepared(it) }
                mutableSelectedMedia.value = prepared
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) { mutableNotice.value = error.message?.take(100) ?: "Could not use that media file." }
            finally { mutablePreparingMedia.value = false }
        }
    }

    fun cancelMedia() {
        val selected = mutableSelectedMedia.value ?: return
        mutableSelectedMedia.value = null
        viewModelScope.launch { app.media.discardPrepared(selected) }
    }

    fun sendMedia() {
        val selected = mutableSelectedMedia.value ?: return
        if (mutableSending.value || mutablePreparingMedia.value) return
        val receiverId = mutableRecipient.value
        if (!WireProtocol.isUuid(receiverId) || receiverId == localId) {
            mutableNotice.value = "Choose a person first."
            return
        }
        mutableSending.value = true
        val relayPolicy = mutableComposerRelayPolicy.value
        viewModelScope.launch {
            try {
                app.delivery.queueMedia(receiverId, selected, relayPolicy)
                if (mutableSelectedMedia.value == selected) mutableSelectedMedia.value = null
                resetComposerRelayPolicy()
                if ((app.profile.profile.value.nearbyConnectionMethod == NearbyConnectionMethod.BLUETOOTH ||
                    app.transport.state.value.transportType == "BLUETOOTH") &&
                    !MediaPolicy.bluetoothAllowed(selected.manifest.mediaType, selected.manifest.fileSize))
                    mutableNotice.value = "This media is too large for Bluetooth delivery. Use Wi-Fi Direct or Internet. It remains queued."
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) { mutableNotice.value = error.message?.take(100) ?: "Could not save media. Please try again." }
            finally { mutableSending.value = false }
        }
    }

    fun cancelDelivery(messageId: String) = viewModelScope.launch {
        try {
            val state = app.recalls.cancelOwn(messageId)
            when (state) {
                RecallState.LOCAL_CANCELLED -> {
                    mutableNotice.value = "Cancelled before leaving this phone."
                }
                RecallState.REQUESTED -> {
                    mutableNotice.value = "Cancel requested. DinoBlog will try to stop delivery."
                    app.coordinator.wake()
                    app.delivery.retryPending()
                }
                RecallState.ALREADY_DELIVERED -> mutableNotice.value = "Message has already been delivered."
                else -> Unit
            }
        } catch (error: Exception) { mutableNotice.value = error.message?.take(100) ?: "Could not cancel delivery." }
    }

    fun startNetwork() = app.wifi.start()
    fun startOnline() = app.coordinator.start()
    fun stopOnline() = app.coordinator.stop()
    fun setServerUrl(url: String) = runCatching { app.coordinator.setDebugUrl(url) }
        .onFailure { mutableNotice.value = "Enter an HTTP or HTTPS server URL." }
    fun syncOnline() = app.coordinator.wake()
    fun stopNetwork() = app.wifi.stop()
    fun discover() = app.wifi.discover()
    private fun selectWifi(address: String) {
        mutableSelectedWifiAddress.value = address
        mutableSelectedWifiResult.value = app.wifi.state.value.peers.firstOrNull { it.addressInternal == address }
    }
    private fun clearSelectedWifi() {
        mutableSelectedWifiAddress.value = null
        mutableSelectedWifiResult.value = null
    }
    fun connect(address: String) {
        val peer = app.wifi.state.value.peers.firstOrNull { it.addressInternal == address }
        if (peer == null) { mutableNotice.value = "Wi-Fi Direct device is no longer available. Scan again."; return }
        if (app.profile.profile.value.nearbyConnectionMethod == NearbyConnectionMethod.BLUETOOTH) {
            mutableNotice.value = "Choose Auto or Wi-Fi Direct to connect to this device."
            return
        }
        mutableSelectedNearbyToken.value = null
        mutableSelectedNearbyResult.value = null
        app.nearby.clearSelectedSession()
        selectWifi(address)
        app.wifi.connect(address)
    }
    fun disconnect() {
        mutableSelectedNearbyToken.value = null
        mutableSelectedNearbyResult.value = null
        clearSelectedWifi()
        app.nearby.clearSelectedSession()
        if (app.transport.state.value.transportType == "BLUETOOTH") app.transport.disconnectBluetooth()
        else app.wifi.disconnect()
    }
    fun startTcp() = app.wifi.startTcp()
    fun clearLogs() = app.logger.clear()
    fun startExperiment(label: String, mode: RoutingExperimentMode, nodeCount: Int?, target: Int?, notes: String) = viewModelScope.launch {
        runCatching { app.experiment.start(label, mode, nodeCount, target, notes) }
            .onFailure { mutableNotice.value = it.message ?: "Could not start experiment" }
    }
    fun stopExperiment() = viewModelScope.launch {
        runCatching { app.experiment.stop() }
            .onFailure { mutableNotice.value = it.message ?: "Could not stop experiment" }
    }
    fun resetExperiment() = viewModelScope.launch {
        runCatching { app.experiment.reset() }
            .onFailure { mutableNotice.value = it.message ?: "Could not reset metrics" }
    }
    fun deleteExperiment(id: String) = viewModelScope.launch {
        runCatching { app.experiment.delete(id) }
            .onFailure { mutableNotice.value = it.message ?: "Could not delete experiment" }
    }
    fun exportExperiment(id: String, uri: Uri) = viewModelScope.launch {
        runCatching { app.experiment.export(id, uri) }
            .onSuccess { mutableNotice.value = "Experiment CSV exported" }
            .onFailure { mutableNotice.value = it.message ?: "Could not export CSV" }
    }
    fun clearNotice() { mutableNotice.value = null }
    fun retryPending() = viewModelScope.launch {
        try { app.delivery.retryPending() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableNotice.value = "Retry failed: ${e.message}" }
    }

    fun setDraft(text: String) {
        if (!mutableSending.value && text.length <= MAX_MESSAGE_CHARS) mutableDraft.value = text
    }

    fun sendMessage() {
        if (mutableSending.value) return
        val text = mutableDraft.value
        if (text.isBlank()) return
        val receiverId = mutableRecipient.value
        if (!WireProtocol.isUuid(receiverId) || receiverId == localId) {
            mutableNotice.value = "Choose a peer or enter its Device ID first."
            return
        }
        mutableSending.value = true
        val relayPolicy = mutableComposerRelayPolicy.value
        viewModelScope.launch {
            try {
                // Room commits before this returns, even when the recipient is offline.
                app.delivery.queue(receiverId, text, DtnConfig.DEFAULT_MESSAGE_LIFETIME_SECONDS,
                    hopLimit = DtnConfig.DEFAULT_HOP_LIMIT, relayPolicy = relayPolicy)
                if (mutableRecipient.value == receiverId && mutableDraft.value == text) {
                    mutableDraft.value = ""
                    drafts.remove(receiverId)
                }
                resetComposerRelayPolicy()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableNotice.value = "Could not save message: ${e.message}. Your draft is kept." }
            finally { mutableSending.value = false }
        }
    }

    companion object { const val MAX_MESSAGE_CHARS = WireProtocol.MAX_PAYLOAD_CHARS }
}
