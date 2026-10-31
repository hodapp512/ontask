package dev.ontask

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.ontask.ui.AppOverlays
import dev.ontask.ui.MainScreen
import dev.ontask.ui.Nav
import dev.ontask.ui.OnTaskTray

fun main() {
    // Black-and-alpha tray icon is drawn as a template image, so it adapts to light and dark menu bars.
    System.setProperty("apple.awt.enableTemplateImages", "true")
    System.setProperty("apple.awt.application.name", "OnTask")
    val app = App().also { it.start() }
    application {
        var visible by remember { mutableStateOf(true) }
        OnTaskTray(app) { dest -> Nav.open(dest); visible = true }
        AppOverlays(app)
        // The window is only composed while visible, so a hidden app holds no UI resources.
        if (visible) {
            Window(onCloseRequest = { visible = false }, title = "OnTask") {
                val raises by Nav.raises.collectAsState()
                LaunchedEffect(raises) { if (raises > 0) { window.toFront(); window.requestFocus() } }
                MainScreen(app)
            }
        }
    }
}
