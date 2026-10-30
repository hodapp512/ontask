package dev.ontask

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.ontask.ui.AppOverlays
import dev.ontask.ui.MainScreen
import dev.ontask.ui.fmtDuration
import java.awt.RenderingHints
import java.awt.image.BufferedImage

private fun trayIcon(): BitmapPainter {
    val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = java.awt.Color(0x2E, 0x9E, 0x5B); g.fillOval(2, 2, 28, 28)
    g.color = java.awt.Color.WHITE; g.fillOval(10, 10, 12, 12)
    g.dispose()
    return BitmapPainter(img.toComposeImageBitmap())
}

fun main() {
    val app = App().also { it.start() }
    application {
        var visible by remember { mutableStateOf(true) }
        val live by app.tracker.live.collectAsState()
        val deep = live.totals[Level.DEEP] ?: 0
        val wasted = live.totals[Level.WASTED] ?: 0

        Tray(
            icon = remember { trayIcon() },
            tooltip = "OnTask — deep ${fmtDuration(deep)}, wasted ${fmtDuration(wasted)}",
            onAction = { visible = true },
            menu = {
                Item("Open OnTask", onClick = { visible = true })
                Separator()
                Item("Quit", onClick = { app.shutdown(); exitApplication() })
            },
        )
        AppOverlays(app)
        // The window is only composed while visible, so a hidden app holds no UI resources.
        if (visible) {
            Window(onCloseRequest = { visible = false }, title = "OnTask") { MainScreen(app) }
        }
    }
}
