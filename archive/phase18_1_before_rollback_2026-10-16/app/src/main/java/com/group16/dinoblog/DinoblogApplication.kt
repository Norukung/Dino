package com.group16.dinoblog

import android.app.Application
import androidx.room.Room
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.dtn.InventoryManager
import com.group16.dinoblog.experiment.ExperimentManager
import com.group16.dinoblog.network.TcpTransport
import com.group16.dinoblog.network.WifiDirectManager
import com.group16.dinoblog.online.TransportCoordinator
import com.group16.dinoblog.repository.MessageRepository
import com.group16.dinoblog.repository.MediaFileStore
import com.group16.dinoblog.repository.MediaRepository
import com.group16.dinoblog.repository.ProfileRepository
import com.group16.dinoblog.repository.RecallRepository
import com.group16.dinoblog.util.DeviceIdentity
import com.group16.dinoblog.util.CrashReport
import com.group16.dinoblog.util.NetworkLogger
import com.group16.dinoblog.security.CryptoIdentityManager
import com.group16.dinoblog.nearby.NearbyDiscoveryManager
import com.group16.dinoblog.nearby.BluetoothPeerTransport
import com.group16.dinoblog.nearby.BleHandoffManager
import com.group16.dinoblog.repository.NearbyConnectionMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DinoblogApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
    }
}

/** Small, explicit dependency container: one database and one connection per process. */
class AppContainer(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val identity = DeviceIdentity(application)
    val profile = ProfileRepository(application)
    val logger = NetworkLogger()
    val nearby by lazy { NearbyDiscoveryManager(application, logger,
        { profile.displayName }, { profile.profile.value.showNearbyDisplayName }) }
    private val database = Room.databaseBuilder(application, DinoblogDatabase::class.java, "dinoblog.db")
        .addMigrations(DinoblogDatabase.MIGRATION_1_2, DinoblogDatabase.MIGRATION_2_3, DinoblogDatabase.MIGRATION_3_4, DinoblogDatabase.MIGRATION_4_5, DinoblogDatabase.MIGRATION_5_6, DinoblogDatabase.MIGRATION_6_7, DinoblogDatabase.MIGRATION_7_8, DinoblogDatabase.MIGRATION_8_9, DinoblogDatabase.MIGRATION_9_10, DinoblogDatabase.MIGRATION_10_11, DinoblogDatabase.MIGRATION_11_12, DinoblogDatabase.MIGRATION_12_13, DinoblogDatabase.MIGRATION_13_14).build()
    val peerIdentities = database.identities().observeAll()
    val security by lazy { CryptoIdentityManager(application, identity.id, database.identities(), logger) }
    val repository = MessageRepository(database, logger, { profile.relayEnabled }, security)
    val media = MediaRepository(database, MediaFileStore(application), repository, logger, security) { profile.relayEnabled }
    val recalls by lazy { RecallRepository(database, identity.id, security, media, logger) }
    val experiment: ExperimentManager = ExperimentManager(application, identity.id, database, repository, media, scope, logger) {
        transport.state.value.peerId == null
    }
    init { media.onHashFailure = { messageId, mediaId ->
        experiment.record("HASH_FAILURE", messageId = messageId, mediaId = mediaId)
    }
        recalls.onEvent = { type, id, bytes, duration ->
            experiment.record(type, messageId = id, bytes = bytes, durationMs = duration)
        }
        media.onChunkRetransmitted = { messageId, mediaId, bytes ->
            experiment.record("MEDIA_BYTES_RETRANSMITTED", messageId = messageId, mediaId = mediaId, bytes = bytes)
        }
    }
    val coordinator = TransportCoordinator(application, identity.id, { profile.displayName }, database,
        repository, media, scope, logger, experiment, security, recalls)
    init { security.serverBinding = coordinator::serverBinding }
    val transport: TcpTransport by lazy { TcpTransport(identity.id, profile.displayName, scope, logger,
        displayNameProvider = { profile.displayName }, relayEnabledProvider = { profile.relayEnabled },
        metrics = experiment, security = security, serverBinding = coordinator::serverBinding,
        afterAuthenticated = { peer -> repository.revalidatePendingReceipts(peer); recalls.revalidatePending(peer) },
        wifiAllowed = { profile.profile.value.nearbyConnectionMethod != NearbyConnectionMethod.BLUETOOTH },
        bluetoothAllowed = { profile.profile.value.nearbyConnectionMethod != NearbyConnectionMethod.WIFI_DIRECT })
    }
    val bluetooth by lazy { BluetoothPeerTransport(application, scope, transport, logger, experiment) }
    val wifi by lazy { WifiDirectManager(application, transport, logger) }
    val handoff by lazy { BleHandoffManager(application, scope, logger,
        { profile.profile.value.nearbyConnectionMethod }, { wifi.handoffAddress() },
        { !transport.state.value.connected && !transport.state.value.awaitingHello },
        { id, device -> transport.prepareHandoff(id.toString()); bluetooth.allowNextIncoming(device) }) }
    val inventory by lazy { InventoryManager(identity.id, repository, transport, scope, logger, recalls = recalls).also {
        transport.onInventory = it::receive
        it.start()
    } }
    val delivery by lazy { DeliveryEngine(identity.id, repository, transport, scope, logger, inventory.state,
        relayEnabled = { profile.relayEnabled }, mediaStore = media,
        serverAccepted = coordinator::accepted, onlinePreferred = coordinator::tryUploadFirst,
        routingMode = experiment::routingMode, metrics = experiment, security = security, recalls = recalls).also { engine ->
        engine.start()
        transport.onSessionPacket = { peer, session, packet -> engine.receive(peer, packet, session) }
    } }
}
