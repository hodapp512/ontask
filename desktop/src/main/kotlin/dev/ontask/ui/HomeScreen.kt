package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.collectAsState
import dev.ontask.Category
import dev.ontask.classify.RuleKind
import dev.ontask.classify.domainOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dev.ontask.Level
import dev.ontask.block.BlockMode
import dev.ontask.block.UnblockKind
import dev.ontask.plan.Prompt
import dev.ontask.report.DailyReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Everything about today on one screen: the summary (as on the phone), what's happening right now, and today's plan.
 * Two columns when the window is wide, one when narrow. Less-used things sit behind buttons and expanders.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(app: App) {
    var tick by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(App.nowSec()) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); now = App.nowSec() } }
    val today = LocalDate.now()
    var date by remember { mutableStateOf(today) }
    // Always redraw on reload: categories compare by name, so a renamed one would otherwise look unchanged.
    var report by remember { mutableStateOf<DailyReport?>(null, androidx.compose.runtime.neverEqualPolicy()) }
    LaunchedEffect(date, tick, now / 60) {
        report = withContext(Dispatchers.IO) { app.tracker.flush(); app.reports.daily(date) }
    }

    var unblocking by remember { mutableStateOf(false) }
    var reflecting by remember { mutableStateOf(false) }
    val name = app.prefs.get("cloud.account_name")?.trim()?.split(Regex("\\s+"))?.firstOrNull()
    val hour = LocalTime.now().hour
    val hello = when { hour in 5..11 -> "Good morning"; hour in 12..17 -> "Good afternoon"; else -> "Good evening" }

    Page(
        if (name != null) "$hello, $name" else hello,
        today.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
        maxWidth = 1500.dp,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ unblocking = true }) { Text("Unblock…") }
            FocusButton(app, now) { tick++ }
            AdsButton(app, tick) { tick++ }
        }
        BlockingHelperBanner(app, now)
        HelperBanner(app)
        TodayCard(app, today) { reflecting = true }
        val summary: @Composable () -> Unit = {
            report?.let { r ->
                SummaryCard(r, if (date == today) "Today so far" else "Your day", app, onRelabel = { tick++ }, header = { DateBar(date, { date = it }) { tick++ } }) {
                    WeekExpander(app, today)
                }
            } ?: Text("Loading…")
        }
        summary()
    }
    if (unblocking) UnblockPickerWindow(app, app.blocking.currentlyBlocked(App.nowSec())) { unblocking = false; tick++ }
    if (reflecting) ReflectionWindow(app) { reflecting = false; tick++ }
}

/** One button that turns ad blocking off and back on (the same as the menu bar's Ad blocking switch). */
@Composable
private fun AdsButton(app: App, version: Int, changed: () -> Unit) {
    val on = remember(version) { app.adBlock.enabled }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val toggle = { app.adBlock.enabled = !on; scope.launch(Dispatchers.IO) { app.blocking.tick(App.nowSec()) }; changed() }
    if (on) OutlinedButton(toggle) { Text("Disable ad blocking") }
    else Button(toggle, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) { Text("Enable ad blocking") }
}

@Composable
private fun FocusButton(app: App, now: Long, changed: () -> Unit) {
    val active = app.focus.active(now)
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) { Text(if (active != null) "Focus: ${fmtDuration(active.plannedEndTs - now)} left ▾" else "Focus ▾") }
        DropdownMenu(open, { open = false }) {
            if (active != null) DropdownMenuItem({ Text("End focus session now") }, { open = false; app.focus.stop(App.nowSec()); app.blocking.tick(App.nowSec()); changed() })
            else listOf(20, 30, 45, 60, 90).forEach { m ->
                DropdownMenuItem({ Text("Block distractions for $m minutes") }, { open = false; app.focus.start(m, App.nowSec()); app.blocking.tick(App.nowSec()); changed() })
            }
        }
    }
}

/** Today's plan (a free-form note, editable inline in the morning, shown as text after) and the evening reflection, which opens in its own window. */
@Composable
private fun TodayCard(app: App, date: LocalDate, onReflect: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val planned = remember(editing) { app.checkins.done(date, "planning") }
    if (!planned || editing) { PrioritiesCard(app, date, short = false, compact = true) { editing = false }; return }
    val priorities = remember(editing) { app.priorities.forDate(date) }
    val end = app.settings.schedule.rangesFor(date).maxOfOrNull { it.endMin }
    val evening = app.checkins.get(date, "evening")
    PlainCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Today's plan", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton({ editing = true }) { Text("Edit") }
        }
        if (priorities.isEmpty()) Text("Nothing planned yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) else Text(priorities.joinToString("\n"))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    evening?.sent == true -> "Evening review sent ✓"
                    end != null -> "Evening reflection · around ${LocalTime.of(end / 60 % 24, end % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}"
                    else -> "Evening reflection"
                },
                Modifier.weight(1f),
            )
            if (evening?.sent != true) OutlinedButton(onReflect) { Text("Reflect now") }
        }
    }
}

/** "This week: how you felt vs. what was measured", folded away. */
@Composable
private fun WeekExpander(app: App, today: LocalDate) {
    val ratings = remember { app.checkins.ratings(today, 7) }
    if (ratings.isEmpty()) return
    Expander("This week: how it felt vs. what was measured") {
        ratings.forEach { (d, r) ->
            val share = dev.ontask.plan.CheckinDraft.deepShare(app.reports.daily(d))
            Row(Modifier.fillMaxWidth()) {
                Text(d.format(DateTimeFormatter.ofPattern("EEE, MMM d")), Modifier.weight(1f))
                Text(dev.ontask.plan.CheckinDraft.feeling(r), Modifier.weight(1f))
                Text(share?.let { "$it% focused" } ?: "—", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ReflectionWindow(app: App, onClose: () -> Unit) {
    androidx.compose.ui.window.Window(
        onCloseRequest = onClose, title = "Evening reflection",
        state = androidx.compose.ui.window.rememberWindowState(size = androidx.compose.ui.unit.DpSize(760.dp, 820.dp), position = androidx.compose.ui.window.WindowPosition.Aligned(Alignment.Center)),
    ) {
        OnTaskTheme(remember { themeOf(app) }) { PlanPromptContent(app, Prompt.EVENING, onDone = onClose, onLater = onClose) }
    }
}

/** Settings → Block list: the sites and how unblocking works. */
@Composable
fun BlockListScreen(app: App) {
    var tick by remember { mutableStateOf(0) }
    Page("Block list", "Sites blocked around the clock. In work hours and Focus sessions, unblocking takes more effort.") {
        BlocklistCard(app) { tick++ }
        BlockingSettings(app)
    }
}
