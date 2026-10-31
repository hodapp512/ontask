package dev.ontask.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import dev.ontask.report.Run
import java.time.LocalTime
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import dev.ontask.App
import dev.ontask.Category
import dev.ontask.Level
import dev.ontask.classify.RuleKind
import dev.ontask.classify.domainOf
import dev.ontask.report.DailyReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateBar(date: LocalDate, onDate: (LocalDate) -> Unit, onRefresh: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val fmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }
    // Compact: ◀ [Today ▾] ▶ — the middle opens a calendar.
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton({ onDate(date.minusDays(1)) }) { Text("◀") }
        TextButton({ picking = true }) { Text(if (date == LocalDate.now()) "📅 Today" else "📅 " + DateTimeFormatter.ofPattern("EEE, MMM d").format(date)) }
        TextButton({ onDate(date.plusDays(1)) }, enabled = date < LocalDate.now()) { Text("▶") }
    }
    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() <= LocalDate.now()
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("Show this day") }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
internal fun HelperBanner(app: App) {
    val live by app.tracker.live.collectAsState()
    val status = app.helper.status
    if (status != "running" && status != "not started") Notice(
        if (status == "timeout") "OnTask's activity reader is slow to respond. If this stays, quit and reopen OnTask."
        else "OnTask can't see what you're working on right now, so nothing is being tracked. Quit and reopen OnTask; if it keeps happening, check Diagnostics.",
        error = true,
    )
    else if (live.sample?.accessibilityTrusted == false) AccessibilityBanner(app)
}

/**
 * Asks for Accessibility, then re-checks every few seconds (restarting the helper so macOS reports the new grant)
 * until it's granted. If it still isn't after a while, offers to clear OnTask's stale entry and ask again.
 */
@Composable
private fun AccessibilityBanner(app: App) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var attempt by remember { mutableStateOf(0) } // bumped on each request, restarting the re-check loop
    var stuck by remember { mutableStateOf(false) }
    val asked = attempt > 0
    LaunchedEffect(attempt) {
        if (attempt == 0) return@LaunchedEffect
        repeat(24) { i ->
            kotlinx.coroutines.delay(5_000)
            if (app.permissions.status(fresh = true).accessibility == dev.ontask.track.PermState.GRANTED) return@LaunchedEffect
            if (i == 5) stuck = true
        }
    }
    when {
        stuck -> Notice(
            "Switched OnTask on but this message is still here? macOS is remembering an older copy of OnTask. " +
                "Fix it removes that old entry and asks again — then switch OnTask on once more.",
            action = "Fix it",
        ) { stuck = false; attempt++; scope.launch { app.permissions.resetAccessibility() } }
        asked -> Notice("Waiting for you to switch OnTask on under Accessibility… this message goes away by itself.", action = "Open settings again") { scope.launch { app.permissions.open(dev.ontask.track.PrivacyPane.ACCESSIBILITY) } }
        else -> Notice(
            "OnTask can't see window titles yet, so it can't tell one document or chat from another. Click Allow, choose Open System Settings in the box macOS shows, then switch OnTask on.",
            action = "Allow…",
        ) { attempt++; scope.launch { app.permissions.requestAccessibility() } }
    }
}

/** The phone-style summary: deep-focus share, a bar by level, the timeline, and where the time went. */
@Composable
fun SummaryCard(r: DailyReport, title: String, app: App? = null, onRelabel: () -> Unit = {}, header: @Composable () -> Unit = {}, more: @Composable ColumnScope.() -> Unit = {}) {
    val denom = Level.entries.filter { it != Level.AWAY }.sumOf { r.totalsAll[it] ?: 0 }
    val total = Level.entries.sumOf { r.totalsAll[it] ?: 0 }
    PlainCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); header()
        }
        if (total == 0L) {
            Text("Nothing tracked for this day. OnTask records automatically while it's running.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@PlainCard
        }
        Text(
            if (denom >= 600) "${(r.totalsAll[Level.DEEP] ?: 0) * 100 / denom}% focused" else "${fmtDuration(r.totalsAll[Level.DEEP] ?: 0)} focused",
            style = MaterialTheme.typography.headlineMedium,
        )
        Row(Modifier.fillMaxWidth().height(12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))) {
            Level.entries.forEach { l -> val v = r.totalsAll[l] ?: 0; if (v > 0) androidx.compose.foundation.layout.Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(levelColor(l))) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Level.entries.forEach { l -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Dot(levelColor(l), 10); Text("${l.label} ${fmtDuration(r.totalsAll[l] ?: 0)}") } }
        }
        val workTotal = r.totalsWork.values.sum()
        if (workTotal > 0 && workTotal < total) Text(
            "During work hours: ${fmtDuration(r.totalsWork[Level.DEEP] ?: 0)} focused, ${fmtDuration(r.totalsWork[Level.MIXED] ?: 0)} mixed, ${fmtDuration(r.totalsWork[Level.WASTED] ?: 0)} wasted",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Timeline(r)
        Text(
            "${r.deepSessions.size} focused session${if (r.deepSessions.size == 1) "" else "s"} (25+ min)" +
                (r.deepSessions.maxOfOrNull { it.seconds }?.let { " (longest ${fmtDuration(it)})" } ?: "") +
                " · ${r.distractionEvents} distraction${if (r.distractionEvents == 1) "" else "s"}" +
                (r.worstHour?.let { " · most wasted around ${LocalTime.of(Instant.ofEpochSecond(r.dayStart + it.first * 3600L).atZone(ZoneId.systemDefault()).hour, 0).format(DateTimeFormatter.ofPattern("h a"))}" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (r.deepSessions.isNotEmpty()) Expander("Longest focused sessions") {
            r.deepSessions.sortedByDescending { it.seconds }.take(5).forEach { Text("${clockRange(it.start, it.end)}  ·  ${fmtDuration(it.seconds)}") }
        }
        if (r.topSubjects.isNotEmpty()) Expander("Where the time went") {
            if (app != null) Text("Labeled wrong? Change it here — OnTask remembers and fixes past days too.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            r.topSubjects.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, Modifier.weight(0.5f), maxLines = 1)
                    Row(Modifier.weight(0.3f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (app != null) CategoryPicker(app, s.name, s.category, onRelabel, item = s.itemKey?.let { it to s.title })
                        else { Dot(levelColor(s.category.level), 8); Text(s.category.label, style = MaterialTheme.typography.bodySmall) }
                    }
                    Text(fmtDuration(s.seconds), Modifier.weight(0.2f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        more()
    }
}

/** Colored stretches across the active part of the day; hover one to see when it was and what you did. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun Timeline(r: DailyReport) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val (from, to) = timelineWindow(r.timeline.map { (it.start - r.dayStart) to (it.end - r.dayStart) }, r.timeline.map { it.level == Level.AWAY })
    val span = (to - from).toFloat()
    var width by remember { mutableStateOf(1) }
    var hover by remember(r) { mutableStateOf<Pair<Run, Float>?>(null) }
    fun runAt(x: Float): Run? {
        val t = r.dayStart + from + (x / width * span).toLong()
        val slack = (4f / width * span).toLong() // a few pixels, so thin stretches can be hovered too
        return r.timeline.firstOrNull { t >= it.start && t < it.end } ?: r.timeline.filter { t >= it.start - slack && t < it.end + slack }.minByOrNull { minOf(kotlin.math.abs(t - it.start), kotlin.math.abs(t - it.end)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        androidx.compose.foundation.layout.Box {
            Canvas(
                Modifier.fillMaxWidth().height(32.dp)
                    .onSizeChanged { width = it.width.coerceAtLeast(1) }
                    .onPointerEvent(PointerEventType.Move) { e -> val x = e.changes.first().position.x; hover = runAt(x)?.let { it to x } }
                    .onPointerEvent(PointerEventType.Exit) { hover = null },
            ) {
                drawRoundRect(track, size = size, cornerRadius = CornerRadius(6f))
                r.timeline.forEach { run ->
                    val x0 = ((run.start - r.dayStart - from) / span * size.width).coerceIn(0f, size.width)
                    val x1 = ((run.end - r.dayStart - from) / span * size.width).coerceIn(0f, size.width)
                    val on = hover?.first == run
                    drawRect(levelColor(run.level).copy(alpha = if (hover == null || on) 1f else 0.55f), Offset(x0, 0f), Size(maxOf(x1 - x0, 1.5f), size.height))
                }
            }
            hover?.let { (run, x) ->
                androidx.compose.ui.window.Popup(offset = androidx.compose.ui.unit.IntOffset((x - 120).toInt().coerceAtLeast(0), 90)) {
                    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 320.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Dot(levelColor(run.level), 8)
                                Text("${dev.ontask.report.runLabel(run)} · ${clockRange(run.start, run.end)} · ${fmtDuration(run.seconds)}", style = MaterialTheme.typography.labelLarge)
                            }
                            val top = run.top(3)
                            if (top.isEmpty()) Text(if (run.level == Level.AWAY) "Away from the computer" else "No app recorded", style = MaterialTheme.typography.bodySmall)
                            top.forEach { (n, sec) -> Text("$n — ${fmtDuration(sec)}", style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            timelineLabels(from, to).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text("Hover over the timeline to see what you did.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val shortTime = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private fun clockRange(a: Long, b: Long): String {
    val z = ZoneId.systemDefault()
    return "${Instant.ofEpochSecond(a).atZone(z).format(shortTime)}–${Instant.ofEpochSecond(b).atZone(z).format(shortTime)}"
}

/**
 * Seconds-after-midnight window to draw: whole hours around the time you were actually at the computer, at least 2
 * hours; the full day if there's nothing. Away time doesn't widen it, and neither does a stray minute or two far from
 * the rest (the Mac waking at night): active stretches within an hour of each other form a session, and sessions with
 * under 5 minutes in them are left out unless nothing else is left.
 */
fun timelineWindow(runs: List<Pair<Long, Long>>, away: List<Boolean> = runs.map { false }): Pair<Long, Long> {
    val active = runs.filterIndexed { i, _ -> !away[i] }.sortedBy { it.first }
    if (active.isEmpty()) return 0L to 86_400L
    val sessions = mutableListOf(mutableListOf(active.first()))
    active.drop(1).forEach { r -> if (r.first - sessions.last().maxOf { it.second } <= 3600) sessions.last().add(r) else sessions.add(mutableListOf(r)) }
    val kept = sessions.filter { s -> s.sumOf { it.second - it.first } >= 300 }.ifEmpty { sessions }.flatten()
    var from = kept.minOf { it.first } / 3600 * 3600
    var to = ((kept.maxOf { it.second } + 3599) / 3600 * 3600).coerceAtMost(86_400)
    if (to - from < 7200) { to = (from + 7200).coerceAtMost(86_400); from = (to - 7200).coerceAtLeast(0) }
    return from to to
}

/** About five evenly spaced clock labels across the window, e.g. "8 AM", "10 AM". */
fun timelineLabels(from: Long, to: Long): List<String> {
    val hours = ((to - from) / 3600).toInt().coerceAtLeast(1)
    val step = listOf(1, 2, 3, 4, 6).firstOrNull { hours / it <= 6 } ?: 6
    val fmt = java.time.format.DateTimeFormatter.ofPattern("h a")
    return (0..hours step step).map { java.time.LocalTime.of(((from / 3600 + it) % 24).toInt(), 0).format(fmt) }
}
