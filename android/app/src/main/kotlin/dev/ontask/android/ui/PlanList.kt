package dev.ontask.android.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
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

/** A tap on empty space ends text editing (and so saves a task being edited). */
fun Modifier.clearFocusOnTap(): Modifier = composed {
    val fm = LocalFocusManager.current
    pointerInput(Unit) { detectTapGestures { fm.clearFocus() } }
}

private fun Modifier.plainClick(onClick: () -> Unit): Modifier = composed {
    clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

/**
 * A day's plan as tasks to check off; finished ones stay, struck through. [editable]: "+ Add task" at the bottom,
 * tap a task to change it (Done on the keyboard, or tapping elsewhere, saves), × to remove, and press and hold ⋮⋮
 * then drag to reorder. Checking off works everywhere.
 */
@Composable
fun PlanList(date: String = dayKey(), editable: Boolean = false, empty: String? = "No plan was set today.") {
    val items = rememberPlan(date)
    var adding by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Column(Modifier.fillMaxWidth().clearFocusOnTap(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (items.isEmpty() && !editable && empty != null) Muted(empty)
            val rowPx = with(LocalDensity.current) { 45.dp.toPx() }
            items.forEachIndexed { i, it ->
                key(it.id) { PlanRow(date, items, it, i, editable, rowPx) }
            }
            if (editable) {
                if (adding) Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⋮⋮", Modifier.padding(end = 4.dp).alpha(0f)) // lines up with the tasks' handles
                    Checkbox(false, null, Modifier.size(36.dp).alpha(0.4f))
                    var round by remember { mutableStateOf(0) }
                    val all by rememberUpdatedState(items)
                    key(round) {
                        TaskField("", Modifier.weight(1f).padding(start = 6.dp), Plan.ADD_HINT,
                            onSave = { t -> if (t.isNotBlank()) PhonePlan.save(date, all + PlanItem(Plan.newId(), t.trim())) },
                            onEnter = { round++ }, onEnd = { adding = false })
                    }
                } else TextButton({ adding = true }, Modifier.padding(top = 5.dp)) { Text("+ Add task") }
                if (items.size >= Plan.SOFT_MAX) Muted(Plan.TOO_MANY)
            }
        }
    }
}

@Composable
private fun PlanRow(date: String, items: List<PlanItem>, item: PlanItem, index: Int, editable: Boolean, rowPx: Float) {
    val all by rememberUpdatedState(items)
    val at by rememberUpdatedState(index)
    var drag by remember { mutableStateOf(0f) }
    fun put(l: List<PlanItem>) = PhonePlan.save(date, l)
    fun toggle() = put(all.map { if (it.id == item.id) it.copy(done = !it.done) else it })
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (editable) Text("⋮⋮", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp).pointerInput(item.id) {
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
        Checkbox(item.done, { toggle() }, Modifier.size(36.dp))
        if (editable) InlineText(item, Modifier.weight(1f).padding(start = 6.dp)) { t ->
            put(if (t.isBlank()) all.filter { it.id != item.id } else all.map { if (it.id == item.id) it.copy(text = t.trim()) else it })
        } else Text(
            item.text, Modifier.weight(1f).padding(start = 6.dp).alpha(if (item.done) 0.6f else 1f).plainClick { toggle() },
            style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (item.done) TextDecoration.LineThrough else null),
        )
        if (editable) Box(
            Modifier.padding(horizontal = 8.dp).size(24.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clip(CircleShape).plainClick { put(all.filter { it.id != item.id }) },
            contentAlignment = Alignment.Center,
        ) { Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A task's text, editable in place: tapping puts the cursor where you tapped; Done or tapping elsewhere saves. */
@Composable
private fun InlineText(item: PlanItem, modifier: Modifier, onSave: (String) -> Unit) {
    var text by remember(item.id) { mutableStateOf(item.text) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(item.text) { if (!focused) text = item.text }
    val fm = LocalFocusManager.current
    BasicTextField(
        text, { text = it },
        modifier.alpha(if (item.done && !focused) 0.6f else 1f)
            .onFocusChanged { if (focused && !it.isFocused && text.trim() != item.text) onSave(text); focused = it.isFocused },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, textDecoration = if (item.done && !focused) TextDecoration.LineThrough else null),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { fm.clearFocus() }),
    )
}

/** An inline, borderless field the size of a task; saves when left in any way (Done, tapping elsewhere, another task). */
@Composable
private fun TaskField(initial: String, modifier: Modifier, placeholder: String?, onSave: (String) -> Unit, onEnter: (() -> Unit)?, onEnd: () -> Unit) {
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
        modifier.focusRequester(focus).onFocusChanged { if (focused && !it.isFocused) { save(); if (!viaEnter) onEnd() }; focused = it.isFocused },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = if (onEnter != null) ImeAction.Next else ImeAction.Done),
        keyboardActions = KeyboardActions(onAny = { save(); if (onEnter != null) { viaEnter = true; onEnter() } else onEnd() }),
        decorationBox = { inner ->
            Box {
                if (text.isEmpty() && placeholder != null) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                inner()
            }
        },
    )
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
