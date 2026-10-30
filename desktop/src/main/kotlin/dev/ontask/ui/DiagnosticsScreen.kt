package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ontask.App
import dev.ontask.Category
import dev.ontask.Level
import dev.ontask.Source
import dev.ontask.classify.DefaultRules
import dev.ontask.classify.RuleKind
import dev.ontask.report.DiagStats
import dev.ontask.report.LogFilter
import dev.ontask.report.LogRow
import dev.ontask.track.ProcMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Resources(val heapMb: Double, val jvmRecent: Double, val jvmAvg: Double, val self: ProcMetrics, val helper: ProcMetrics)

@Composable
fun DiagnosticsScreen(app: App) {
    var refresh by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(LogFilter()) }
    var stats by remember { mutableStateOf<DiagStats?>(null) }
    var log by remember { mutableStateOf<List<LogRow>>(emptyList()) }
    var res by remember { mutableStateOf<Resources?>(null) }
    var rules by remember { mutableStateOf(app.ruleStore.userRules()) }

    // Everything is computed when the screen opens / refreshes — nothing runs in the background for it.
    LaunchedEffect(refresh, filter) {
        withContext(Dispatchers.IO) {
            app.tracker.flush()
            stats = app.diagnostics.stats()
            log = app.diagnostics.log(filter)
            val (recent, avg) = app.metrics.jvmCpu()
            res = Resources(app.metrics.heapMb(), recent, avg, app.metrics.self(), app.helper.pid?.let { app.metrics.ps(it) } ?: ProcMetrics(null, null))
            rules = app.ruleStore.userRules()
        }
    }

    Page("Diagnostics", "How OnTask is doing: accuracy, resource use and the raw activity log. Computed when you open it.", actions = { Button({ refresh++ }) { Text("Refresh") } }) {
        res?.let { r ->
            SectionCard("Resources (budget: ~250 MB, <1% CPU)") {
                KV("App RSS", r.self.rssMb?.let { "%.0f MB".format(it) } ?: "?")
                KV("JVM heap in use", "%.0f MB".format(r.heapMb))
                KV("App CPU (since last refresh / avg since start)", "%.2f%% / %.2f%%".format(r.jvmRecent, r.jvmAvg))
                KV("Helper RSS / CPU", "${r.helper.rssMb?.let { "%.0f MB".format(it) } ?: "—"} / ${r.helper.cpuPct?.let { "%.1f%%".format(it) } ?: "—"}")
            }
        }
        SectionCard("Helper") {
            KV("Status", app.helper.status)
            KV("Restarts / failed samples", "${app.helper.restarts} / ${app.helper.failures}")
            KV("Accessibility permission", if (app.tracker.live.value.sample?.accessibilityTrusted == true) "granted" else "not granted (window titles unavailable)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                TextButton({ scope.launch { app.helper.request("request_accessibility") } }) { Text("Prompt for Accessibility…") }
            }
        }
        stats?.let { s -> StatsSection(s) }
        RulesSection(app, rules) { rules = app.ruleStore.userRules() }
        SectionCard("Activity log (newest first)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(filter.text, { filter = filter.copy(text = it) }, label = { Text("app / domain / title") }, singleLine = true, modifier = Modifier.weight(1f))
                Picker("Level: ${filter.level?.label ?: "all"}", listOf<Level?>(null) + Level.entries, { it?.label ?: "all" }) { filter = filter.copy(level = it) }
                Picker("Source: ${filter.source?.label ?: "all"}", listOf<Source?>(null) + Source.entries, { it?.label ?: "all" }) { filter = filter.copy(source = it) }
            }
            log.forEach { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(fmtTime(r.ts), Modifier.weight(0.09f), style = MaterialTheme.typography.bodySmall)
                    Text(fmtDuration(r.seconds.toLong()), Modifier.weight(0.06f), style = MaterialTheme.typography.bodySmall)
                    Text(listOfNotNull(r.app, r.domain).joinToString(" · ").ifEmpty { "—" } + (r.title?.let { " — ${it.take(60)}" } ?: ""), Modifier.weight(0.45f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Text(r.category.label, Modifier.weight(0.14f), style = MaterialTheme.typography.bodySmall, color = levelColor(r.level))
                    Text(r.source.label + if (r.workTime) "" else " (off-hours)", Modifier.weight(0.2f), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (log.isEmpty()) Text("No matching activity.")
        }
    }
}



@Composable
private fun ColumnScope.StatsSection(s: DiagStats) {
    SectionCard("Classification") {
        Source.entries.forEach { src ->
            val n = s.sourceTicks[src] ?: 0
            KV(src.label, if (s.totalTicks > 0) "%.1f%%  (%d ticks)".format(n * 100.0 / s.totalTicks, n) else "—")
        }
        KV("Classifier vs. your labels", if (s.labels > 0) "%d of %d labels disagreed (%.0f%%)".format(s.disagreements, s.labels, s.disagreements * 100.0 / s.labels) else "no labels yet")
    }
    SectionCard("Ticks") {
        KV("Total ticks", s.totalTicks.toString())
        KV("Skipped — away/idle", s.skippedIdle.toString())
        KV("Skipped — unchanged (reused classification)", s.skippedUnchanged.toString())
        KV("Avg processing per tick", "%.1f ms".format(s.avgProcMs))
        KV("Raw rows / hourly rollup rows", "${s.rawRows} / ${s.hourlyRows}")
        KV("Oldest raw tick", s.oldestRaw?.let { java.time.Instant.ofEpochSecond(it).toString() } ?: "—")
        KV("Database size", s.dbBytes?.let { "%.1f MB".format(it / 1048576.0) } ?: "in memory")
    }
}

@Composable
private fun RulesSection(app: App, userRules: List<dev.ontask.classify.Rule>, onChange: () -> Unit) {
    var kind by remember { mutableStateOf(RuleKind.DOMAIN) }
    var pattern by remember { mutableStateOf("") }
    SectionCard("Rules (yours override built-in)") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Picker(kind.name.lowercase(), RuleKind.entries, { it.name.lowercase() }) { kind = it }
            OutlinedTextField(pattern, { pattern = it }, label = { Text("pattern") }, singleLine = true, modifier = Modifier.weight(1f))
            Picker("Add as", Category.assignable, { it.label }) { cat ->
                if (pattern.isNotBlank()) { app.ruleStore.label(kind, pattern, cat, Category.UNKNOWN, App.nowSec()); app.reloadRules(); pattern = ""; onChange() }
            }
        }
        userRules.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${r.kind.name.lowercase()}: ${r.pattern} → ${r.category.label}", Modifier.weight(1f))
                TextButton({ app.ruleStore.delete(r.id); app.reloadRules(); onChange() }) { Text("Delete") }
            }
        }
        if (userRules.isEmpty()) Text("No user rules yet. Label the current activity on the Report tab.", style = MaterialTheme.typography.bodySmall)
        Text("${DefaultRules.all.size} built-in rules (see DefaultRules.kt).", style = MaterialTheme.typography.bodySmall)
    }
}
