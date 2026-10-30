package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ontask.App
import dev.ontask.classify.TimeRange
import dev.ontask.classify.WorkWeek
import dev.ontask.db.Settings
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

private fun parseHm(s: String): Int? {
    val p = s.trim().split(":"); if (p.size != 2) return null
    val h = p[0].toIntOrNull() ?: return null; val m = p[1].toIntOrNull() ?: return null
    return if (h in 0..24 && m in 0..59) h * 60 + m else null
}
private fun fmtHm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

private class DayEdit(enabled: Boolean, start: String, end: String) {
    var enabled by mutableStateOf(enabled); var start by mutableStateOf(start); var end by mutableStateOf(end)
}

@Composable
fun SettingsScreen(app: App) {
    val cur = app.settings
    var tick by remember { mutableStateOf(cur.tickSec.toString()) }
    var away by remember { mutableStateOf(cur.awayAfterSec.toString()) }
    var retention by remember { mutableStateOf(cur.retentionDays.toString()) }
    val days = remember {
        DayOfWeek.entries.associateWith { d ->
            val r = cur.workWeek.days[d.value]?.firstOrNull()
            DayEdit(r != null, fmtHm(r?.startMin ?: 9 * 60), fmtHm(r?.endMin ?: 17 * 60))
        }
    }
    var msg by remember { mutableStateOf("") }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Section("Tracking") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(tick, { tick = it }, label = { Text("Tick interval (s)") }, singleLine = true, modifier = Modifier.width(160.dp))
                OutlinedTextField(away, { away = it }, label = { Text("Away after idle (s)") }, singleLine = true, modifier = Modifier.width(160.dp))
                OutlinedTextField(retention, { retention = it }, label = { Text("Raw retention (days)") }, singleLine = true, modifier = Modifier.width(160.dp))
            }
        }
        Section("Work hours (weekly template; calendar UI comes in phase 2)") {
            days.forEach { (d, e) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(e.enabled, { e.enabled = it })
                    Text(d.getDisplayName(TextStyle.FULL, Locale.getDefault()), Modifier.width(100.dp))
                    OutlinedTextField(e.start, { e.start = it }, singleLine = true, enabled = e.enabled, modifier = Modifier.width(100.dp))
                    Text("–")
                    OutlinedTextField(e.end, { e.end = it }, singleLine = true, enabled = e.enabled, modifier = Modifier.width(100.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({
                val map = HashMap<Int, List<TimeRange>>()
                var bad = false
                days.forEach { (d, e) ->
                    if (e.enabled) {
                        val s = parseHm(e.start); val en = parseHm(e.end)
                        if (s == null || en == null || en <= s) bad = true else map[d.value] = listOf(TimeRange(s, en))
                    }
                }
                val t = tick.toIntOrNull(); val a = away.toIntOrNull(); val r = retention.toIntOrNull()
                if (bad || t == null || a == null || r == null) msg = "Check the values (times are HH:mm, end after start)."
                else {
                    app.updateSettings(Settings(t.coerceIn(1, 60), a.coerceIn(30, 3600), r.coerceIn(1, 3650), WorkWeek(map)))
                    msg = "Saved. Work-hour changes apply to new ticks."
                }
            }) { Text("Save") }
            Text(msg, style = MaterialTheme.typography.bodySmall)
        }
    }
}
