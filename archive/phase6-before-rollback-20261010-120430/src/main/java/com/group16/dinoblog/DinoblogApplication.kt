package com.group16.dinoblog

import android.app.Application
import androidx.room.Room
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.dtn.InventoryManager
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
    private val database = Room.databaseBuilder(application, DinoblogDatabase::class.java, "dinoblog.db")
        .addMigrations(DinoblogDatabase.MIGRATION_1_2, DinoblogDatabase.MIGRATION_2_3).build()
    val repository = MessageRepository(database)
    val transport = TcpTransport(identity.id, identity.name, scope, logger)
    val wifi = WifiDirectManager(application, transport, logger)
    val inventory = InventoryManager(identity.id, repository, transport, scope, logger).also {
        transport.onInventory = it::receive
        it.start()
    }
    val delivery = DeliveryEngine(identity.id, repository, transport, scope, logger, inventory.state).also { engine ->
        engine.start()
        transport.onSessionPacket = { peer, session, packet -> engine.receive(peer, packet, session) }
    }
}
