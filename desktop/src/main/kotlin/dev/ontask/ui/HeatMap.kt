package dev.ontask.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.ontask.App
import dev.ontask.Level
import dev.ontask.report.DayStat
import dev.ontask.report.Heat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GAP = 3.dp
private val LABEL_W = 30.dp

/** The five shades of the Focused green (0 = no computer: the track color). */
@Composable
fun heatColor(level: Int): Color {
    val empty = MaterialTheme.colorScheme.surfaceVariant
    if (level <= 0) return empty
    return levelColor(Level.DEEP).copy(alpha = listOf(0.25f, 0.45f, 0.65f, 0.85f, 1f)[level - 1]).compositeOver(MaterialTheme.colorScheme.surface)
}

/**
 * Focus over the last 52 weeks, one square per day (weeks as columns, Sunday on top), shaded by effective focus
 * ([DayStat.effectiveSec]). Hover for the day's numbers; click to open that day on Home.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HeatMapCard(app: App, today: LocalDate, version: Int, onPick: (LocalDate) -> Unit) {
    val first = remember(today) { Heat.firstDay(today) }
    var days by remember { mutableStateOf<Map<LocalDate, DayStat>>(emptyMap()) }
    // This Mac's days plus the phone's.
    LaunchedEffect(today, version) {
        days = withContext(Dispatchers.IO) {
            val other = if (app.cloud.signedIn) app.cloud.otherHeat() else emptyMap()
            val mine = app.reports.days(first, today)
            (mine.keys + other.keys).associateWith { d ->
                val a = mine[d]; val b = other[d]
                if (a == null) b!! else if (b == null) a else DayStat(a.deep + b.deep, a.active + b.active, a.workDeep + b.workDeep, a.workActive + b.workActive, a.offWasted + b.offWasted, a.offActive + b.offActive)
            }
        }
    }
    val streak = remember(days) { Heat.streak(days, today, { d -> app.settings.schedule.rangesFor(d).isNotEmpty() }) }
    val total = days.values.sumOf { it.deep }
    var hover by remember { mutableStateOf<Pair<LocalDate, Offset>?>(null) }
    val density = LocalDensity.current
    val weeks = 52
    val shades = (0..5).map { heatColor(it) }

    PlainCard { androidx.compose.foundation.layout.BoxWithConstraints { val avail = maxWidth; Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Squares grow to fill the card's width (10–18 dp), so the year spans it like on GitHub.
        val CELL = ((avail - LABEL_W) / weeks - GAP).coerceIn(10.dp, 18.dp)
        val cellPx = with(density) { CELL.toPx() }; val stepPx = with(density) { (CELL + GAP).toPx() }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Focus over the year", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "${total / 3600}h focused in the last year" + if (streak > 0) " · $streak-day streak" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            // Mon / Wed / Fri on the left, level with their rows (below the month labels).
            Column(Modifier.width(LABEL_W).padding(top = 16.dp)) {
                (0..6).forEach { i ->
                    Box(Modifier.height(CELL + GAP)) {
                        if (i in listOf(1, 3, 5)) Text(listOf("", "Mon", "", "Wed", "", "Fri", "")[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column {
                // Month labels over the first week that starts in each month.
                Box(Modifier.height(16.dp).width((CELL + GAP) * weeks)) {
                    var last = -1
                    (0 until weeks).forEach { w ->
                        val d = first.plusWeeks(w.toLong())
                        if (d.monthValue != last) {
                            last = d.monthValue
                            if (w < weeks - 1) Text(d.format(DateTimeFormatter.ofPattern("MMM", Locale.US)), Modifier.padding(start = (CELL + GAP) * w),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Box {
                    fun dayAt(p: Offset): LocalDate? {
                        val w = (p.x / stepPx).toInt(); val r = (p.y / stepPx).toInt()
                        if (w !in 0 until weeks || r !in 0..6) return null
                        return first.plusDays(w * 7L + r).takeIf { !it.isAfter(today) }
                    }
                    Canvas(
                        Modifier.width((CELL + GAP) * weeks).height((CELL + GAP) * 7)
                            .onPointerEvent(PointerEventType.Move) { e -> val p = e.changes.first().position; hover = dayAt(p)?.let { it to p } }
                            .onPointerEvent(PointerEventType.Exit) { hover = null }
                            .onPointerEvent(PointerEventType.Release) { e -> dayAt(e.changes.first().position)?.let { hover = null; onPick(it) } },
                    ) {
                        for (w in 0 until weeks) for (r in 0..6) {
                            val d = first.plusDays(w * 7L + r)
                            if (d.isAfter(today)) continue
                            drawRoundRect(shades[Heat.level(days[d])], Offset(w * stepPx, r * stepPx), Size(cellPx, cellPx), CornerRadius(2.5f * density.density))
                        }
                    }
                    hover?.let { (d, p) ->
                        val s = days[d]
                        Popup(offset = IntOffset((p.x - 110).toInt().coerceAtLeast(0), (p.y - 56).toInt())) {
                            Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
                                Text(
                                    fmtDate(d) + " · " + if (s == null || s.active <= 0) "no computer" else "${fmtDuration(s.deep)} focused (${s.pct}%)",
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(start = LABEL_W)) {
            Text("Less ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            shades.forEach { c -> Box(Modifier.size(CELL).background(c, RoundedCornerShape(2.5.dp))) }
            Text(" More", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } } }
}
