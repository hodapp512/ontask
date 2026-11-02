package dev.ontask.android.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.Query
import dev.ontask.android.Cloud
import dev.ontask.android.REACTIONS
import dev.ontask.android.ago
import dev.ontask.android.dayKey
import dev.ontask.android.firstName
import dev.ontask.android.fmtDate
import dev.ontask.android.num
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Your accountability partner (what they can see, encouragement) and the people you support. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PeopleScreen(uid: String, onOpenPerson: (String) -> Unit) {
    val mine by remember { Cloud.query(Cloud.db.collection("links").whereEqualTo("trackedUid", uid)) }.collectAsState(null)
    val supporting by remember { Cloud.query(Cloud.db.collection("links").whereEqualTo("partnerUid", uid)) }.collectAsState(null)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Your accountability partner")
        val links = mine?.documents.orEmpty()
        if (mine != null && links.isEmpty()) InviteCard()
        links.forEach { d ->
            val partner = d.getString("partnerName") ?: "Your partner"
            OCard(partner) {
                Muted("Gets your daily report and the alerts you choose to send")
                var open by remember { mutableStateOf(false) }
                val share = d.getString("share") ?: "report"
                Text("What ${firstName(partner)} can see")
                androidx.compose.foundation.layout.Box {
                    OutlinedButton({ open = true }) { Text((if (share == "report") "Your full daily report" else "Only the messages you send") + " ▾") }
                    DropdownMenu(open, { open = false }) {
                        DropdownMenuItem({ Text("Your full daily report") }, { open = false; d.reference.update("share", "report") })
                        DropdownMenuItem({ Text("Only the messages you send") }, { open = false; d.reference.update("share", "messages") })
                    }
                }
            }
        }
        SectionTitle(if (supporting?.documents.orEmpty().size > 1) "People you support" else "Someone you support")
        val sup = supporting?.documents.orEmpty()
        if (supporting != null && sup.isEmpty()) JoinCard()
        sup.forEach { d ->
            val who = d.getString("trackedName") ?: "Your friend"
            OCard(who) {
                ReactionButtons(d.id, d.getString("trackedUid") ?: "", uid)
                TextButton({ onOpenPerson(d.id) }) { Text("Their day, earlier days and messages →") }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReactionButtons(linkId: String, trackedUid: String, uid: String) {
    var status by remember { mutableStateOf<String?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        REACTIONS.forEach { (kind, label) ->
            OutlinedButton({
                Cloud.db.collection("links/$linkId/reactions").add(mapOf("kind" to kind, "fromUid" to uid, "trackedUid" to trackedUid, "ts" to System.currentTimeMillis()))
                status = "Sent. ✔"
            }) { Text(label) }
        }
    }
    status?.let { Muted(it) }
}

/** Ask someone to be your partner: a link they open (in the web app, or this app's People tab with the code). */
@Composable
private fun InviteCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    OCard {
        Muted("Someone you trust gets a short daily report from you and, if you want, a heads-up when you're struggling.")
        code?.let { c ->
            val link = "https://ontask-ed2df.web.app/#/join/$c"
            Text("Send this link to the person you want. They open it, sign in, and you're paired.")
            Button({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Will you be my accountability partner on OnTask? Open this link: $link"), "Share invite")) }) { Text("Share link…") }
            Muted("Code $c · works once, for 7 days")
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (code == null) Button({ scope.launch { runCatching { Cloud.call("createInvite") }.onSuccess { code = it?.get("code") as? String }.onFailure { err = it.message } } }) { Text("Invite a partner") }
    }
}

@Composable
private fun JoinCard() {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    OCard("Someone asked you to be their partner?") {
        Muted("Type the code from the link they sent you.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(code, { code = it.uppercase() }, Modifier.weight(1f), singleLine = true, label = { Text("Invite code") })
            TextButton({ scope.launch { runCatching { Cloud.call("acceptInvite", mapOf("code" to code.trim())) }.onSuccess { msg = "Paired. ✔" }.onFailure { msg = it.message } } }, enabled = code.isNotBlank()) { Text("Join") }
        }
        msg?.let { Muted(it) }
    }
}

/** One person you support: their day (any day), the year, encouragement and messages. */
@Suppress("UNCHECKED_CAST")
@Composable
fun PersonScreen(uid: String, linkId: String, onBack: () -> Unit) {
    val link by remember { Cloud.doc("links/$linkId") }.collectAsState(null)
    val l = link ?: run { Muted("Loading…"); return }
    val tracked = l.getString("trackedUid") ?: return
    val name = l.getString("trackedName") ?: "Your friend"
    var day by remember { mutableStateOf(LocalDate.now()) }
    val report by remember(day) { Cloud.doc("users/$tracked/reports/${dayKey(day)}") }.collectAsState(null)
    val heat by remember { Cloud.doc("users/$tracked/heat/year") }.collectAsState(null)
    val msgs by remember { Cloud.query(Cloud.db.collection("links/$linkId/messages").orderBy("ts", Query.Direction.DESCENDING).limit(30)) }.collectAsState(null)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onBack) { Text("← Back") }; Text(name, style = MaterialTheme.typography.titleLarge) }
        if (l.getString("share") == "report") {
            OCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ day = day.minusDays(1) }) { Text("←") }
                    Text(if (day == LocalDate.now()) "Today" else fmtDate(day), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton({ day = day.plusDays(1) }, enabled = day < LocalDate.now()) { Text("→") }
                }
                report?.data?.let { ReportBody(it) } ?: Muted(if (day == LocalDate.now()) "${firstName(name)} hasn't been at the computer yet today." else "No report for this day.")
            }
            HeatCard(heat?.data) { day = it }
        } else OCard { Muted("${firstName(name)} shares messages only.") }
        OCard("Send encouragement") { ReactionButtons(linkId, tracked, uid) }
        OCard("Messages") {
            val list = msgs?.documents.orEmpty()
            if (list.isEmpty()) Muted("No messages yet. You'll see check-ins and alerts here.")
            list.forEach { m -> Column { Muted(ago(m.get("ts").num()) + " · " + (m.getString("type") ?: "").replace('_', ' ')); Text(m.getString("text") ?: "") } }
        }
    }
}
