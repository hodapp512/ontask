package dev.ontask

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.ontask.ui.OnTaskTheme
import dev.ontask.ui.SummaryCard
import dev.ontask.ui.ThemeMode
import java.time.LocalDate

/** Home's day card with sample data, to try the timeline's hover on screen. Quits after [args][0] seconds (default 120). */
fun main(args: Array<String>) {
    // "real" (third argument): a copy of your own data (never the original; no server), else sample data.
    val app = if (args.getOrNull(2) == "real") {
        val tmp = java.nio.file.Files.createTempDirectory("ontask-real")
        val src = java.nio.file.Path.of(System.getProperty("user.home"), "Library", "Application Support", "OnTask")
        listOf("ontask.db", "ontask.db-wal", "ontask.db-shm").forEach { n -> src.resolve(n).takeIf { java.nio.file.Files.exists(it) }?.let { java.nio.file.Files.copy(it, tmp.resolve(n)) } }
        System.setProperty("ontask.helper", "/nonexistent")
        App(tmp)
    } else UiShots.newApp()
    app.prefs.set("setup.done", true)
    val report = app.reports.daily(LocalDate.now())
    Thread { Thread.sleep((args.firstOrNull()?.toLongOrNull() ?: 120) * 1000); kotlin.system.exitProcess(0) }.start()
    application {
        Window(onCloseRequest = ::exitApplication, title = "Timeline preview", state = rememberWindowState(size = DpSize(1100.dp, 820.dp), position = WindowPosition(80.dp, 120.dp))) {
            if (args.getOrNull(1) == "main") dev.ontask.ui.MainScreen(app)
            else OnTaskTheme(ThemeMode.LIGHT) { Surface { Column(Modifier.padding(16.dp)) { SummaryCard(report, "Today so far", app) } } }
        }
    }
}
