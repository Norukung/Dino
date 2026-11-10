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
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.repository.PreparedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = (application as DinoblogApplication).container
    val localId = app.identity.id
    val profile = app.profile.profile
    val network = app.wifi.state
    val socket = app.transport.state
    val inventory = app.inventory.state
    val forwarding = app.delivery.forwarding
    val mediaProgress = app.delivery.mediaProgress
    val predictability = app.delivery.predictability
    val predictabilities = app.repository.predictabilities.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val logs = app.logger.logs
    val messages = app.repository.messages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val media = app.media.media.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun mediaPath(mediaId: String) = app.media.filePath(mediaId)
    suspend fun verifiedMedia(mediaId: String) = app.media.verifiedForPlayback(mediaId)
    val receipts = app.repository.receipts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val peers = app.repository.peers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val preferences = application.getSharedPreferences("chat_preferences", Context.MODE_PRIVATE)
    private val mutableRecipient = MutableStateFlow(preferences.getString("last_recipient", "") ?: "")
    val recipient = mutableRecipient.asStateFlow()
    private val drafts = mutableMapOf<String, String>()
    private val mutableDraft = MutableStateFlow("")
    val draft = mutableDraft.asStateFlow()
    private val mutableAllowRelay = MutableStateFlow(preferences.getBoolean("allow_relay", true))
    val allowRelay = mutableAllowRelay.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private val mutableSending = MutableStateFlow(false)
    val sending = mutableSending.asStateFlow()
    private val mutableSelectedMedia = MutableStateFlow<PreparedMedia?>(null)
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

    fun saveDisplayName(name: String): Boolean = app.profile.setDisplayName(name)
    fun completeOnboarding(name: String?, relayEnabled: Boolean): Boolean = app.profile.completeOnboarding(name, relayEnabled)
    fun setRelayEnabled(enabled: Boolean) {
        if (app.profile.setRelayEnabled(enabled)) {
            app.logger.log("DTN", "DTN RELAY SETTING CHANGED enabled=$enabled")
            retryPending()
        } else mutableNotice.value = "Could not save this setting. Try again."
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
        if (type == MessageContentType.TEXT) return
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
            catch (error: Exception) { mutableNotice.value = error.message?.take(120) ?: "Could not use selected media." }
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
        viewModelScope.launch {
            try {
                app.delivery.queueMedia(receiverId, selected, allowRelay = mutableAllowRelay.value)
                if (mutableSelectedMedia.value == selected) mutableSelectedMedia.value = null
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) { mutableNotice.value = error.message?.take(100) ?: "Could not save media. Please try again." }
            finally { mutableSending.value = false }
        }
    }

    fun startNetwork() = app.wifi.start()
    fun stopNetwork() = app.wifi.stop()
    fun discover() = app.wifi.discover()
    fun connect(address: String) = app.wifi.connect(address)
    fun disconnect() = app.wifi.disconnect()
    fun startTcp() = app.wifi.startTcp()
    fun clearLogs() = app.logger.clear()
    fun clearNotice() { mutableNotice.value = null }
    fun retryPending() = viewModelScope.launch {
        try { app.delivery.retryPending() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableNotice.value = "Retry failed: ${e.message}" }
    }

    fun setDraft(text: String) {
        if (!mutableSending.value && text.length <= MAX_MESSAGE_CHARS) mutableDraft.value = text
    }

    fun setAllowRelay(enabled: Boolean) {
        if (mutableSending.value) return
        mutableAllowRelay.value = enabled
        preferences.edit().putBoolean("allow_relay", enabled).apply()
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
        viewModelScope.launch {
            try {
                // Room commits before this returns, even when the recipient is offline.
                app.delivery.queue(receiverId, text, DtnConfig.DEFAULT_MESSAGE_LIFETIME_SECONDS,
                    hopLimit = DtnConfig.DEFAULT_HOP_LIMIT, allowRelay = mutableAllowRelay.value)
                if (mutableRecipient.value == receiverId && mutableDraft.value == text) {
                    mutableDraft.value = ""
                    drafts.remove(receiverId)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableNotice.value = "Could not save message: ${e.message}. Your draft is kept." }
            finally { mutableSending.value = false }
        }
    }

    companion object { const val MAX_MESSAGE_CHARS = WireProtocol.MAX_PAYLOAD_CHARS }
}
