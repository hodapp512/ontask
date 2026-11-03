package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.ontask.ai.AiAccuracy
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
import androidx.compose.runtime.collectAsState
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
    var accuracy by remember { mutableStateOf(app.ai.accuracy.weekly(App.nowSec())) }

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
                accuracy = app.ai.accuracy.weekly(App.nowSec())
            }
            delay(10_000)
        }
    }

    Page("Diagnostics", "A look under the hood: is OnTask working, how accurate is it, and how much of your Mac does it use. Updates every 10 seconds while open.") {
        val tick = app.settings.tickSec
        res?.let { r ->
            val problems = app.problems()
            SectionCard("Is everything running?", if (problems.isEmpty()) "Everything is working." else "🔴 marks what isn't working.") {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val reader = app.helper.status
                Status("Activity reader", reader == "running", if (reader == "running") "Running ✓" else reader)
                Hint("OnTask's small companion program (ontask-helper): it tells OnTask which app and window are in front, whether you're at the keyboard, and, if you allow it, the text on screen.")
                val blockingHelper by app.helperHealth.collectAsState()
                Status("Blocking helper", !app.helperNeeded(), when { app.blocking.mode == dev.ontask.block.BlockMode.DRY_RUN -> "Test mode (not used)"; blockingHelper == dev.ontask.block.HelperHealth.OK -> "Running ✓"; else -> blockingHelper?.problem?.replaceFirstChar { it.uppercase() } ?: "Checking…" })
                Hint("A separate service that edits your hosts file to block sites.")
                if (app.helper.restarts > 0 || app.helper.failures > 0) KV("Reader hiccups", "restarted ${app.helper.restarts}×, ${app.helper.failures} missed checks")
                val ax = app.tracker.live.value.sample?.accessibilityTrusted != false
                Status("Can see window titles", ax, if (ax) "Yes ✓" else "No")
                if (!ax) {
                    Hint("Needs Accessibility for OnTask in System Settings → Privacy & Security. If OnTask is already switched on there, macOS is matching an older copy of OnTask: Reset and ask again removes that entry, then switch OnTask on once more.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ scope.launch { app.permissions.requestAccessibility() } }) { Text("Allow…") }
                        OutlinedButton({ scope.launch { app.permissions.resetAccessibility() } }) { Text("Reset and ask again") }
                    }
                }
                if (app.ai.enabled) {
                    val aiProblem = app.ai.lastProblem ?: app.aiError
                    Status("AI labeling", aiProblem == null, aiProblem ?: "Working ✓")
                }
            }
            SectionCard("How much of your Mac it uses", "Target: about 250 MB of memory and under 1% CPU.") {
                KV("Memory", "OnTask " + (r.self.rssMb?.let { "%.0f MB".format(it) } ?: "?") + " · activity reader " + (r.helper.rssMb?.let { "%.0f MB".format(it) } ?: "—"))
                Hint("Memory each program is holding right now. The activity reader is the companion program above.")
                KV("CPU right now", "%.1f%%".format(r.jvmRecent + (r.helper.cpuPct ?: 0.0)))
                Hint("OnTask and the activity reader together, over the last 10 seconds, as a share of one processor core.")
                KV("CPU on average", "%.1f%%".format(r.jvmAvg))
                Hint("OnTask itself, averaged since it started.")
            }
        }
        stats?.let { s -> StatsSection(s, tick) }
        AccuracySection(accuracy)
        SectionCard("AI use (last 24 hours)", "Only when nothing else could label what you were doing. Kev is free; Jev's costs are rough estimates.") {
            if (aiStats.isEmpty()) Text("No AI calls yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            aiStats.forEach { a ->
                KV("${a.provider.lowercase().replaceFirstChar { it.uppercase() }} · ${a.model}",
                    "${a.calls} calls" + (if (a.calls > a.failed) ", %.1f s each".format(a.avgMs / 1000) else "") + (if (a.failed > 0) ", ${a.failed} failed" else "") + (if (a.costUsd > 0) ", ~$${"%.4f".format(a.costUsd)}" else ""))
                if (a.failed > 0) Hint("Latest failure: " + (a.lastError ?: "reason not recorded (before this update) — most likely the model wasn't downloaded yet"))
            }
        }
        var showRules by remember { mutableStateOf(false) }
        var showLog by remember { mutableStateOf(false) }
        SectionCard(
            "Labeling rules",
            "A rule tells OnTask what something is without guessing or asking AI, e.g. \"Website github.com is Core work\". " +
                "Rules are added for you whenever you answer \"Yes, it's work\" or correct a label, and yours win over the built-in list.",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${rules.size} of your own · ${DefaultRules.all.size} built-in", Modifier.weight(1f))
                OutlinedButton({ showRules = true }) { Text("See and edit rules…") }
            }
        }
        SectionCard("Activity log", "Every check, newest first: what was in front of you, how it was labeled, and what decided the label. Stays on this Mac.") {
            OutlinedButton({ showLog = true }) { Text("Open activity log…") }
        }
        if (showRules) BigDialog("Labeling rules", { showRules = false }) { RulesPanel(app, rules) { rules = app.ruleStore.userRules() } }
        if (showLog) BigDialog("Activity log", { showLog = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(filter.text, { filter = filter.copy(text = it) }, label = { Text("Search app, website or title") }, singleLine = true, modifier = Modifier.weight(1f))
                Picker("Level: ${filter.level?.label ?: "all"}", listOf<Level?>(null) + Level.entries, { it?.label ?: "all" }) { filter = filter.copy(level = it) }
                Picker("Decided by: ${filter.source?.label ?: "all"}", listOf<Source?>(null) + Source.entries, { it?.label ?: "all" }) { filter = filter.copy(source = it) }
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(log) { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(fmtTime(r.ts), Modifier.weight(0.09f), style = MaterialTheme.typography.bodySmall)
                        Text(fmtDuration(r.seconds.toLong()), Modifier.weight(0.06f), style = MaterialTheme.typography.bodySmall)
                        Text(listOfNotNull(r.app, r.domain).joinToString(" · ").ifEmpty { "—" } + (r.title?.let { " — ${it.take(60)}" } ?: ""), Modifier.weight(0.45f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Box(Modifier.weight(0.14f)) { (r.domain ?: r.app)?.let { n -> CategoryPicker(app, dev.ontask.track.displayAppName(n), r.category, { relabeled++ }, item = if (r.category.variable) dev.ontask.classify.itemKey(r.app, r.title, r.url) to r.title else null) } ?: Text(r.category.label, style = MaterialTheme.typography.bodySmall) }
                        Text(r.source.label + if (r.workTime) "" else " (off-hours)", Modifier.weight(0.2f), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (log.isEmpty()) item { Text("No matching activity.") }
            }
        }
    }
}

/** One status line; anything not working is red with a 🔴. */
@Composable
private fun Status(k: String, ok: Boolean, v: String) {
    if (ok) KV(k, v)
    else Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("🔴 $k", Modifier.weight(0.45f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Text(v, Modifier.weight(0.55f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/** A large window over the app for a long, searchable list (like a browser's cookie list). */
@Composable
private fun BigDialog(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val size = DpSize(960.dp, 720.dp)
    DialogWindow(onCloseRequest = onClose, state = rememberDialogState(size = size), title = title) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClose) { Text("Done") }
                }
                content()
            }
        }
    }
}

@Composable
private fun AccuracySection(weeks: List<AiAccuracy.Week>) {
    SectionCard(
        "How often the AI was right",
        "Each time you answer a question about something Kev or Jev labeled, or correct a label they chose, OnTask checks whether they had picked the same category. Labels you never touched aren't counted.",
    ) {
        if (weeks.isEmpty()) { Hint("Not measured yet. It starts counting the next time you answer a question or correct an AI label."); return@SectionCard }
        val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d")
        weeks.groupBy { it.weekStart }.forEach { (start, ws) ->
            val label = java.time.Instant.ofEpochSecond(start).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(fmt)
            KV("Week of $label", ws.joinToString(" · ") { w -> "${w.provider.name.lowercase().replaceFirstChar { it.uppercase() }} %.0f%% (%d of %d)".format(w.matched * 100.0 / w.checked, w.matched, w.checked) })
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
    }
    SectionCard("Checks", "One check = one look at what's in front of you. Checks are skipped when you're away or nothing changed, to save battery.") {
        KV("Checks so far", "%,d".format(s.totalTicks))
        KV("Skipped: you were away", "%,d".format(s.skippedIdle))
        KV("Skipped: screen hadn't changed", "%,d".format(s.skippedUnchanged))
        KV("Time per check", "%.1f ms".format(s.avgProcMs))
        KV("Detailed history since", s.oldestRaw?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let(::fmtDate) } ?: "—")
        KV("Storage used", s.dbBytes?.let { "%.1f MB".format(it / 1048576.0) } ?: "in memory")
    }
}

private fun kindLabel(k: RuleKind) = when (k) { RuleKind.DOMAIN -> "Website"; RuleKind.APP -> "App"; RuleKind.TITLE -> "Window title contains" }

@Composable
private fun ColumnScope.RulesPanel(app: App, userRules: List<dev.ontask.classify.Rule>, onChange: () -> Unit) {
    var kind by remember { mutableStateOf(RuleKind.DOMAIN) }
    var pattern by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    Text("Add a rule", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Picker(kindLabel(kind), RuleKind.entries, ::kindLabel) { kind = it }
        OutlinedTextField(pattern, { pattern = it }, label = { Text(when (kind) { RuleKind.DOMAIN -> "e.g. github.com"; RuleKind.APP -> "e.g. Xcode"; RuleKind.TITLE -> "e.g. Jira" }) }, singleLine = true, modifier = Modifier.weight(1f))
        Picker("is…", Category.assignable, { it.label }) { cat ->
            if (pattern.isNotBlank()) { app.ruleStore.label(kind, pattern, cat, Category.UNKNOWN, App.nowSec()); app.reloadRules(); pattern = ""; onChange() }
        }
    }
    HorizontalDivider()
    OutlinedTextField(search, { search = it }, label = { Text("Search rules") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val q = search.trim()
    fun matches(r: dev.ontask.classify.Rule) = q.isEmpty() || r.pattern.contains(q, true) || r.category.label.contains(q, true)
    val mine = userRules.filter(::matches)
    // Built-in rules you've overridden aren't shown twice.
    val own = userRules.map { it.kind to it.pattern.lowercase() }.toSet()
    val builtIn = DefaultRules.all.filter { (it.kind to it.pattern.lowercase()) !in own && matches(it) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Yours (${mine.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp)) }
        items(mine, key = { "u" + it.id }) { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${kindLabel(r.kind)} ${r.pattern}  →  ${r.category.label}", Modifier.weight(1f))
                TextButton({ app.ruleStore.delete(r.id); app.reloadRules(); onChange() }) { Text("Delete") }
            }
        }
        if (mine.isEmpty()) item { Hint(if (q.isEmpty()) "No rules of your own yet." else "None match.") }
        item { Text("Built in (${builtIn.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) }
        item { Hint("Common sites and apps. Add a rule of your own above to file one differently.") }
        items(builtIn, key = { "b" + it.id }) { r -> Text("${kindLabel(r.kind)} ${r.pattern}  →  ${r.category.label}", Modifier.padding(vertical = 2.dp)) }
    }
}
