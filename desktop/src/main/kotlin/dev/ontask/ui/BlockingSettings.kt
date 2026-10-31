package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ontask.App

@Composable
fun BlockingSettings(app: App) {
    var message by remember { mutableStateOf(app.prefs.str("unblock.message", "Is this really what you want to be doing right now?")) }
    var random by remember { mutableStateOf(app.prefs.bool("unblock.random", false)) }
    var alts by remember { mutableStateOf(app.prefs.str("unblock.alternatives", "")) }
    var delays by remember { mutableStateOf(app.blocking.unblocks.delays.let { d -> (0..3).map { d.getOrElse(it) { d.last() } } }) }
    var reset by remember { mutableStateOf(app.prefs.int("unblock.reset_hour", 4)) }

    SectionCard("Unblocking") {
        SettingRow("Message on the delay screen", "What you see while you wait. Write something that will actually stop you.") {
            OutlinedTextField(message, { message = it; app.prefs.set("unblock.message", it) }, singleLine = true, modifier = Modifier.fillMaxWidth(0.5f))
        }
        SwitchSetting("Vary the message", "Show a random alternative each time instead of always the same one (add alternatives under Advanced).", random) { random = it; app.prefs.set("unblock.random", it) }
        Expander("Advanced: delays") {
            val labels = listOf("1st", "2nd", "3rd", "4th and later")
            labels.forEachIndexed { i, l ->
                SliderSetting("Delay before the $l unblock of the day", "Seconds you must wait on the delay screen.", delays[i], 5..600, "seconds", step = 5, format = { fmtDuration(it.toLong()) }) {
                    delays = delays.toMutableList().also { d -> d[i] = it }; app.prefs.set("unblock.delays", delays.joinToString(","))
                }
            }
            SliderSetting("Day resets at", "The unblock counter starts again at this hour.", reset, 0..8, "", format = { "%02d:00".format(it) }) { reset = it; app.prefs.set("unblock.reset_hour", it) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Alternative messages (one per line)", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                OutlinedTextField(alts, { alts = it; app.prefs.set("unblock.alternatives", it) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
