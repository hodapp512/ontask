package dev.ontask.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.ontask.android.Lvl
import dev.ontask.android.fmtDate
import dev.ontask.android.fmtDuration
import dev.ontask.android.num
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Shade 0 (no computer) to 5 from effective focus: under 1h, then 1.5-hour steps (as on the Mac). */
fun heatLevel(e: Long?): Int = when { e == null -> 0; e < 3600 -> 1; e < 9000 -> 2; e < 14400 -> 3; e < 19800 -> 4; else -> 5 }

/** Focus over the last 52 weeks from users/{uid}/heat/year. Tap a square for that day; [onPick] opens it. */
@Suppress("UNCHECKED_CAST")
@Composable
fun HeatCard(doc: Map<String, Any?>?, onPick: ((LocalDate) -> Unit)? = null) {
    if (doc == null) return
    val days = (doc["days"] as? Map<String, Map<String, Any?>>) ?: emptyMap()
    val today = LocalDate.now()
    val first = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).minusWeeks(51)
    var picked by remember { mutableStateOf<String?>(null) }
    val empty = MaterialTheme.colorScheme.surfaceVariant; val surface = MaterialTheme.colorScheme.surface
    val shades = listOf(empty) + listOf(0.25f, 0.45f, 0.65f, 0.85f, 1f).map { Lvl.DEEP.color.copy(alpha = it).compositeOver(surface) }
    val total = doc["totalSec"].num() / 3600; val streak = doc["streak"].num()
    OCard("Focus over the year") {
        Muted("${total}h focused · " + if (streak > 0) "$streak-day streak" else "no streak yet")
        val scroll = rememberScrollState()
        LaunchedEffect(Unit) { scroll.scrollTo(scroll.maxValue) }
        Row(Modifier.horizontalScroll(scroll)) {
            val cell = 12.dp; val step = 15.dp
            Canvas(Modifier.width(step * 52).height(step * 7).pointerInput(days) {
                detectTapGestures { p ->
                    val w = (p.x / step.toPx()).toInt(); val r = (p.y / step.toPx()).toInt()
                    val d = first.plusDays(w * 7L + r)
                    if (r in 0..6 && !d.isAfter(today)) { picked = d.toString(); onPick?.invoke(d) }
                }
            }) {
                for (w in 0 until 52) for (r in 0..6) {
                    val d = first.plusDays(w * 7L + r); if (d.isAfter(today)) continue
                    val e = days[d.toString()]?.get("e")?.num()
                    drawRoundRect(shades[heatLevel(e)], Offset(w * step.toPx(), r * step.toPx()), Size(cell.toPx(), cell.toPx()), CornerRadius(2.dp.toPx()))
                }
            }
        }
        picked?.let { k ->
            val d = days[k]
            Text(fmtDate(LocalDate.parse(k)) + " · " + if (d == null) "no computer" else "${fmtDuration(d["f"].num())} focused (${d["p"].num()}%)", style = MaterialTheme.typography.bodySmall)
        }
    }
}
