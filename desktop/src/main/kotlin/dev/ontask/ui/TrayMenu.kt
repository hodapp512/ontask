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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.OutlinedButton
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

/**
 * The logo's shield with the clock hands cut out, drawn black: macOS renders it as a template image (see
 * `apple.awt.enableTemplateImages`). Same shapes as desktop/icons/MakeIcons.java.
 */
class TrayGlyph(private val color: Color = Color.Black) : Painter() {
    override val intrinsicSize = Size(44f, 44f)
    override fun DrawScope.onDraw() {
        val u = size.minDimension / 100f
        val shield = androidx.compose.ui.graphics.Path().apply {
            moveTo(50 * u, 4 * u)
            quadraticTo(72 * u, 15 * u, 95 * u, 15 * u)
            lineTo(95 * u, 44 * u)
            cubicTo(95 * u, 72 * u, 74 * u, 89 * u, 50 * u, 97 * u)
            cubicTo(26 * u, 89 * u, 5 * u, 72 * u, 5 * u, 44 * u)
            lineTo(5 * u, 15 * u)
            quadraticTo(28 * u, 15 * u, 50 * u, 4 * u)
            close()
        }
        val hands = androidx.compose.ui.graphics.Path().apply { moveTo(50 * u, 31 * u); lineTo(50 * u, 55 * u); lineTo(70 * u, 55 * u) }
        val stroke = Stroke(width = 9 * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), androidx.compose.ui.graphics.Paint())
        drawPath(shield, color)
        drawPath(hands, color, style = stroke, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
        drawContext.canvas.restore()
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
    val blocked = remember(now, version) { svc.currentlyBlocked(now) }
    val openCount = remember(now, version) { svc.unblocks.active(now).size }
    val adOn = remember(version) { app.adBlock.enabled }
    val status = when {
        app.helperNeeded() -> "Blocking isn't on: the blocking helper ${app.helperHealth.value?.problem}"
        svc.strictNow(now) -> "Work hours: blocking ${blocked.size} sites"
        else -> null
    }?.let { it + if (svc.mode == BlockMode.DRY_RUN) " (test mode)" else "" }

    var picking by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    Tray(
        icon = remember { TrayGlyph() },
        tooltip = "OnTask — focused ${fmtDuration(deep)}, wasted ${fmtDuration(wasted)}",
        onAction = { open(null) },
        menu = {
            status?.let { Item(it, enabled = false, onClick = {}) }
            val problems = remember(now, version) { app.problems() }
            if (problems.isNotEmpty()) Item("⚠️ " + (problems.singleOrNull() ?: "${problems.size} things aren't working") + " — see Diagnostics", onClick = { open(Dest.DIAGNOSTICS) })
            Item("Today: focused ${fmtDuration(deep)}, wasted ${fmtDuration(wasted)}", enabled = false, onClick = {})
            if (openCount > 0) Item("$openCount site${if (openCount == 1) "" else "s"} unblocked right now", enabled = false, onClick = {})
            val pausedUntil = remember(now, version) { app.askPausedUntil }
            if (pausedUntil > 0) {
                Item("Asking paused until ${fmtHm(pausedUntil)}", enabled = false, onClick = {})
                Item("Resume asking", onClick = { app.resumeAsking(); changed() })
            }
            Separator()
            Item("Show OnTask", onClick = { open(null) })
            Separator()
            Item("Unblock a site…", enabled = blocked.isNotEmpty(), onClick = { changed(); picking = true })
            Item("Add site to block…", onClick = { adding = true })
            CheckboxItem("Ad blocking", checked = adOn, onCheckedChange = { app.adBlock.enabled = it; retick(); changed() })
            Separator()
            Item("Quit OnTask", onClick = { exitApplication() })
        },
    )
    if (picking) UnblockPickerWindow(app, blocked) { picking = false; changed() }
    if (adding) AddSitesWindow(app) { adding = false; changed() }
}

internal fun themeOf(app: App) = runCatching { ThemeMode.valueOf(app.prefs.str("theme", "SYSTEM")) }.getOrDefault(ThemeMode.SYSTEM)

/** One way to unblock: choose a site, say whether it's for work, then either wait out the delay or state the purpose. */
@Composable
internal fun UnblockPickerWindow(app: App, blocked: List<String>, startSite: String? = null, startForWork: Boolean? = null, onClose: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(startSite) }
    var regular by remember { mutableStateOf(false) }
    // In work hours, one question first ("Is it for work?" Yes / No); then the work form, or the not-for-work unblock.
    var forWork by remember(chosen) { mutableStateOf(if (chosen == startSite) startForWork else null) }
    val state = rememberWindowState(size = DpSize(520.dp, 560.dp), position = centeredOnActiveScreen(DpSize(520.dp, 560.dp)))
    // The site list has a fixed height; the later steps take exactly their own (see FitHeight), so the yes/no
    // question isn't a small question in a big empty window.
    LaunchedEffect(chosen) { if (chosen == null) state.size = DpSize(520.dp, 560.dp) }
    Window(
        onCloseRequest = onClose, title = "Unblock a site", alwaysOnTop = true, resizable = true,
        state = state,
    ) {
        OnTaskTheme(remember { themeOf(app) }) { Surface(Modifier.fillMaxSize()) {
            val site = chosen
            val strict = remember { app.blocking.strictNow(App.nowSec()) }
            if (site != null) FitHeight(state, 520.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        !strict -> { LeisureUnblockForm(app, site, onDone = onClose); TextButton({ chosen = null }) { Text("← Choose another site") } }
                        forWork == null -> {
                            Text("Is $site for work?", style = MaterialTheme.typography.titleLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button({ forWork = true }) { Text("Yes") }
                                OutlinedButton({ forWork = false; regular = true }) { Text("No") }
                            }
                            TextButton({ chosen = null }) { Text("← Choose another site") }
                        }
                        forWork == true -> {
                            Text("Unblock $site for work", style = MaterialTheme.typography.titleMedium)
                            WorkUnblockForm(app, site, onDone = onClose)
                            TextButton({ forWork = null }) { Text("← Back") }
                        }
                        // Not for work: the unblock with a wait opens on its own.
                        else -> TextButton({ forWork = null; regular = false }) { Text("← Back") }
                    }
                }
            } else Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Which site?", style = MaterialTheme.typography.titleMedium)
                if (blocked.isEmpty()) Text("Nothing is blocked right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else OutlinedTextField(filter, { filter = it }, label = { Text("Filter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    blocked.filter { filter.isBlank() || it.contains(filter.trim(), ignoreCase = true) }.forEach { d ->
                        TextButton({ chosen = d }, Modifier.fillMaxWidth()) { Text(d, Modifier.fillMaxWidth()) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClose) { Text("Cancel") } }
            }
            if (regular) chosen?.let { UnblockDialog(app, listOf(it), onClose = onClose) }
        } }
    }
}

/** Outside work hours: pick how long (5 minutes unless you choose more) and it's unblocked at once — no wait, nobody is told. Ends when work hours start. */
@Composable
internal fun LeisureUnblockForm(app: App, site: String, onDone: () -> Unit) {
    var minutes by remember { mutableStateOf(5) }
    var error by remember { mutableStateOf<String?>(null) }
    val now = remember { App.nowSec() }
    val workStart = remember { app.settings.schedule.nextWorkStart(now) }
    Text("Unblock $site for…", style = MaterialTheme.typography.titleMedium)
    Text("Outside work hours there's no wait. It's blocked again when time's up.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        app.blocking.unblocks.leisureDurationsMin.forEach { m -> FilterChip(minutes == m, { minutes = m }, label = { Text(fmtDuration(m * 60L)) }) }
    }
    if (workStart != null && workStart < now + minutes * 60L)
        Text("Work hours start at ${fmtHm(workStart)}, so it closes then.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button({ try { app.unblockLeisure(listOf(site), minutes); onDone() } catch (e: dev.ontask.block.UnblockRejected) { error = e.message } }) { Text("Unblock for ${fmtDuration(minutes * 60L)}") }
}

/** Work unblock: no delay, but a stated purpose and your partner is told. */
@Composable
internal fun WorkUnblockForm(app: App, site: String, onDone: () -> Unit) {
    var purpose by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf(60) }
    var error by remember { mutableStateOf<String?>(null) }
    val max = app.blocking.unblocks.maxWorkMinutes
    Text("Unblocks right away. Your partner is told the site and why, and OnTask checks you stay on that purpose.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(purpose, { purpose = it; error = null }, label = { Text("What for? e.g. Acme customer: onboarding videos") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("For", style = MaterialTheme.typography.bodyMedium)
        listOf(15, 30, 60, 120, 180).filter { it <= max }.forEach { m -> FilterChip(minutes == m, { minutes = m }, label = { Text(fmtDuration(m * 60L)) }) }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button({
        try { val now = App.nowSec(); app.blocking.unblocks.startPurpose(site, purpose, minutes, now); app.blocking.tick(now); onDone() }
        catch (e: dev.ontask.block.UnblockRejected) { error = e.message }
    }, enabled = purpose.isNotBlank()) { Text("Unblock for work") }
}

@Composable
internal fun AddSitesWindow(app: App, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Window(
        onCloseRequest = onClose, title = "Add sites to block", alwaysOnTop = true, resizable = false,
        state = rememberWindowState(size = DpSize(420.dp, 360.dp), position = WindowPosition.Aligned(Alignment.Center)),
    ) {
        OnTaskTheme(remember { themeOf(app) }) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Blocked around the clock; unblocking is harder in work hours.", style = MaterialTheme.typography.bodyMedium)
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
