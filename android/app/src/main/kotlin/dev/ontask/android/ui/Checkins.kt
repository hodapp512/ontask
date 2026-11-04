package dev.ontask.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.SetOptions
import dev.ontask.android.Cloud
import dev.ontask.android.dayKey
import kotlinx.coroutines.tasks.await

private val FEELINGS = listOf(1 to "😣 Rough", 2 to "😕 Meh", 3 to "🙂 Okay", 4 to "😊 Good", 5 to "🤩 Great")
private val OBSTACLES = listOf("Distracted / low energy", "Interruptions/meetings", "Unclear what to do", "Stuck on a problem", "Overwhelmed", "Other")

/**
 * A check-in done on the phone (when you're on the phone rather than the Mac): planning, midday or evening, saved
 * to users/{uid}/phoneCheckins/{date}; the Mac takes it in as if done there, so it's done on both.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CheckinScreen(kind: String, onSaved: () -> Unit = {}, onDone: () -> Unit) {
    val uid = Cloud.uid ?: return
    var plan by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { runCatching { Cloud.db.document("users/$uid/checkins/${dayKey()}").get().await().getString("plan") }.getOrNull()?.let { if (plan.isBlank()) plan = it } }
    // Already done, on the Mac or on this phone (an old notification, or done meanwhile): close instead of asking again.
    androidx.compose.runtime.DisposableEffect(kind) {
        val key = kind.lowercase().removeSuffix("_short")
        fun macDone(d: Map<String, Any?>) = when (key) {
            "planning" -> !(d["plan"] as? String).isNullOrBlank()
            "midday" -> d["midday"] != null
            else -> d["evening"] == true
        }
        val regs = listOf(
            Cloud.db.document("users/$uid/checkins/${dayKey()}").addSnapshotListener { s, _ -> if (macDone(s?.data ?: emptyMap())) onDone() },
            Cloud.db.document("users/$uid/phoneCheckins/${dayKey()}").addSnapshotListener { s, _ -> if (s?.get(key) != null) onDone() },
        )
        onDispose { regs.forEach { it.remove() } }
    }
    fun save(data: Map<String, Any?>) {
        Cloud.db.document("users/$uid/phoneCheckins/${dayKey()}").set(mapOf(kind.lowercase().removeSuffix("_short") to data + ("ts" to System.currentTimeMillis())), SetOptions.merge())
        onSaved(); onDone()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (kind) {
            "PLANNING", "PLANNING_SHORT" -> {
                Text(if (kind == "PLANNING") "Good morning" else "Quick plan for the rest of the day", style = MaterialTheme.typography.headlineMedium)
                OCard("Today's plan") {
                    OutlinedTextField(plan, { plan = it }, Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("What matters today?") })
                    Button({ save(mapOf("plan" to plan.trim())) }, enabled = plan.isNotBlank()) { Text("Start my day") }
                }
            }
            "MIDDAY" -> {
                Text("Halfway through", style = MaterialTheme.typography.headlineMedium)
                var done by remember { mutableStateOf("") }; var should by remember { mutableStateOf("") }
                val chips = remember { mutableStateListOf<String>() }; var showChips by remember { mutableStateOf(false) }
                OCard("How's the day going?") {
                    OutlinedTextField(done, { done = it }, Modifier.fillMaxWidth(), label = { Text("What did you get done?") })
                    OutlinedTextField(should, { should = it }, Modifier.fillMaxWidth(), label = { Text("What should you have gotten done?") })
                }
                OCard("This afternoon's plan") { Muted("Still the same as this morning?"); OutlinedTextField(plan, { plan = it }, Modifier.fillMaxWidth(), minLines = 2) }
                if (showChips) OCard("What's in the way?") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { OBSTACLES.forEach { c -> FilterChip(c in chips, { if (c in chips) chips.remove(c) else chips.add(c) }, label = { Text(c) }) } }
                } else TextButton({ showChips = true }) { Text("Something in the way?") }
                Button({ save(mapOf("done" to done.trim(), "should" to should.trim(), "chips" to chips.joinToString(", "), "plan" to plan.trim())) }) { Text("Done") }
            }
            else -> {
                Text("Wrapping up", style = MaterialTheme.typography.headlineMedium)
                // Today's goals, for reference while you reflect.
                OCard("Today's plan") { Text(plan.ifBlank { "No plan was set today." }) }
                var rating by remember { mutableStateOf<Int?>(null) }
                var helped by remember { mutableStateOf("") }; var hurt by remember { mutableStateOf("") }
                var change by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
                OCard("How did today feel?") { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FEELINGS.forEach { (r, l) -> FilterChip(rating == r, { rating = r }, label = { Text(l) }) } } }
                OCard("What helped and what hurt?") {
                    OutlinedTextField(helped, { helped = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("What helped") })
                    OutlinedTextField(hurt, { hurt = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("What hurt") })
                }
                OCard("One change for tomorrow (optional)") { OutlinedTextField(change, { change = it }, Modifier.fillMaxWidth(), singleLine = true) }
                OCard("A note for your partner (optional)") { OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), minLines = 2) }
                Button({ save(mapOf("rating" to rating?.toString(), "helped" to helped.trim(), "hurt" to hurt.trim(), "change" to change.trim(), "note" to note.trim())) }) { Text("Done") }
            }
        }
        Row { OutlinedButton(onDone) { Text("Later") } }
    }
}
