package com.group16.dinoblog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.group16.dinoblog.ui.DinoblogApp
import com.group16.dinoblog.ui.DinoblogTheme
import com.group16.dinoblog.util.Permissions
import com.group16.dinoblog.viewmodel.AppViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DinoblogTheme { DinoblogApp(viewModel) } }
    }
    override fun onStart() {
        super.onStart()
        if (Permissions.granted(this)) viewModel.startNetwork()
    }
    override fun onStop() {
        // Networking runs while visible. Room preserves messages and the pending queue.
        // A configuration change keeps the process-owned socket alive for the new Activity.
        if (!isChangingConfigurations) viewModel.stopNetwork()
        super.onStop()
    }
}
