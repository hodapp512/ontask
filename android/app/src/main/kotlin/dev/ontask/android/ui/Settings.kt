package dev.ontask.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.SetOptions
import dev.ontask.android.Cloud
import dev.ontask.android.MESSAGE_TYPES
import dev.ontask.android.ago
import dev.ontask.android.num
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Account, notifications, linked devices, backup contact, and how messages from people you support reach you. */
@Suppress("UNCHECKED_CAST")
@Composable
fun SettingsScreen(uid: String, notificationsOn: Boolean, onEnableNotifications: () -> Unit, onSignOut: () -> Unit) {
    val devices by remember { Cloud.query(Cloud.db.collection("users/$uid/devices")) }.collectAsState(null)
    val supporting by remember { Cloud.query(Cloud.db.collection("links").whereEqualTo("partnerUid", uid)) }.collectAsState(null)
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        OCard("Account") {
            Text(Cloud.auth.currentUser?.displayName ?: ""); Muted(Cloud.auth.currentUser?.email ?: "")
            OutlinedButton(onSignOut) { Text("Sign out") }
        }
        OCard("Notifications on this phone") {
            Muted(if (notificationsOn) "On ✓" else "Off")
            if (!notificationsOn) Button(onEnableNotifications) { Text("Turn on notifications") }
        }
        OCard("Linked devices") {
            val list = devices?.documents.orEmpty().map { it.data ?: emptyMap() }.sortedByDescending { maxOf(it["lastSyncMs"].num(), it["lastSeenMs"].num()) }
            if (list.isEmpty()) Muted("None yet. Link your Mac from Home.")
            list.forEach { d -> Row { Text("${if (d["kind"] == "mac") "💻" else "📱"} ${d["name"] ?: d["kind"]}", Modifier.weight(1f)); Muted(ago(maxOf(d["lastSyncMs"].num(), d["lastSeenMs"].num()))) } }
        }
        BackupContact(uid) { note = it }
        supporting?.documents.orEmpty().forEach { d ->
            val prefs = (d.get("partnerPrefs") as? Map<String, Any?>) ?: emptyMap()
            val channels = remember(d.id) { mutableStateMapOf<String, String>().apply { MESSAGE_TYPES.forEach { (k, _) -> put(k, ((prefs["channels"] as? Map<String, Any?>)?.get(k) as? String) ?: "push") } } }
            OCard("How you hear about ${d.getString("trackedName") ?: "your friend"}") {
                Muted("Pick how each kind of message reaches you. Text and email use the backup contact above.")
                MESSAGE_TYPES.forEach { (k, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f))
                        var open by remember { mutableStateOf(false) }
                        Box {
                            TextButton({ open = true }) { Text((channels[k] ?: "push").replaceFirstChar { it.uppercase() } + " ▾") }
                            DropdownMenu(open, { open = false }) { listOf("push", "text", "email", "off").forEach { c -> DropdownMenuItem({ Text(c.replaceFirstChar { it.uppercase() }) }, { open = false; channels[k] = c }) } }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ d.reference.update("partnerPrefs.channels", channels.toMap()); note = "Saved." }) { Text("Save") }
                    OutlinedButton({ scope.launch { note = runCatching { Cloud.call("sendTestMessage", mapOf("linkId" to d.id)) }.fold({ r -> if (r?.get("delivered") == true) "Test sent via ${r["channel"]}." else "Not delivered (${r?.get("reason") ?: "see Messages"})." }, { it.message }) } }) { Text("Send test message") }
                }
            }
        }
        note?.let { Muted(it) }
    }
}

@Composable
private fun BackupContact(uid: String, say: (String) -> Unit) {
    val ref = remember { Cloud.db.document("users/$uid/contact/main") }
    var phone by remember { mutableStateOf("") }; var email by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { runCatching { ref.get().await() }.getOrNull()?.let { phone = it.getString("phone") ?: ""; email = it.getString("email") ?: "" } }
    OCard("Backup contact (optional)") {
        Muted("Used only if push can't reach you. Stored privately in your account.")
        OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Phone (for texts)") })
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Email") })
        OutlinedButton({ ref.set(mapOf("phone" to phone.trim().ifBlank { null }, "email" to email.trim().ifBlank { null }), SetOptions.merge()); say("Saved.") }) { Text("Save contact details") }
    }
}
