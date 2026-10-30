package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.ontask.Level
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun levelColor(l: Level): Color = when (l) {
    Level.DEEP -> Color(0xFF2E9E5B)
    Level.MIXED -> Color(0xFFE0A526)
    Level.WASTED -> Color(0xFFD64545)
    Level.AWAY -> Color(0xFF9AA5B1)
}

fun fmtDuration(sec: Long): String {
    val h = sec / 3600; val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m" else "${sec}s"
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
fun fmtTime(epochSec: Long): String = timeFmt.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

@Composable
fun <T> Picker(label: String, options: List<T>, display: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) { Text("$label ▾") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        options.forEach { o -> DropdownMenuItem(text = { Text(display(o)) }, onClick = { open = false; onPick(o) }) }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(k, Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium)
        Text(v, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}

