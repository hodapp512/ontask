package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ontask.App
import dev.ontask.block.BlockMode
import dev.ontask.block.UnblockKind
import dev.ontask.block.UnblockRejected
import kotlinx.coroutines.delay

internal fun fmtDelay(s: Int) = if (s >= 60 && s % 60 == 0) "${s / 60}m" else "${s}s"
private val App.unblocks get() = blocking.unblocks

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BlocklistCard(app: App, changed: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val list = remember { mutableStateOf(app.blocking.blocklist.all()) }
    fun reload() { list.value = app.blocking.blocklist.all(); changed() }
    SectionCard("Block list", "Sites blocked during work hours and Focus sessions. www. is covered automatically.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, label = { Text("Add a site, e.g. news.ycombinator.com") }, singleLine = true, modifier = Modifier.weight(1f))
            Button({ val n = app.blocking.blocklist.add(text.split(',', ' ', '\n'), App.nowSec()); msg = if (n == 0) "Nothing new added (invalid or already listed)." else "Added $n."; text = ""; reload() }, enabled = text.isNotBlank()) { Text("Add") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ pickTextFile("Import sites to block")?.let { val n = app.blocking.blocklist.importFile(it, App.nowSec()); msg = if (n == 0) "No new sites found in that file." else "Imported $n sites."; reload() } }) { Text("Import from file…") }
            Text(msg, style = MaterialTheme.typography.bodySmall)
        }
        if (list.value.isEmpty()) EmptyState("No sites yet", "Add a site above or import a list from a file.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            list.value.forEach { d -> InputChip(false, { app.blocking.blocklist.remove(d); reload() }, label = { Text(d) }, trailingIcon = { Text("✕") }) }
        }
    }
}

@Composable
internal fun BlockingSetup(app: App, mode: BlockMode, onMode: (BlockMode) -> Unit) {
    val helper = remember { dev.ontask.block.RootHelper() }
    var msg by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    Expander("Advanced: blocking mode and root helper") {
        SettingRow("Blocking mode", mode.explain) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BlockMode.entries.forEach { m -> FilterChip(mode == m, { app.blocking.mode = m; onMode(m) }, label = { Text(m.label) }) }
            }
        }
        KV("Root helper installed", if (helper.installed) "yes" else "no")
        KV("Staged hosts file", app.blocking.stagedFile.toString())
        KV("Daemon port", app.blocking.port.toString())
        Notice("If another app or script manages /etc/hosts, turn it off before installing — both would overwrite the same file.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({ confirm = true }, enabled = helper.available) { Text(if (helper.installed) "Reinstall helper…" else "Install helper…") }
            OutlinedButton({ msg = helper.install(app.blocking.stagedFile, app.blocking.port, dryRun = true).output }) { Text("Preview install") }
            if (helper.installed) TextButton({ msg = helper.uninstall().output }) { Text("Uninstall") }
        }
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Install the blocking helper?") },
        text = { Text("macOS will ask for your administrator password. This installs a small background daemon that copies OnTask's hosts file to /etc/hosts. Then switch the blocking mode to On.") },
        confirmButton = { TextButton({ confirm = false; msg = helper.install(app.blocking.stagedFile, app.blocking.port).output.ifBlank { "Done." } }) { Text("Install") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

/** The friction screen: pick a duration, wait out the delay (showing today's count, your plan and your own message). */
@Composable
fun UnblockDialog(app: App, domains: List<String>, onClose: () -> Unit) {
    val now0 = remember { App.nowSec() }
    val delay = remember { app.unblocks.nextDelaySec(now0) }
    val count = remember { app.unblocks.todayCount(now0) }
    var minutes by remember { mutableStateOf(5) }
    var remaining by remember { mutableStateOf(delay) }
    var error by remember { mutableStateOf<String?>(null) }
    val message = remember { app.unblockMessage() }
    LaunchedEffect(Unit) { while (remaining > 0) { delay(1000); remaining-- } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Unblock ${domains.joinToString()}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message, style = MaterialTheme.typography.titleSmall)
                Text("Unblocks so far today: $count. Your partner is told about this one.", style = MaterialTheme.typography.bodyMedium)
                val priorities = remember { app.priorities.today(now0) }
                if (priorities.isNotEmpty()) {
                    Text("Today's plan:", style = MaterialTheme.typography.labelMedium)
                    Text(priorities.joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    app.unblocks.durationsMin.forEach { m -> FilterChip(minutes == m, { minutes = m }, label = { Text("$m min") }) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button({
                try { app.unblockRegular(domains, minutes, delay); onClose() } catch (e: UnblockRejected) { error = e.message }
            }, enabled = remaining <= 0) { Text(if (remaining > 0) "Wait ${remaining}s" else "Unblock for $minutes min") }
        },
        dismissButton = { TextButton(onClose) { Text("Never mind") } },
    )
}
