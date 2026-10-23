package com.group16.dinoblog.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.group16.dinoblog.DinoblogApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = (application as DinoblogApplication).container
    val localId = app.identity.id
    val localName = app.identity.name
    val network = app.wifi.state
    val socket = app.transport.state
    val logs = app.logger.logs
    val messages = app.repository.messages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val peers = app.repository.peers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableSending = MutableStateFlow(false)
    val sending = mutableSending.asStateFlow()

    fun startNetwork() = app.wifi.start()
    fun stopNetwork() = app.wifi.stop()
    fun discover() = app.wifi.discover()
    fun connect(address: String) = app.wifi.connect(address)
    fun disconnect() = app.wifi.disconnect()
    fun clearLogs() = app.logger.clear()
    fun clearError() { mutableError.value = null }
    fun retry() = viewModelScope.launch {
        try { app.delivery.retryPending() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableError.value = e.message ?: "Retry failed" }
    }

    fun send(receiverId: String, payload: String, ttl: Long, onSaved: () -> Unit) {
        if (mutableSending.value) return
        mutableSending.value = true
        viewModelScope.launch {
            try {
                app.delivery.queue(receiverId, payload, ttl)
                onSaved()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableError.value = e.message ?: "Could not save message" }
            finally { mutableSending.value = false }
        }
    }
}
