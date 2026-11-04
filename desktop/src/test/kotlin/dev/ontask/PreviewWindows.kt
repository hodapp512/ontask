package dev.ontask

import androidx.compose.ui.window.application
import dev.ontask.intervene.Intervention
import dev.ontask.intervene.Step
import dev.ontask.ui.InterventionWindow
import dev.ontask.ui.ThemeMode
import java.time.LocalDate

/** Opens real prompt windows on screen (not headless), prints their sizes, and quits after a few seconds. */
fun main(args: Array<String>) {
    val app = UiShots.newApp()
    listOf("Worker's comp", "Apply to jobs", "Interview skills").forEach { app.priorities.add(LocalDate.now(), it) }
    val iv = when (args.firstOrNull()) {
        "reminder" -> Intervention(Step.PROMPT, 300, 0, reminder = true, site = "youtube.com", lunch = true)
        "break" -> Intervention(Step.PROMPT, 60, 0, breakSec = 300, focusedSec = 2400, lunch = true)
        else -> Intervention(Step.PROMPT, 120, 0, focusedSec = 720, toEarnSec = 780, lunch = true, site = "youtube.com")
    }
    Thread {
        Thread.sleep(3500)
        java.awt.Window.getWindows().filter { it.isShowing }.forEach { println("WINDOW ${(it as? java.awt.Frame)?.title} bounds=${it.bounds}") }
        Runtime.getRuntime().exec(arrayOf("screencapture", "-x", System.getProperty("java.io.tmpdir") + "/ontask-prompt-${args.firstOrNull() ?: "step1"}.png")).waitFor()
        Thread.sleep(500); kotlin.system.exitProcess(0)
    }.start()
    application { InterventionWindow(app, iv, ThemeMode.LIGHT) }
}
