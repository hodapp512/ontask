package dev.ontask

import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp

import dev.ontask.ui.DiagnosticsScreen
import dev.ontask.ui.MainScreen
import dev.ontask.ui.Dest
import kotlin.test.Test

class UiRenderTest {
    @Test fun renderMainScreens() {
        val app = UiShots.newApp()
        app.prefs.set("setup.done", true)
        for (dark in listOf(false, true)) {
            app.prefs.set("theme", if (dark) "DARK" else "LIGHT")
            for (d in Dest.entries) UiShots.shot(d.name.lowercase(), dark, 1100, 1500) { MainScreen(app, d) }
        }
    }

    @Test fun renderSettingsWithAi() {
        val app = UiShots.newApp(); app.prefs.set("setup.done", true); app.ai.enabled = true
        UiShots.shot("settings-ai", false, 1100, 2400) { MainScreen(app, Dest.SETTINGS) }
    }

    @Test fun renderTrayGlyph() {
        UiShots.shot("tray-glyph", false, 88, 88) {
            androidx.compose.foundation.Image(dev.ontask.ui.TrayGlyph(), null, androidx.compose.ui.Modifier.size(88.dp))
        }
    }

    @Test fun renderMiddayPrompt() {
        val app = UiShots.newApp()
        app.priorities.set(java.time.LocalDate.now(), listOf("Finish the proposal\nReview PRs"))
        UiShots.shot("midday", false, 700, 1500) { dev.ontask.ui.PlanPromptContent(app, dev.ontask.plan.Prompt.MIDDAY, {}, {}, laterLeft = 2) }
    }

    @Test fun renderTodayHours() {
        val app = UiShots.newApp()
        UiShots.shot("today-hours", false, 560, 532) {
            androidx.compose.material3.Surface { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(20.dp)) { dev.ontask.ui.HoursCheckCard(app, java.time.LocalDate.now(), startEditing = true, onClose = {}) } }
        }
    }

    @Test fun renderEveningPrompt() {
        val app = UiShots.newApp()
        UiShots.shot("evening", false, 700, 1300) { dev.ontask.ui.PlanPromptContent(app, dev.ontask.plan.Prompt.EVENING, {}, {}) }
        UiShots.shot("planning", true, 700, 700) { dev.ontask.ui.PlanPromptContent(app, dev.ontask.plan.Prompt.PLANNING, {}, {}) }
    }

    @Test fun renderWizard() {
        val app = UiShots.newApp()
        for (dark in listOf(false)) UiShots.shot("wizard", dark, 1100, 900) { dev.ontask.ui.OnTaskTheme(dev.ontask.ui.ThemeMode.LIGHT) { dev.ontask.ui.SetupWizard(app) {} } }
    }
}
