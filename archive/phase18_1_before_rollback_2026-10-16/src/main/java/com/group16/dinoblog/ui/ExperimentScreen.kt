package com.group16.dinoblog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.group16.dinoblog.experiment.ExperimentDashboard
import com.group16.dinoblog.model.ExperimentSession
import com.group16.dinoblog.model.RoutingExperimentMode
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ExperimentScreen(active: ExperimentSession?, dashboard: ExperimentDashboard,
    sessions: List<ExperimentSession>, onStart: (String, RoutingExperimentMode, Int?, Int?, String) -> Unit,
    onStop: () -> Unit, onReset: () -> Unit, onExport: (ExperimentSession) -> Unit,
    onDelete: (String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var nodeCount by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(RoutingExperimentMode.SMART_SPRAY) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Delete experiment results?") },
            text = { Text("Only measurement rows for this experiment will be deleted. Chats and media stay intact.") },
            confirmButton = { TextButton(onClick = { onDelete(id); deleteId = null }) { Text("Delete results") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Experiment / Evaluation", style = MaterialTheme.typography.headlineSmall)
        Text("Measurements are stored on this device. Start the same routing mode on each test phone before an encounter.",
            style = MaterialTheme.typography.bodyMedium)
        if (active == null) {
            OutlinedTextField(label = { Text("Experiment label") }, value = label,
                onValueChange = { if (it.length <= 64) label = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(label = { Text("Number of nodes (optional)") }, value = nodeCount,
                onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) nodeCount = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(label = { Text("Message target (optional)") }, value = target,
                onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) target = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(label = { Text("Notes (optional)") }, value = notes,
                onValueChange = { if (it.length <= 256) notes = it }, modifier = Modifier.fillMaxWidth())
            Text("Routing test mode", style = MaterialTheme.typography.titleMedium)
            RoutingExperimentMode.entries.forEach { choice ->
                Row(Modifier.fillMaxWidth().clickable { mode = choice }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected = mode == choice, onClick = { mode = choice })
                    Text(choice.name.replace('_', ' '), modifier = Modifier.padding(top = 12.dp))
                }
            }
            Button(enabled = label.isNotBlank(), onClick = {
                onStart(label, mode, nodeCount.toIntOrNull(), target.toIntOrNull(), notes)
            }) { Text("Start experiment") }
        } else {
            Text("Running: ${active.label}", style = MaterialTheme.typography.titleMedium)
            Text("Mode: ${active.routingMode}  ·  ID: ${active.experimentId}")
            Text("Started: ${formatTime(active.startedAt)}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStop) { Text("Stop") }
                OutlinedButton(onClick = onReset) { Text("Reset metrics") }
            }
        }
        dashboard.summary?.let { summary ->
            HorizontalDivider()
            Text("Current metrics", style = MaterialTheme.typography.titleMedium)
            Text("Created ${summary.messagesCreated}  ·  Delivered ${summary.messagesDelivered}  ·  Expired ${summary.messagesExpired}")
            Text("Delivery ratio: ${summary.deliveryRatio?.let { String.format(Locale.US, "%.1f%%", it * 100) } ?: "N/A"}")
            Text("Average delay: ${summary.averageDelayMs?.let { String.format(Locale.US, "%.1f s", it / 1000) } ?: "N/A"}")
            Text("DTN transfers ${summary.dtnTransmissions}  ·  Online accepted ${summary.onlineTransmissions}")
            Text("Nearby connections: Wi-Fi ${summary.wifiPeerConnections}  ·  Bluetooth ${summary.bluetoothPeerConnections}")
            Text("Auto fallbacks ${summary.autoFallbackCount}  ·  Wi-Fi failures ${summary.wifiConnectionFailures}  ·  Bluetooth failures ${summary.bluetoothConnectionFailures}")
            Text("Local overhead (combine all phones for report): ${summary.routingOverheadRatio?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A"}")
            Text("Relay buffer ${dashboard.session?.currentBufferBytes ?: 0} B  ·  Peak ${dashboard.session?.peakBufferBytes ?: 0} B")
            Text("Bytes sent ${dashboard.session?.bytesSent ?: 0}  ·  Received ${dashboard.session?.bytesReceived ?: 0}")
            Text("Resumes ${summary.resumeCount}  ·  Failures ${summary.failedTransfers}  ·  Rejections ${summary.rejectedTransfers}")
            summary.averageOnlineUploadMs?.let { Text("Average online upload: ${String.format(Locale.US, "%.1f s", it / 1000)}") }
            summary.averageMailboxWaitMs?.let { Text("Average mailbox wait: ${String.format(Locale.US, "%.1f s", it / 1000)}") }
            Text("Last event: ${dashboard.lastEvent ?: "None"}")
        }
        HorizontalDivider()
        Text("Completed experiments", style = MaterialTheme.typography.titleMedium)
        sessions.filter { it.endedAt != null }.forEach { session ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(session.label, style = MaterialTheme.typography.titleSmall)
                Text("${session.routingMode}  ·  ${formatTime(session.startedAt)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onExport(session) }) { Text("Export CSV") }
                    TextButton(onClick = { deleteId = session.experimentId }) { Text("Delete") }
                }
            }
        }
        if (sessions.none { it.endedAt != null }) Text("No completed experiments yet")
    }
}

private fun formatTime(time: Long): String = DateFormat.getDateTimeInstance().format(Date(time))
