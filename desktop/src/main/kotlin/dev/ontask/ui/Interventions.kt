package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.ontask.App
import dev.ontask.intervene.Breaks
import dev.ontask.intervene.Intervention
import dev.ontask.intervene.Step
import dev.ontask.plan.Prompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Hosts the intervention windows, daily-rhythm prompts and the battery question. Composed only while something is showing. */
@Composable
fun AppOverlays(app: App) {
    val iv by app.intervention.collectAsState()
    val prompt by app.duePrompt.collectAsState()
    val battery by app.batteryPrompt.collectAsState()
    val mode = remember { runCatching { ThemeMode.valueOf(app.prefs.str("theme", "SYSTEM")) }.getOrDefault(ThemeMode.SYSTEM) }

    val variable by app.variableAsk.collectAsState()
    iv?.let { InterventionWindow(app, it, mode) }
    val breakCard by app.breakCard.collectAsState()
    breakCard?.let { BreakCardWindow(app, it, mode) }
    variable?.let { VariableAskWindow(app, it, mode) }
    val unknown by app.unknownAsk.collectAsState()
    unknown?.let { UnknownAskWindow(app, it, mode) }
    val sort by app.sortWindow.collectAsState()
    sort?.let { SortWindow(app, it, mode) }
    val bedtime by app.bedtimeNudge.collectAsState()
    bedtime?.let { BedtimeWindow(app, it, mode) }
    val unblockNudge by app.unblockNudge.collectAsState()
    unblockNudge?.let { UnblockNudgeWindow(app, it, mode) }
    prompt?.let { PromptWindow(app, it, mode) }
    val helperPrompt by app.helperPrompt.collectAsState()
    if (helperPrompt) BlockingHelperPromptWindow(app, mode)
    if (battery) OnTaskTheme(mode) {
        AlertDialog(
            onDismissRequest = { app.answerBattery(false) },
            title = { Text("Battery is at 20%") },
            text = { Text("Reading on-screen text with an AI model uses extra power. Switch to metadata-only (app names and sites) until you plug in?") },
            confirmButton = { Button({ app.answerBattery(true) }) { Text("Switch to metadata-only") } },
            dismissButton = { TextButton({ app.answerBattery(false) }) { Text("Keep going") } },
        )
    }
}

@Composable
fun InterventionWindow(app: App, iv: Intervention, mode: ThemeMode) {
    if (iv.countdown != null) { CountdownWindow(app, iv, mode); return }
    // Every prompt's close button only hides it for a moment: it comes back until it's answered.
    when (iv.step) {
        // The first prompt: in the middle of the screen you're using, on top, and it takes the focus so it can't be missed.
        Step.PROMPT -> {
            val size = DpSize(540.dp, ((if (iv.reminder) 200 else if (iv.breakSec > 0) 260 else 300) + planHeight(app)).dp)
            Window(
                onCloseRequest = { app.interventionClosed() }, title = "OnTask — are you on task?", alwaysOnTop = true, resizable = false,
                state = rememberWindowState(size = DpSize(540.dp, androidx.compose.ui.unit.Dp.Unspecified), position = centeredOnActiveScreen(size)),
            ) {
                LaunchedEffect(Unit) { bringForwardAlone(window) }
                OnTaskTheme(mode) { Surface(Modifier.fillMaxSize().clearFocusOnTap()) { if (iv.reminder) Reminder(app, iv) else if (iv.breakSec > 0) BreakCheck(app, iv) else StepOne(app, iv) } }
            }
        }

        // Before work hours, step 3 is the firmer window again (no partner message then).
        Step.WINDOW, Step.PARTNER.takeIf { !iv.partnerAllowed } -> {
            Window(
                // Normal-sized window (not full screen), answerable at once; closing it only hides it for a moment.
                onCloseRequest = { app.interventionClosed() }, title = "OnTask — are you on task?", alwaysOnTop = true, resizable = true,
                state = rememberWindowState(size = DpSize(560.dp, 640.dp), position = centeredOnActiveScreen(DpSize(560.dp, 640.dp))),
            ) {
                LaunchedEffect(Unit) { bringForwardAlone(window) }
                OnTaskTheme(mode) { Surface(Modifier.fillMaxSize().clearFocusOnTap()) { StepTwo(app, iv) } }
            }
        }

        Step.PARTNER -> Window(
            onCloseRequest = { app.interventionClosed() }, title = "OnTask — reach out?", alwaysOnTop = true,
            state = rememberWindowState(size = DpSize(540.dp, 620.dp), position = centeredOnActiveScreen(DpSize(540.dp, 620.dp))),
        ) {
            LaunchedEffect(Unit) { bringForwardAlone(window) }
            OnTaskTheme(mode) { Surface(Modifier.fillMaxSize().clearFocusOnTap()) { StepThree(app, iv) } }
        }

        Step.NONE -> {}
    }
}

/**
 * "Sending a message to your partner in 10…": step 3 went unanswered while you kept drifting (or "Not now" and still
 * there). It says why and shows the message word for word. Closing it or "I'm back to work" only closes the window:
 * still on time-wasting when the 10 seconds are up sends it; back at work starts a 10-minute watch. "I'm actually on
 * task" files what you pick as work and sends nothing.
 */
@Composable
private fun CountdownWindow(app: App, iv: Intervention, mode: ThemeMode) {
    val size = DpSize(560.dp, 520.dp)
    Window(
        onCloseRequest = { app.countdownClosed() }, title = "OnTask — telling your partner", alwaysOnTop = true, resizable = true,
        state = rememberWindowState(size = size, position = centeredOnActiveScreen(size)),
    ) {
        LaunchedEffect(Unit) { bringForwardAlone(window) }
        OnTaskTheme(mode) { Surface(Modifier.fillMaxSize().clearFocusOnTap()) { Countdown(app, iv) } }
    }
}

@Composable
private fun Countdown(app: App, iv: Intervention) {
    var left by remember(iv) { mutableStateOf(app.ladder.cfg.countdownSec) }
    LaunchedEffect(iv) { while (left > 0) { delay(1000); left-- } }
    var onTask by remember(iv) { mutableStateOf(false) }
    val reason = iv.countdown ?: return
    val site = iv.site ?: "time-wasting sites"
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (left > 0) "Sending a message to your partner in $left…" else "Sending…", style = MaterialTheme.typography.headlineSmall)
        Text(dev.ontask.intervene.autoTrigger(reason, site, maxOf(1, iv.driftSec / 60)), style = MaterialTheme.typography.bodyLarge)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
            Text(app.autoText(iv), Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Text("Back at work when the time is up, nothing goes, unless you're back on time-wasting in the next 10 minutes.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PlanBox(app)
        if (!onTask) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ app.countdownClosed() }) { Text("I'm back to work") }
            OutlinedButton({ onTask = true }) { Text("I'm actually on task") }
            if (iv.lunch) OutlinedButton({ app.interventionLunch() }) { Text(if (iv.stillLunch) "Still at lunch" else "I'm at lunch") }
        } else OnTaskChooser(app, iv)
    }
}

/** Which of this drift's sites, apps, pages or videos was work: the longest first; a page or the whole site. */
@Composable
private fun OnTaskChooser(app: App, iv: Intervention) {
    val items = remember(iv) { app.driftItems(iv) }
    var picked by remember(items) { mutableStateOf(items.firstOrNull()) }
    var wholeSite by remember(picked) { mutableStateOf(false) }
    Text("What were you working on?", style = MaterialTheme.typography.titleMedium)
    if (items.isEmpty()) Text("Nothing time-wasting recorded in this drift.", style = MaterialTheme.typography.bodyMedium)
    items.forEach { d ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.RadioButton(picked == d, { picked = d })
            Text(d.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text(fmtDuration(d.seconds), style = MaterialTheme.typography.bodySmall)
        }
    }
    picked?.takeIf { it.item != null && it.domain != null }?.let { d ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(!wholeSite, { wholeSite = false }); Text("Just this page or video  ")
            androidx.compose.material3.RadioButton(wholeSite, { wholeSite = true }); Text("All of ${d.domain}")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ picked?.let { app.countdownOnTask(it, wholeSite) } ?: app.interventionWork() }) { Text("It's work — don't send") }
    }
}

/** "You've had 14 min of breaks in the last hour (35 min today)." Nothing when there were none. */
@Composable
private fun BreakTotals(hourSec: Long, todaySec: Long) {
    if (todaySec < 60) return
    Text(
        if (hourSec >= 60) "You've had ${fmtDuration(hourSec)} of breaks in the last hour (${fmtDuration(todaySec)} today)." else "You've had ${fmtDuration(todaySec)} of breaks today.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Today's plan in a nudge: check off tasks right here (nothing when there's no plan). */
@Composable
private fun PlanBox(app: App) {
    val v by app.priorities.version.collectAsState()
    if (remember(v) { app.priorities.itemsToday(App.nowSec()).isEmpty() }) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Today's plan", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PlanList(app)
        }
    }
}

/** Roughly how much taller today's plan makes a nudge (only to center it: the window fits its content). */
private fun planHeight(app: App) = app.priorities.itemsToday(App.nowSec()).size.let { if (it == 0) 0 else 50 + 35 * it }

/** "Back to work": OnTask then checks you switch (see Ladder.backToWork). */
@Composable
private fun BackButton(app: App) = Button({ app.backToWork() }) { Text("Back to work") }

/** After "Back to work", every 30 seconds while you're still on something time-wasting. */
@Composable
private fun Reminder(app: App, iv: Intervention) {
    val site = iv.site?.let { remember(it) { dev.ontask.classify.Pages.siteName(it) ?: it } } ?: "it"
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Still on $site. Back to work?", style = MaterialTheme.typography.headlineSmall)
        Text("You said you'd get back to it. Switch to your work now.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PlanBox(app)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackButton(app)
            OutlinedButton({ app.interventionWork() }) { Text("I'm working") }
            if (iv.lunch) OutlinedButton({ app.interventionLunch() }) { Text(if (iv.stillLunch) "Still at lunch" else "I'm at lunch") }
        }
    }
}

/** After 25+ minutes of focus, a minute on something time-wasting: "Is this a break?" */
@Composable
private fun BreakCheck(app: App, iv: Intervention) {
    val min = iv.breakSec / 60
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Is this a break?", style = MaterialTheme.typography.headlineSmall)
        Text("You've focused ${fmtDuration(iv.focusedSec)}, so you've got $min minutes.", style = MaterialTheme.typography.bodyLarge)
        BreakTotals(iv.breaksHourSec, iv.breaksTodaySec)
        PlanBox(app)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ app.interventionBreak() }) { Text("Yes, $min-min break") }
            OutlinedButton({ app.interventionWork() }) { Text("I'm working") }
            OutlinedButton({ app.backToWork() }) { Text("Back to work") }
            if (iv.lunch) OutlinedButton({ app.interventionLunch() }) { Text(if (iv.stillLunch) "Still at lunch" else "Lunch") }
        }
    }
}

@Composable
private fun StepOne(app: App, iv: Intervention) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Are you on task?", style = MaterialTheme.typography.headlineSmall)
        Text("Is this what you meant to be doing right now?", style = MaterialTheme.typography.bodyLarge)
        if (iv.toEarnSec > 0) Text(
            "You've focused ${fmtDuration(iv.focusedSec)} since your last break. ${fmtDuration(iv.toEarnSec)} more earns a 5-minute break.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BreakTotals(iv.breaksHourSec, iv.breaksTodaySec)
        PlanBox(app)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BackButton(app)
            OutlinedButton({ app.interventionWork() }) { Text("I'm working") }
            if (iv.lunch) OutlinedButton({ app.interventionLunch() }) { Text(if (iv.stillLunch) "Still at lunch" else "Lunch") }
        }
    }
}

/**
 * "Help me get started": your plan with "pick the smallest next step and start it now", blocking the site for a
 * while, and (in work hours, not on step 3 itself) a message to your partner.
 */
@Composable
private fun HelpPanel(app: App, iv: Intervention, partner: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Pick the smallest next step in your plan and start it now.", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            iv.site?.let { site ->
                val name = remember(site) { dev.ontask.classify.Pages.siteName(site) ?: site }
                OutlinedButton({ app.interventionBlock(site) }) { Text("Block $name for 15 minutes") }
            }
            if (partner) TextButton({ app.interventionStruggling() }) { Text("Message my partner") }
        }
    }
}

@Composable
private fun StepTwo(app: App, iv: Intervention) {
    var help by remember(iv) { mutableStateOf(false) }
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Time to come back", style = MaterialTheme.typography.headlineMedium)
        Text(app.unblockMessage(), style = MaterialTheme.typography.titleMedium)
        Text("You've been off track for ${iv.driftSec / 60} min. Unblocks in the last hour: ${iv.unblocks}.", style = MaterialTheme.typography.bodyLarge)
        BreakTotals(iv.breaksHourSec, iv.breaksTodaySec)
        PlanBox(app)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BackButton(app)
            OutlinedButton({ app.interventionWork() }) { Text("It's actually work") }
            if (iv.lunch) OutlinedButton({ app.interventionLunch() }) { Text(if (iv.stillLunch) "Still at lunch" else "I'm at lunch") }
            if (!help) TextButton({ help = true }) { Text("Help me get started") }
        }
        if (help) HelpPanel(app, iv, partner = iv.partnerAllowed)
    }
}

@Composable
private fun StepThree(app: App, iv: Intervention) {
    val s = app.tracker.live.value.sample
    val what = remember { dev.ontask.classify.domainOf(s?.url) ?: s?.app ?: "something else" }
    var text by remember { mutableStateOf("I've been drifting on $what. I'm getting back to work.\nNo need to reply — but a 👍 helps.") }
    var status by remember { mutableStateOf("") }
    var help by remember(iv) { mutableStateOf(false) }
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Want to let your partner know?", style = MaterialTheme.typography.headlineMedium)
        Text("It's your call. You can edit the message.", style = MaterialTheme.typography.bodyMedium)
        BreakTotals(iv.breaksHourSec, iv.breaksTodaySec)
        PlanBox(app)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 4)
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                status = if (app.sendPartnerAlert(text)) "Sent." else "You've already sent today's alerts — this will go in your next check-in instead."
                app.backToWork()
            }) { Text("Send, and back to work") }
            OutlinedButton({ app.backToWork() }) { Text("Not now, back to work") }
            if (iv.lunch) OutlinedButton({ app.interventionLunch() }) { Text(if (iv.stillLunch) "Still at lunch" else "I'm at lunch") }
            if (!help) TextButton({ help = true }) { Text("Help me get started") }
        }
        if (help) HelpPanel(app, iv, partner = false)
    }
}

/** When a break or lunch runs out (or lunch reaches 30 minutes): centered, takes the focus, comes back if closed. */
@Composable
fun BreakCardWindow(app: App, c: App.BreakCard, mode: ThemeMode) {
    val size = DpSize(500.dp, (240 + planHeight(app)).dp)
    Window(
        onCloseRequest = { app.breakCardClosed() }, title = "OnTask", alwaysOnTop = true, resizable = false,
        state = rememberWindowState(size = DpSize(500.dp, androidx.compose.ui.unit.Dp.Unspecified), position = centeredOnActiveScreen(size)),
    ) {
        LaunchedEffect(Unit) { bringForwardAlone(window) }
        OnTaskTheme(mode) { Surface(Modifier.fillMaxSize().clearFocusOnTap()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when (c.due) {
                        Breaks.Due.BREAK_OVER -> "That's ${c.minutes} minutes. Back to work?"
                        Breaks.Due.LUNCH_CHECK -> "Is lunch over?"
                        Breaks.Due.LUNCH_OVER -> "Lunch is over. Back to work?"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (c.due == Breaks.Due.LUNCH_CHECK) Text("You've had ${c.minutes} minutes.", style = MaterialTheme.typography.bodyLarge)
                if (c.due == Breaks.Due.BREAK_OVER) BreakTotals(c.breaksHourSec, c.breaksTodaySec)
                PlanBox(app)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackButton(app)
                    if (c.canExtend) OutlinedButton({ app.breakMore() }) { Text(if (c.due == Breaks.Due.BREAK_OVER) "5 more minutes" else "15 more minutes") }
                }
            }
        } }
    }
}

/** Daily-rhythm prompt: planning / midday / evening, shown as its own window. */
@Composable
fun PromptWindow(app: App, prompt: Prompt, mode: ThemeMode) {
    val title = when (prompt) { Prompt.PLANNING, Prompt.PLANNING_SHORT -> "Plan your day"; Prompt.MIDDAY -> "Midday check-in"; else -> "Evening check-in" }
    // On top of everything; closing counts as "Later" (10 min), and after two of those it stays until answered.
    Window(
        onCloseRequest = { app.finishPrompt(prompt, snoozeMin = 10) }, title = "OnTask — $title", alwaysOnTop = true,
        state = rememberWindowState(size = DpSize(640.dp, 760.dp), position = centeredOnActiveScreen(DpSize(640.dp, 760.dp))),
    ) {
        OnTaskTheme(mode) {
            Surface(Modifier.fillMaxSize().clearFocusOnTap()) {
                PlanPromptContent(app, prompt, onDone = { app.finishPrompt(prompt) }, onLater = { m -> app.finishPrompt(prompt, snoozeMin = m) }, laterLeft = app.snoozesLeft(prompt))
            }
        }
    }
}

/** Corner nudge for a not-for-work unblock in work hours: when it opens, and when it's blocked again. */
@Composable
fun UnblockNudgeWindow(app: App, n: App.UnblockNudge, mode: ThemeMode) {
    val sites = n.domains.joinToString(", ")
    Window(
        onCloseRequest = { app.unblockNudgeOk() }, title = "OnTask", alwaysOnTop = true, resizable = false, focusable = false,
        state = rememberWindowState(size = DpSize(440.dp, 190.dp), position = WindowPosition.Aligned(Alignment.BottomEnd)),
    ) {
        OnTaskTheme(mode) { Surface(Modifier.fillMaxSize().clearFocusOnTap()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (n.ending) {
                    Text("Time's up: $sites ${if (n.domains.size == 1) "is" else "are"} blocked again.", style = MaterialTheme.typography.titleSmall)
                    Text("Back to what you planned?", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ app.unblockNudgeOk() }) { Text("OK") }
                        TextButton({ app.unblockNudgeStruggling() }) { Text("I'm struggling") }
                    }
                } else {
                    Text("You unblocked $sites for ${n.minutes} min (${ordinal(n.countToday)} today).", style = MaterialTheme.typography.titleSmall)
                    Text("Back to work when it's up? Your partner has been told.", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ app.unblockNudgeOk() }) { Text("OK") }
                        TextButton({ app.unblockNudgeStruggling() }) { Text("I'm struggling") }
                    }
                }
            }
        } }
    }
}

private fun ordinal(n: Int) = "$n" + if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

/**
 * Bedtime: a reminder at bedtime, then firmer ones every 15 minutes while you're still at it. From the third on, it
 * can't be closed for a few seconds.
 */
@Composable
fun BedtimeWindow(app: App, n: App.BedtimeNudge, mode: ThemeMode) {
    var left by remember(n) { mutableStateOf(n.lockSec) }
    LaunchedEffect(n) { while (left > 0) { delay(1000); left-- } }
    val size = DpSize(480.dp, 300.dp)
    Window(
        onCloseRequest = { if (left <= 0) app.bedtimeSnooze(10) }, title = "OnTask", alwaysOnTop = true, resizable = false,
        state = rememberWindowState(size = size, position = centeredOnActiveScreen(size)),
    ) {
        OnTaskTheme(mode) { Surface(Modifier.fillMaxSize().clearFocusOnTap()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when {
                        n.level == 1 -> "It's bedtime (${n.bedtime})."
                        else -> "You're ${fmtDuration(n.minutesPast * 60L)} past bedtime."
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    when (n.level) {
                        1 -> "Time to wind down and put the screen away. Whatever's left will still be there tomorrow."
                        2 -> "Still here? Late nights borrow from tomorrow, work included. Wrap up and go to bed."
                        else -> "This is reminder number ${n.level} tonight. Close the laptop and get some sleep."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (left > 0) Text("Take a breath… ${left}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ app.bedtimeGoingToBed() }, enabled = left <= 0) { Text("Going to bed") }
                    OutlinedButton({ app.bedtimeSnooze(10) }, enabled = left <= 0) { Text("10 more minutes") }
                    if (n.level > 1) TextButton({ app.bedtimeSnooze(30) }, enabled = left <= 0) { Text("I'm working") }
                }
            }
        } }
    }
}
