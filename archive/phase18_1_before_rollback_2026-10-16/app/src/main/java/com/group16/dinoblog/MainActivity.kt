package com.group16.dinoblog

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.group16.dinoblog.ui.DinoblogApp
import com.group16.dinoblog.ui.DinoblogTheme
import com.group16.dinoblog.util.CrashReport
import com.group16.dinoblog.util.Permissions
import com.group16.dinoblog.viewmodel.AppViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private var showingCrashReport = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val report = CrashReport.read(this)
        if (report != null) {
            showingCrashReport = true
            setContent { DinoblogTheme { CrashRecoveryScreen(report, ::shareCrashReport) {
                CrashReport.clear(this)
                showingCrashReport = false
                showApp()
                startServices()
            } } }
            return
        }
        showApp()
    }

    private fun showApp() {
        setContent { DinoblogTheme { DinoblogApp(viewModel) } }
    }

    private fun shareCrashReport(report: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DinoBlog crash report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(intent, "Share crash report"))
    }

    private fun startServices() {
        viewModel.startOnline()
        if (Permissions.granted(this)) viewModel.startNetwork()
    }

    override fun onStart() {
        super.onStart()
        if (!showingCrashReport) {
            startServices()
            viewModel.startNearbyForeground()
        }
    }
    override fun onResume() {
        super.onResume()
        if (!showingCrashReport) viewModel.nearbyBluetoothChanged()
    }
    override fun onStop() {
        viewModel.stopNearbyForeground(!isChangingConfigurations)
        if (!isChangingConfigurations && !showingCrashReport) viewModel.stopOnline()
        // Networking runs while visible. Room preserves messages and the pending queue.
        // A configuration change keeps the process-owned socket alive for the new Activity.
        if (!isChangingConfigurations && !showingCrashReport) viewModel.stopNetwork()
        super.onStop()
    }
}

@Composable
private fun CrashRecoveryScreen(report: String, onShare: (String) -> Unit, onTryAgain: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("DinoBlog closed unexpectedly")
        Text("Your messages and app data have not been cleared. Share this report to help find the cause.")
        Button(onClick = { onShare(report) }) { Text("Share error report") }
        Button(onClick = onTryAgain) { Text("Try opening DinoBlog") }
        SelectionContainer { Text(report.take(4_000)) }
    }
}
