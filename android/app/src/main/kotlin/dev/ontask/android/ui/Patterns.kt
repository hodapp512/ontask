package dev.ontask.android.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ontask.android.Cloud
import dev.ontask.report.Patterns
import dev.ontask.report.Patterns.Metric
import dev.ontask.report.Patterns.Range
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.LocalTime

private fun metricColor(m: Metric) = when (m) { Metric.FOCUSED -> Color(0xFF2E9E5B); Metric.WASTED -> Color(0xFFD64545); Metric.SCREEN -> Color(0xFF4A7BD0) }

/** Every merged day's timeline (Mac and phone), by date. */
private suspend fun loadDays(uid: String): Map<LocalDate, List<Patterns.Seg>> = runCatching {
    Cloud.db.collection("users/$uid/reports").get().await().documents.mapNotNull { d ->
        val date = runCatching { LocalDate.parse(d.id) }.getOrNull() ?: return@mapNotNull null
        val segs = (d.get("timeline") as? List<*>)?.mapNotNull { e ->
            val o = e as? Map<*, *> ?: return@mapNotNull null
            val k = (o["k"] as? String)?.firstOrNull() ?: return@mapNotNull null
            Patterns.Seg((o["a"] as? Number)?.toInt() ?: return@mapNotNull null, (o["b"] as? Number)?.toInt() ?: return@mapNotNull null, k)
        } ?: emptyList()
        date to segs
    }.toMap()
}.getOrDefault(emptyMap())

/**
 * "Your patterns", as on the Mac: Today / This week / This month / Since you started, and when in the day it happens
 * (30 min to 2 h bars; tap one for details). Choices are kept on this phone.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PatternsCard(uid: String, workRanges: List<Pair<Int, Int>>) {
    val prefs = LocalContext.current.getSharedPreferences("patterns", Context.MODE_PRIVATE)
    var metric by remember { mutableStateOf(runCatching { Metric.valueOf(prefs.getString("metric", "FOCUSED")!!) }.getOrDefault(Metric.FOCUSED)) }
    var range by remember { mutableStateOf(Range.saved(prefs.getString("range", null))) }
    var slot by remember { mutableStateOf(prefs.getInt("slot", Patterns.DEFAULT_SLOT).takeIf { it in Patterns.SLOTS && it >= 30 } ?: Patterns.DEFAULT_SLOT) }
    var days by remember { mutableStateOf<Map<LocalDate, List<Patterns.Seg>>?>(null) }
    LaunchedEffect(uid) { days = loadDays(uid) }
    val today = LocalDate.now()
    OCard {
        Text("Your patterns", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Metric.entries.forEach { m -> FilterChip(metric == m, { metric = m; prefs.edit().putString("metric", m.name).apply() }, label = { Text(m.label) }) }
        }
        val d = days
        if (d == null) { Muted("Loading…"); return@OCard }
        val first = d.keys.minOrNull()
        if (first == null) { Muted("Nothing yet: your days show here once they've synced."); return@OCard }
        Row(Modifier.fillMaxWidth()) {
            listOf(
                "Today" to Patterns.total(d, today, today, first, metric),
                "Last 7 days" to Patterns.total(d, Range.LAST_7.from(today), today, first, metric),
                "Last 30 days" to Patterns.total(d, Range.LAST_30.from(today), today, first, metric),
                "Since ${first.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}" to Patterns.total(d, first, today, first, metric),
            ).forEach { (label, t) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Patterns.fmt(t.sec), style = MaterialTheme.typography.titleMedium, color = metricColor(metric), fontWeight = FontWeight.SemiBold)
                    Text(label, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    if (label != "Today") Text("${Patterns.fmt(t.avgSec)} avg", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Range.entries.forEach { r -> FilterChip(range == r, { range = r; prefs.edit().putString("range", r.name).apply() }, label = { Text(r.label) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Patterns.SLOTS.filter { it >= 30 }.forEach { s -> FilterChip(slot == s, { slot = s; prefs.edit().putInt("slot", s).apply() }, label = { Text(if (s < 60) "$s min" else "${s / 60} hr") }) }
        }
        val now = LocalTime.now()
        val bars = remember(d, range, slot, metric) { Patterns.distribution(d, range.from(today), today, first, slot, metric, now.hour * 60 + now.minute) }
        val from = maxOf(range.from(today), first)
        val since = if (from > range.from(today)) "Since ${first.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))} (${java.time.temporal.ChronoUnit.DAYS.between(from, today) + 1} days). " else ""
        Chart(bars, slot, metric, workRanges, since, oneDay = range == Range.TODAY)
    }
}

@Composable
private fun Chart(bars: List<Patterns.Bar>, slot: Int, metric: Metric, work: List<Pair<Int, Int>>, since: String, oneDay: Boolean) {
    var picked by remember(bars) { mutableStateOf<Int?>(null) }
    val color = metricColor(metric)
    val shade = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    // The y-axis is fixed by the slot size (a slot can hold at most its own length), so switching range or metric
    // never rescales it: 15, 30, 60 or 120 minutes.
    val top = slot
    Muted(picked?.let { Patterns.describe(bars[it], slot, metric, oneDay) } ?: (since + (if (oneDay) "Minutes in each slot today." else "Average minutes per day.") + " Tap a bar for details."))
    Row {
        Box(Modifier.width(24.dp).height(140.dp)) {
            listOf(top, top / 2, 0).forEachIndexed { i, v ->
                Text("$v", Modifier.align(when (i) { 0 -> Alignment.TopEnd; 1 -> Alignment.CenterEnd; else -> Alignment.BottomEnd }),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(140.dp).pointerInput(bars.size) {
                detectTapGestures { p -> val i = (p.x / size.width * bars.size).toInt().coerceIn(0, bars.size - 1); picked = if (picked == i) null else i }
            }) {
                val w = size.width / bars.size
                work.forEach { (a, b) -> drawRect(shade, Offset(a / 1440f * size.width, 0f), Size((b - a) / 1440f * size.width, size.height)) }
                listOf(0f, 0.5f, 1f).forEach { f -> drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1f) }
                bars.forEachIndexed { i, b ->
                    val h = if (top == 0) 0f else (b.avgSec / 60 / top).toFloat().coerceAtMost(1f) * size.height
                    val gap = (w * 0.15f).coerceAtMost(3f)
                    drawRect(if (picked == null || picked == i) color else color.copy(alpha = 0.55f), Offset(i * w + gap / 2, size.height - h), Size(w - gap, h))
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
                (0..8).forEach { i ->
                    val min = i * 180
                    Text(Patterns.axis(min), Modifier.offset(x = (maxWidth * (min / 1440f) - 12.dp).coerceIn(0.dp, maxWidth - 24.dp)).width(24.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
