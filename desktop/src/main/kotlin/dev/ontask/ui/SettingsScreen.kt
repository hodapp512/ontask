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
import dev.ontask.App

/** Settings that apply immediately: each change is saved as it is made. */
@Composable
fun SettingsScreen(app: App, theme: ThemeMode, onTheme: (ThemeMode) -> Unit) {
    var s by remember { mutableStateOf(app.settings) }
    fun update(n: dev.ontask.db.Settings) { s = n; app.updateSettings(n) }

    Page("Settings", "Changes are saved as you make them.") {
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
        SectionCard("Your data") {
            SliderSetting(
                "Keep detailed history", "Keep detailed history for this many days. Older data is kept forever as hourly summaries.",
                s.retentionDays, 7..365, "days", step = 1,
            ) { update(s.copy(retentionDays = it)) }
        }
        ExtraSettings(app)
        Expander("Advanced") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                KV("Data folder", app.dir.toString())
                KV("Helper", app.helper.status)
            }
        }
    }
}
