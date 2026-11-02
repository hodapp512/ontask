package dev.ontask.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import dev.ontask.Category
import dev.ontask.Level

/** "I can't tell whether this is work" for a page or video on a Varies site that nothing else could decide. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VariableAskWindow(app: App, ask: App.VariableAsk, mode: ThemeMode) {
    val cats = remember { Category.assignable.filter { !it.variable } }
    Window(
        onCloseRequest = { app.variableClosed(ask.item) }, title = "OnTask", alwaysOnTop = true, resizable = false, focusable = false,
        state = rememberWindowState(size = DpSize(480.dp, 250.dp), position = centeredOnActiveScreen(DpSize(480.dp, 250.dp))),
    ) {
        OnTaskTheme(mode) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("I can't tell whether this is work. What is it?", style = MaterialTheme.typography.titleMedium)
                Text(ask.label ?: ask.title ?: "This page", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cats.forEach { c ->
                        OutlinedButton({ app.answerVariable(ask.item, ask.title, c, ask.sourceKey) }) {
                            Box(Modifier.size(8.dp).background(levelColor(c.level), CircleShape)); Text("  ${c.label}")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ app.answerVariable(ask.item, ask.title, null) }) { Text("Not now") }
                    PauseAskingButton(app)
                }
            }
        } }
    }
}

/** "Which category is this?" for a site or app no rule covers: the answer becomes a rule for it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnknownAskWindow(app: App, ask: App.UnknownAsk, mode: ThemeMode) {
    val cats = remember { Category.assignable }
    val size = DpSize(480.dp, 250.dp)
    Window(
        onCloseRequest = { app.unknownClosed(ask.name) }, title = "OnTask", alwaysOnTop = true, resizable = false, focusable = false,
        state = rememberWindowState(size = size, position = centeredOnActiveScreen(size)),
    ) {
        OnTaskTheme(mode) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Which category is this?", style = MaterialTheme.typography.titleMedium)
                Text(ask.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cats.forEach { c ->
                        OutlinedButton({ app.answerUnknown(ask.name, c) }) {
                            Box(Modifier.size(8.dp).background(levelColor(c.level), CircleShape)); Text("  ${c.label}")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ app.answerUnknown(ask.name, null) }) { Text("Not now") }
                    PauseAskingButton(app)
                }
            }
        } }
    }
}

@Composable
private fun PauseAskingButton(app: App) {
    var pause by remember { mutableStateOf(false) }
    Box {
        TextButton({ pause = true }) { Text("Pause asking ▾") }
        DropdownMenu(pause, { pause = false }) {
            listOf(15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour", 120 to "2 hours", 1440 to "1 day").forEach { (m, l) ->
                DropdownMenuItem({ Text(l) }, { pause = false; app.pauseAsking(m) })
            }
        }
    }
}

/** What a category counts as: one of the three levels, or "Varies" (decided per page or video). */
private enum class Kind(val label: String, val level: Level, val variable: Boolean, val hint: String) {
    FOCUSED("Focused", Level.DEEP, false, "Real work"),
    MIXED("Mixed", Level.MIXED, false, "Work-ish: email, chat, admin"),
    WASTED("Wasted", Level.WASTED, false, "Distractions"),
    VARIES("Varies", Level.MIXED, true, "Decided per video or page (AI or asks you)"),
    ;
    companion object { fun of(c: Category) = if (c.variable) VARIES else entries.first { !it.variable && it.level == c.level } }
}

/** Settings → Categories: rename, change what each counts as, add your own, delete. */
@Composable
fun CategoriesSettings(app: App) {
    // Bumped on every change: categories compare by name, so a refreshed list would otherwise look unchanged.
    var version by remember { mutableStateOf(0) }
    val cats = remember(version) { Category.assignable }
    var newLabel by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf(Kind.FOCUSED) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    fun reload() { version++; app.reloadRules(); app.tracker.invalidate() }

    SectionCard(
        "Categories",
        "Each counts as Focused, Mixed or Wasted, or Varies (judged per video or page).",
    ) {
        cats.forEach { c ->
            var label by remember(c, version) { mutableStateOf(c.label) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, singleLine = true, modifier = Modifier.weight(1f),
                    trailingIcon = if (label.trim() != c.label && label.isNotBlank()) ({ TextButton({ app.categories.update(c, label, c.level, c.variable); reload() }) { Text("Save") } }) else null)
                KindPicker(Kind.of(c)) { k -> app.categories.update(c, label, k.level, k.variable); reload() }
                TextButton({ deleting = c }) { Text("Delete") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newLabel, { newLabel = it }, singleLine = true, label = { Text("New category, e.g. Client work") }, modifier = Modifier.weight(1f))
            KindPicker(newKind) { newKind = it }
            Button({ app.categories.add(newLabel, newKind.level, newKind.variable); newLabel = ""; reload() }, enabled = newLabel.isNotBlank()) { Text("Add") }
        }
        val learned = remember(version) { app.verdicts.rules() }
        if (learned.isNotEmpty()) {
            Text("Learned from your answers", style = MaterialTheme.typography.titleSmall)
            Text("After two matching answers, pages from the same place are sorted without asking.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            learned.forEach { (key, cat) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(levelColor(cat.level), CircleShape))
                    Text("${key.replaceFirstChar { it.uppercase() }} → ${cat.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton({ app.verdicts.deleteRule(key); version++ }) { Text("Remove") }
                }
            }
        }
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${c.label}\"?") },
            text = { Text("Your rules for it are removed. Everything in it is sorted again with OnTask's other rules; whatever doesn't match becomes Unclassified.") },
            confirmButton = { TextButton({ deleting = null; app.deleteCategory(c); reload() }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KindPicker(current: Kind, onPick: (Kind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.width(150.dp)) {
            Box(Modifier.size(8.dp).background(if (current.variable) MaterialTheme.colorScheme.tertiary else levelColor(current.level), CircleShape))
            Text("  ${current.label} ▾")
        }
        DropdownMenu(open, { open = false }) {
            Kind.entries.forEach { k ->
                DropdownMenuItem(
                    text = { Column { Text(k.label); Text(k.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    onClick = { open = false; onPick(k) },
                )
            }
        }
    }
}
