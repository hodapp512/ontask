package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Resources(val heapMb: Double, val jvmRecent: Double, val jvmAvg: Double, val self: ProcMetrics, val helper: ProcMetrics)

@Composable
fun DiagnosticsScreen(app: App) {
    var filter by remember { mutableStateOf(LogFilter()) }
    var stats by remember { mutableStateOf<DiagStats?>(null) }
    var log by remember { mutableStateOf<List<LogRow>>(emptyList()) }
    var res by remember { mutableStateOf<Resources?>(null) }
    var rules by remember { mutableStateOf(app.ruleStore.userRules()) }
    var aiStats by remember { mutableStateOf(app.ai.stats(App.nowSec() - 86400)) }

    // Recomputed every 10 seconds, only while this screen is open.
    var relabeled by remember { mutableStateOf(0) }
    LaunchedEffect(filter, relabeled) {
        while (true) {
            withContext(Dispatchers.IO) {
                app.tracker.flush()
                stats = app.diagnostics.stats()
                log = app.diagnostics.log(filter)
                val (recent, avg) = app.metrics.jvmCpu()
                res = Resources(app.metrics.heapMb(), recent, avg, app.metrics.self(), app.helper.pid?.let { app.metrics.ps(it) } ?: ProcMetrics(null, null))
                rules = app.ruleStore.userRules()
                aiStats = app.ai.stats(App.nowSec() - 86400)
            }
            delay(10_000)
        }
    }

    Page("Diagnostics", "A look under the hood: is OnTask working, how accurate is it, and how much of your Mac does it use. Updates every 10 seconds while open.") {
        val tick = app.settings.tickSec
        res?.let { r ->
            SectionCard("Is everything running?") {
                val reader = app.helper.status
                KV("Activity reader", if (reader == "running") "Running ✓" else reader)
                Hint("A small companion program (ontask-helper) that tells OnTask which app and window are in front, whether you're at the keyboard, and — if you allow it — the text on screen. It's separate from the blocking service, which edits your hosts file.")
                if (app.helper.restarts > 0 || app.helper.failures > 0) KV("Reader hiccups", "restarted ${app.helper.restarts}×, ${app.helper.failures} missed checks")
                val ax = app.tracker.live.value.sample?.accessibilityTrusted == true
                if (ax) KV("Can see window titles", "Yes ✓")
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Can see window titles: no", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    Button({ scope.launch { app.permissions.requestAccessibility() } }) { Text("Allow…") }
                }
            }
            SectionCard("How much of your Mac it uses", "Target: about 250 MB of memory and under 1% CPU.") {
                KV("Memory (OnTask)", r.self.rssMb?.let { "%.0f MB".format(it) } ?: "?")
                KV("Memory (activity reader)", r.helper.rssMb?.let { "%.0f MB".format(it) } ?: "—")
                KV("CPU (OnTask, last 10 s / average)", "%.2f%% / %.2f%%".format(r.jvmRecent, r.jvmAvg))
                KV("CPU (activity reader)", r.helper.cpuPct?.let { "%.1f%%".format(it) } ?: "—")
            }
        }
        stats?.let { s -> StatsSection(s, tick) }
        SectionCard("AI use (last 24 hours)", "Only when nothing else could label what you were doing. Local models are free; cloud costs are rough estimates.") {
            if (aiStats.isEmpty()) Text("No AI calls yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            aiStats.forEach { a ->
                KV("${a.provider.lowercase().replaceFirstChar { it.uppercase() }} · ${a.model} (${if (a.tier.equals("vision", true)) "images" else "text"})",
                    "${a.calls} calls" + (if (a.calls > a.failed) ", %.1f s each".format(a.avgMs / 1000) else "") + (if (a.failed > 0) ", ${a.failed} failed" else "") + (if (a.costUsd > 0) ", ~$${"%.4f".format(a.costUsd)}" else ""))
                if (a.failed > 0) Hint("Latest failure: " + (a.lastError ?: "reason not recorded (before this update) — most likely the model wasn't downloaded yet"))
            }
            (app.ai.lastProblem ?: app.aiError)?.let { Notice("AI isn't running right now: $it", error = true) }
        }
        RulesSection(app, rules) { rules = app.ruleStore.userRules() }
        SectionCard("Activity log", "Every check, newest first: what was in front of you, how it was labeled, and what decided the label. Stays on this Mac.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(filter.text, { filter = filter.copy(text = it) }, label = { Text("Search app, website or title") }, singleLine = true, modifier = Modifier.weight(1f))
                Picker("Level: ${filter.level?.label ?: "all"}", listOf<Level?>(null) + Level.entries, { it?.label ?: "all" }) { filter = filter.copy(level = it) }
                Picker("Decided by: ${filter.source?.label ?: "all"}", listOf<Source?>(null) + Source.entries, { it?.label ?: "all" }) { filter = filter.copy(source = it) }
            }
            log.forEach { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(fmtTime(r.ts), Modifier.weight(0.09f), style = MaterialTheme.typography.bodySmall)
                    Text(fmtDuration(r.seconds.toLong()), Modifier.weight(0.06f), style = MaterialTheme.typography.bodySmall)
                    Text(listOfNotNull(r.app, r.domain).joinToString(" · ").ifEmpty { "—" } + (r.title?.let { " — ${it.take(60)}" } ?: ""), Modifier.weight(0.45f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Box(Modifier.weight(0.14f)) { (r.domain ?: r.app)?.let { n -> CategoryPicker(app, dev.ontask.track.displayAppName(n), r.category, { relabeled++ }, item = Triple(dev.ontask.classify.itemKey(r.app, r.title, r.url), r.title, r.level)) } ?: Text(r.category.label, style = MaterialTheme.typography.bodySmall) }
                    Text(r.source.label + if (r.workTime) "" else " (off-hours)", Modifier.weight(0.2f), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (log.isEmpty()) Text("No matching activity.")
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun sourceHelp(s: Source) = when (s) {
    Source.RULE -> "OnTask's built-in list, e.g. netflix.com is entertainment"
    Source.USER_LABEL -> "A rule you made (see below)"
    Source.METADATA -> "Guessed from the app name and window title"
    Source.OCR -> "AI read the text on screen"
    Source.VISION -> "AI looked at a screenshot"
    Source.AI_PAGE -> "AI decided whether a page or video on a Varies site was work"
    Source.AWAY -> "You weren't at the computer"
}

@Composable
private fun ColumnScope.StatsSection(s: DiagStats, tickSec: Int) {
    SectionCard("What decided each label", "Every $tickSec seconds OnTask checks what you're doing and labels it focused, mixed or wasted. This is what made that call, cheapest first.") {
        Source.entries.forEach { src ->
            val n = s.sourceTicks[src] ?: 0
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(src.label, style = MaterialTheme.typography.bodyMedium); Hint(sourceHelp(src)) }
                Text(if (s.totalTicks > 0) "%.0f%%".format(n * 100.0 / s.totalTicks) else "—", style = MaterialTheme.typography.titleSmall)
            }
        }
        HorizontalDivider()
        KV("Accuracy", if (s.labels > 0) "When you corrected a label, OnTask had it wrong %d of %d times (%.0f%%)".format(s.disagreements, s.labels, s.disagreements * 100.0 / s.labels) else "Not measured yet — it learns from your corrections")
    }
    SectionCard("Checks", "One check = one look at what's in front of you. Checks are skipped when you're away or nothing changed, to save battery.") {
        KV("Checks so far", "%,d".format(s.totalTicks))
        KV("Skipped: you were away", "%,d".format(s.skippedIdle))
        KV("Skipped: screen hadn't changed", "%,d".format(s.skippedUnchanged))
        KV("Time per check", "%.1f ms".format(s.avgProcMs))
        KV("Detailed history since", s.oldestRaw?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() } ?: "—")
        KV("Storage used", s.dbBytes?.let { "%.1f MB".format(it / 1048576.0) } ?: "in memory")
    }
}

private fun kindLabel(k: RuleKind) = when (k) { RuleKind.DOMAIN -> "Website"; RuleKind.APP -> "App"; RuleKind.TITLE -> "Window title contains" }

@Composable
private fun RulesSection(app: App, userRules: List<dev.ontask.classify.Rule>, onChange: () -> Unit) {
    var kind by remember { mutableStateOf(RuleKind.DOMAIN) }
    var pattern by remember { mutableStateOf("") }
    SectionCard(
        "Your labeling rules",
        "A rule tells OnTask what something is without guessing or asking AI — e.g. \"Website github.com is Core work\". " +
            "Rules are added for you whenever you answer \"Yes, it's work\" or correct a label, and yours win over the built-in list.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Picker(kindLabel(kind), RuleKind.entries, ::kindLabel) { kind = it }
            OutlinedTextField(pattern, { pattern = it }, label = { Text(when (kind) { RuleKind.DOMAIN -> "e.g. github.com"; RuleKind.APP -> "e.g. Xcode"; RuleKind.TITLE -> "e.g. Jira" }) }, singleLine = true, modifier = Modifier.weight(1f))
            Picker("is…", Category.assignable, { it.label }) { cat ->
                if (pattern.isNotBlank()) { app.ruleStore.label(kind, pattern, cat, Category.UNKNOWN, App.nowSec()); app.reloadRules(); pattern = ""; onChange() }
            }
        }
        userRules.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${kindLabel(r.kind)} ${r.pattern}  →  ${r.category.label}", Modifier.weight(1f))
                TextButton({ app.ruleStore.delete(r.id); app.reloadRules(); onChange() }) { Text("Delete") }
            }
        }
        if (userRules.isEmpty()) Hint("No rules of your own yet.")
        Hint("Plus ${DefaultRules.all.size} built-in rules for common sites and apps.")
    }
}
