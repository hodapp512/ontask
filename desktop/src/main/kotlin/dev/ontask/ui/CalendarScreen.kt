package dev.ontask.ui

import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.widthIn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.ontask.App
import dev.ontask.block.ReasonRequired
import dev.ontask.classify.Slots
import dev.ontask.classify.WorkSchedule
import dev.ontask.classify.WorkWeek
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val CELL = 14.dp

/** Visual work-hours editor: paint half-hour cells; edit the weekly template or this week's exceptions. */
@Composable
fun CalendarScreen(app: App, initialWeekStart: LocalDate = LocalDate.now().with(DayOfWeek.MONDAY)) {
    var templateMode by remember { mutableStateOf(true) }
    var weekStart by remember { mutableStateOf(initialWeekStart) }
    var version by remember { mutableStateOf(0) }       // bump to redraw after in-place edits
    var reloadKey by remember { mutableStateOf(0) }
    var askReason by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf("") }

    val schedule = app.settings.schedule
    val dates = remember(weekStart) { (0..6).map { weekStart.plusDays(it.toLong()) } }
    // Draft: one 48-slot mask per column.
    val draft = remember(templateMode, weekStart, reloadKey) {
        dates.mapIndexed { i, d ->
            val r = if (templateMode) schedule.week.days[i + 1].orEmpty() else schedule.rangesFor(d)
            Slots.fromRanges(r)
        }
    }

    fun draftWeek() = WorkWeek((0..6).associate { (it + 1) to Slots.toRanges(draft[it]) }.filterValues { it.isNotEmpty() })
    fun draftSchedule(): WorkSchedule =
        if (templateMode) schedule.copy(week = draftWeek())
        else schedule.copy(exceptions = schedule.exceptions + dates.indices.associate { dates[it] to Slots.toRanges(draft[it]) })

    fun removed(): Int = if (templateMode)
        DayOfWeek.entries.sumOf { Slots.removedMinutes(schedule.week.days[it.value].orEmpty(), draftWeek().days[it.value].orEmpty()) }
    else dates.sumOf { Slots.removedMinutes(schedule.rangesFor(it), Slots.toRanges(draft[dates.indexOf(it)])) }

    fun commit(reason: String?) {
        val now = App.nowSec()
        try {
            if (templateMode) app.workHours.saveTemplate(draftWeek(), reason, now)
            else dates.forEachIndexed { i, d ->
                val ranges = Slots.toRanges(draft[i])
                if (ranges != schedule.rangesFor(d)) {
                    // Back to the template = drop the exception
                    app.workHours.saveException(d, if (ranges == schedule.week.days[d.dayOfWeek.value].orEmpty()) null else ranges, reason, now)
                }
            }
            message = "Saved."; reloadKey++
        } catch (e: ReasonRequired) { askReason = e.removedMinutes }
    }

    val total = draft.sumOf { d -> d.count { it } } * Slots.MINUTES / 60.0
    Page("Work hours", "Paint the hours you work. Blocking and strict monitoring apply inside them; outside, anything goes.") {
        SectionCard("What are you editing?") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(templateMode, { templateMode = true }, label = { Text("Weekly template") })
                FilterChip(!templateMode, { templateMode = false }, label = { Text("A specific week (exceptions)") })
            }
            Text(
                if (templateMode) "Your usual week. Applies every week unless a date has an exception."
                else "Changes only the dates below, e.g. a day off or a short day. The template stays as is.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!templateMode) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconTextButton("◀", "Previous week") { weekStart = weekStart.minusWeeks(1) }
                Text(
                    "Week of " + weekStart.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")),
                    Modifier.widthIn(min = 190.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleSmall,
                )
                IconTextButton("▶", "Next week") { weekStart = weekStart.plusWeeks(1) }
                if (weekStart != LocalDate.now().with(DayOfWeek.MONDAY)) TextButton({ weekStart = LocalDate.now().with(DayOfWeek.MONDAY) }) { Text("Back to this week") }
            }
        }
        SectionCard("%.1f hours %s".format(total, if (templateMode) "per week" else "this week"), "Click or drag to paint. Starting on a filled cell erases instead.") {
            WeekGrid(draft, dates, templateMode, version) { version++ }
            if (!templateMode) TextButton({
                dates.forEachIndexed { i, d -> if (d in schedule.exceptions) { /* revert draft to template */ val t = Slots.fromRanges(schedule.week.days[i + 1].orEmpty()); t.copyInto(draft[i]) } }
                version++
            }) { Text("Reset this week to the template") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({ commit(null) }) { Text("Save") }
            OutlinedButton({ reloadKey++; message = "" }) { Text("Discard changes") }
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
    askReason?.let { mins ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askReason = null },
            title = { Text("Why are you reducing work time?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This removes ${if (mins % 60 == 0) "${mins / 60}h" else "${mins / 60}h ${mins % 60}m"} of work time, which turns blocking off during it. If you've paired with an accountability partner, they'll be told your reason and what it means. Adding hours never notifies anyone.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(reason, { reason = it }, label = { Text("Reason") }, singleLine = false, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button({ askReason = null; commit(reason) }, enabled = reason.isNotBlank()) { Text("Save and notify") } },
            dismissButton = { TextButton({ askReason = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun WeekGrid(draft: List<BooleanArray>, dates: List<LocalDate>, template: Boolean, version: Int, onEdit: () -> Unit) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    val guide = MaterialTheme.colorScheme.onSurface
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Cell under the mouse, and while dragging the cell the drag started on: both shown in the caption above.
    var hover by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var dragFrom by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var painting by remember { mutableStateOf(true) }
    val timeFmt = remember { java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT) }
    fun clock(slot: Int) = java.time.LocalTime.of(slot * Slots.MINUTES / 60 % 24, slot * Slots.MINUTES % 60).format(timeFmt)
    fun dayName(c: Int) = DayOfWeek.of(c + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault()) + if (template) "" else " ${dates[c].dayOfMonth}"
    val caption = run {
        val h = hover
        val from = dragFrom
        when {
            from != null && h != null -> {
                val (c0, c1) = minOf(from.first, h.first) to maxOf(from.first, h.first)
                val (r0, r1) = minOf(from.second, h.second) to maxOf(from.second, h.second)
                (if (painting) "Adding " else "Removing ") + (if (c0 == c1) dayName(c0) else "${dayName(c0)}–${dayName(c1)}") + ", ${clock(r0)} – ${clock(r1 + 1)}"
            }
            h != null -> "${dayName(h.first)}, ${clock(h.second)} – ${clock(h.second + 1)} · " + if (draft[h.first][h.second]) "work hours (click to remove)" else "free (click to add)"
            else -> "Point at a cell to see its time. Click or drag to add or remove work time."
        }
    }
    Column {
        Text(caption, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleSmall, color = if (hover != null) MaterialTheme.colorScheme.primary else textColor)
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(56.dp))
            dates.forEachIndexed { i, d ->
                Text(
                    DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault()) + if (template) "" else "\n${d.dayOfMonth}",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = if (hover?.first == i) MaterialTheme.colorScheme.primary else textColor, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(56.dp)) {
                for (h in 0 until 24) Box(Modifier.height(CELL * 2)) {
                    Text(
                        java.time.LocalTime.of(h, 0).format(java.time.format.DateTimeFormatter.ofPattern("h a")), style = MaterialTheme.typography.labelSmall,
                        color = if (hover?.second?.div(2) == h) MaterialTheme.colorScheme.primary else textColor,
                    )
                }
            }
            Canvas(
                Modifier.weight(1f).height(CELL * Slots.PER_DAY)
                    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move) { e ->
                        val p = e.changes.first().position
                        val c = (p.x / (size.width / 7f)).toInt(); val r = (p.y / (size.height / Slots.PER_DAY.toFloat())).toInt()
                        hover = if (c in 0..6 && r in 0 until Slots.PER_DAY) c to r else null
                    }
                    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { if (dragFrom == null) hover = null }
                    .pointerInput(draft) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val colW = size.width / 7f; val rowH = size.height / Slots.PER_DAY.toFloat()
                            fun cell(p: Offset): Pair<Int, Int>? {
                                val c = (p.x / colW).toInt(); val r = (p.y / rowH).toInt()
                                return if (c in 0..6 && r in 0 until Slots.PER_DAY) c to r else null
                            }
                            val first = cell(down.position) ?: return@awaitEachGesture
                            val paint = !draft[first.first][first.second]
                            painting = paint; dragFrom = first; hover = first
                            fun apply(p: Offset) { cell(p)?.let { (c, r) -> hover = c to r; if (draft[c][r] != paint) { draft[c][r] = paint; onEdit() } } }
                            apply(down.position)
                            drag(down.id) { change -> apply(change.position); change.consume() }
                            dragFrom = null
                        }
                    },
            ) {
                version.hashCode() // read to redraw on edits
                val colW = size.width / 7f; val rowH = size.height / Slots.PER_DAY
                for (c in 0 until 7) for (r in 0 until Slots.PER_DAY) {
                    drawRoundRect(if (draft[c][r]) on else off, Offset(c * colW + 1.5f, r * rowH + 1f), Size(colW - 3f, rowH - 2f), CornerRadius(3f))
                }
                for (h in 0..24 step 6) drawLine(line, Offset(0f, h * 2 * rowH), Offset(size.width, h * 2 * rowH), 1f)
                hover?.let { (c, r) ->
                    // A faint guide across the week at that time, and a ring around the cell itself.
                    drawLine(guide.copy(alpha = 0.25f), Offset(0f, (r + 0.5f) * rowH), Offset(size.width, (r + 0.5f) * rowH), 1f)
                    drawRoundRect(guide, Offset(c * colW + 1f, r * rowH + 0.5f), Size(colW - 2f, rowH - 1f), CornerRadius(3f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                }
            }
        }
    }
}

/** Small round arrow button. */
@Composable
private fun IconTextButton(symbol: String, description: String, onClick: () -> Unit) {
    androidx.compose.material3.FilledTonalIconButton(onClick, Modifier.semantics { contentDescription = description }) { Text(symbol) }
}
