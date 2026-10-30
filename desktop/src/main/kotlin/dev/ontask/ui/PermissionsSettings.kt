package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.ontask.App
import dev.ontask.Level
import dev.ontask.track.PermState
import dev.ontask.track.PermissionStatus
import dev.ontask.track.Permissions
import dev.ontask.track.PrivacyPane
import kotlinx.coroutines.launch

val App.permissions: Permissions get() = Permissions(helper)

@Composable
fun PermissionsSettings(app: App) {
    SectionCard("Permissions", "OnTask works with app names alone. These macOS permissions let it see more; grant what you're comfortable with.") {
        PermissionsBody(app)
    }
}

/** Status and grant buttons for each permission. Shared by Settings and the first-run wizard. */
@Composable
fun PermissionsBody(app: App) {
    val perms = remember { app.permissions }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<PermissionStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun check(fresh: Boolean) = scope.launch { busy = true; status = perms.status(fresh); busy = false }
    LaunchedEffect(Unit) { status = perms.status() }
    val s = status ?: PermissionStatus.unknown

    PermissionRow(
        "Accessibility", "Lets OnTask read window titles of apps, so it can tell one document or chat from another.",
        if (status == null) null else s.accessibility, busy,
        grant = { scope.launch { perms.requestAccessibility() } }, recheck = { check(true) },
    )
    PermissionRow(
        "Screen Recording", "Lets OnTask read on-screen text for smarter classification. Optional; nothing is ever saved.",
        if (status == null) null else s.screenRecording, busy,
        grant = { scope.launch { perms.requestScreenRecording() } }, recheck = { check(true) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Automation (browsers)", style = MaterialTheme.typography.bodyLarge)
                Text("Lets OnTask read the address of the page you're on. macOS asks once per browser.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton({ scope.launch { perms.open(PrivacyPane.AUTOMATION) } }) { Text("Open Settings…") }
        }
        if (status != null && s.browsers.isEmpty()) Text("No supported browser is open right now. Open Safari, Chrome, Arc, Brave, Edge or Vivaldi to check it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.browsers.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(stateColor(b.state))
                Text("${b.name}: ${stateLabel(b.state)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (b.state == PermState.NOT_ASKED) TextButton({ scope.launch { perms.requestAutomation(b.bundleId); status = perms.status() } }) { Text("Grant…") }
                if (b.state == PermState.DENIED) TextButton({ scope.launch { perms.open(PrivacyPane.AUTOMATION) } }) { Text("Turn on in Settings…") }
            }
        }
    }
    Notice(
        if (perms.installed) "macOS lists these under \"OnTask\". After switching one on, come back and press Check again."
        else "You're running a development build (./gradlew run). macOS lists these under the app that started it, such as Terminal, iTerm or your IDE, not \"OnTask\", and grants made there apply to that app. The installed OnTask.app shows up as \"OnTask\".",
    )
}

@Composable
private fun PermissionRow(name: String, why: String, state: PermState?, busy: Boolean, grant: () -> Unit, recheck: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(stateColor(state))
        Column(Modifier.weight(1f)) {
            Text("$name · ${stateLabel(state)}", style = MaterialTheme.typography.bodyLarge)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state != PermState.GRANTED) OutlinedButton(grant) { Text("Grant…") }
        TextButton(recheck, enabled = !busy) { Text(if (busy) "Checking…" else "Check again") }
    }
}

private fun stateLabel(s: PermState?) = when (s) {
    PermState.GRANTED -> "granted"
    PermState.DENIED -> "turned off"
    PermState.NOT_ASKED -> "not granted"
    PermState.UNKNOWN -> "unknown (helper not running)"
    null -> "checking…"
}

@Composable
private fun stateColor(s: PermState?): Color = when (s) {
    PermState.GRANTED -> levelColor(Level.DEEP)
    PermState.NOT_ASKED -> levelColor(Level.MIXED)
    PermState.DENIED -> levelColor(Level.WASTED)
    else -> MaterialTheme.colorScheme.outline
}
