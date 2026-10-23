package com.group16.dinoblog.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.group16.dinoblog.DinoblogApplication
import com.group16.dinoblog.network.WireProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = (application as DinoblogApplication).container
    val localId = app.identity.id
    val localName = app.identity.name
    val network = app.wifi.state
    val socket = app.transport.state
    val inventory = app.inventory.state
    val forwarding = app.delivery.forwarding
    val logs = app.logger.logs
    val messages = app.repository.messages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val receipts = app.repository.receipts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val peers = app.repository.peers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val preferences = application.getSharedPreferences("chat_preferences", Context.MODE_PRIVATE)
    private val mutableRecipient = MutableStateFlow(preferences.getString("last_recipient", "") ?: "")
    val recipient = mutableRecipient.asStateFlow()
    private val drafts = mutableMapOf<String, String>()
    private val mutableDraft = MutableStateFlow("")
    val draft = mutableDraft.asStateFlow()
    private val mutableTtl = MutableStateFlow("86400")
    val ttlInput = mutableTtl.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private val mutableSending = MutableStateFlow(false)
    val sending = mutableSending.asStateFlow()

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
        drafts[mutableRecipient.value] = mutableDraft.value
        mutableRecipient.value = target
        mutableDraft.value = drafts[target].orEmpty()
        if (WireProtocol.isUuid(target) && target != localId) {
            preferences.edit().putString("last_recipient", target).apply()
        }
    }

    fun setTtlInput(value: String) {
        if (!mutableSending.value) mutableTtl.value = value.filter(Char::isDigit).take(6)
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

    fun sendMessage(viaCarrier: Boolean = false, hopLimit: Int = 8) {
        if (mutableSending.value) return
        val text = mutableDraft.value
        if (text.isBlank()) return
        val receiverId = mutableRecipient.value
        val ttl = mutableTtl.value.toLongOrNull()
        val carrierId = if (viaCarrier) socket.value.peerId else null
        if (viaCarrier && (carrierId == null || carrierId == receiverId)) {
            mutableNotice.value = "Connect carrier B, then enter a different final destination C."
            return
        }
        if (hopLimit !in 1..255) {
            mutableNotice.value = "Hop limit must be between 1 and 255."
            return
        }
        if (!WireProtocol.isUuid(receiverId) || receiverId == localId) {
            mutableNotice.value = "Choose a peer or enter its Device ID first."
            return
        }
        if (ttl == null || ttl !in 1..WireProtocol.MAX_TTL_SECONDS) {
            mutableNotice.value = "TTL must be between 1 and 604800 seconds."
            return
        }
        mutableSending.value = true
        viewModelScope.launch {
            try {
                // Room commits before this returns, even when the recipient is offline.
                app.delivery.queue(receiverId, text, ttl, carrierId = carrierId, hopLimit = hopLimit)
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
