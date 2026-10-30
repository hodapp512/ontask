package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
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

    iv?.let { InterventionWindow(app, it, mode) }
    prompt?.let { PromptWindow(app, it, mode) }
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
    when (iv.step) {
        Step.PROMPT -> Window(
            onCloseRequest = { app.interventionDismiss() }, title = "OnTask", alwaysOnTop = true, resizable = false,
            state = rememberWindowState(size = DpSize(420.dp, 190.dp), position = WindowPosition.Aligned(Alignment.BottomEnd)),
        ) { OnTaskTheme(mode) { Surface(Modifier.fillMaxSize()) { StepOne(app, iv) } } }

        Step.WINDOW -> {
            val lock = app.ladder.cfg.lockSec
            var left by remember(iv) { mutableStateOf(lock) }
            LaunchedEffect(iv) { while (left > 0) { delay(1000); left-- } }
            Window(
                // Normal-sized window (not full screen). Closing is ignored until the lock runs out.
                onCloseRequest = { if (left <= 0) app.interventionDismiss() }, title = "OnTask — are you on task?", alwaysOnTop = true, resizable = true,
                state = rememberWindowState(size = DpSize(560.dp, 520.dp), position = WindowPosition.Aligned(Alignment.Center)),
            ) { OnTaskTheme(mode) { Surface(Modifier.fillMaxSize()) { StepTwo(app, iv, left) } } }
        }

        Step.PARTNER -> Window(
            onCloseRequest = { app.interventionDismiss() }, title = "OnTask — reach out?", alwaysOnTop = true,
            state = rememberWindowState(size = DpSize(520.dp, 460.dp), position = WindowPosition.Aligned(Alignment.Center)),
        ) { OnTaskTheme(mode) { Surface(Modifier.fillMaxSize()) { StepThree(app, iv) } } }

        Step.NONE -> {}
    }
}

@Composable
private fun StepOne(app: App, iv: Intervention) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("You've drifted for ${iv.driftSec / 60} min.", style = MaterialTheme.typography.titleMedium)
        Text("Is this work?", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ app.interventionWork() }) { Text("Yes, it's work") }
            OutlinedButton({ app.interventionBack() }) { Text("Back to work") }
            TextButton({ app.interventionStruggling() }) { Text("I'm struggling") }
        }
    }
}

@Composable
private fun StepTwo(app: App, iv: Intervention, lockLeft: Int) {
    val priorities = remember { app.priorities.today(App.nowSec()) }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Time to come back", style = MaterialTheme.typography.headlineMedium)
        Text(app.unblockMessage(), style = MaterialTheme.typography.titleMedium)
        Text("You've been off track for ${iv.driftSec / 60} min. Unblocks in the last hour: ${iv.unblocks}.", style = MaterialTheme.typography.bodyLarge)
        if (priorities.isNotEmpty()) {
            Text("Today's priorities", style = MaterialTheme.typography.titleSmall)
            priorities.forEach { Text("• $it") }
        }
        if (lockLeft > 0) Text("You can respond in ${lockLeft}s…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ app.interventionBack() }, enabled = lockLeft <= 0) { Text("Back to work") }
            OutlinedButton({ app.interventionWork() }, enabled = lockLeft <= 0) { Text("It's actually work") }
            TextButton({ app.interventionStruggling() }, enabled = lockLeft <= 0) { Text("I'm struggling") }
        }
    }
}

@Composable
private fun StepThree(app: App, iv: Intervention) {
    val s = app.tracker.live.value.sample
    val what = remember { dev.ontask.classify.domainOf(s?.url) ?: s?.app ?: "something else" }
    var text by remember { mutableStateOf("I've been drifting on $what. I'm getting back to work.\nNo need to reply — but a 👍 helps.") }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        // AI adds a one-line context to the standard template (best effort).
        withContext(Dispatchers.IO) { app.ai.takeIf { it.enabled }?.contextLine(what, iv.driftSec / 60, app.priorities.today(App.nowSec())) }
            ?.let { text = "$it\nI'm getting back to work. No need to reply — but a 👍 helps." }
    }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Want to let your partner know?", style = MaterialTheme.typography.headlineMedium)
        Text("It's your call — nothing is sent unless you confirm. You can edit the message.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 4)
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                status = if (app.sendPartnerAlert(text)) "Sent." else "You've already sent today's alerts — this will go in your next check-in instead."
                app.interventionBack()
            }) { Text("Send to partner") }
            OutlinedButton({ app.interventionBack() }) { Text("Not now, back to work") }
        }
    }
}

/** Daily-rhythm prompt: planning / midday / evening, shown as its own window. */
@Composable
fun PromptWindow(app: App, prompt: Prompt, mode: ThemeMode) {
    val title = when (prompt) { Prompt.PLANNING, Prompt.PLANNING_SHORT -> "Plan your day"; Prompt.MIDDAY -> "Midday check-in"; else -> "Evening check-in" }
    Window(
        onCloseRequest = { app.finishPrompt(prompt, snoozeMin = 10) }, title = "OnTask — $title",
        state = rememberWindowState(size = DpSize(640.dp, 760.dp), position = WindowPosition.Aligned(Alignment.Center)),
    ) {
        OnTaskTheme(mode) {
            Surface(Modifier.fillMaxSize()) {
                PlanPromptContent(app, prompt, onDone = { app.finishPrompt(prompt) }, onLater = { app.finishPrompt(prompt, snoozeMin = 10) })
            }
        }
    }
}
