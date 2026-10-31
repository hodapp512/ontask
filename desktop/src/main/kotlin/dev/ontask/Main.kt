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
    // Compose otherwise puts its own default icon in the Dock.
    runCatching { java.awt.Taskbar.getTaskbar().iconImage = javax.imageio.ImageIO.read(object {}.javaClass.getResource("/icon.png")) }
    val app = App()
    val login = dev.ontask.block.LoginItem()
    val packaged = login.appExecutable() != null
    // First run of the installed app: start at login and reopen after quitting, both on by default.
    if (packaged && app.prefs.get("startup.at_login") == null) { app.prefs.set("startup.at_login", true); app.prefs.set("startup.reopen", true) }
    val atLogin = app.prefs.bool("startup.at_login", false); val reopen = app.prefs.bool("startup.reopen", false)
    if (packaged) login.set(atLogin, keepAlive = reopen)
    // Opened by hand while the agent exists: let launchd run OnTask instead, so it can bring it back later.
    if (packaged && login.enabled && !login.managed) {
        app.prefs.set("startup.show_window", true)
        login.handOff()
        kotlin.system.exitProcess(0)
    }
    // One copy at a time (launchd may start one while another is still closing).
    val lock = runCatching { java.nio.channels.FileChannel.open(app.dir.resolve("instance.lock"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).tryLock() }.getOrNull()
    if (lock == null) kotlin.system.exitProcess(0)
    // Started by launchd at login or after a quit: stay in the menu bar unless the user just opened it.
    val showWindow = !login.managed || app.prefs.bool("startup.show_window", false)
    app.prefs.set("startup.show_window", null)
    app.start()
    // However OnTask is quit (menu bar, Cmd-Q, Dock), a non-zero exit code makes launchd bring it back when "reopen" is on.
    fun exitCode() = if (login.managed && app.prefs.bool("startup.reopen", false)) dev.ontask.block.LoginItem.QUIT_REOPEN else 0
    runCatching {
        java.awt.Desktop.getDesktop().setQuitHandler { _, response ->
            app.shutdown(); response.cancelQuit(); kotlin.system.exitProcess(exitCode())
        }
    }
    application {
        var visible by remember { mutableStateOf(showWindow) }
        OnTaskTray(app) { dest -> Nav.open(dest); visible = true }
        // Opening OnTask again (Finder, Dock, Spotlight) while it runs in the menu bar shows the window.
        LaunchedEffect(Unit) {
            runCatching { java.awt.Desktop.getDesktop().addAppEventListener(java.awt.desktop.AppReopenedListener { Nav.open(null); visible = true }) }
        }
        AppOverlays(app)
        // The window is only composed while visible, so a hidden app holds no UI resources.
        if (visible) {
            Window(onCloseRequest = { visible = false }, title = "OnTask", icon = androidx.compose.ui.res.painterResource("icon.png")) {
                val raises by Nav.raises.collectAsState()
                LaunchedEffect(raises) { if (raises > 0) { window.toFront(); window.requestFocus() } }
                MainScreen(app)
            }
        }
    }
    app.shutdown()
    kotlin.system.exitProcess(exitCode())
}
