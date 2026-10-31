package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.ontask.App
import dev.ontask.Level
import dev.ontask.block.HelperHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val WHY = "OnTask blocks sites by updating your Mac's hosts file. That needs a small helper, installed once. macOS will ask for your password."

private fun headline(h: HelperHealth?) = when (h) {
    HelperHealth.OUTDATED -> "OnTask's blocking helper needs an update"
    HelperHealth.NOT_APPLYING -> "OnTask's blocking helper isn't working"
    else -> "Blocking isn't on yet: OnTask's blocking helper isn't installed"
}

/** Install (or reinstall) button with its result; [onDone] runs after a successful install. */
@Composable
private fun InstallButton(app: App, primary: Boolean = true, onDone: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val health by app.helperHealth.collectAsState()
    val label = if (busy) "Installing…" else if (health == HelperHealth.MISSING) "Install…" else "Fix it…"
    val click = { busy = true; error = null; scope.launch { error = app.installHelper(); busy = false; if (error == null) onDone() }; Unit }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (primary) Button(click, enabled = !busy) { Text(label) } else OutlinedButton(click, enabled = !busy) { Text(label) }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** Top of Home while blocking can't work. Can be dismissed; it comes back on the next launch or after 4 hours. */
@Composable
fun BlockingHelperBanner(app: App, now: Long) {
    val health by app.helperHealth.collectAsState()
    var hiddenUntil by remember { mutableStateOf(app.helperBannerHiddenUntil) }
    if (health == null || !app.helperNeeded() || now < hiddenUntil) return
    PlainCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Dot(levelColor(Level.WASTED))
            Column(Modifier.weight(1f)) {
                Text(headline(health) + " — nothing is blocked.", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                Text(WHY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InstallButton(app)
            TextButton({ app.hideHelperBanner(); hiddenUntil = app.helperBannerHiddenUntil }) { Text("Later") }
        }
    }
}

/** Settings → Permissions (and the setup wizard): shown only while the helper is missing or broken. */
@Composable
fun BlockingHelperRow(app: App) {
    val health by app.helperHealth.collectAsState()
    if (health == null || !app.helperNeeded()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(levelColor(Level.WASTED))
        Column(Modifier.weight(1f)) {
            Text("Blocking helper · ${health?.problem ?: "checking…"}", style = MaterialTheme.typography.bodyLarge)
            Text(WHY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        InstallButton(app, primary = false)
    }
}

/** Once per launch while the helper is missing or broken: one explanation, then macOS's password prompt. */
@Composable
fun BlockingHelperPromptWindow(app: App, mode: ThemeMode) {
    val health by app.helperHealth.collectAsState()
    Window(
        onCloseRequest = { app.helperPromptLater() }, title = "OnTask", alwaysOnTop = true, resizable = false,
        state = rememberWindowState(size = DpSize(480.dp, 230.dp), position = WindowPosition.Aligned(Alignment.Center)),
    ) {
        OnTaskTheme(mode) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(headline(health), style = MaterialTheme.typography.titleMedium)
                Text("$WHY Until then, nothing is blocked.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    InstallButton(app)
                    TextButton({ app.helperPromptLater() }) { Text("Not now") }
                }
            }
        } }
    }
}

/** Top of Settings: removes OnTask and everything it added to this Mac. */
@Composable
fun UninstallButton(app: App) {
    var open by remember { mutableStateOf(false) }
    var deleteData by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    OutlinedButton({ open = true; error = null }) { Text("Uninstall OnTask…") }
    if (open) AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text("Uninstall OnTask?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This removes the blocking helper and OnTask's lines from your Mac's hosts file (macOS asks for your password), stops OnTask from starting at login, moves OnTask to the Trash and quits it. Your own hosts entries are kept.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(deleteData, { deleteData = it })
                    Text("Also delete my history and settings")
                }
                if (!deleteData) Text("Your history stays in ~/Library/Application Support/OnTask, so reinstalling picks up where you left off.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button({ busy = true; scope.launch { error = withContext(Dispatchers.IO) { app.uninstallOnTask(deleteData) }; busy = false } }, enabled = !busy) {
                Text(if (busy) "Uninstalling…" else "Uninstall")
            }
        },
        dismissButton = { TextButton({ open = false }, enabled = !busy) { Text("Cancel") } },
    )
}
