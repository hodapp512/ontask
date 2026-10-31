package dev.ontask.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.ontask.App
import dev.ontask.cloud.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun QrCode(text: String, sizeDp: Int = 180) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)) }
    Canvas(Modifier.size(sizeDp.dp)) {
        drawRect(Color.White)
        val cell = size.width / matrix.width
        for (x in 0 until matrix.width) for (y in 0 until matrix.height) if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}

@Composable
fun CloudSettings(app: App) {
    SectionCard("Phone & partner", "Link your phone to see your day and get reminders there, and invite someone you trust to receive your evening review. Only daily summaries leave this Mac, never the detailed activity log.") { CloudSettingsBody(app) }
}

@Composable
fun CloudSettingsBody(app: App) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf<Pair<String, String>?>(null) }
    var tick by remember { mutableStateOf(0) }
    val signedIn = remember(tick) { app.cloud.signedIn }
    val web = app.cloud.config?.webBase

    fun link() {
        if (busy) return
        busy = true; msg = "Linking…"
        scope.launch(Dispatchers.IO) {
            msg = try { app.cloud.linkWithCode(code); app.cloud.syncOnce(App.nowSec(), force = true); code = ""; "Linked! Your phone will show today's progress in a moment." } catch (e: Exception) { e.message ?: "Couldn't link" }
            busy = false; tick++
        }
    }

    if (!signedIn) {
        Text("Connect your phone", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            web?.let { QrCode(it, 140) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. Scan the code with your phone's camera${web?.let { " (or open $it)" } ?: ""} and sign in.", style = MaterialTheme.typography.bodyMedium)
                Text("2. Tap Link my Mac.", style = MaterialTheme.typography.bodyMedium)
                Text("3. Type the 5-character code here:", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    code,
                    { v -> code = v.uppercase().filter { it.isLetterOrDigit() }.take(5); if (code.length == 5) link() },
                    placeholder = { Text("K7M2Q") }, singleLine = true, enabled = !busy,
                    textStyle = MaterialTheme.typography.headlineSmall, modifier = Modifier.width(180.dp),
                )
            }
        }
    } else {
        val info = remember(tick, app.cloud.lastSyncTs) { app.cloud.linkInfo() }
        Text("Linked ✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        KV("Account", listOfNotNull(info?.name, info?.email?.let { "($it)" }).joinToString(" ").ifBlank { "Signed in (details appear after the next sync)" })
        KV("This Mac", "Sending updates" + (app.cloud.lastSyncTs.takeIf { it > 0 }?.let { " · last sent ${fmtTime(it)}" } ?: ""))
        KV("Phones", info?.phones?.takeIf { it.isNotEmpty() }?.joinToString(", ") { (n, seen) -> if (seen > 0) "$n (opened ${fmtAgo(seen / 1000)})" else n } ?: "None yet. Open ${web ?: "the OnTask website"} on your phone and sign in.")
        KV("Accountability partner", info?.partners?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "None yet. Invite one below.")
        info?.supporting?.takeIf { it.isNotEmpty() }?.let { KV("You support", it.joinToString(", ")) }
        app.cloud.lastError?.let { KV("Last sync problem", it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                scope.launch(Dispatchers.IO) {
                    msg = try {
                        val c = app.cloud.client()!!.call("createInvite")["code"]!!.jsonPrimitive.content
                        invite = c to "${app.cloud.config!!.webBase}/#/join/$c"; ""
                    } catch (e: Exception) { e.message ?: "Failed" }
                }
            }) { Text(if (info?.partners.isNullOrEmpty()) "Invite a partner" else "Invite another partner") }
            OutlinedButton({ scope.launch(Dispatchers.IO) { app.cloud.syncOnce(App.nowSec(), force = true); tick++; msg = app.cloud.lastError ?: "Synced." } }) { Text("Sync now") }
            TextButton({ app.cloud.signOut(); tick++ }) { Text("Unlink this Mac") }
        }
        invite?.let { (c, link) ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(link)
                Column { Text(c, style = MaterialTheme.typography.headlineMedium); Text(link, style = MaterialTheme.typography.bodySmall); Text("Send this to your partner. It works once and expires in 7 days.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    val reactions = remember(tick) { app.cloud.reactions() }
    if (reactions.isNotEmpty()) {
        Text("Recent encouragement", style = MaterialTheme.typography.titleSmall)
        reactions.takeLast(5).reversed().forEach { Text(reactionLabel(it.kind) + (it.reply?.let { r -> " — \"$r\"" } ?: "")) }
    }
    if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodyMedium, color = if (msg.startsWith("Linked") || msg == "Synced." || msg == "Linking…") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
    DevServerSettings(app) { tick++ }
}

/** Development only: point at the local emulators or another Firebase project. Hidden under an expander. */
@Composable
private fun DevServerSettings(app: App, changed: () -> Unit) {
    var emulator by remember { mutableStateOf(app.prefs.bool("cloud.emulator", false)) }
    var project by remember { mutableStateOf(app.prefs.str("cloud.project", "")) }
    Expander("Advanced: developer server settings") {
        SwitchSetting("Use local emulators", "For development: talk to the Firebase emulator suite on this Mac instead of the OnTask server.", emulator) { emulator = it; app.prefs.set("cloud.emulator", it); changed() }
        OutlinedTextField(project, { project = it; app.prefs.set("cloud.project", it.trim().ifEmpty { null }); changed() }, label = { Text("Other Firebase project ID (leave empty for OnTask's)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

private fun reactionLabel(kind: String) = when (kind) { "thumbs_up" -> "👍"; "proud" -> "Proud of you"; "call_me" -> "Call me when you can"; "take_a_walk" -> "Take a walk"; else -> kind }

@Composable
fun UpdateSettings(app: App) {
    var repo by remember { mutableStateOf(app.prefs.str("update.repo", "")) }
    var msg by remember { mutableStateOf(app.updateAvailable?.let { "Version ${it.version} is available." } ?: "") }
    val scope = rememberCoroutineScope()
    SectionCard("Updates", "OnTask checks GitHub Releases weekly and tells you when a new version is out. It never installs anything by itself.") {
        KV("Installed version", UpdateCheck.currentVersion())
        OutlinedTextField(repo, { repo = it; app.prefs.set("update.repo", it.trim().ifEmpty { null }) }, label = { Text("GitHub repository (owner/name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ scope.launch { val u = withContext(Dispatchers.IO) { app.checkForUpdates() }; msg = u?.let { "Version ${it.version} is available." } ?: "You're up to date." } }, enabled = repo.isNotBlank()) { Text("Check now") }
            app.updateAvailable?.let { u -> Button({ runCatching { ProcessBuilder("open", u.dmgUrl ?: u.pageUrl).start() } }) { Text("Download ${u.version}") } }
        }
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
    }
}
