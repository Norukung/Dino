package com.group16.dinoblog

import com.group16.dinoblog.experiment.ExperimentAnalysis
import com.group16.dinoblog.experiment.ExperimentCsv
import com.group16.dinoblog.model.ExperimentEvent
import com.group16.dinoblog.model.ExperimentMessageResult
import com.group16.dinoblog.model.ExperimentSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class Phase14ExperimentTest {
    private val session = ExperimentSession("experiment-1", "ทดลอง,\"A\"", 1_000, 10_000,
        "SMART_SPRAY", deviceHash = "device-hash", appVersion = "test", buildType = "DEBUG")

    @Test fun `summary uses source submitted messages and final receipt time`() {
        val messages = listOf(
            ExperimentMessageResult("experiment-1", "one", "a", "d", "TEXT", 0, 1_000,
                deliveredAt = 4_000, receiptReceivedAt = 5_000, finalHopCount = 2,
                routingMode = "SMART_SPRAY", createdLocally = true),
            ExperimentMessageResult("experiment-1", "two", "a", "d", "IMAGE", 10, 2_000,
                routingMode = "SMART_SPRAY", createdLocally = true),
            ExperimentMessageResult("experiment-1", "relay", "x", "d", "TEXT", 0, 2_000,
                deliveredAt = 3_000, finalHopCount = 1, routingMode = "SMART_SPRAY", createdLocally = false),
        )
        val events = listOf("TRANSFER_COMPLETED", "TRANSFER_COMPLETED", "TRANSFER_COMPLETED", "MESSAGE_DELIVERED")
            .mapIndexed { index, type -> ExperimentEvent(experimentId = "experiment-1", eventTime = 2_000L + index,
                elapsedRealtimeMs = 1_000, eventType = type, localDeviceHash = "device-hash",
                routingMode = "SMART_SPRAY", transport = "DTN") } + listOf(
            ExperimentEvent(experimentId = "experiment-1", eventTime = 3_000, elapsedRealtimeMs = 1_000,
                eventType = "ONLINE_SERVER_ACCEPTED", localDeviceHash = "device-hash",
                routingMode = "SMART_SPRAY", transport = "ONLINE", durationMs = 500),
            ExperimentEvent(experimentId = "experiment-1", eventTime = 3_100, elapsedRealtimeMs = 1_000,
                eventType = "ONLINE_MAILBOX_WAIT", localDeviceHash = "device-hash",
                routingMode = "SMART_SPRAY", transport = "ONLINE", durationMs = 900))
        val result = ExperimentAnalysis.summarize(session, messages, events)
        assertEquals(2, result.messagesCreated)
        assertEquals(1, result.messagesDelivered)
        assertEquals(0.5, result.deliveryRatio!!, 0.0)
        assertEquals(3_000.0, result.averageDelayMs!!, 0.0)
        assertEquals(3_000L, result.medianDelayMs)
        assertEquals(1_000.0, result.averageReceiptDelayMs!!, 0.0)
        assertEquals(3, result.dtnTransmissions)
        assertEquals(2.0, result.routingOverheadRatio!!, 0.0)
        assertEquals(1.5, result.medianHopCount!!, 0.0)
        assertEquals(500.0, result.averageOnlineUploadMs!!, 0.0)
        assertEquals(900.0, result.averageMailboxWaitMs!!, 0.0)
        assertNull(result.p95DelayMs)
    }

    @Test fun `zero delivery has no divided overhead and export quotes Thai text safely`() {
        val message = ExperimentMessageResult("experiment-1", "one", "a", "d", "TEXT", 0, 1_000,
            routingMode = "SMART_SPRAY", createdLocally = true)
        assertNull(ExperimentAnalysis.summarize(session, listOf(message), emptyList()).routingOverheadRatio)
        assertEquals("-42", ExperimentCsv.cell(-42))
        assertEquals("\"ทดลอง,\"\"A\"\"\"", ExperimentCsv.cell("ทดลอง,\"A\""))
        assertEquals("'=SUM(1,2)", ExperimentCsv.cell("=SUM(1,2)").replace("\"", ""))
        val output = ByteArrayOutputStream()
        ExperimentCsv.writeZip(output, session, listOf(message), emptyList())
        val names = mutableSetOf<String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                val text = zip.readBytes().toString(Charsets.UTF_8)
                if (entry.name == "experiment_summary.csv") assertTrue(text.contains("ทดลอง,\"\"A\"\""))
                entry = zip.nextEntry
            }
        }
        assertEquals(setOf("experiment_summary.csv", "message_results.csv", "event_log.csv"), names)
    }
}
