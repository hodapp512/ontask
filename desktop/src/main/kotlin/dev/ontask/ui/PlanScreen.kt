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

/** Window version of the same content. */
@Composable
fun PlanPromptContent(app: App, prompt: Prompt, onDone: () -> Unit, onLater: () -> Unit) {
    Page(
        when (prompt) { Prompt.PLANNING -> "Good morning"; Prompt.PLANNING_SHORT -> "Quick plan for the rest of the day"; Prompt.MIDDAY -> "Halfway through"; else -> "Wrapping up" },
        when (prompt) { Prompt.MIDDAY -> "A minute to check you're on track."; Prompt.EVENING -> "A few minutes to reflect before you wrap up." else -> "Set what matters today." },
    ) {
        PlanBody(app, prompt, LocalDate.now(), onDone)
        TextButton(onLater) { Text("Remind me in 10 minutes") }
    }
}

@Composable
private fun PlanBody(app: App, prompt: Prompt, date: LocalDate, onDone: () -> Unit) {
    when (prompt) {
        Prompt.PLANNING, Prompt.PLANNING_SHORT -> { HoursCheckCard(app, date); PrioritiesCard(app, date, prompt == Prompt.PLANNING_SHORT, onDone = onDone) }
        Prompt.MIDDAY -> MiddayCard(app, date, onDone)
        else -> EveningCard(app, date, onDone)
    }
}

@Composable
internal fun PrioritiesCard(app: App, date: LocalDate, short: Boolean, compact: Boolean = false, onDone: () -> Unit) {
    var text by remember { mutableStateOf(app.priorities.forDate(date).joinToString("\n")) }
    val change = remember { app.checkins.get(date.minusDays(1), "evening")?.change.orEmpty() }
    fun save() { app.priorities.set(date, listOf(text)); app.checkins.save(Checkin(date, "planning", App.nowSec(), null, null, null, null, null, false)); onDone() }
    // Home: one line to start, growing as you type; the title and button share a row.
    if (compact) {
        PlainCard {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Today's plan", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), maxLines = 4, placeholder = { Text("What matters today?") })
                Button(::save, enabled = text.isNotBlank()) { Text("Save") }
            }
            if (change.isNotBlank()) Text("Yesterday you wanted to change: $change", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    SectionCard("Today's plan", "What matters today, in your own words.") {
        if (change.isNotBlank()) Notice("Yesterday you wanted to change: $change")
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 4, placeholder = { Text("e.g. Finish the Acme proposal, then review PRs. No news before lunch.") })
        Button({
            app.priorities.set(date, listOf(text)); app.checkins.save(Checkin(date, "planning", App.nowSec(), null, null, null, null, null, false)); onDone()
        }, enabled = text.isNotBlank()) { Text(if (short) "Save plan" else "Start my day") }
    }
}

@Composable
internal fun MiddayCard(app: App, date: LocalDate, onDone: () -> Unit) {
    val p = app.priorities.forDate(date)
    var note by remember { mutableStateOf("") }
    SectionCard("Midday check-in", "Halfway through the day: are you still working on what you planned?") {
        if (p.isEmpty()) Text("No plan set today.", color = MaterialTheme.colorScheme.onSurfaceVariant) else Text(p.joinToString("\n"))
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Anything to adjust for the afternoon? (optional)") })
        Button({ app.checkins.save(Checkin(date, "midday", App.nowSec(), null, null, null, note.ifBlank { null }, null, false)); app.events.emit("checkin_midday", App.nowSec(), "note" to note); onDone() }) { Text("Mark midday check-in done") }
    }
}

@Composable
internal fun EveningCard(app: App, date: LocalDate, onDone: () -> Unit) {
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
    if (!edited && existing?.message != null) draft = existing.message
    var step by remember { mutableStateOf(if (existing?.sent == true) 3 else 1) }

    SectionCard("Evening reflection", "How the day felt, what helped and hurt, one change for tomorrow, and a note for your partner.") {
        if (step == 3 && existing?.sent == true) { Text("Today's check-in is done. ✔ It goes out with your daily report."); return@SectionCard }
        Text("1. How did today feel?", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { CheckinDraft.FEELINGS.forEach { (r, label) -> FilterChip(rating == r, { rating = r }, label = { Text(label) }) } }
        Text("2. What helped and what hurt?", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(helped, { helped = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("What helped (one line)") })
        OutlinedTextField(hurt, { hurt = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("What hurt (one line)") })
        Text("3. One change for tomorrow (optional)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(change, { change = it }, Modifier.fillMaxWidth(), singleLine = true)
        val partners = app.cloud.linkInfo()?.partners.orEmpty()
        Text("4. A note for ${partners.firstOrNull()?.trim()?.split(" ")?.firstOrNull() ?: "your partner"} (optional)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(draft, { draft = it; edited = true }, Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("Anything you want them to know about today") })
        Text(
            if (partners.isNotEmpty()) "Your daily report goes to ${partners.joinToString(" and ")} once you've wrapped up for the day (8 PM at the latest), with your answers and this note. You get a copy by email."
            else "Saved on this Mac. With a partner, it goes out in your daily report.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button({
            val now = App.nowSec()
            app.checkins.save(Checkin(date, "evening", now, rating, helped, hurt, change, draft.trim(), true))
            app.events.emit("checkin", now, "rating" to rating, "date" to date.toString())
            app.prefs.set("alerts.queued", null)
            onDone()
        }, enabled = rating != null) { Text(if (rating == null) "Pick how today felt first" else "Save check-in") }
    }
}


/**
 * The morning check: "Working 9:00 AM–5:00 PM today?" Yes, start later / finish early (today only), or a day off.
 * Shortening asks for a reason and tells your partner, like any other change to work hours.
 */
@Composable
internal fun HoursCheckCard(app: App, date: LocalDate) {
    val fmt = java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
    fun clock(m: Int) = java.time.LocalTime.of((m / 60) % 24, m % 60).format(fmt)
    var ranges by remember { mutableStateOf(app.settings.schedule.rangesFor(date)) }
    var mode by remember { mutableStateOf("ask") } // ask, edit, off, done
    var start by remember { mutableStateOf(ranges.minOfOrNull { it.startMin } ?: 540) }
    var end by remember { mutableStateOf(ranges.maxOfOrNull { it.endMin } ?: 1020) }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    if (ranges.isEmpty() && mode != "done") return
    fun save(newRanges: List<dev.ontask.classify.TimeRange>) {
        try {
            app.workHours.saveException(date, newRanges, reason.ifBlank { null }, App.nowSec())
            ranges = newRanges; mode = "done"; error = null
        } catch (e: dev.ontask.block.ReasonRequired) { error = "Say why: your partner is told when work hours get shorter." }
    }
    SectionCard("Today's hours") {
        val hours = ranges.joinToString(", ") { "${clock(it.startMin)}–${clock(it.endMin)}" }
        when (mode) {
            "done" -> Text(if (ranges.isEmpty()) "Day off today." else "Working $hours today.")
            "ask" -> {
                Text("Working $hours today?", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ mode = "done" }) { Text("Yes") }
                    OutlinedButton({ mode = "edit" }) { Text("Start later / finish early…") }
                    OutlinedButton({ mode = "off" }) { Text("Day off") }
                }
            }
            "edit" -> {
                SliderSetting("Start", "", start / 15, 16..60, "", format = { clock(it * 15) }) { start = (it * 15).coerceAtMost(end - 15) }
                SliderSetting("End", "", end / 15, 32..92, "", format = { clock(it * 15) }) { end = (it * 15).coerceAtLeast(start + 15) }
                OutlinedTextField(reason, { reason = it; error = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Why? (needed if it's shorter; your partner sees it)") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ save(listOf(dev.ontask.classify.TimeRange(start, end))) }) { Text("Save today's hours") }
                    TextButton({ mode = "ask" }) { Text("Cancel") }
                }
            }
            "off" -> {
                OutlinedTextField(reason, { reason = it; error = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Why? Your partner sees it") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ save(emptyList()) }, enabled = reason.isNotBlank()) { Text("Take the day off") }
                    TextButton({ mode = "ask" }) { Text("Cancel") }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
