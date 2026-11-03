package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.ontask.App
import dev.ontask.ai.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiSettings(app: App) {
    SectionCard("AI labeling", "Optional. Sorts what no rule covers, and judges pages on Varies sites. Nothing is kept.") {
        AiSettingsBody(app)
    }
}

/** Settings and the setup wizard: on/off, Kev (this Mac) or Jev (cloud), and installing Kev (0.8B, about 3 GB of memory). */
@Composable
fun AiSettingsBody(app: App) {
    var enabled by remember { mutableStateOf(app.ai.enabled) }
    var provider by remember { mutableStateOf(app.ai.provider) }
    var notice by remember { mutableStateOf(false) }
    SwitchSetting("Use AI", "Decides what your rules don't cover. Reads the text on screen only with the Screen Recording permission.", enabled) { enabled = it; app.ai.enabled = it }
    if (!enabled) return
    SettingRow("Where it runs", "Kev runs on this Mac. Jev is TypeSafe's cloud service: nothing to install, but page titles and on-screen text are sent to TypeSafe.") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiProvider.entries.forEach { p ->
                FilterChip(provider == p, {
                    if (!p.local && !app.ai.jevAcknowledged()) notice = true else { app.ai.provider = p; provider = p }
                }, label = { Text(p.label) })
            }
        }
    }
    when (provider) {
        AiProvider.KEV -> KevSetup(app)
        AiProvider.JEV -> JevSetup(app)
    }
    if (notice) AlertDialog(
        onDismissRequest = { notice = false },
        title = { Text("Send pages to TypeSafe?") },
        text = { Text("With Jev, the title, address and (if allowed) on-screen text of pages OnTask can't sort by itself are sent to TypeSafe's servers to be judged. Your history stays on this Mac.") },
        confirmButton = { TextButton({ notice = false; app.ai.acknowledgeJev(); app.ai.provider = AiProvider.JEV; provider = AiProvider.JEV }) { Text("Use Jev") } },
        dismissButton = { TextButton({ notice = false }) { Text("Cancel") } },
    )
}

@Composable
private fun KevSetup(app: App) {
    val kev = app.ai.kev
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(kev.installed) }
    var busy by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    if (!installed) {
        Notice(
            if (kev.uv == null) "Kev needs uv, a Python tool. In Terminal run: brew install uv — then press Install Kev."
            else "Kev isn't installed yet. Installing takes a few minutes and about 2 GB; the model itself downloads the first time it runs.",
        )
        Button({
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { kev.install { l -> line = l.take(120) } }
                installed = ok; busy = false; app.ai.retryNow()
                if (!ok && line.isBlank()) line = "Install failed."
            }
        }, enabled = !busy && kev.uv != null) { Text(if (busy) "Installing…" else "Install Kev") }
        if (busy) LinearProgressIndicator()
        if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(
            (if (kev.running) "Running. " else "Starts when there's something to decide and stops after 10 idle minutes. ") +
                if (kev.downloaded) "" else "The first start downloads the model.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun JevSetup(app: App) {
    var key by remember { mutableStateOf("") }
    var has by remember { mutableStateOf(app.ai.hasJevKey()) }
    SettingRow("TypeSafe API key", if (has) "Stored in your Mac's Keychain." else "From the TypeSafe console. Stored in your Mac's Keychain, never in OnTask's files.") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(key, { key = it }, singleLine = true, placeholder = { Text(if (has) "•••• saved" else "Paste key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier)
            OutlinedButton({ if (app.ai.setJevKey(key.trim())) { has = true; key = "" } }, enabled = key.isNotBlank()) { Text("Save") }
        }
    }
}
