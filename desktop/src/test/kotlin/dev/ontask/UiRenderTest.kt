package dev.ontask

import dev.ontask.ui.DiagnosticsScreen
import dev.ontask.ui.MainScreen
import dev.ontask.ui.Dest
import kotlin.test.Test

class UiRenderTest {
    @Test fun renderMainScreens() {
        val app = UiShots.newApp()
        for (dark in listOf(false, true)) {
            app.prefs.set("theme", if (dark) "DARK" else "LIGHT")
            for (d in Dest.entries) UiShots.shot(d.name.lowercase(), dark, 1100, 1500) { MainScreen(app, d) }
        }
    }
}
