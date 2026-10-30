package dev.ontask.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.time.LocalDate

@Composable
fun ReportScreen(app: App) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    var refresh by remember { mutableStateOf(0) }
    var report by remember { mutableStateOf<DailyReport?>(null) }
    // Computed on demand: when the screen opens, the date changes, or the user hits refresh.
    LaunchedEffect(date, refresh) {
        withContext(Dispatchers.IO) { app.tracker.flush() }
        report = withContext(Dispatchers.IO) { app.reports.daily(date) }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HelperBanner(app)
        NowCard(app) { refresh++ }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ date = date.minusDays(1) }) { Text("◀") }
            Text(date.toString(), style = MaterialTheme.typography.titleLarge)
            TextButton({ date = date.plusDays(1) }, enabled = date < LocalDate.now()) { Text("▶") }
            TextButton({ date = LocalDate.now() }) { Text("Today") }
            TextButton({ refresh++ }) { Text("Refresh") }
        }
        report?.let { ReportBody(it) } ?: Text("Loading…")
    }
}

@Composable
private fun HelperBanner(app: App) {
    val live by app.tracker.live.collectAsState()
    val status = app.helper.status
    if (status != "running" || (live.sample != null && !live.sample!!.accessibilityTrusted)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                if (status != "running") Text("Helper: $status", color = MaterialTheme.colorScheme.error)
                if (live.sample?.accessibilityTrusted == false)
                    Text("Window titles need Accessibility permission (System Settings → Privacy & Security). See Diagnostics.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun NowCard(app: App, onLabeled: () -> Unit) {
    val live by app.tracker.live.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Right now", style = MaterialTheme.typography.titleSmall)
            val s = live.sample; val c = live.classification
            if (live.away) Text("Away")
            else if (s == null || c == null) Text("Waiting for the first tick…")
            else {
                val domain = domainOf(s.url)
                Text(listOfNotNull(s.app, domain, s.title?.take(80)).joinToString("  ·  "))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${c.category.label} → ${c.level.label} (${c.source.label})", color = levelColor(c.level))
                    val kind = if (domain != null) RuleKind.DOMAIN else RuleKind.APP
                    val pattern = domain ?: s.app
                    if (pattern != null) Picker("Label ${kind.name.lowercase()} $pattern as", Category.assignable, { it.label }) { cat ->
                        app.ruleStore.label(kind, pattern, cat, c.category, App.nowSec())
                        app.reloadRules(); onLabeled()
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportBody(r: DailyReport) {
    Section("Totals") {
        Row(Modifier.fillMaxWidth()) {
            Text("", Modifier.weight(1f)); Text("All day", Modifier.weight(1f)); Text("Work hours", Modifier.weight(1f))
        }
        Level.entries.forEach { l ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(levelColor(l))); Text("  ${l.label}")
                }
                Text(fmtDuration(r.totalsAll[l] ?: 0), Modifier.weight(1f))
                Text(fmtDuration(r.totalsWork[l] ?: 0), Modifier.weight(1f))
            }
        }
        val denom = Level.entries.filter { it != Level.AWAY }.sumOf { r.totalsAll[it] ?: 0 }
        if (denom > 0) Text("Deep share (excluding away): ${(r.totalsAll[Level.DEEP] ?: 0) * 100 / denom}%", style = MaterialTheme.typography.bodySmall)
    }
    Section("Timeline") { Timeline(r) }
    HorizontalDivider()
    Section("Focus") {
        val longest = r.deepSessions.maxOfOrNull { it.seconds }
        KV("Deep-work sessions (25+ min)", "${r.deepSessions.size}" + (longest?.let { ", longest ${fmtDuration(it)}" } ?: ""))
        r.deepSessions.forEach { Text("  ${fmtTime(it.start).take(5)}–${fmtTime(it.end).take(5)}  (${fmtDuration(it.seconds)})", style = MaterialTheme.typography.bodySmall) }
        KV("Distraction events (30s+ wasted)", r.distractionEvents.toString())
        KV("Worst hour", r.worstHour?.let { "%02d:00 — %s wasted".format(java.time.Instant.ofEpochSecond(r.dayStart + it.first * 3600L).atZone(java.time.ZoneId.systemDefault()).hour, fmtDuration(it.second)) } ?: "—")
    }
    Section("Where the time went") {
        r.topSubjects.forEach { s ->
            Row(Modifier.fillMaxWidth()) {
                Text(s.name, Modifier.weight(0.5f)); Text(s.category.label, Modifier.weight(0.3f), color = levelColor(s.category.level)); Text(fmtDuration(s.seconds), Modifier.weight(0.2f))
            }
        }
        if (r.topSubjects.isEmpty()) Text("No data for this day.")
    }
}

@Composable
private fun Timeline(r: DailyReport) {
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        drawRect(Color(0x22888888), size = size)
        r.timeline.forEach { run ->
            val x0 = ((run.start - r.dayStart) / 86400f * size.width).coerceIn(0f, size.width)
            val x1 = ((run.end - r.dayStart) / 86400f * size.width).coerceIn(0f, size.width)
            drawRect(levelColor(run.level), Offset(x0, 0f), Size(maxOf(x1 - x0, 1f), size.height))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("0", "6", "12", "18", "24").forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}
