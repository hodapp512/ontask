package dev.ontask.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.Query
import dev.ontask.android.Cloud
import dev.ontask.android.dayKey
import dev.ontask.plan.Plan
import dev.ontask.plan.PlanItem
import java.time.LocalDate

/** The plan shared with the Mac: users/{uid}/plans/{date}, the whole list rewritten on each change (newest wins). */
object PhonePlan {
    fun save(date: String, items: List<PlanItem>) {
        val uid = Cloud.uid ?: return
        Cloud.db.document("users/$uid/plans/$date").set(mapOf("date" to date, "items" to Plan.toMaps(items), "updatedAt" to System.currentTimeMillis(), "by" to "phone"))
    }
}

/** Today's (or [date]'s) tasks, live. */
@Composable
fun rememberPlan(date: String = dayKey()): List<PlanItem> {
    val uid = Cloud.uid
    val d by remember(uid, date) { Cloud.doc("users/$uid/plans/$date") }.collectAsState(null)
    return Plan.fromMaps(d?.get("items"))
}

/**
 * A day's plan as tasks to check off; finished ones stay, struck through. [editable]: add at the bottom, tap a task
 * to change it, × to remove, and press and hold ⋮⋮ then drag to reorder. Checking off works everywhere.
 */
@Composable
fun PlanList(date: String = dayKey(), editable: Boolean = false, empty: String? = "No plan was set today.") {
    val items = rememberPlan(date)
    Column {
        if (items.isEmpty() && !editable && empty != null) Muted(empty)
        val rowPx = with(LocalDensity.current) { 48.dp.toPx() }
        items.forEachIndexed { i, it -> key(it.id) { PlanRow(date, items, it, i, editable, rowPx) } }
        if (editable) {
            var text by remember { mutableStateOf("") }
            fun add() { if (text.isNotBlank()) { PhonePlan.save(date, items + PlanItem(Plan.newId(), text.trim())); text = "" } }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(Plan.ADD_HINT) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }))
                TextButton(::add, enabled = text.isNotBlank()) { Text("Add") }
            }
            if (items.size >= Plan.SOFT_MAX) Muted(Plan.TOO_MANY)
        }
    }
}

@Composable
private fun PlanRow(date: String, items: List<PlanItem>, item: PlanItem, index: Int, editable: Boolean, rowPx: Float) {
    val all by rememberUpdatedState(items)
    val at by rememberUpdatedState(index)
    var editing by remember(item.id) { mutableStateOf(false) }
    var text by remember(item.id, item.text) { mutableStateOf(item.text) }
    var drag by remember { mutableStateOf(0f) }
    fun put(l: List<PlanItem>) = PhonePlan.save(date, l)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (editable) Text("⋮⋮", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp).pointerInput(item.id) {
            detectDragGesturesAfterLongPress(onDragEnd = { drag = 0f }, onDragCancel = { drag = 0f }) { ch, d ->
                ch.consume(); drag += d.y
                val step = (drag / rowPx).toInt()
                if (step != 0) {
                    val to = (at + step).coerceIn(0, all.size - 1)
                    if (to != at) put(all.toMutableList().apply { add(to, removeAt(at)) })
                    drag -= step * rowPx
                }
            }
        })
        Checkbox(item.done, { put(all.map { if (it.id == item.id) it.copy(done = !it.done) else it }) })
        if (editing) {
            fun save() { editing = false; put(if (text.isBlank()) all.filter { it.id != item.id } else all.map { if (it.id == item.id) it.copy(text = text.trim()) else it }) }
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }))
        } else Text(
            item.text, Modifier.weight(1f).alpha(if (item.done) 0.6f else 1f)
                .clickable { if (editable) editing = true else put(all.map { if (it.id == item.id) it.copy(done = !it.done) else it }) },
            style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (item.done) TextDecoration.LineThrough else null),
        )
        if (editable && !editing) TextButton({ put(all.filter { it.id != item.id }) }) { Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * The previous plan day ("Yesterday", or the day's name), struck through where finished. [carry]: each unfinished
 * task has "Add to today".
 */
@Composable
fun YesterdayPlan(carry: Boolean = false) {
    val uid = Cloud.uid ?: return
    val today = dayKey()
    val q by remember(uid, today) {
        Cloud.query(Cloud.db.collection("users/$uid/plans").whereLessThan("date", today).orderBy("date", Query.Direction.DESCENDING).limit(1))
    }.collectAsState(null)
    val doc = q?.documents?.firstOrNull() ?: return
    val items = Plan.fromMaps(doc.get("items")).takeIf { it.isNotEmpty() } ?: return
    val day = runCatching { LocalDate.parse(doc.getString("date")) }.getOrNull() ?: return
    val todays = rememberPlan(today)
    val label = if (day == LocalDate.now().minusDays(1)) "Yesterday" else day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$label's plan · ${Plan.progress(items)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { it ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (it.done) "✓ " else "• ") + it.text, Modifier.weight(1f).alpha(0.75f),
                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = if (it.done) TextDecoration.LineThrough else null))
                if (carry && !it.done) {
                    if (todays.any { t -> t.text.equals(it.text, ignoreCase = true) }) Muted("Added")
                    else TextButton({ PhonePlan.save(today, todays + PlanItem(Plan.newId(), it.text)) }) { Text("Add to today") }
                }
            }
        }
    }
}
