package dev.ontask

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
