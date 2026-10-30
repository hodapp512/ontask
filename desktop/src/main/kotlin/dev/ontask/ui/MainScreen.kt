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
    PLAN("Today", "✓"),
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
                    Dest.PLAN -> PlanScreen(app)
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
    InterventionSettings(app)
    AiSettings(app)
    SettingsHooks.sections.forEach { it(app) }
}

object SettingsHooks { val sections = mutableListOf<@Composable (App) -> Unit>() }

// Filled in by later phases; defined here so the first-run wizard compiles at every step.
@Composable fun PartnerSetupContent(app: App) { Text("Available once cloud sync is set up (see SETUP.md).") }
@Composable fun AiSetupContent(app: App) { AiSettingsBody(app) }
@Composable fun ChannelsSetupContent(app: App) { Text("Available once cloud sync is set up (see SETUP.md).") }

@Composable
fun InterventionSettings(app: App) {
    var enabled by remember { mutableStateOf(app.prefs.bool("ladder.enabled", true)) }
    var lock by remember { mutableStateOf(app.prefs.int("ladder.lock_sec", 30)) }
    var first by remember { mutableStateOf(app.prefs.int("ladder.step1_sec", 180) / 60) }
    SectionCard("Gentle nudges", "If you drift onto wasted time during work hours or a Focus session, OnTask steps in — gently first.") {
        SwitchSetting("Nudge me when I drift", "A prompt after a few minutes of wasted time, then a firmer window, then an offer to tell your partner.", enabled) { enabled = it; app.prefs.set("ladder.enabled", it) }
        SliderSetting("First nudge after", "Minutes of continuous wasted time before the first prompt.", first, 1..15, "minutes") { first = it; app.prefs.set("ladder.step1_sec", it * 60) }
        SliderSetting("Firm window can't be closed for", "The second-step window stays open this long before you can respond.", lock, 30..180, "seconds", step = 10) { lock = it; app.prefs.set("ladder.lock_sec", it) }
    }
}
