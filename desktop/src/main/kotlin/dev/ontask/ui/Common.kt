package dev.ontask.ui

import dev.ontask.App
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val ContentMaxWidth = 880.dp

fun fmtDuration(sec: Long): String {
    val h = sec / 3600; val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m" else "${sec}s"
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
fun fmtTime(epochSec: Long): String = timeFmt.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

/** "just now", "5 min ago", "2 h ago", "3 days ago". */
fun fmtAgo(epochSec: Long, nowSec: Long = System.currentTimeMillis() / 1000): String {
    val s = (nowSec - epochSec).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        s < 2 * 86400 -> "yesterday"
        else -> "${s / 86400} days ago"
    }
}
fun fmtHm(epochSec: Long): String = fmtTime(epochSec).take(5)

/** A scrolling, centered page with a title and a one-line description. */
@Composable
fun Page(title: String, subtitle: String? = null, actions: @Composable () -> Unit = {}, maxWidth: androidx.compose.ui.unit.Dp = ContentMaxWidth, afterSubtitle: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = maxWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                    if (subtitle != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        afterSubtitle?.invoke()
                    }
                }
                actions()
            }
            content()
        }
    }
}

/** A card without a title row, for content that builds its own header. */
@Composable
fun PlainCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun SectionCard(title: String, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** Label + one-line explanation on the left, control on the right. */
@Composable
fun SettingRow(title: String, description: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
    }
}

@Composable
fun SwitchSetting(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    SettingRow(title, description) { Switch(checked, onChange) }

/** Slider with the current value and unit shown next to it. */
@Composable
fun SliderSetting(
    title: String, description: String, value: Int, range: IntRange, unit: String,
    step: Int = 1, format: (Int) -> String = { "$it $unit" }, onChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val count = (range.last - range.first) / step
        val steps = if (count <= 12) (count - 1).coerceAtLeast(0) else 0 // tick marks only when there are few values
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange((((it - range.first) / step).roundToInt() * step + range.first).coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = steps,
        )
    }
}

@Composable
fun <T> Picker(label: String, options: List<T>, display: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label  ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(display(o)) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

/** Collapsible "Advanced" block. */
@Composable
fun Expander(title: String, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton({ open = !open }) { Text((if (open) "▾  " else "▸  ") + title) }
        if (open) Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
fun EmptyState(title: String, hint: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(k, Modifier.weight(0.45f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, Modifier.weight(0.55f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Dot(color: Color, size: Int = 10) = Box(Modifier.size(size.dp).clip(CircleShape).background(color))

@Composable
fun Notice(text: String, error: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = if (action != null) 6.dp else 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (action != null) Button(onAction) { Text(action) }
        }
    }
}

/** Native macOS file chooser for a text or hosts file; null if cancelled. */
fun pickTextFile(title: String): java.nio.file.Path? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    d.isVisible = true
    val f = d.file ?: return null
    return java.nio.file.Path.of(d.directory, f)
}

/**
 * Dropdown showing [current]'s category; picking another saves a rule for [name] (an app or a website) and relabels its
 * past activity, so the change shows everywhere at once.
 */
@Composable
fun CategoryPicker(
    app: dev.ontask.App, name: String, current: dev.ontask.Category, onChanged: () -> Unit,
    /** For a page or video on a Varies site: its item key and title. Picking a category then changes just this one. */
    item: Pair<String, String?>? = null,
    /** Told which category was picked (after it's saved). */
    onPicked: (dev.ontask.Category) -> Unit = {},
    /** Saves the pick itself instead of the usual rule (a phone app: a rule for its package). */
    save: ((dev.ontask.Category) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val unsure = item != null && current.variable
    Box {
        TextButton({ open = true }) {
            Box(Modifier.size(8.dp).background(if (unsure) MaterialTheme.colorScheme.outline else levelColor(current.level), androidx.compose.foundation.shape.CircleShape))
            Text("  ${if (unsure) "Unsure" else current.label} ▾", style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(open, { open = false }) {
            Text(if (item != null) "This page or video is…" else "$name is…", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            dev.ontask.Category.assignable.filter { item == null || !it.variable }.forEach { cat ->
                DropdownMenuItem(
                    text = { Text(cat.label + if (cat == current) "  ✓" else "") },
                    leadingIcon = { Box(Modifier.size(8.dp).background(levelColor(cat.level), androidx.compose.foundation.shape.CircleShape)) },
                    onClick = {
                        open = false
                        if (cat != current) {
                            when { save != null -> save(cat); item != null -> app.answerVariable(item.first, item.second, cat); else -> reclassify(app, name, current, cat) }
                            onPicked(cat); onChanged()
                        }
                    },
                )
            }
        }
    }
}

/** Saves the rule and relabels history (see [App.reclassify]). */
fun reclassify(app: dev.ontask.App, name: String, from: dev.ontask.Category, to: dev.ontask.Category) = app.reclassify(name, to, from)

/**
 * Centered on the screen you're working on (the one with the mouse pointer), not always the main display. For
 * pop-ups that need an answer, such as "I can't tell whether this is work".
 */
fun centeredOnActiveScreen(size: androidx.compose.ui.unit.DpSize): androidx.compose.ui.window.WindowPosition {
    val bounds = runCatching { java.awt.MouseInfo.getPointerInfo().device.defaultConfiguration.bounds }.getOrNull()
        ?: return androidx.compose.ui.window.WindowPosition.Aligned(androidx.compose.ui.Alignment.Center)
    return androidx.compose.ui.window.WindowPosition.Absolute(
        (bounds.x + (bounds.width - size.width.value) / 2).dp,
        (bounds.y + (bounds.height - size.height.value) / 2).dp,
    )
}

/** Dates everywhere read like "Nov 1, 2026" ([weekday]: "Sunday, Nov 1, 2026"). */
fun fmtDate(d: java.time.LocalDate, weekday: Boolean = false): String =
    d.format(java.time.format.DateTimeFormatter.ofPattern(if (weekday) "EEEE, MMM d, yyyy" else "MMM d, yyyy", java.util.Locale.US))

/**
 * Brings a pop-up forward with keyboard focus, and only the pop-up: activating OnTask makes macOS raise its other
 * windows too (the main window), so when OnTask wasn't in front those go back behind the app you were using.
 */
suspend fun bringForwardAlone(popup: java.awt.Window) {
    val wasActive = java.awt.Window.getWindows().any { it.isActive }
    runCatching { java.awt.Desktop.getDesktop().requestForeground(true) }
    popup.toFront(); popup.requestFocus()
    if (wasActive) return
    // Activation finishes asynchronously, so send the others back again once it has.
    for (wait in listOf(0L, 150L, 500L)) {
        kotlinx.coroutines.delay(wait)
        java.awt.Window.getWindows().filter { it !== popup && it.isShowing && !it.isAlwaysOnTop }.forEach { it.toBack() }
    }
}

/**
 * Keeps OnTask's main window where it was when OnTask comes to the front through a pop-up (clicking a category
 * question, a check-in, a card…): macOS raises all of an app's windows when it's activated, so the main window is sent
 * back behind the app you were using. Opening the main window yourself ([allowMain]) is left alone.
 */
object KeepMainBack {
    @Volatile var main: java.awt.Window? = null
    @Volatile private var allowUntil = 0L
    /** The last app you were in other than OnTask (bundle id), to give the front back to when pop-ups close. */
    @Volatile var lastOtherApp: () -> String? = { null }
    private var popupsBefore = 0
    /** When the main window was last the focused one with no pop-up up, and whether it was when the pop-ups came. */
    private var mainFocusedAt = 0L
    private var mainWasFront = false
    @Volatile private var popupGoneAt = 0L

    /** The main window is being opened on purpose (menu bar, Dock, Spotlight): let it come forward. */
    fun allowMain() { allowUntil = System.currentTimeMillis() + 3000 }

    /** OnTask's pop-ups showing now: every window but the main one (tiny helper windows aside). */
    private fun popups() = java.awt.Window.getWindows().count { it !== main && it.isShowing && it.width > 60 && it.height > 60 }

    fun install() = runCatching {
        java.awt.Desktop.getDesktop().addAppEventListener(object : java.awt.desktop.AppForegroundListener {
            override fun appRaisedToForeground(e: java.awt.desktop.AppForegroundEvent) {
                // Activation settles over a few hundred milliseconds; check a few times.
                listOf(40, 200, 600).forEach { ms -> javax.swing.Timer(ms) { pushBack() }.apply { isRepeats = false; start() } }
            }
            override fun appMovedToBackground(e: java.awt.desktop.AppForegroundEvent) {}
        })
        // When the last pop-up closes (answered, or closed with its X), macOS would make the main window the front
        // one: send it back and give the front to the app you were in.
        javax.swing.Timer(300) {
            val n = popups(); val now = System.currentTimeMillis()
            if (n == 0 && main?.isActive == true) mainFocusedAt = now
            if (popupsBefore == 0 && n > 0) mainWasFront = now - mainFocusedAt < 700
            if (popupsBefore > 0 && n == 0) { popupGoneAt = now; if (!mainWasFront) afterPopups() }
            popupsBefore = n
        }.start()
    }

    private fun allowed() = System.currentTimeMillis() < allowUntil

    /**
     * OnTask came to the front. If that happened through a pop-up (one is showing, or one just closed), the main
     * window goes back behind the app you were using, even if macOS made it the focused window. Otherwise (Cmd-Tab,
     * clicking the main window itself) it's left alone.
     */
    private fun pushBack() {
        val m = main ?: return
        if (allowed() || !m.isShowing) return
        if (popups() == 0 && System.currentTimeMillis() - popupGoneAt > 2000) return
        if (mainWasFront) return
        m.toBack()
    }

    /**
     * Closing a pop-up you answered while OnTask's main window wasn't in use: give the front back to the app you were
     * in first, then close it (after [action]). Closed the other way round, macOS makes the main window the front one
     * for a moment, a flash behind the closing pop-up.
     */
    fun handBackThen(action: () -> Unit) {
        val id = lastOtherApp()?.takeIf { it.isNotBlank() && it != "dev.ontask.app" }
        val m = main
        if (id == null || allowed() || mainWasFront || (m != null && m.isActive)) { action(); return }
        Thread {
            runCatching { ProcessBuilder("open", "-b", id).start().waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }
            Thread.sleep(120)
            javax.swing.SwingUtilities.invokeLater(action)
        }.apply { isDaemon = true }.start()
    }

    private fun afterPopups() {
        val m = main ?: return
        if (allowed()) return
        // Only if OnTask is in front now (a pop-up you clicked made it so); otherwise nothing to undo.
        if (java.awt.Window.getWindows().none { it.isActive || it.isFocused }) return
        if (m.isShowing) m.toBack()
        lastOtherApp()?.takeIf { it.isNotBlank() && it != "dev.ontask.app" }?.let { id -> runCatching { ProcessBuilder("open", "-b", id).start() } }
    }
}

