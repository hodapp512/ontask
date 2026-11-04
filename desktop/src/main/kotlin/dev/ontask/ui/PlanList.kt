package dev.ontask.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.ontask.App
import dev.ontask.plan.Plan
import dev.ontask.plan.PlanItem
import java.time.LocalDate

/**
 * A day's plan as tasks to check off. Finished ones stay, struck through. [editable]: add (the field at the bottom),
 * click a task's text to change it, × to remove it, and drag ⋮⋮ to reorder. Checking off works everywhere.
 */
@Composable
fun PlanList(app: App, date: LocalDate = LocalDate.now(), editable: Boolean = false, empty: String = "No plan was set today.") {
    val v by app.priorities.version.collectAsState()
    val items = remember(v, date) { app.priorities.items(date) }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (items.isEmpty() && !editable) Text(empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val rowPx = with(LocalDensity.current) { 40.dp.toPx() }
        items.forEachIndexed { i, it -> androidx.compose.runtime.key(it.id) { PlanRow(app, date, it, i, items.size, editable, rowPx) } }
        if (editable) AddRow(app, date, items.size)
    }
}

@Composable
private fun PlanRow(app: App, date: LocalDate, item: PlanItem, index: Int, count: Int, editable: Boolean, rowPx: Float) {
    var editing by remember(item.id) { mutableStateOf(false) }
    var text by remember(item.id, item.text) { mutableStateOf(item.text) }
    var drag by remember { mutableStateOf(0f) }
    val at by androidx.compose.runtime.rememberUpdatedState(index)
    val n by androidx.compose.runtime.rememberUpdatedState(count)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (editable) Text(
            "⋮⋮", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 2.dp).pointerInput(item.id) {
                detectDragGestures(onDragEnd = { drag = 0f }, onDragCancel = { drag = 0f }) { ch, d ->
                    ch.consume(); drag += d.y
                    // A row's height of dragging moves the task one place.
                    val step = (drag / rowPx).toInt()
                    if (step != 0) {
                        val to = (at + step).coerceIn(0, n - 1)
                        if (to != at) app.priorities.move(date, at, to)
                        drag -= step * rowPx
                    }
                }
            },
        )
        Checkbox(item.done, { app.priorities.toggle(date, item.id) })
        if (editing) {
            fun save() { editing = false; if (text.trim() != item.text) app.priorities.edit(date, item.id, text) }
            OutlinedTextField(text, { text = it }, Modifier.weight(1f).onPreviewKeyEvent { e -> if (e.key == Key.Enter) { save(); true } else false },
                singleLine = true, trailingIcon = { TextButton(::save) { Text("Save") } })
        } else Text(
            item.text,
            Modifier.weight(1f).alpha(if (item.done) 0.6f else 1f).then(if (editable) Modifier.clickable { editing = true } else Modifier.clickable { app.priorities.toggle(date, item.id) }),
            style = MaterialTheme.typography.bodyMedium.copy(textDecoration = if (item.done) TextDecoration.LineThrough else null),
        )
        if (editable && !editing) IconButton({ app.priorities.remove(date, item.id) }, Modifier.size(32.dp)) { Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun AddRow(app: App, date: LocalDate, count: Int) {
    var text by remember { mutableStateOf("") }
    fun add() { if (text.isNotBlank()) { app.priorities.add(date, text); text = "" } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        OutlinedTextField(text, { text = it }, Modifier.weight(1f).onPreviewKeyEvent { e -> if (e.key == Key.Enter) { add(); true } else false },
            singleLine = true, placeholder = { Text(Plan.ADD_HINT) })
        TextButton(::add, enabled = text.isNotBlank()) { Text("Add") }
    }
    if (count >= Plan.SOFT_MAX) Text(Plan.TOO_MANY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The previous plan day ("Yesterday", or the day's name), struck through where finished. [carry]: each unfinished
 * task has "Add to today".
 */
@Composable
fun YesterdayPlan(app: App, today: LocalDate = LocalDate.now(), carry: Boolean = false) {
    val v by app.priorities.version.collectAsState()
    val prev = remember(v, today) { app.priorities.previous(today) } ?: return
    val (day, items) = prev
    if (items.isEmpty()) return
    val todays = remember(v, today) { app.priorities.items(today).map { it.text.lowercase() }.toSet() }
    val label = if (day == today.minusDays(1)) "Yesterday" else day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$label's plan · ${Plan.progress(items)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { it ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (it.done) "✓ " else "• ") + it.text, Modifier.weight(1f).alpha(0.75f),
                    style = MaterialTheme.typography.bodySmall.copy(textDecoration = if (it.done) TextDecoration.LineThrough else null),
                )
                if (carry && !it.done) {
                    if (it.text.lowercase() in todays) Text("Added", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else TextButton({ app.priorities.add(today, it.text) }) { Text("Add to today") }
                }
            }
        }
    }
}
