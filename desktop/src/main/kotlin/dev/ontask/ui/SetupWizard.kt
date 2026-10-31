package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ontask.App
import dev.ontask.classify.TimeRange
import dev.ontask.classify.WorkWeek
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

private class Step(val title: String, val blurb: String, val skippable: Boolean = true, val content: @Composable (App) -> Unit)

/** First-run setup. Every step is skippable and can be finished later in Settings, except role and permissions. */
@Composable
fun SetupWizard(app: App, onDone: () -> Unit) {
    val steps = remember { listOf(roleStep, hoursStep, blocklistStep, habitsStep, rhythmStep, partnerStep, permissionsStep, aiStep, channelsStep) }
    var i by remember { mutableStateOf(0) }
    val step = steps[i]
    Page("Welcome to OnTask", "Step ${i + 1} of ${steps.size}: ${step.title}") {
        LinearProgressIndicator(progress = { (i + 1f) / steps.size }, Modifier.fillMaxWidth())
        SectionCard(step.title, step.blurb) { step.content(app) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (i > 0) OutlinedButton({ i-- }) { Text("Back") }
            Button({ if (i == steps.lastIndex) { app.prefs.set("setup.done", true); onDone() } else i++ }) { Text(if (i == steps.lastIndex) "Finish" else "Next") }
            if (step.skippable && i < steps.lastIndex) TextButton({ i++ }) { Text("Skip for now") }
        }
    }
}

private val roleStep = Step("Your role", "OnTask is for tracking yourself. A partner can also use it to support someone else (partner features arrive with the phone app).", skippable = false) { app ->
    var role by remember { mutableStateOf(app.prefs.str("role", "user")) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(role == "user", { role = "user"; app.prefs.set("role", "user") }, label = { Text("I'm tracking myself") })
        FilterChip(role == "partner", { role = "partner"; app.prefs.set("role", "partner") }, label = { Text("I'm an accountability partner") })
    }
}

private val hoursStep = Step("Work hours", "Pick your usual work days and hours. You can fine-tune in the Work hours calendar, including one-off exceptions.") { app ->
    val s = app.settings
    val first = s.schedule.week.days.values.firstOrNull()?.firstOrNull()
    var start by remember { mutableStateOf((first?.startMin ?: 540) / 60) }
    var end by remember { mutableStateOf((first?.endMin ?: 1020) / 60) }
    val days = remember { mutableStateOf(s.schedule.week.days.keys.ifEmpty { setOf(1, 2, 3, 4, 5) }) }
    fun save() {
        val week = WorkWeek(days.value.associateWith { listOf(TimeRange(start * 60, end * 60)) })
        // First-run: no partner to notify yet; save directly.
        app.settingsStore.save(app.settings.copy(schedule = app.settings.schedule.copy(week = week)))
        app.updateSettings(app.settingsStore.load())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { d ->
            FilterChip(d.value in days.value, { days.value = if (d.value in days.value) days.value - d.value else days.value + d.value; save() }, label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) })
        }
    }
    SliderSetting("Start", "When your work day begins.", start, 4..14, "", format = { "%02d:00".format(it) }) { start = it.coerceAtMost(end - 1); save() }
    SliderSetting("End", "When your work day ends.", end, 12..23, "", format = { "%02d:00".format(it) }) { end = it.coerceAtLeast(start + 1); save() }
}

private val blocklistStep = Step("Block list", "Sites to block during work hours. Start from the built-in list and optionally import your own.") { app ->
    var msg by remember { mutableStateOf("The built-in list (${app.blocklist.all().size} sites) is already loaded.") }
    OutlinedButton({ pickTextFile("Import sites to block")?.let { val n = app.blocklist.importFile(it, App.nowSec()); msg = if (n == 0) "No new sites found in that file." else "Imported $n sites." } }) { Text("Import from file…") }
    Text(msg, style = MaterialTheme.typography.bodyMedium)
    Text("These are blocked around the clock once OnTask's blocking helper is installed (next to the permissions, later in setup).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val habitsStep = Step("Habit prompts", "Reminders at set times, like a morning prayer. They go to your phone even when you're away from the Mac.") { app ->
    var name by remember { mutableStateOf("") }; var time by remember { mutableStateOf("06:30") }
    var list by remember { mutableStateOf(app.habits.all()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(name, { name = it }, label = { Text("Habit, e.g. Morning prayer") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(time, { time = it }, label = { Text("Time (HH:mm)") }, singleLine = true, modifier = Modifier.weight(0.4f))
        Button({ if (name.isNotBlank() && Regex("\\d\\d:\\d\\d").matches(time)) { app.habits.add(name, time); list = app.habits.all(); name = "" } }) { Text("Add") }
    }
    list.forEach { h -> Row(verticalAlignment = Alignment.CenterVertically) { Text("${h.time}  ${h.name}", Modifier.weight(1f)); TextButton({ app.habits.remove(h.id); list = app.habits.all() }) { Text("Remove") } } }
    if (list.isEmpty()) Text("No habits yet — that's fine, you can add them later in Settings.", style = MaterialTheme.typography.bodySmall)
}

private val rhythmStep = Step("Daily rhythm", "OnTask anchors your day to when you first sit down to work. Choose when check-ins happen.") { app ->
    var evening by remember { mutableStateOf(app.prefs.bool("rhythm.evening_at_end", true)) }
    var midday by remember { mutableStateOf(app.prefs.bool("rhythm.midday", true)) }
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(true, {}, enabled = false); Text("Planning when you first arrive at the computer on a work day") }
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(midday, { midday = it; app.prefs.set("rhythm.midday", it) }); Text("Midday check-in at the midpoint of your working time") }
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(evening, { evening = it; app.prefs.set("rhythm.evening_at_end", it) }); Text("Evening check-in and reflection at the end of work hours") }
}

private val partnerStep = Step("Accountability partner", "Pair with someone who supports you. They never have to approve anything — they just see what you choose to share. You can skip this.") { app -> PartnerSetupContent(app) }

private val permissionsStep = Step("macOS permissions", "OnTask works with app names alone, but these unlock more. Grant what you're comfortable with.", skippable = false) { app ->
    PermissionsBody(app)
}

private val aiStep = Step("Smarter classification (optional)", "Rules handle most things. An AI model can classify the rest from on-screen text. Local models keep everything on your Mac.") { app -> AiSetupContent(app) }

private val channelsStep = Step("Partner channels", "How your partner hears from you: push (default), text or email. Contact details are stored in the cloud settings, never in this repo.") { app -> ChannelsSetupContent(app) }
