package com.group16.dinoblog

import android.app.Application
import androidx.room.Room
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.network.TcpTransport
import com.group16.dinoblog.network.WifiDirectManager
import com.group16.dinoblog.repository.MessageRepository
import com.group16.dinoblog.util.DeviceIdentity
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DinoblogApplication : Application() {
    val container by lazy { AppContainer(this) }
}

/** Small, explicit dependency container: one database and one connection per process. */
class AppContainer(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val identity = DeviceIdentity(application)
    val logger = NetworkLogger()
    private val database = Room.databaseBuilder(application, DinoblogDatabase::class.java, "dinoblog.db").build()
    val repository = MessageRepository(database)
    val transport = TcpTransport(identity.id, identity.name, scope, logger)
    val wifi = WifiDirectManager(application, transport, logger)
    val delivery = DeliveryEngine(identity.id, repository, transport, scope, logger).also { it.start() }
}
