package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import dev.ontask.App

/** Settings that apply immediately: each change is saved as it is made. */
@Composable
fun SettingsScreen(app: App, theme: ThemeMode, onTheme: (ThemeMode) -> Unit, open: (Dest) -> Unit) {
    var s by remember { mutableStateOf(app.settings) }
    fun update(n: dev.ontask.db.Settings) { s = n; app.updateSettings(n) }

    Page("Settings") {
        UninstallButton(app)
        SectionCard("Work hours", "When unblocking takes more effort.") {
            val hours = remember { s.schedule.week.days.values.sumOf { r -> r.sumOf { it.endMin - it.startMin } } / 60.0 }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("%.1f hours a week".format(hours), Modifier.weight(1f))
                androidx.compose.material3.Button({ open(Dest.WORK_HOURS) }) { Text("Edit work hours") }
            }
        }
        RhythmSettings(app)
        SectionCard("Block list", "Blocked around the clock.") {
            val n = remember { app.blocking.blocklist.all().size }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("$n site${if (n == 1) "" else "s"}" + if (app.adBlock.enabled) ", plus ads and malware" else "", Modifier.weight(1f))
                androidx.compose.material3.Button({ open(Dest.BLOCK_LIST) }) { Text("Edit block list") }
            }
        }
        CategoriesSettings(app)
        StartupSettings(app)
        SectionCard("Appearance") {
            SettingRow("Theme", "Light, dark, or follow your Mac's appearance setting.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m -> FilterChip(theme == m, { onTheme(m) }, label = { Text(m.label) }) }
                }
            }
        }
        SectionCard("Tracking") {
            SliderSetting(
                "Check interval", "How often OnTask checks what you're doing. Shorter is more precise, longer uses less battery.",
                s.tickSec, 2..30, "seconds", step = 1,
            ) { update(s.copy(tickSec = it)) }
            SliderSetting(
                "Away after idle", "Mark me as away after this many minutes without keyboard or mouse input (unless a video is playing).",
                s.awayAfterSec / 60, 1..30, "minutes",
            ) { update(s.copy(awayAfterSec = it * 60)) }
        }
        PermissionsSettings(app)
        ExtraSettings(app)
        Expander("Advanced") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                KV("Data folder", app.dir.toString())
                KV("Activity reader (ontask-helper)", if (app.helper.status == "running") "Running ✓" else app.helper.status)
            }
        }
    }
}

/** Start at login and reopen after quitting: both on by default, kept running by a launchd agent. */
@Composable
private fun StartupSettings(app: App) {
    val login = remember { dev.ontask.block.LoginItem() }
    var atLogin by remember { mutableStateOf(app.prefs.bool("startup.at_login", false)) }
    var reopen by remember { mutableStateOf(app.prefs.bool("startup.reopen", false)) }
    val packaged = login.appExecutable() != null
    fun apply() { app.prefs.set("startup.at_login", atLogin); app.prefs.set("startup.reopen", reopen); if (packaged) login.set(atLogin, keepAlive = reopen) }
    SectionCard("Startup", if (packaged) null else "Available in the installed app (not when running from Gradle).") {
        SwitchSetting("Start when you log in", "OnTask opens in the menu bar when you log in to your Mac.", atLogin) { atLogin = it; apply() }
        SwitchSetting("Reopen if it's quit or crashes", "OnTask comes back a few seconds after it closes. Turn this off to be able to quit it for good.", reopen) { reopen = it; apply() }
        if (packaged && !login.managed && (atLogin || reopen)) Text("Takes effect the next time OnTask opens.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    }
}

/** Midday and evening check-ins; the evening one comes a set time before work hours end. */
@Composable
private fun RhythmSettings(app: App) {
    var midday by remember { mutableStateOf(app.prefs.bool("rhythm.midday", true)) }
    var evening by remember { mutableStateOf(app.prefs.bool("rhythm.evening_at_end", true)) }
    var lead by remember { mutableStateOf(app.prefs.int("rhythm.evening_lead_min", 30)) }
    var bedOn by remember { mutableStateOf(app.bedtimeEnabled) }
    var bed by remember { mutableStateOf(app.bedtimeMin) }
    SectionCard("Daily rhythm") {
        SwitchSetting("Midday check-in", "Halfway through your working time.", midday) { midday = it; app.prefs.set("rhythm.midday", it) }
        SwitchSetting("Evening check-in", "How the day felt, before your daily report goes out.", evening) { evening = it; app.prefs.set("rhythm.evening_at_end", it) }
        if (evening) SettingRow("Evening check-in comes", "Before your work hours end, so you do it while you're still at the desk.") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 45, 60).forEach { m -> FilterChip(lead == m, { lead = m; app.prefs.set("rhythm.evening_lead_min", m) }, label = { Text("$m min before") }) }
            }
        }
        SwitchSetting("Bedtime reminder", "At bedtime, a reminder to put the screen away; more of them every 15 minutes while you're still at it.", bedOn) { bedOn = it; app.prefs.set("bedtime.enabled", it) }
        if (bedOn) {
            // 8:00 PM to 2:00 AM in 15-minute steps (values past midnight wrap).
            val fmt = java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
            val steps = (80..104).map { (it * 15) % 1440 }
            SliderSetting("Bedtime", "", steps.indexOf(bed).coerceAtLeast(0), steps.indices, "", format = { java.time.LocalTime.of(steps[it] / 60, steps[it] % 60).format(fmt) }) {
                bed = steps[it]; app.prefs.set("bedtime.min", bed)
            }
        }
    }
}
