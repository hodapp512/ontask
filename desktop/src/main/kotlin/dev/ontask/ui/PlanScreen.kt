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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import dev.ontask.App
import dev.ontask.plan.Checkin
import dev.ontask.plan.CheckinDraft
import dev.ontask.plan.Prompt
import java.time.LocalDate

/** Window version of the same content. [laterLeft]: snoozes left today (0 hides Later). */
@Composable
fun PlanPromptContent(app: App, prompt: Prompt, onDone: () -> Unit, onLater: (Int) -> Unit, laterLeft: Int = 0) {
    Page(
        when (prompt) { Prompt.PLANNING -> "Good morning"; Prompt.PLANNING_SHORT -> "Quick plan for the rest of the day"; Prompt.MIDDAY -> "Halfway through"; else -> "Wrapping up" },
        when (prompt) { Prompt.MIDDAY -> "A few minutes to look at the morning and set up the afternoon."; Prompt.EVENING -> "A few minutes to reflect before you wrap up." else -> "Set what matters today." },
    ) {
        if (laterLeft > 0) {
            var open by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box {
                    TextButton({ open = true }) { Text("Do this later ▾") }
                    androidx.compose.material3.DropdownMenu(open, { open = false }) {
                        listOf(10 to "In 10 minutes", 30 to "In 30 minutes", 60 to "In 1 hour").forEach { (m, l) ->
                            androidx.compose.material3.DropdownMenuItem({ Text(l) }, { open = false; onLater(m) })
                        }
                    }
                }
                if (laterLeft == 1) Text("(once more today)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PlanBody(app, prompt, LocalDate.now(), onDone)
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

/** "What's in the way?" at the midday check-in, each with one suggestion. Other is only recorded. */
private val OBSTACLES = listOf(
    "Distracted / low energy" to "Take a 10-minute break away from the screen, then start the next small step.",
    "Interruptions/meetings" to "Block out a meeting-free hour this afternoon.",
    "Unclear what to do" to "Write the very next physical step at the top of your plan.",
    "Stuck on a problem" to "Write down what you've tried, then ask someone or switch to another task for 25 minutes.",
    "Overwhelmed" to "Pick the one thing that matters most and move the rest to tomorrow.",
    "Other" to null,
)

/**
 * Midday check-in: the morning in numbers, what got done, what should have, the plan to revise, and, after a
 * struggling morning, what's in the way.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MiddayCard(app: App, date: LocalDate, onDone: () -> Unit) {
    val m = remember { app.morningNow() }
    var plan by remember { mutableStateOf(app.priorities.forDate(date).joinToString("\n")) }
    var gotDone by remember { mutableStateOf("") }
    var should by remember { mutableStateOf("") }
    val chips = remember { mutableStateListOf<String>() }
    var showChips by remember { mutableStateOf(m.struggling) }
    val planFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    fun save(focus: Boolean) {
        val now = App.nowSec()
        app.priorities.set(date, listOf(plan))
        val done = gotDone.trim().ifBlank { null }
        app.checkins.save(Checkin(date, "midday", now, null, done, should.trim().ifBlank { null }, chips.joinToString(", ").ifBlank { null }, null, false))
        app.events.emit("checkin_midday", now, "done" to done, "should" to should, "chips" to chips.joinToString(","), "focusPct" to m.pct, "struggling" to m.struggling)
        onDone()
    }

    Text(
        "This morning: ${fmtDuration(m.focusedSec)} focused, ${fmtDuration(m.wastedSec)} wasted." +
            if (m.longestFocusSec >= 300) " Longest focused stretch ${fmtDuration(m.longestFocusSec)}." else "",
        style = MaterialTheme.typography.bodyLarge,
    )
    SectionCard("How's the day going?", null) {
        OutlinedTextField(gotDone, { gotDone = it }, Modifier.fillMaxWidth(), label = { Text("What did you get done?") })
        OutlinedTextField(should, { should = it }, Modifier.fillMaxWidth(), label = { Text("What should you have gotten done?") })
    }
    SectionCard("This afternoon's plan", "Still the same as this morning?") {
        OutlinedTextField(plan, { plan = it }, Modifier.fillMaxWidth().focusRequester(planFocus), minLines = 3)
    }
    if (showChips) SectionCard("What's in the way?", if (m.struggling) "The morning was hard to focus in. Pick anything that applies." else null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OBSTACLES.forEach { (c, _) -> FilterChip(c in chips, { if (c in chips) chips.remove(c) else chips.add(c) }, label = { Text(c) }) }
        }
        OBSTACLES.filter { it.first in chips && it.second != null }.forEach { (c, tip) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$c: $tip", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (c.startsWith("Distracted")) OutlinedButton({ app.startRestBreak(); save(focus = false) }) { Text("Start break") }
                if (c == "Unclear what to do") TextButton({ runCatching { planFocus.requestFocus() } }) { Text("Go to plan") }
            }
        }
    } else TextButton({ showChips = true }) { Text("Something in the way?") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ save(focus = false) }) { Text("Done") }
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
    val plan = remember { app.priorities.forDate(date).joinToString("\n").trim() }

    SectionCard("Evening reflection", "How the day felt, what helped and hurt, one change for tomorrow, and a note for your partner.") {
        if (step == 3 && existing?.sent == true) { Text("Today's check-in is done. ✔ It goes out with your daily report."); return@SectionCard }
        // Today's goals, for reference while you reflect.
        androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Today's plan", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(plan.ifEmpty { "No plan was set today." }, style = MaterialTheme.typography.bodyMedium)
            }
        }
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
internal fun HoursCheckCard(app: App, date: LocalDate, startEditing: Boolean = false, onClose: (() -> Unit)? = null) {
    val fmt = java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
    fun clock(m: Int) = java.time.LocalTime.of((m / 60) % 24, m % 60).format(fmt)
    var ranges by remember { mutableStateOf(app.settings.schedule.rangesFor(date)) }
    var mode by remember { mutableStateOf(if (startEditing) "edit" else "ask") } // ask, edit, off, done
    var start by remember { mutableStateOf(ranges.minOfOrNull { it.startMin } ?: 540) }
    var end by remember { mutableStateOf(ranges.maxOfOrNull { it.endMin } ?: 1020) }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    if (ranges.isEmpty() && mode != "done" && !startEditing) return
    fun save(newRanges: List<dev.ontask.classify.TimeRange>) {
        try {
            app.workHours.saveException(date, newRanges, reason.ifBlank { null }, App.nowSec())
            ranges = newRanges; mode = "done"; error = null
            onClose?.invoke()
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
                    if (startEditing && ranges.isNotEmpty()) OutlinedButton({ mode = "off" }) { Text("Day off") }
                    TextButton({ onClose?.invoke() ?: run { mode = "ask" } }) { Text("Cancel") }
                }
            }
            "off" -> {
                OutlinedTextField(reason, { reason = it; error = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Why? Your partner sees it") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ save(emptyList()) }, enabled = reason.isNotBlank()) { Text("Take the day off") }
                    TextButton({ mode = if (startEditing) "edit" else "ask" }) { Text("Cancel") }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
