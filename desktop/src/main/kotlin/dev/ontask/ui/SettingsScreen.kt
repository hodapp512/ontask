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

    Page("Settings", "Changes are saved as you make them.") {
        SectionCard("Work hours", "Inside your work hours, distracting sites are blocked and OnTask watches more closely. Outside them, anything goes.") {
            val hours = remember { s.schedule.week.days.values.sumOf { r -> r.sumOf { it.endMin - it.startMin } } / 60.0 }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("%.1f hours a week".format(hours), Modifier.weight(1f))
                androidx.compose.material3.Button({ open(Dest.WORK_HOURS) }) { Text("Edit work hours") }
            }
        }
        SectionCard("Block list", "The sites blocked during work hours and Focus sessions.") {
            val n = remember { app.blocking.blocklist.all().size }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("$n site${if (n == 1) "" else "s"}" + if (app.adBlock.enabled) ", plus ads and malware" else "", Modifier.weight(1f))
                androidx.compose.material3.Button({ open(Dest.BLOCK_LIST) }) { Text("Edit block list") }
            }
        }
        SectionCard("Appearance") {
            SettingRow("Theme", "Light, dark, or follow your Mac's appearance setting.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m -> FilterChip(theme == m, { onTheme(m) }, label = { Text(m.label) }) }
                }
            }
        }
        SectionCard("Tracking", "How closely OnTask watches what's on your screen.") {
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
