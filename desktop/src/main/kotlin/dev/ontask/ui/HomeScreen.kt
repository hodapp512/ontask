package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
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
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Everything about today in one place: blocking status, your plan, the report, and the evening reflection. Actions live in the header. */
@Composable
fun HomeScreen(app: App) {
    var tick by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(App.nowSec()) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); now = App.nowSec() } }
    val today = LocalDate.now()
    var date by remember { mutableStateOf(today) }
    var report by remember { mutableStateOf<DailyReport?>(null) }
    LaunchedEffect(date, tick, now / 60) {
        report = withContext(Dispatchers.IO) { app.tracker.flush(); app.reports.daily(date) }
    }

    var unblocking by remember { mutableStateOf(false) }
    var forWork by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val name = app.prefs.get("cloud.account_name")?.trim()?.split(Regex("\\s+"))?.firstOrNull()
    val hour = LocalTime.now().hour
    val hello = when { hour in 5..11 -> "Good morning"; hour in 12..17 -> "Good afternoon"; else -> "Good evening" }

    Page(
        if (name != null) "$hello, $name" else hello,
        today.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ unblocking = true }) { Text("Unblock a site…") }
            FocusButton(app, now) { tick++ }
            MoreActions(onWork = { forWork = true }, onAdd = { adding = true })
        }
        HelperBanner(app)
        StatusCard(app, now) { tick++ }
        TodayPlan(app, today, now)
        NowCard(app) { tick++ }
        DateBar(date, { date = it }) { tick++ }
        report?.let { ReportBody(it) } ?: Text("Loading…")
        WeeklyCard(app, today)
    }
    if (unblocking) UnblockPickerWindow(app, app.blocking.currentlyBlocked(App.nowSec())) { unblocking = false; tick++ }
    if (forWork) WorkUnblockWindow(app) { forWork = false; tick++ }
    if (adding) AddSitesWindow(app) { adding = false; tick++ }
}

@Composable
private fun FocusButton(app: App, now: Long, changed: () -> Unit) {
    val active = app.focus.active(now)
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) { Text(if (active != null) "Focus: ${fmtDuration(active.plannedEndTs - now)} left ▾" else "Focus session ▾") }
        DropdownMenu(open, { open = false }) {
            if (active != null) DropdownMenuItem({ Text("End focus session now") }, { open = false; app.focus.stop(App.nowSec()); app.blocking.tick(App.nowSec()); changed() })
            else listOf(25, 45, 60, 90, 120).forEach { m ->
                DropdownMenuItem({ Text("Block distractions for $m minutes") }, { open = false; app.focus.start(m, App.nowSec()); app.blocking.tick(App.nowSec()); changed() })
            }
        }
    }
}

@Composable
private fun MoreActions(onWork: () -> Unit, onAdd: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) { Text("More ▾") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Unblock a site for work…") }, { open = false; onWork() })
            DropdownMenuItem({ Text("Block another site…") }, { open = false; onAdd() })
        }
    }
}

/** One line on blocking, plus anything unblocked right now. */
@Composable
private fun StatusCard(app: App, now: Long, changed: () -> Unit) {
    val svc = app.blocking
    val strict = svc.strictNow(now)
    val blocked = remember(now / 30, strict) { svc.currentlyBlocked(now) }
    val active = remember(now) { svc.unblocks.active(now) }
    SectionCard(if (strict) "Distractions are blocked" else "Nothing is blocked right now") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(if (strict) levelColor(Level.DEEP) else levelColor(Level.AWAY))
            Text(
                when {
                    app.focus.active(now) != null -> "Focus session: ${blocked.size} sites blocked until ${fmtHm(app.focus.active(now)!!.plannedEndTs)}."
                    strict -> "Work hours: ${blocked.size} sites blocked."
                    else -> "Outside work hours, anything goes. Start a Focus session to block distractions now."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (svc.mode != BlockMode.LIVE) Notice("Blocking is in ${svc.mode.label.lowercase()}: ${svc.mode.explain} Change it in Settings → Block list.")
        svc.lastError?.let { Notice(it, error = true) }
        active.forEach { u ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${u.domain} is open" + (if (u.kind == UnblockKind.WORK) " for work (${u.purpose})" else "") + " · ${fmtDuration(u.expiresTs - now)} left", Modifier.weight(1f))
                TextButton({ svc.unblocks.end(u.id, now); svc.tick(now); changed() }) { Text("Block again") }
            }
        }
    }
}

/** Morning plan (compact once set) and the evening reflection, which opens at the end of the work day. */
@Composable
private fun TodayPlan(app: App, date: LocalDate, now: Long) {
    var editing by remember { mutableStateOf(false) }
    val planned = remember(editing) { app.checkins.done(date, "planning") }
    val priorities = remember(editing) { app.priorities.forDate(date) }
    if (!planned || editing) PrioritiesCard(app, date, short = false) { editing = false }
    else SectionCard("Today's priorities") {
        priorities.forEach { Text("• $it") }
        if (priorities.isEmpty()) Text("None set.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton({ editing = true }) { Text("Edit priorities") }
    }

    val end = app.settings.schedule.rangesFor(date).maxOfOrNull { it.endMin }
    val minuteNow = LocalTime.now().let { it.hour * 60 + it.minute }
    val evening = app.checkins.get(date, "evening")
    val due = evening != null || end == null || minuteNow >= end - 15
    var early by remember { mutableStateOf(false) }
    if (due || early) EveningCard(app, date) {}
    else SectionCard("Evening reflection", "Opens around ${LocalTime.of(end!! / 60 % 24, end % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}, at the end of your work day.") {
        TextButton({ early = true }) { Text("Do it now") }
    }
}

/** Settings → Block list: the sites, ad blocking, and the blocking helper. */
@Composable
fun BlockListScreen(app: App) {
    var tick by remember { mutableStateOf(0) }
    var mode by remember(tick) { mutableStateOf(app.blocking.mode) }
    Page("Block list", "Sites blocked during work hours and Focus sessions.") {
        BlocklistCard(app) { tick++ }
        BlockingSettings(app)
        SectionCard("How blocking works", "OnTask blocks sites by editing your Mac's hosts file through a small helper you install once.") {
            BlockingSetup(app, mode) { mode = it; tick++ }
        }
    }
}
