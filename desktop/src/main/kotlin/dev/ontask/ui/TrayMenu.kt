package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.ontask.App
import dev.ontask.Level
import dev.ontask.block.BlockMode
import dev.ontask.block.normalizeDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Screen requests from outside the main window (the menu bar). [MainScreen] consumes [requested]. */
object Nav {
    val requested = MutableStateFlow<Dest?>(null)
    /** Bumped on every request so an already-open window comes to the front. */
    val raises = MutableStateFlow(0)
    fun open(dest: Dest?) { if (dest != null) requested.value = dest; raises.value++ }
}

/** Monochrome target glyph. Drawn black: macOS renders it as a template image (see `apple.awt.enableTemplateImages`). */
class TrayGlyph(private val color: Color = Color.Black) : Painter() {
    override val intrinsicSize = Size(44f, 44f)
    override fun DrawScope.onDraw() {
        val s = size.minDimension; val c = Offset(size.width / 2, size.height / 2)
        drawCircle(color, s * 0.37f, c, style = Stroke(s * 0.11f))
        drawCircle(color, s * 0.14f, c)
    }
}

@Composable
fun ApplicationScope.OnTaskTray(app: App, open: (Dest?) -> Unit) {
    var now by remember { mutableStateOf(App.nowSec()) }
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = App.nowSec() } }
    val scope = rememberCoroutineScope()
    fun changed() { now = App.nowSec(); version++ }
    fun retick() { scope.launch(Dispatchers.IO) { app.blocking.tick(App.nowSec()) } }

    val live by app.tracker.live.collectAsState()
    val deep = live.totals[Level.DEEP] ?: 0
    val wasted = live.totals[Level.WASTED] ?: 0
    val svc = app.blocking
    val focus = remember(now, version) { app.focus.active(now) }
    val blocked = remember(now, version) { svc.currentlyBlocked(now) }
    val openCount = remember(now, version) { svc.unblocks.active(now).size }
    val adOn = remember(version) { app.adBlock.enabled }
    val status = when {
        svc.mode == BlockMode.OFF -> "Blocking is off"
        focus != null -> "Focus session: ${fmtDuration(focus.plannedEndTs - now)} left, blocking ${blocked.size} sites"
        svc.strictNow(now) -> "Work hours: blocking ${blocked.size} sites"
        else -> "Outside work hours: nothing blocked"
    } + if (svc.mode == BlockMode.DRY_RUN) " (test mode)" else ""

    var picking by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    Tray(
        icon = remember { TrayGlyph() },
        tooltip = "OnTask — deep ${fmtDuration(deep)}, wasted ${fmtDuration(wasted)}",
        onAction = { open(null) },
        menu = {
            Item(status, enabled = false, onClick = {})
            Item("Today: deep ${fmtDuration(deep)}, wasted ${fmtDuration(wasted)}", enabled = false, onClick = {})
            if (openCount > 0) Item("$openCount site${if (openCount == 1) "" else "s"} unblocked right now", enabled = false, onClick = {})
            Separator()
            Item(if (blocked.isEmpty()) "Unblock a site… (nothing blocked)" else "Unblock a site…", enabled = blocked.isNotEmpty(), onClick = { changed(); picking = true })
            Item("Unblock for work…", onClick = { open(Dest.BLOCKING) })
            if (focus != null) Menu("Focus session") {
                Item("Ends at ${fmtHm(focus.plannedEndTs)} (${fmtDuration(focus.plannedEndTs - now)} left)", enabled = false, onClick = {})
                Item("End early…", onClick = { open(Dest.BLOCKING) })
            } else Menu("Start Focus session") {
                listOf(25, 45, 60, 90).forEach { m ->
                    Item("$m minutes", onClick = { app.focus.start(m, App.nowSec()); retick(); changed() })
                }
            }
            Separator()
            Item("Add site to block…", onClick = { adding = true })
            CheckboxItem("Ad blocking", checked = adOn, onCheckedChange = { app.adBlock.enabled = it; retick(); changed() })
            val last = app.adBlock.lastRefresh
            Item("Refresh ad-block list now" + if (last > 0) " (updated ${fmtDuration(now - last)} ago)" else "", enabled = adOn, onClick = { app.refreshAdBlock() })
            Separator()
            Item("Today's report", onClick = { open(Dest.REPORT) })
            Item("Settings…", onClick = { open(Dest.SETTINGS) })
            Item("Open OnTask", onClick = { open(null) })
            Separator()
            Item("Quit OnTask", onClick = { app.shutdown(); exitApplication() })
        },
    )
    if (picking) UnblockPickerWindow(app, blocked) { picking = false; changed() }
    if (adding) AddSitesWindow(app) { adding = false; changed() }
}

private fun themeOf(app: App) = runCatching { ThemeMode.valueOf(app.prefs.str("theme", "SYSTEM")) }.getOrDefault(ThemeMode.SYSTEM)

/** Choose a blocked site, then hand over to the regular [UnblockDialog] (delay and friction included). */
@Composable
private fun UnblockPickerWindow(app: App, blocked: List<String>, onClose: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<String?>(null) }
    Window(
        onCloseRequest = onClose, title = "Unblock a site", alwaysOnTop = true, resizable = false,
        state = rememberWindowState(size = DpSize(460.dp, 540.dp), position = WindowPosition.Aligned(Alignment.Center)),
    ) {
        OnTaskTheme(remember { themeOf(app) }) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Which site?", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(filter, { filter = it }, label = { Text("Filter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    blocked.filter { filter.isBlank() || it.contains(filter.trim(), ignoreCase = true) }.forEach { d ->
                        TextButton({ chosen = d }, Modifier.fillMaxWidth()) { Text(d, Modifier.fillMaxWidth()) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClose) { Text("Cancel") } }
            }
            chosen?.let { UnblockDialog(app, listOf(it), onClose = onClose) }
        } }
    }
}

@Composable
private fun AddSitesWindow(app: App, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Window(
        onCloseRequest = onClose, title = "Add sites to block", alwaysOnTop = true, resizable = false,
        state = rememberWindowState(size = DpSize(420.dp, 360.dp), position = WindowPosition.Aligned(Alignment.Center)),
    ) {
        OnTaskTheme(remember { themeOf(app) }) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Blocked during work hours and Focus sessions.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(text, { text = it; msg = null }, label = { Text("Sites, one per line (e.g. reddit.com)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp))
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClose) { Text("Done") }
                    Button({
                        val items = text.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
                        val bad = items.count { normalizeDomain(it) == null }
                        val n = app.blocklist.add(items, App.nowSec())
                        scope.launch(Dispatchers.IO) { app.blocking.tick(App.nowSec()) }
                        msg = "Added $n site${if (n == 1) "" else "s"}" + (if (bad > 0) "; $bad not recognised as a domain." else ".")
                        if (bad == 0) text = ""
                    }, enabled = text.isNotBlank()) { Text("Add") }
                }
            }
        } }
    }
}
