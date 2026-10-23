package com.group16.dinoblog.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RoutingExperimentMode { EPIDEMIC, SPRAY_AND_WAIT, SMART_SPRAY }

@Entity(tableName = "experiment_sessions")
data class ExperimentSession(
    @PrimaryKey val experimentId: String,
    val label: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val routingMode: String,
    val transportMode: String = "HYBRID",
    val deviceHash: String,
    val appVersion: String,
    val buildType: String,
    val nodeCount: Int? = null,
    val messageCountTarget: Int? = null,
    val notes: String = "",
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val mediaBytesSent: Long = 0,
    val mediaBytesReceived: Long = 0,
    val dtnControlBytesSent: Long = 0,
    val dtnPayloadBytesSent: Long = 0,
    val onlineControlBytesSent: Long = 0,
    val onlinePayloadBytesSent: Long = 0,
    val currentBufferBytes: Long = 0,
    val peakBufferBytes: Long = 0,
    val partialMediaBytes: Long = 0,
)

@Entity(tableName = "experiment_events", indices = [Index("experimentId", "eventTime")])
data class ExperimentEvent(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val experimentId: String,
    val eventTime: Long,
    val elapsedRealtimeMs: Long,
    val eventType: String,
    val messageId: String? = null,
    val mediaId: String? = null,
    val localDeviceHash: String,
    val peerDeviceHash: String? = null,
    val routingMode: String,
    val decision: String? = null,
    val bytes: Long? = null,
    val hopCount: Int? = null,
    val copiesRemaining: Int? = null,
    val bufferBytes: Long? = null,
    val contentType: String? = null,
    val transport: String? = null,
    val durationMs: Long? = null,
    val result: String? = null,
    val reason: String? = null,
)

@Entity(tableName = "experiment_messages", primaryKeys = ["experimentId", "messageId"],
    indices = [Index("experimentId")])
data class ExperimentMessageResult(
    val experimentId: String,
    val messageId: String,
    val sourceHash: String,
    val destinationHash: String,
    val contentType: String,
    val fileSizeBytes: Long,
    val createdAt: Long,
    val deliveredAt: Long? = null,
    val receiptReceivedAt: Long? = null,
    val finalHopCount: Int? = null,
    val deliveryTransport: String? = null,
    val routingMode: String,
    val finalStatus: String = "PENDING",
    val createdLocally: Boolean = false,
)
