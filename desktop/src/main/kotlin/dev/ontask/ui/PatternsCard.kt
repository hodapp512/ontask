package dev.ontask.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ontask.App
import dev.ontask.Level
import dev.ontask.report.Patterns
import dev.ontask.report.Patterns.Metric
import dev.ontask.report.Patterns.Range
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

private val ScreenBlue = Color(0xFF4A7BD0)

fun metricColor(m: Metric): Color = when (m) { Metric.FOCUSED -> levelColor(Level.DEEP); Metric.WASTED -> levelColor(Level.WASTED); Metric.SCREEN -> ScreenBlue }

/**
 * "Your patterns": Today / This week / This month / Since you started for Focused, Wasted or Screen time, and when in
 * the day it happens, averaged over a range (bars of 15 min to 2 h). Both devices' merged days. Choices are kept.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PatternsCard(app: App, today: LocalDate, version: Int, sample: Map<LocalDate, List<Patterns.Seg>>? = null) {
    var metric by remember { mutableStateOf(runCatching { Metric.valueOf(app.prefs.str("patterns.metric", "FOCUSED")) }.getOrDefault(Metric.FOCUSED)) }
    var range by remember { mutableStateOf(runCatching { Range.valueOf(app.prefs.str("patterns.range", "LAST_30")) }.getOrDefault(Range.LAST_30)) }
    var slot by remember { mutableStateOf(app.prefs.int("patterns.slot", Patterns.DEFAULT_SLOT).takeIf { it in Patterns.SLOTS } ?: Patterns.DEFAULT_SLOT) }
    var days by remember { mutableStateOf<Map<LocalDate, List<Patterns.Seg>>?>(null) }
    LaunchedEffect(today, version) { days = sample ?: withContext(Dispatchers.IO) { app.patternDays() } }
    val d = days
    PlainCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your patterns", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Metric.entries.forEach { m ->
                FilterChip(metric == m, { metric = m; app.prefs.set("patterns.metric", m.name) }, label = { Text(m.label) }, modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (d == null) { Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant); return@PlainCard }
        val first = d.keys.minOrNull()
        if (first == null) { Text("Nothing yet: your days show here once they've synced.", color = MaterialTheme.colorScheme.onSurfaceVariant); return@PlainCard }
        // The four numbers.
        Row(Modifier.fillMaxWidth()) {
            val monday = Range.THIS_WEEK.from(today)
            listOf(
                "Today" to Patterns.total(d, today, today, first, metric),
                "This week" to Patterns.total(d, monday, today, first, metric),
                "This month" to Patterns.total(d, today.withDayOfMonth(1), today, first, metric),
                "Since ${first.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}" to Patterns.total(d, first, today, first, metric),
            ).forEach { (label, t) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Patterns.fmt(t.sec), style = MaterialTheme.typography.headlineSmall, color = metricColor(metric), fontWeight = FontWeight.SemiBold)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    if (label != "Today") Text("${Patterns.fmt(t.avgSec)} avg", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // The chart's controls: range on the left, slot size on the right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Range.entries.forEach { r -> FilterChip(range == r, { range = r; app.prefs.set("patterns.range", r.name) }, label = { Text(r.label) }) }
            }
            Patterns.SLOTS.forEach { s ->
                FilterChip(slot == s, { slot = s; app.prefs.set("patterns.slot", s) }, label = { Text(if (s < 60) "$s min" else "${s / 60} hr") }, modifier = Modifier.padding(start = 6.dp))
            }
        }
        val now = LocalTime.now(); val nowMin = now.hour * 60 + now.minute
        val bars = remember(d, range, slot, metric, today) { Patterns.distribution(d, range.from(today), today, first, slot, metric, nowMin) }
        val from = maxOf(range.from(today), first)
        val work = remember(today) { (app.settings.schedule.rangesFor(today).ifEmpty { app.settings.schedule.rangesFor(today.with(java.time.DayOfWeek.MONDAY)) }).map { it.startMin to it.endMin } }
        val since = if (from > range.from(today)) "Since ${first.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))} (${java.time.temporal.ChronoUnit.DAYS.between(from, today) + 1} days). " else ""
        DistributionChart(bars, slot, metric, work, since)
    }
}

/** The bars over the whole day, work hours shaded; hovering one says what it is. */
@Composable
private fun DistributionChart(bars: List<Patterns.Bar>, slot: Int, metric: Metric, work: List<Pair<Int, Int>>, since: String) {
    var hover by remember { mutableStateOf<Int?>(null) }
    val color = metricColor(metric)
    val shade = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val maxMin = (bars.maxOfOrNull { it.avgSec } ?: 0.0) / 60
    // A tidy top for the y-axis: 5, 10, 15, 30, 60…
    val top = listOf(10, 20, 30, 40, 60, 90, 120).firstOrNull { it >= maxMin } ?: (Math.ceil(maxMin / 60) * 60).toInt()
    Text(hover?.let { Patterns.describe(bars[it], slot, metric) } ?: (since + "Average minutes per day. Hover a bar for details."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row {
        // y-axis: minutes per day.
        Box(Modifier.width(28.dp).height(160.dp)) {
            listOf(top, top / 2, 0).forEachIndexed { i, v ->
                Text("$v", Modifier.align(when (i) { 0 -> Alignment.TopEnd; 1 -> Alignment.CenterEnd; else -> Alignment.BottomEnd }).padding(end = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(160.dp).pointerInput(bars.size) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        hover = if (e.type == PointerEventType.Exit) null else e.changes.firstOrNull()?.position?.x?.let { x -> (x / size.width * bars.size).toInt().coerceIn(0, bars.size - 1) }
                    }
                }
            }) {
                val w = size.width / bars.size
                work.forEach { (a, b) -> drawRect(shade, Offset(a / 1440f * size.width, 0f), Size((b - a) / 1440f * size.width, size.height)) }
                listOf(0f, 0.5f, 1f).forEach { f -> drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1f) }
                bars.forEachIndexed { i, b ->
                    val h = if (top == 0) 0f else (b.avgSec / 60 / top).toFloat().coerceAtMost(1f) * size.height
                    val gap = (w * 0.15f).coerceAtMost(3f)
                    drawRect(if (hover == null || hover == i) color else color.copy(alpha = 0.55f), Offset(i * w + gap / 2, size.height - h), Size(w - gap, h))
                }
            }
            // x-axis: every 3 hours.
            Box(Modifier.fillMaxWidth().height(16.dp)) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    (0..8).forEach { i ->
                        val min = i * 180
                        Text(Patterns.axis(min), Modifier.offset(x = (maxWidth * (min / 1440f) - 14.dp).coerceIn(0.dp, maxWidth - 28.dp)).width(28.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
