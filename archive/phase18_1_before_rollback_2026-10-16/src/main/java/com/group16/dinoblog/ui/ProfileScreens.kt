package com.group16.dinoblog.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.group16.dinoblog.model.DisplayNames
import com.group16.dinoblog.repository.UserProfile
import com.group16.dinoblog.repository.NearbyConnectionMethod
import com.group16.dinoblog.model.RelayPolicy

@Composable
fun ProfileAvatar(profile: UserProfile, modifier: Modifier = Modifier.size(56.dp)) {
    val image = remember(profile.imagePath, profile.imageRevision) {
        profile.imagePath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (image != null) Image(image.asImageBitmap(), contentDescription = "Profile picture", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(profile.displayName.firstOrNull()?.toString() ?: "D", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun SettingsScreen(profile: UserProfile, onSaveName: (String) -> Boolean, onPickImage: () -> Unit,
    onRemoveImage: () -> Unit, onRelayEnabled: (Boolean) -> Unit,
    onDefaultRelayPolicy: (RelayPolicy) -> Unit, onNearbyDiscoverable: (Boolean) -> Unit,
    nearbyAdvertiserSupported: Boolean, onShowNearbyName: (Boolean) -> Unit,
    onConnectionMethod: (NearbyConnectionMethod) -> Unit,
    onHelp: () -> Unit, version: String) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        InfoCard("Profile") {
            ProfileAvatar(profile, Modifier.size(96.dp))
            Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
            Text("Your display name is visible to DinoBlog peers you connect with.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { editing = true }) { Text("Change display name") }
            OutlinedButton(onClick = onPickImage) { Text(if (profile.imagePath == null) "Choose profile picture" else "Change profile picture") }
            if (profile.imagePath != null) TextButton(onClick = onRemoveImage) { Text("Remove picture") }
        }
        InfoCard("Offline messaging") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Help carry messages for others", modifier = Modifier.weight(1f))
                Switch(checked = profile.relayEnabled, onCheckedChange = onRelayEnabled)
            }
            Text("When enabled, DinoBlog may temporarily store and forward messages for other people.",
                style = MaterialTheme.typography.bodySmall)
            Text(if (profile.relayEnabled) "Carried messages are removed when no longer needed."
                else "Your own messages and messages addressed to you still work.", style = MaterialTheme.typography.bodySmall)
        }
        InfoCard("Message Delivery") {
            Text("Default delivery method", style = MaterialTheme.typography.titleSmall)
            listOf(
                RelayPolicy.ALLOW_RELAY to Pair("Allow nearby relay",
                    "Other DinoBlog devices can temporarily carry your message when the recipient is offline."),
                RelayPolicy.DIRECT_ONLY to Pair("Direct only",
                    "Only deliver directly to the recipient or through the DinoBlog server."),
            ).forEach { (policy, description) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = profile.defaultRelayPolicy == policy,
                        onClick = { onDefaultRelayPolicy(policy) })
                    Column {
                        Text(description.first)
                        Text(description.second, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("This default applies only to new messages. You can change it for each message before sending.",
                style = MaterialTheme.typography.bodySmall)
        }
        InfoCard("Nearby Devices") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Allow nearby DinoBlog users to find me", modifier = Modifier.weight(1f))
                Switch(checked = profile.nearbyDiscoverableEnabled, onCheckedChange = onNearbyDiscoverable,
                    enabled = nearbyAdvertiserSupported || profile.nearbyDiscoverableEnabled)
            }
            Text("When enabled, DinoBlog advertises a temporary nearby identifier while the app is open. Your permanent DinoBlog ID is not broadcast.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Show my DinoBlog name nearby", modifier = Modifier.weight(1f))
                Switch(checked = profile.showNearbyDisplayName, onCheckedChange = onShowNearbyName)
            }
            Text("Your DinoBlog name can be seen by nearby DinoBlog users before they connect while Nearby Discovery is enabled. A visible name may let others recognize repeated visits even as the temporary code changes.",
                style = MaterialTheme.typography.bodySmall)
            if (!nearbyAdvertiserSupported) Text("BLE advertising is unavailable on this device or Bluetooth is off.",
                style = MaterialTheme.typography.bodySmall)
        }
        InfoCard("Nearby connection method") {
            listOf(
                Triple(NearbyConnectionMethod.AUTO, "Auto", "DinoBlog chooses an available nearby connection method."),
                Triple(NearbyConnectionMethod.WIFI_DIRECT, "Wi-Fi Direct", "Faster and recommended for photos, audio and video."),
                Triple(NearbyConnectionMethod.BLUETOOTH, "Bluetooth", "Large media may take longer or require Wi-Fi or Internet."),
            ).forEach { (method, label, detail) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = profile.nearbyConnectionMethod == method, onClick = { onConnectionMethod(method) })
                    Column {
                        Text(label)
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        InfoCard("About") {
            Text("DinoBlog $version")
            TextButton(onClick = onHelp) { Text("How DinoBlog works") }
        }
    }
    if (editing) {
        var name by rememberSaveable { mutableStateOf(profile.displayName) }
        var error by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text("Display name") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it; error = false },
                label = { Text("DinoBlog name") }, singleLine = true, isError = error,
                supportingText = { Text(if (error) "Use 1 to 32 visible characters" else "Up to 32 characters") }) },
            confirmButton = { TextButton(onClick = { if (onSaveName(name)) editing = false else error = true }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
    }
}

private data class HelpStep(val title: String, val body: String)
private val helpSteps = listOf(
    HelpStep("Welcome to DinoBlog", "Send text, photos, audio and supported videos. Larger videos can be sent when Internet is available."),
    HelpStep("No online connection needed", "Nearby devices use Wi-Fi Direct. Messages can move through encounters while phones carry them."),
    HelpStep("Nearby discovery", "Bluetooth can show a chosen DinoBlog name; Wi-Fi Direct can show the Android device name. Both are unverified before connection. DinoBlog then connects using Wi-Fi Direct or Bluetooth and verifies device identity with a cryptographic proof."),
    HelpStep("How messages travel", "A → B → C → D\n\nChoose whether each message may use nearby carriers. Direct only still permits delivery to the recipient or through DinoBlog's server. A separate setting controls whether this phone carries other people's messages. Direct only does not add end-to-end encryption."),
    HelpStep("Your identity", "Your chosen DinoBlog name can also be visible to nearby scanners before connection when you enable both discovery and name visibility. A visible name may allow repeated presence to be recognized. Your technical Device ID is used internally for delivery."),
    HelpStep("Ready", "Choose a display name and start using DinoBlog. You can change your name later in Settings."),
)

@Composable
fun OnboardingScreen(firstRun: Boolean, onComplete: (String?, Boolean) -> Boolean, onClose: () -> Unit) {
    var step by rememberSaveable(firstRun) { mutableIntStateOf(0) }
    var name by rememberSaveable(firstRun) { mutableStateOf("") }
    var relayEnabled by rememberSaveable(firstRun) { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${step + 1} / ${helpSteps.size}", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(16.dp))
            Text(helpSteps[step].title, style = MaterialTheme.typography.headlineMedium)
            Text(if (!firstRun && step == helpSteps.lastIndex) "You can change your display name and picture in Settings."
                else helpSteps[step].body, style = MaterialTheme.typography.bodyLarge)
            if (firstRun && step == 3) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Help carry messages for others", modifier = Modifier.weight(1f))
                    Switch(checked = relayEnabled, onCheckedChange = { relayEnabled = it })
                }
            }
            if (step == helpSteps.lastIndex && firstRun) {
                OutlinedTextField(value = name, onValueChange = { name = it; error = false },
                    label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = error, supportingText = { Text(if (error) "Use 1 to 32 visible characters" else "Visible to connected DinoBlog peers") })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (step > 0) step-- else if (!firstRun) onClose() }) { Text(if (step == 0 && !firstRun) "Close" else "Back") }
            if (firstRun && step < helpSteps.lastIndex) TextButton(onClick = { if (onComplete(null, relayEnabled)) onClose() }) { Text("Skip") }
            Button(onClick = {
                if (step < helpSteps.lastIndex) step++
                else if (!firstRun) onClose()
                else if (DisplayNames.normalize(name) != null && onComplete(name, relayEnabled)) onClose()
                else error = true
            }) { Text(if (step == helpSteps.lastIndex) (if (firstRun) "Start using DinoBlog" else "Done") else "Next") }
        }
    }
}
