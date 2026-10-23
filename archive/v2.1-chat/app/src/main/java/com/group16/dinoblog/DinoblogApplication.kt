package com.group16.dinoblog

import android.app.Application
import com.group16.dinoblog.network.TcpTransport
import com.group16.dinoblog.network.WifiDirectManager
import com.group16.dinoblog.util.DeviceIdentity
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DinoblogApplication : Application() {
    val container by lazy { AppContainer(this) }
}

/** Phase 2 owns one Wi-Fi Direct connection and one in-memory TCP text stream. */
class AppContainer(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val identity = DeviceIdentity(application)
    val logger = NetworkLogger()
    val transport = TcpTransport(scope, logger)
    val wifi = WifiDirectManager(application, transport, logger)
}
