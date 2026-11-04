package dev.ontask.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ontask.App
import dev.ontask.plan.Plan
import dev.ontask.plan.PlanItem
import java.time.LocalDate

private const val ROW_DP = 30
private const val NEW = "+new"

/** A click on empty space ends text editing (and so saves a task being edited). */
fun Modifier.clearFocusOnTap(): Modifier = composed {
    val fm = LocalFocusManager.current
    pointerInput(Unit) { detectTapGestures { fm.clearFocus() } }
}

/** clickable without the hover and press shading. */
private fun Modifier.plainClick(onClick: () -> Unit): Modifier = composed {
    clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

/**
 * A day's plan as tasks to check off. Finished ones stay, struck through. [editable]: "+ Add task" at the bottom,
 * click a task's text to change it (Enter, or clicking anywhere else, saves), × to remove, drag ⋮⋮ to reorder.
 * Checking off works everywhere.
 */
@Composable
fun PlanList(app: App, date: LocalDate = LocalDate.now(), editable: Boolean = false, empty: String = "No plan was set today.") {
    val v by app.priorities.version.collectAsState()
    val items = remember(v, date) { app.priorities.items(date) }
    // One task is edited at a time: starting another (or clicking away) ends and saves the current one.
    var editing by remember { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Column(Modifier.fillMaxWidth().clearFocusOnTap()) {
            if (items.isEmpty() && !editable) Text(empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val rowPx = with(LocalDensity.current) { ROW_DP.dp.toPx() }
            items.forEachIndexed { i, it ->
                key(it.id) {
                    PlanRow(app, date, it, i, items.size, editable, rowPx, editing == it.id, onEdit = { editing = it.id }, onEditEnd = { if (editing == it.id) editing = null })
                }
            }
            if (editable) {
                if (editing == NEW) NewRow(app, date) { if (editing == NEW) editing = null }
                else TextButton({ editing = NEW }, Modifier.height(32.dp)) { Text("+ Add task", style = MaterialTheme.typography.labelLarge) }
                if (items.size >= Plan.SOFT_MAX) Text(Plan.TOO_MANY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PlanRow(
    app: App, date: LocalDate, item: PlanItem, index: Int, count: Int, editable: Boolean, rowPx: Float,
    editing: Boolean, onEdit: () -> Unit, onEditEnd: () -> Unit,
) {
    var drag by remember { mutableStateOf(0f) }
    val at by rememberUpdatedState(index)
    val n by rememberUpdatedState(count)
    Row(Modifier.fillMaxWidth().height(ROW_DP.dp), verticalAlignment = Alignment.CenterVertically) {
        if (editable) Text(
            "⋮⋮", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = 4.dp).pointerInput(item.id) {
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
        Checkbox(item.done, { app.priorities.toggle(date, item.id) }, Modifier.size(28.dp))
        if (editing) TaskField(item.text, Modifier.weight(1f).padding(start = 6.dp), onSave = { t -> if (t.trim() != item.text) app.priorities.edit(date, item.id, t) }, onEnd = onEditEnd)
        else Text(
            item.text,
            Modifier.weight(1f).padding(start = 6.dp).alpha(if (item.done) 0.6f else 1f).plainClick { if (editable) onEdit() else app.priorities.toggle(date, item.id) },
            style = MaterialTheme.typography.bodyMedium.copy(textDecoration = if (item.done) TextDecoration.LineThrough else null),
        )
        if (editable && !editing) Text("×", Modifier.padding(horizontal = 8.dp).plainClick { app.priorities.remove(date, item.id) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The task being added: Enter adds it and opens a fresh one; clicking away adds what's typed and closes. */
@Composable
private fun NewRow(app: App, date: LocalDate, onEnd: () -> Unit) {
    var round by remember { mutableStateOf(0) }
    Row(Modifier.fillMaxWidth().height(ROW_DP.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(false, null, Modifier.size(28.dp).alpha(0.4f))
        key(round) {
            TaskField("", Modifier.weight(1f).padding(start = 6.dp), placeholder = Plan.ADD_HINT,
                onSave = { t -> if (t.isNotBlank()) app.priorities.add(date, t) },
                onEnter = { round++ }, onEnd = onEnd)
        }
    }
}

/**
 * An inline, borderless text field the same size as a task. It saves when it's left in any way: Enter, a click
 * elsewhere, or starting to edit another task. Escape leaves without saving.
 */
@Composable
private fun TaskField(initial: String, modifier: Modifier, placeholder: String? = null, onSave: (String) -> Unit, onEnter: (() -> Unit)? = null, onEnd: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val latest by rememberUpdatedState(text)
    var saved by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var viaEnter by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun save() { if (!saved) { saved = true; onSave(latest) } }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DisposableEffect(Unit) { onDispose { save() } }
    BasicTextField(
        text, { text = it },
        modifier.focusRequester(focus)
            .onFocusChanged { if (focused && !it.isFocused) { save(); if (!viaEnter) onEnd() }; focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                when (e.key) {
                    Key.Enter, Key.NumPadEnter -> { if (e.type == KeyEventType.KeyDown) { save(); if (onEnter != null) { viaEnter = true; onEnter() } else onEnd() }; true }
                    Key.Escape -> { saved = true; onEnd(); true }
                    else -> false
                }
            },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            androidx.compose.foundation.layout.Box {
                if (text.isEmpty() && placeholder != null) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                inner()
            }
        },
    )
}

/**
 * The previous plan day ("Yesterday", or the day's name), struck through where finished. [carry]: each unfinished
 * task has "Add to today". Shown in the morning plan only.
 */
@Composable
fun YesterdayPlan(app: App, today: LocalDate = LocalDate.now(), carry: Boolean = false) {
    val v by app.priorities.version.collectAsState()
    val prev = remember(v, today) { app.priorities.previous(today) } ?: return
    val (day, items) = prev
    if (items.isEmpty()) return
    val todays = remember(v, today) { app.priorities.items(today).map { it.text.lowercase() }.toSet() }
    val label = if (day == today.minusDays(1)) "Yesterday" else day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text("$label's plan · ${Plan.progress(items)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { it ->
            Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (it.done) "✓ " else "• ") + it.text, Modifier.weight(1f).alpha(0.75f),
                    style = MaterialTheme.typography.bodySmall.copy(textDecoration = if (it.done) TextDecoration.LineThrough else null),
                )
                if (carry && !it.done) {
                    if (it.text.lowercase() in todays) Text("Added", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Text("Add to today", Modifier.plainClick { app.priorities.add(today, it.text) }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
