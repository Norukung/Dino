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
    onRemoveImage: () -> Unit, onRelayEnabled: (Boolean) -> Unit, onHelp: () -> Unit, version: String) {
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
    HelpStep("Welcome to DinoBlog", "Exchange messages with nearby Android devices. DinoBlog can keep a message for delivery later when a direct path is unavailable."),
    HelpStep("No online connection needed", "Nearby devices use Wi-Fi Direct. Messages can move through encounters while phones carry them."),
    HelpStep("How messages travel", "A → B → C → D\n\nA and D do not have to be connected at the same time. You can choose whether this phone helps carry messages for others."),
    HelpStep("Your identity", "Your DinoBlog display name is visible to connected DinoBlog peers. A technical Device ID is used internally for delivery."),
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
            if (firstRun && step == 2) {
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
