package dev.ontask.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton({ onDate(date.minusDays(1)) }) { Text("◀") }
        OutlinedButton({ picking = true }, Modifier.weight(1f)) { Text("📅  " + fmt.format(date)) }
        OutlinedButton({ onDate(date.plusDays(1)) }, enabled = date < LocalDate.now()) { Text("▶") }
        TextButton({ onDate(LocalDate.now()) }, enabled = date != LocalDate.now()) { Text("Today") }
        TextButton(onRefresh) { Text("Refresh") }
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
    else if (live.sample?.accessibilityTrusted == false)
        Notice("Window titles of non-browser apps are hidden until you grant Accessibility access (System Settings → Privacy & Security). Diagnostics has a shortcut.")
}

@Composable
internal fun NowCard(app: App, onLabeled: () -> Unit) {
    val live by app.tracker.live.collectAsState()
    SectionCard("Right now", "What OnTask sees at this moment and how it labeled it. If it's wrong, correct it here and OnTask will remember.") {
        val s = live.sample; val c = live.classification
        if (live.away) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Dot(levelColor(Level.AWAY)); Text("You're away — not counted against you.") }
        else if (s == null || c == null) Text("Waiting for the first reading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            val domain = domainOf(s.url)
            Text(listOfNotNull(s.app, domain).joinToString("  ·  "), style = MaterialTheme.typography.titleSmall)
            s.title?.let { Text(it.take(120), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(levelColor(c.level))
                Text("${c.category.label} → ${c.level.label}", Modifier.weight(1f), color = levelColor(c.level))
                Text(c.source.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val kind = if (domain != null) RuleKind.DOMAIN else RuleKind.APP
            val pattern = domain ?: s.app
            if (pattern != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Wrong? Teach OnTask that $pattern is:", style = MaterialTheme.typography.bodySmall)
                Picker("choose category", Category.assignable, { it.label }) { cat ->
                    app.ruleStore.label(kind, pattern, cat, c.category, App.nowSec()); app.reloadRules(); onLabeled()
                }
            }
        }
    }
}

@Composable
fun ReportBody(r: DailyReport) {
    val denom = Level.entries.filter { it != Level.AWAY }.sumOf { r.totalsAll[it] ?: 0 }
    if (r.totalsAll.isEmpty() && r.timeline.isEmpty()) {
        EmptyState("Nothing recorded for this day", "Tracking starts automatically while OnTask is running. Pick another day or check Diagnostics if you expected data.")
        return
    }
    SectionCard("Time by level", if (denom > 0) "Deep share of tracked time: ${(r.totalsAll[Level.DEEP] ?: 0) * 100 / denom}% (away excluded)" else null) {
        Row(Modifier.fillMaxWidth()) {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1.2f)); HeaderCell("All day"); HeaderCell("Work hours")
        }
        Level.entries.forEach { l ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Dot(levelColor(l)); Text(l.label) }
                Cell(fmtDuration(r.totalsAll[l] ?: 0)); Cell(fmtDuration(r.totalsWork[l] ?: 0))
            }
        }
    }
    SectionCard("Timeline", "From your first to your last activity today. Gaps mean the Mac was off or asleep.") { Timeline(r) }
    SectionCard("Deep-work sessions", "Stretches of 25 minutes or more without a distraction.") {
        val longest = r.deepSessions.maxOfOrNull { it.seconds }
        KV("Deep-work sessions (25+ min)", "${r.deepSessions.size}" + (longest?.let { " · longest ${fmtDuration(it)}" } ?: ""))
        r.deepSessions.forEach { Text("${fmtHm(it.start)}–${fmtHm(it.end)}  (${fmtDuration(it.seconds)})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp)) }
        KV("Distraction events (30 s+ wasted)", r.distractionEvents.toString())
        KV("Worst hour", r.worstHour?.let { "%02d:00 — %s wasted".format(Instant.ofEpochSecond(r.dayStart + it.first * 3600L).atZone(ZoneId.systemDefault()).hour, fmtDuration(it.second)) } ?: "None 🎉")
    }
    SectionCard("Where the time went", "Your top apps and websites today.") {
        if (r.topSubjects.isEmpty()) Text("No apps or sites recorded.")
        r.topSubjects.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, Modifier.weight(0.5f), maxLines = 1)
                Row(Modifier.weight(0.3f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(levelColor(s.category.level), 8); Text(s.category.label, style = MaterialTheme.typography.bodySmall)
                }
                Text(fmtDuration(s.seconds), Modifier.weight(0.2f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable private fun androidx.compose.foundation.layout.RowScope.HeaderCell(t: String) =
    Text(t, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable private fun androidx.compose.foundation.layout.RowScope.Cell(t: String) = Text(t, Modifier.weight(1f))


@Composable
private fun Timeline(r: DailyReport) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val (from, to) = timelineWindow(r.timeline.map { (it.start - r.dayStart) to (it.end - r.dayStart) })
    val span = (to - from).toFloat()
    Canvas(Modifier.fillMaxWidth().height(32.dp)) {
        drawRoundRect(track, size = size, cornerRadius = CornerRadius(6f))
        r.timeline.forEach { run ->
            val x0 = ((run.start - r.dayStart - from) / span * size.width).coerceIn(0f, size.width)
            val x1 = ((run.end - r.dayStart - from) / span * size.width).coerceIn(0f, size.width)
            drawRect(levelColor(run.level), Offset(x0, 0f), Size(maxOf(x1 - x0, 1.5f), size.height))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        timelineLabels(from, to).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Level.entries.forEach { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Dot(levelColor(it), 8); Text(it.label, style = MaterialTheme.typography.labelSmall) } }
    }
}

/** Seconds-after-midnight window to draw: whole hours around the data, at least 2 hours; the full day if empty. */
fun timelineWindow(runs: List<Pair<Long, Long>>): Pair<Long, Long> {
    if (runs.isEmpty()) return 0L to 86_400L
    var from = runs.minOf { it.first } / 3600 * 3600
    var to = ((runs.maxOf { it.second } + 3599) / 3600 * 3600).coerceAtMost(86_400)
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
