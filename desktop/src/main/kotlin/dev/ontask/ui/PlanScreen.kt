package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ontask.App
import dev.ontask.plan.Checkin
import dev.ontask.plan.CheckinDraft
import dev.ontask.plan.Prompt
import java.time.LocalDate

@Composable
fun PlanScreen(app: App) {
    val today = LocalDate.now()
    Page("Today", "Your priorities and daily check-ins.") {
        PlanBody(app, Prompt.PLANNING, today) {}
        PlanBody(app, Prompt.EVENING, today) {}
        WeeklyCard(app, today)
    }
}

/** Window version of the same content. */
@Composable
fun PlanPromptContent(app: App, prompt: Prompt, onDone: () -> Unit, onLater: () -> Unit) {
    Page(
        when (prompt) { Prompt.PLANNING -> "Good morning"; Prompt.PLANNING_SHORT -> "Quick plan for the rest of the day"; Prompt.MIDDAY -> "Halfway through"; else -> "Wrapping up" },
        when (prompt) { Prompt.MIDDAY -> "A minute to check you're on track."; Prompt.EVENING -> "Reflect on today, then send a check-in." else -> "Set what matters today." },
    ) {
        PlanBody(app, prompt, LocalDate.now(), onDone)
        TextButton(onLater) { Text("Remind me in 10 minutes") }
    }
}

@Composable
private fun PlanBody(app: App, prompt: Prompt, date: LocalDate, onDone: () -> Unit) {
    when (prompt) {
        Prompt.PLANNING, Prompt.PLANNING_SHORT -> PrioritiesCard(app, date, prompt == Prompt.PLANNING_SHORT, onDone)
        Prompt.MIDDAY -> MiddayCard(app, date, onDone)
        else -> EveningCard(app, date, onDone)
    }
}

@Composable
private fun PrioritiesCard(app: App, date: LocalDate, short: Boolean, onDone: () -> Unit) {
    val items = remember { mutableStateListOf<String>().apply { addAll(app.priorities.forDate(date).ifEmpty { app.priorities.previous(date) }.ifEmpty { listOf("") }) } }
    var change by remember { mutableStateOf(app.checkins.get(date.minusDays(1), "evening")?.change.orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    SectionCard("Today's priorities", "Pre-filled from your last plan — edit only what changed. Up to 5.") {
        if (change.isNotBlank()) Notice("Yesterday you wanted to change: $change")
        items.forEachIndexed { i, t ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(t, { items[i] = it; saved = false }, Modifier.weight(1f), singleLine = true, label = { Text("Priority ${i + 1}") })
                if (items.size > 1) TextButton({ items.removeAt(i); saved = false }) { Text("Remove") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (items.size < 5) OutlinedButton({ items.add("") }) { Text("Add priority") }
            Button({
                app.priorities.set(date, items.toList()); app.checkins.save(Checkin(date, "planning", App.nowSec(), null, null, null, null, null, false)); saved = true; onDone()
            }) { Text(if (short) "Save plan" else "Start my day") }
            if (saved) Text("Saved.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MiddayCard(app: App, date: LocalDate, onDone: () -> Unit) {
    val p = app.priorities.forDate(date)
    var note by remember { mutableStateOf("") }
    SectionCard("How is it going?", "Check against your priorities.") {
        p.forEach { Text("• $it") }
        if (p.isEmpty()) Text("No priorities set today.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Anything to adjust for the afternoon? (optional)") })
        Button({ app.checkins.save(Checkin(date, "midday", App.nowSec(), null, null, null, note.ifBlank { null }, null, false)); app.events.emit("checkin_midday", App.nowSec(), "note" to note); onDone() }) { Text("Mark midday check-in done") }
    }
}

@Composable
private fun EveningCard(app: App, date: LocalDate, onDone: () -> Unit) {
    val existing = remember { app.checkins.get(date, "evening") }
    var rating by remember { mutableStateOf(existing?.rating) }
    var helped by remember { mutableStateOf(existing?.helped.orEmpty()) }
    var hurt by remember { mutableStateOf(existing?.hurt.orEmpty()) }
    var change by remember { mutableStateOf(existing?.change.orEmpty()) }
    val today = remember { app.reports.daily(date) }
    val yesterday = remember { app.reports.daily(date.minusDays(1)) }
    var draft by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf(false) }
    val struggles = remember { app.prefs.str("alerts.queued", "").lines().filter { it.isNotBlank() } }
    val auto = CheckinDraft.message(today, yesterday, rating, helped, hurt, change, struggles)
    if (!edited) draft = auto
    var step by remember { mutableStateOf(if (existing?.sent == true) 3 else 1) }

    SectionCard("Evening reflection", "One prompt, four steps: rate the day, what helped and hurt, a change for tomorrow, then review what's sent.") {
        if (step == 3 && existing?.sent == true) { Text("Today's check-in was sent. ✔"); return@SectionCard }
        Text("1. How was today?", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..5).forEach { r -> FilterChip(rating == r, { rating = r }, label = { Text("$r") }) } }
        Text("2. What helped and what hurt?", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(helped, { helped = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("What helped (one line)") })
        OutlinedTextField(hurt, { hurt = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("What hurt (one line)") })
        Text("3. One change for tomorrow (optional)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(change, { change = it }, Modifier.fillMaxWidth(), singleLine = true)
        Text("4. Review your check-in", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(draft, { draft = it; edited = true }, Modifier.fillMaxWidth(), minLines = 5)
        Text("Sent to your partner's chosen channels. If you haven't paired, it's just saved here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button({
            val now = App.nowSec()
            app.checkins.save(Checkin(date, "evening", now, rating, helped, hurt, change, draft, true))
            app.events.emit("checkin", now, "message" to draft, "rating" to rating, "date" to date.toString())
            app.prefs.set("alerts.queued", null)
            onDone()
        }, enabled = rating != null) { Text("Send check-in") }
    }
}

@Composable
private fun WeeklyCard(app: App, today: LocalDate) {
    val ratings = remember { app.checkins.ratings(today, 7) }
    SectionCard("This week: how you felt vs. what was measured", "Your self-rating next to the deep-focus share OnTask measured.") {
        if (ratings.isEmpty()) EmptyState("No ratings yet", "Rate your days in the evening check-in to see the comparison.")
        ratings.forEach { (d, r) ->
            val share = CheckinDraft.deepShare(app.reports.daily(d))
            Row(Modifier.fillMaxWidth()) {
                Text(d.toString(), Modifier.weight(1f)); Text("Felt: $r/5", Modifier.weight(1f)); Text(share?.let { "Measured: $it% deep" } ?: "Measured: —", Modifier.weight(1f))
            }
        }
    }
}
