package dev.ontask.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ontask.App

@Composable
fun MainScreen(app: App) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Report", "Diagnostics", "Settings")
    MaterialTheme {
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> ReportScreen(app)
                1 -> DiagnosticsScreen(app)
                2 -> SettingsScreen(app)
            }
        }
    }
}
