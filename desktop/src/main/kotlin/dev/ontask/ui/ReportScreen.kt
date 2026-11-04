package dev.ontask.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
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
        // Narrow arrow buttons, so they sit close to the date.
        val arrow = PaddingValues(horizontal = 4.dp)
        TextButton({ onDate(date.minusDays(1)) }, Modifier.widthIn(min = 28.dp), contentPadding = arrow) { Text("◀") }
        // Sized to the date, so the arrows sit right next to it (they move a little as the date gets longer).
        TextButton({ picking = true }, contentPadding = arrow) { Text(if (date == LocalDate.now()) "📅 Today" else "📅 " + fmtDate(date), maxLines = 1) }
        TextButton({ onDate(date.plusDays(1)) }, Modifier.widthIn(min = 28.dp), enabled = date < LocalDate.now(), contentPadding = arrow) { Text("▶") }
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

/** A one-line pointer to Diagnostics for problems that have no banner of their own here. */
@Composable
internal fun ProblemsHint(app: App, now: Long) {
    val others = remember(now) { app.problems().filter { it != "OnTask can't see window titles" && it != "The activity reader isn't running" && !it.startsWith("Blocking isn't on") } }
    if (others.isNotEmpty()) Text(
        "⚠️ " + others.joinToString(" · ") + " — see Diagnostics",
        Modifier.clickable { Nav.open(Dest.DIAGNOSTICS) }.pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand),
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
    )
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
        // Screen time = everything but Away (focused + mixed + wasted).
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                if (denom >= 600) "${(r.totalsAll[Level.DEEP] ?: 0) * 100 / denom}% focused" else "${fmtDuration(r.totalsAll[Level.DEEP] ?: 0)} focused",
                Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium,
            )
            Column(horizontalAlignment = Alignment.End) {
                Text("Screen time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmtDuration(denom), style = MaterialTheme.typography.titleLarge)
            }
        }
        Row(Modifier.fillMaxWidth().height(12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))) {
            // Screen time only: Away isn't part of the bar or the totals below it.
            Level.entries.filter { it != Level.AWAY }.forEach { l -> val v = r.totalsAll[l] ?: 0; if (v > 0) androidx.compose.foundation.layout.Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(levelColor(l))) }
        }
        // The day's totals; hovering shows the same split for work hours only (when there was time outside them).
        val workTotal = r.totalsWork.values.sum()
        val legend: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Level.entries.filter { it != Level.AWAY }.forEach { l -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Dot(levelColor(l), 10); Text("${l.label} ${fmtDuration(r.totalsAll[l] ?: 0)}") } }
            }
        }
        if (workTotal > 0 && workTotal < total) androidx.compose.foundation.TooltipArea(
            tooltip = {
                androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("During work hours", style = MaterialTheme.typography.labelLarge)
                        Text("${fmtDuration(r.totalsWork[Level.DEEP] ?: 0)} focused · ${fmtDuration(r.totalsWork[Level.MIXED] ?: 0)} mixed · ${fmtDuration(r.totalsWork[Level.WASTED] ?: 0)} wasted",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            delayMillis = 300,
        ) { legend() } else legend()
        Timeline(r, app, onRelabel)
        if (r.deepSessions.isNotEmpty()) Expander("Longest focused sessions") {
            r.deepSessions.sortedByDescending { it.seconds }.take(5).forEach { Text("${clockRange(it.start, it.end)}  ·  ${fmtDuration(it.seconds)}") }
        }
        if (r.topSubjects.isNotEmpty()) Expander("Where the time went") {
            r.topSubjects.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text((if (s.phone) "📱 " else "") + s.name, Modifier.weight(0.5f), maxLines = 1)
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

/**
 * Colored stretches across the active part of the day; hover one to see when it was and what you did, click it for
 * everything in it (and to change their categories). Zoomable for a closer look at busy stretches: pinch on the
 * trackpad (or ⌘-scroll) to zoom around the pointer, swipe sideways with two fingers or drag to move along the day, or
 * use the − / + / Reset buttons. Scrolling up and down scrolls the page, as in a browser.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun Timeline(r: DailyReport, app: App?, onRelabel: () -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    // Glances under 15 s are drawn as part of the stretch around them (listed in its details).
    val timeline = remember(r) { dev.ontask.report.Sessions.absorbShort(r.timeline) }
    val (from0, to0) = timelineWindow(timeline.map { (it.start - r.dayStart) to (it.end - r.dayStart) }, timeline.map { it.level == Level.AWAY })
    // The visible window, in seconds after midnight.
    var view by remember(r.date, from0, to0) { mutableStateOf(from0.toDouble() to to0.toDouble()) }
    val (from, to) = view
    val span = (to - from)
    var width by remember { mutableStateOf(1) }
    var hover by remember(r) { mutableStateOf<Pair<Run, Float>?>(null) }
    var dragX by remember { mutableStateOf<Float?>(null) }
    var pressX by remember { mutableStateOf<Float?>(null) }
    var selected by remember(r) { mutableStateOf<Run?>(null) }
    // Both read the current view (not the values from the last redraw): a pinch delivers many steps in a row.
    fun zoom(factor: Double, atFrac: Double = 0.5) {
        val (from, to) = view; val span = to - from
        val newSpan = (span * factor).coerceIn(MIN_SPAN_SEC, 86_400.0)
        val anchor = from + span * atFrac
        var a = anchor - newSpan * atFrac
        a = a.coerceIn(0.0, 86_400.0 - newSpan)
        view = a to a + newSpan
    }
    fun pan(dx: Float) {
        val (from, to) = view; val span = to - from
        val shift = -dx / width * span
        val a = (from + shift).coerceIn(0.0, 86_400.0 - span)
        view = a to a + span
    }
    fun runAt(x: Float): Run? {
        val t = r.dayStart + (from + x / width * span).toLong()
        val slack = (4f / width * span).toLong() // a few pixels, so thin stretches can be hovered too
        val shown = timeline.filter { it.level != Level.AWAY }
        return shown.firstOrNull { t >= it.start && t < it.end } ?: shown.filter { t >= it.start - slack && t < it.end + slack }.minByOrNull { minOf(kotlin.math.abs(t - it.start), kotlin.math.abs(t - it.end)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        androidx.compose.foundation.layout.Box {
            Canvas(
                Modifier.fillMaxWidth().height(32.dp)
                    .onSizeChanged { width = it.width.coerceAtLeast(1) }
                    .onPointerEvent(PointerEventType.Move) { e ->
                        val x = e.changes.first().position.x
                        // A trackpad pinch zooms around the pointer.
                        Pinch.target = { f -> zoom(f, (x / width).toDouble().coerceIn(0.0, 1.0)) }
                        dragX?.let { pan(x - it); dragX = x; hover = null } ?: run { hover = runAt(x)?.let { it to x } }
                    }
                    .onPointerEvent(PointerEventType.Press) { e -> dragX = e.changes.first().position.x; pressX = dragX }
                    .onPointerEvent(PointerEventType.Release) { e ->
                        // A click (not a drag) opens the stretch under the pointer.
                        val x = e.changes.first().position.x
                        if (pressX?.let { kotlin.math.abs(x - it) < 4f } == true) runAt(x)?.takeIf { it.level != Level.AWAY }?.let { selected = it; hover = null }
                        dragX = null; pressX = null
                    }
                    .onPointerEvent(PointerEventType.Enter) { e -> val x = e.changes.first().position.x; Pinch.target = { f -> zoom(f, (x / width).toDouble().coerceIn(0.0, 1.0)) } }
                    .onPointerEvent(PointerEventType.Exit) { hover = null; dragX = null; Pinch.target = null }
                    .onPointerEvent(PointerEventType.Scroll) { e ->
                        // Like a browser: a sideways swipe moves along the day; up and down scroll the page (left alone here);
                        // ⌘ or Ctrl with the wheel zooms, in steps as small as the scroll. Zooming is otherwise the pinch.
                        val c = e.changes.first()
                        val d = c.scrollDelta
                        val zoomKey = e.keyboardModifiers.isMetaPressed || e.keyboardModifiers.isCtrlPressed
                        if (zoomKey && d.y != 0f) { zoom(kotlin.math.exp(d.y.toDouble().coerceIn(-3.0, 3.0) * 0.1), (c.position.x / width).toDouble().coerceIn(0.0, 1.0)); c.consume(); hover = null }
                        else if (kotlin.math.abs(d.x) > kotlin.math.abs(d.y)) { pan(-d.x * 20f); c.consume(); hover = null }
                    },
            ) {
                drawRoundRect(track, size = size, cornerRadius = CornerRadius(6f))
                // Away time isn't drawn: the gaps say it. Mac and phone stretches share the one bar.
                timeline.filter { it.level != Level.AWAY }.forEach { run ->
                    val x0 = ((run.start - r.dayStart - from) / span * size.width).toFloat().coerceIn(0f, size.width)
                    val x1 = ((run.end - r.dayStart - from) / span * size.width).toFloat().coerceIn(0f, size.width)
                    if (x1 <= 0f || x0 >= size.width) return@forEach
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
                                Text((if (run.phone) "📱 " else "") + dev.ontask.report.runLabel(run), style = MaterialTheme.typography.labelLarge)
                            }
                            Text("${clockRange(run.start, run.end)} · ${fmtDuration(run.seconds)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val top = run.top(3)
                            if (top.isEmpty()) Text("No app recorded", style = MaterialTheme.typography.bodySmall)
                            top.forEach { (n, sec) -> Text("$n — ${fmtDuration(sec)}", style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                        }
                    }
                }
            }
        }
        selected?.let { run -> RunDetails(run, app, { selected = null }) { selected = null; onRelabel() } }
        // Clock ticks placed at their times (5-minute steps when zoomed in, hours when zoomed out).
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            val w = maxWidth
            timelineTicks(from.toLong(), to.toLong(), r.dayStart).forEach { (t, label) ->
                val frac = ((t - from) / span).toFloat()
                Text(label, Modifier.offset(x = (w * frac - 16.dp).coerceIn(0.dp, w - 40.dp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            // Reset appears to the left, so it never lands under a second quick click on +.
            if (from != from0.toDouble() || to != to0.toDouble()) TextButton({ view = from0.toDouble() to to0.toDouble() }) { Text("Reset") }
            TextButton({ zoom(1.5) }, enabled = span < 86_400.0) { Text("−") }
            TextButton({ zoom(1 / 1.5) }, enabled = span > MIN_SPAN_SEC) { Text("+") }
        }
    }
}

/** A clicked stretch of the timeline: when, how long, and everything in it, each with its category to change. */
@Composable
private fun RunDetails(run: Run, app: App?, onClose: () -> Unit, onRelabel: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(levelColor(run.level), 10); Text(dev.ontask.report.runLabel(run))
            }
        },
        text = {
            Column(Modifier.widthIn(min = 360.dp, max = 560.dp)) {
                Text("${clockRange(run.start, run.end)} · ${fmtDuration(run.seconds)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                val items = run.top(Int.MAX_VALUE)
                if (items.isEmpty()) Text("No app recorded")
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    // Glances drawn as part of this stretch aren't listed: they were in passing.
                    items.forEachIndexed { i, (name, sec) ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text(fmtDuration(sec), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
                            val ref = run.refs[name]; val cat = run.category ?: Category.UNKNOWN
                            if (app != null && ref != null) CategoryPicker(app, ref.subject, cat, onRelabel, item = ref.itemKey?.let { it to ref.title })
                            else { Dot(levelColor(run.level), 8); Text("  ${dev.ontask.report.runLabel(run)}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } },
    )
}

/** Zooming stops at a 5-minute window. */
private const val MIN_SPAN_SEC = 300.0

/**
 * Clock ticks for a window (seconds after midnight): the smallest step from 5 minutes to 6 hours that gives at most
 * six ticks, each at a round time ("10 AM", "10:15").
 */
/** At most this many clock markers under a timeline: hourly across a working day. */
private const val MAX_TICKS = 12

fun timelineTicks(from: Long, to: Long, dayStart: Long? = null): List<Pair<Long, String>> {
    val step = listOf(5, 10, 15, 30, 60, 120, 180, 240, 360).map { it * 60L }.firstOrNull { (to - from) / it <= MAX_TICKS } ?: 21_600L
    val first = (from + step - 1) / step * step
    return (first..to step step).map { t ->
        // With [dayStart], the label is the real clock time (right on daylight-saving days, when the day isn't 24 h).
        val time = dayStart?.let { Instant.ofEpochSecond(it + t).atZone(ZoneId.systemDefault()).toLocalTime() }
            ?: java.time.LocalTime.of(((t / 3600) % 24).toInt(), ((t % 3600) / 60).toInt())
        t to time.format(java.time.format.DateTimeFormatter.ofPattern(if (time.minute == 0) "h a" else "h:mm", java.util.Locale.US))
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
    val step = listOf(1, 2, 3, 4, 6).firstOrNull { hours / it <= MAX_TICKS } ?: 6
    val fmt = java.time.format.DateTimeFormatter.ofPattern("h a")
    return (0..hours step step).map { java.time.LocalTime.of(((from / 3600 + it) % 24).toInt(), 0).format(fmt) }
}
