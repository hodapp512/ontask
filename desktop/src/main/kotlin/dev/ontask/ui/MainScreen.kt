package dev.ontask.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ontask.App

enum class Dest(val label: String, val glyph: String) {
    REPORT("Report", "▤"),
    BLOCKING("Blocking", "⛔"),
    CALENDAR("Work hours", "🗓"),
    DIAGNOSTICS("Diagnostics", "⚙"),
    SETTINGS("Settings", "☰"),
}

@Composable
fun MainScreen(app: App, startAt: Dest = Dest.REPORT) {
    var dest by remember { mutableStateOf(startAt) }
    var mode by remember { mutableStateOf(runCatching { ThemeMode.valueOf(app.prefs.str("theme", "SYSTEM")) }.getOrDefault(ThemeMode.SYSTEM)) }
    OnTaskTheme(mode) {
        var setupDone by remember { mutableStateOf(app.prefs.bool("setup.done", false)) }
        if (!setupDone) { SetupWizard(app) { setupDone = true }; return@OnTaskTheme }
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) { Row(Modifier.fillMaxSize()) {
            NavigationRail {
                Dest.entries.forEach { d ->
                    NavigationRailItem(selected = dest == d, onClick = { dest = d }, icon = { Text(d.glyph) }, label = { Text(d.label) })
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (dest) {
                    Dest.REPORT -> ReportScreen(app)
                    Dest.BLOCKING -> BlockingScreen(app)
                    Dest.CALENDAR -> CalendarScreen(app)
                    Dest.DIAGNOSTICS -> DiagnosticsScreen(app)
                    Dest.SETTINGS -> SettingsScreen(app, mode) { mode = it; app.prefs.set("theme", it.name) }
                }
            }
        } }
    }
}

/** Hook for settings sections added by later phases (blocking, AI, partner, ...). */
@Composable
fun ExtraSettings(app: App) {
    BlockingSettings(app)
    SettingsHooks.sections.forEach { it(app) }
}

object SettingsHooks { val sections = mutableListOf<@Composable (App) -> Unit>() }

// Filled in by later phases; defined here so the first-run wizard compiles at every step.
@Composable fun PartnerSetupContent(app: App) { Text("Available once cloud sync is set up (see SETUP.md).") }
@Composable fun AiSetupContent(app: App) { Text("Available in AI settings.") }
@Composable fun ChannelsSetupContent(app: App) { Text("Available once cloud sync is set up (see SETUP.md).") }
