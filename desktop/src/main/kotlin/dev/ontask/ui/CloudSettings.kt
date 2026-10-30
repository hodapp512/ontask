package dev.ontask.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    SectionCard("Accountability partner & phone", "Pair with a partner and your phone. Only what you choose to share leaves your Mac — never the raw activity log.") { CloudSettingsBody(app) }
}

@Composable
fun CloudSettingsBody(app: App) {
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf(app.prefs.str("cloud.project", "")) }
    var apiKey by remember { mutableStateOf(app.prefs.str("cloud.api_key", "")) }
    var emulator by remember { mutableStateOf(app.prefs.bool("cloud.emulator", false)) }
    var token by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf<Pair<String, String>?>(null) }
    var tick by remember { mutableStateOf(0) }
    val signedIn = remember(tick) { app.cloud.signedIn }

    fun saveCfg() { app.prefs.set("cloud.project", project.trim().ifEmpty { null }); app.prefs.set("cloud.api_key", apiKey.trim().ifEmpty { null }); app.prefs.set("cloud.emulator", emulator) }

    if (!app.cloud.configured) Text("Not connected yet. Enter your Firebase project below (SETUP.md explains how to create it).", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(project, { project = it; saveCfg() }, label = { Text("Firebase project ID") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(apiKey, { apiKey = it; saveCfg() }, label = { Text("Web API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
    }
    SwitchSetting("Use local emulators", "For development: talk to the Firebase emulator suite on this Mac instead of the cloud.", emulator) { emulator = it; saveCfg() }

    KV("Status", if (signedIn) "This Mac is linked" + (app.cloud.lastSyncTs.takeIf { it > 0 }?.let { " · last sync ${fmtTime(it)}" } ?: "") else "Not linked")
    app.cloud.lastError?.let { KV("Last sync error", it) }
    if (!signedIn) {
        Text("In the OnTask web app: Me → Link my Mac → Create link code, then paste it here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(token, { token = it }, label = { Text("Link code") }, singleLine = true, modifier = Modifier.weight(1f))
            Button({
                scope.launch(Dispatchers.IO) { msg = try { app.cloud.linkThisMac(token); token = ""; "Linked." } catch (e: Exception) { e.message ?: "Failed" }; tick++ }
            }, enabled = token.isNotBlank() && app.cloud.configured) { Text("Link this Mac") }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                scope.launch(Dispatchers.IO) {
                    msg = try {
                        val code = app.cloud.client()!!.call("createInvite")["code"]!!.jsonPrimitive.content
                        invite = code to "${app.cloud.config!!.webBase}/#/join/$code"; "Invite created."
                    } catch (e: Exception) { e.message ?: "Failed" }
                }
            }) { Text("Invite a partner") }
            OutlinedButton({ scope.launch(Dispatchers.IO) { app.cloud.syncOnce(App.nowSec(), force = true); tick++; msg = app.cloud.lastError ?: "Synced." } }) { Text("Sync now") }
            TextButton({ app.cloud.signOut(); tick++ }) { Text("Unlink") }
        }
        invite?.let { (code, link) ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(link)
                Column { Text(code, style = MaterialTheme.typography.headlineMedium); Text(link, style = MaterialTheme.typography.bodySmall); Text("Send this to your partner. It works once and expires in 7 days.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    val reactions = remember(tick) { app.cloud.reactions() }
    if (reactions.isNotEmpty()) {
        Text("Recent encouragement", style = MaterialTheme.typography.titleSmall)
        reactions.takeLast(5).reversed().forEach { Text(reactionLabel(it.kind) + (it.reply?.let { r -> " — \"$r\"" } ?: "")) }
    }
    if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
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
