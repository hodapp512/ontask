package dev.ontask.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.ontask.android.Cloud
import dev.ontask.android.Lvl
import dev.ontask.android.clockAt
import dev.ontask.android.fmtDuration
import dev.ontask.android.num

/** A category the user can pick (from the shared schedule document). */
data class Cat(val id: String, val label: String, val level: String, val variable: Boolean)

@Suppress("UNCHECKED_CAST")
fun categoriesOf(schedule: Map<String, Any?>?): List<Cat> = ((schedule?.get("categories") as? List<Map<String, Any?>>) ?: emptyList())
    .map { Cat(it["id"] as String, it["label"] as String, it["level"] as? String ?: "MIXED", it["variable"] == true) }

/**
 * The "Today so far" view: the same for you and your partner (who can't relabel). [categories] non-empty makes each
 * row's category a menu that files it (the whole app or site, or just that page or video).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Suppress("UNCHECKED_CAST")
@Composable
fun ReportBody(r: Map<String, Any?>, categories: List<Cat> = emptyList(), refs: Map<String, Map<String, Any?>> = emptyMap()) {
    val totals = (r["totals"] as? Map<String, Any?>) ?: emptyMap()
    fun t(l: Lvl) = totals[l.key].num()
    val shown = listOf(Lvl.DEEP, Lvl.MIXED, Lvl.WASTED)
    val screen = shown.sumOf { t(it) }
    val dayStart = (r["dayStart"] as? Number)?.toLong(); val tz = r["tz"] as? String
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(if (screen >= 600) "${t(Lvl.DEEP) * 100 / screen}% focused" else "${fmtDuration(t(Lvl.DEEP))} focused", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            Column(horizontalAlignment = Alignment.End) {
                Text("Screen time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmtDuration(screen), style = MaterialTheme.typography.titleLarge)
            }
        }
        if (screen > 0) Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
            shown.forEach { l -> if (t(l) > 0) Box(Modifier.weight(t(l).toFloat()).fillMaxHeight().background(l.color)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            shown.forEach { l -> Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).background(l.color, CircleShape)); Text("  ${l.label} ${fmtDuration(t(l))}", style = MaterialTheme.typography.bodyMedium) } }
        }
        // Away time isn't drawn: the gaps say it.
        Timeline(((r["timeline"] as? List<Map<String, Any?>>) ?: emptyList()).filter { it["k"] != "A" }, dayStart, tz, refs, categories)
        val sites = (r["topSites"] as? List<Map<String, Any?>>) ?: emptyList()
        if (sites.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            TextButton({ open = !open }) { Text((if (open) "▾ " else "▸ ") + "Where the time went") }
            if (open) sites.take(10).forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SiteRow(s, categories)
            }
        }
    }
}

@Composable
private fun SiteRow(s: Map<String, Any?>, categories: List<Cat>) {
    val name = s["name"] as? String ?: ""
    val itemKey = s["itemKey"] as? String
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(fmtDuration(s["seconds"].num()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val current = s["category"] as? String ?: "Unclassified"
        if (categories.isEmpty()) { Text(current, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); return@Column }
        var open by remember { mutableStateOf(false) }
        var sent by remember { mutableStateOf<String?>(null) }
        Box {
            TextButton({ open = true }) { Text((sent ?: current) + " ▾", style = MaterialTheme.typography.bodySmall) }
            DropdownMenu(open, { open = false }) {
                // A page or video gets one of your categories; Varies is for whole sites.
                categories.filter { itemKey == null || !it.variable }.forEach { c ->
                    DropdownMenuItem({ Text(c.label) }, {
                        open = false; sent = "${c.label} (updating…)"
                        Cloud.labelRequest(name, c.id, itemKey, s["title"] as? String)
                    })
                }
            }
        }
    }
}

/**
 * The day's stretches; tap one for what it was (with a category menu for each app, site, page or video when [refs]
 * says what they are), pinch to zoom, drag to move.
 */
@Composable
fun Timeline(raw: List<Map<String, Any?>>, dayStart: Long?, tz: String?, refs: Map<String, Map<String, Any?>> = emptyMap(), categories: List<Cat> = emptyList()) {
    if (raw.isEmpty()) return
    val segs = remember(raw) { absorbShort(raw) }
    val active = segs.filter { it["k"] != "A" }
    val from0 = ((active.minOfOrNull { it["a"].num() } ?: 0) / 3600) * 3600
    val to0 = (((active.maxOfOrNull { it["b"].num() } ?: 86_400) + 3599) / 3600 * 3600).coerceAtLeast(from0 + 7200)
    var win by remember(segs.size, from0) { mutableStateOf(from0.toDouble() to to0.toDouble()) }
    var picked by remember { mutableStateOf<Map<String, Any?>?>(null) }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp))
                .pointerInput(segs) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val (a, b) = win; val span = b - a
                        val newSpan = (span / zoom).coerceIn(300.0, 86_400.0)
                        val at = a + span * (centroid.x / size.width)
                        var na = at - newSpan * (centroid.x / size.width) - pan.x / size.width * newSpan
                        na = na.coerceIn(0.0, 86_400.0 - newSpan)
                        win = na to na + newSpan
                    }
                }
                .pointerInput(segs) {
                    detectTapGestures { p ->
                        val (a, b) = win; val t = a + (b - a) * p.x / size.width
                        picked = segs.firstOrNull { t >= it["a"].num() && t < it["b"].num() }
                    }
                },
        ) {
            drawRect(track, size = size)
            val (a, b) = win; val span = (b - a).toFloat()
            segs.forEach { s ->
                val x0 = ((s["a"].num() - a) / span * size.width).toFloat(); val x1 = ((s["b"].num() - a) / span * size.width).toFloat()
                if (x1 < 0 || x0 > size.width) return@forEach
                val dim = picked != null && picked !== s
                drawRect(Lvl.of(s["k"] as? String).color.copy(alpha = if (dim) 0.5f else 1f), Offset(x0, 0f), Size(maxOf(x1 - x0, 1.5f), size.height))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(clockAt(win.first.toLong(), dayStart, tz), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f))
            if (win != from0.toDouble() to to0.toDouble()) TextButton({ win = from0.toDouble() to to0.toDouble() }) { Text("Reset", style = MaterialTheme.typography.labelSmall) }
            Text(clockAt(win.second.toLong(), dayStart, tz), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        picked?.let { s ->
            Text((if (s["d"] == "phone") "📱 " else "") + "${s["c"] ?: Lvl.of(s["k"] as? String).label} · ${clockAt(s["a"].num(), dayStart, tz)}–${clockAt(s["b"].num(), dayStart, tz)}",
                style = MaterialTheme.typography.bodySmall)
            // Glances drawn as part of this stretch aren't listed: they were in passing.
            @Suppress("UNCHECKED_CAST") (s["t"] as? List<Map<String, Any?>>)?.forEach { x ->
                val n = x["n"] as? String ?: return@forEach
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$n · ${fmtDuration(x["s"].num())}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    refs[n]?.let { ref -> if (categories.isNotEmpty()) RefMenu(ref, categories) }
                }
            }
        }
    }
}

/**
 * Glances under [minSec] (switching between apps), and Other under 2 minutes, are drawn as part of the stretch before them, or after them when
 * nothing comes just before; the two sides of a glance join up. They're kept under "also" for the details.
 */
@Suppress("UNCHECKED_CAST")
fun absorbShort(segs: List<Map<String, Any?>>, minSec: Long = 15): List<Map<String, Any?>> {
    fun a(m: Map<String, Any?>) = m["a"].num()
    fun b(m: Map<String, Any?>) = m["b"].num()
    fun also(m: Map<String, Any?>) = (m["also"] as? List<Map<String, Any?>>).orEmpty()
    val out = ArrayList<Map<String, Any?>>(); val pending = ArrayList<Map<String, Any?>>()
    for (r in segs.sortedBy { a(it) }) {
        val last = out.lastOrNull()?.takeIf { pending.isEmpty() && a(r) - b(it) <= minSec }
        if (b(r) - a(r) < minSec || r["c"] == "Other" && b(r) - a(r) < dev.ontask.Borrow.LIMIT_SEC) {
            if (last != null) out[out.lastIndex] = last + mapOf("b" to maxOf(b(last), b(r)), "also" to also(last) + (r - "also") + also(r))
            else pending.add(r)
            continue
        }
        var cur = r
        if (pending.isNotEmpty() && a(cur) - b(pending.last()) <= minSec) cur = cur + mapOf("a" to a(pending.first()), "also" to pending.toList() + also(cur))
        else out.addAll(pending)
        pending.clear()
        val prev = out.lastOrNull()
        if (prev != null && a(cur) - b(prev) <= minSec && prev["k"] == cur["k"] && prev["c"] == cur["c"] && prev["d"] == cur["d"]) {
            val t = LinkedHashMap<String, Long>()
            ((prev["t"] as? List<Map<String, Any?>>).orEmpty() + (cur["t"] as? List<Map<String, Any?>>).orEmpty()).forEach { x -> (x["n"] as? String)?.let { t.merge(it, x["s"].num(), Long::plus) } }
            out[out.lastIndex] = prev + mapOf("b" to maxOf(b(prev), b(cur)), "t" to t.entries.sortedByDescending { it.value }.map { mapOf("n" to it.key, "s" to it.value) }, "also" to also(prev) + also(cur))
        } else out.add(cur)
    }
    out.addAll(pending)
    return out
}

/**
 * A category menu for one thing in a stretch (on the Mac or this phone): an app or site becomes a rule for every
 * device (the Mac relabels its history); a page or video gets its own verdict.
 */
@Composable
internal fun RefMenu(ref: Map<String, Any?>, categories: List<Cat>, onPicked: (Cat) -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var open by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    val itemKey = ref["itemKey"] as? String
    val current = categories.firstOrNull { it.id == ref["categoryId"] }?.label ?: "Unclassified"
    Box {
        TextButton({ open = true }) { Text((picked ?: current) + " ▾", style = MaterialTheme.typography.bodySmall) }
        DropdownMenu(open, { open = false }) {
            categories.filter { itemKey == null || !it.variable }.forEach { c ->
                DropdownMenuItem({ Text(c.label) }, {
                    open = false; picked = c.label; onPicked(c)
                    val subject = ref["subject"] as? String ?: return@DropdownMenuItem
                    if (itemKey != null) dev.ontask.android.track.PhoneRules.saveVerdict(dev.ontask.android.Cloud.deviceId(ctx), itemKey, ref["title"] as? String, c.id, dev.ontask.Source.USER_LABEL, null)
                    else {
                        // "Google Chrome – Gmail" is a rule for that browser's windows with that title; a site has a dot
                        // or is all lowercase with no spaces ("newtab"); anything else is an app.
                        val window = dev.ontask.classify.browserWindowPattern(subject)
                        val site = window == null && ' ' !in subject && ('.' in subject || subject == subject.lowercase())
                        dev.ontask.android.track.PhoneRules.saveRule(
                            when { window != null -> dev.ontask.classify.RuleKind.TITLE; site -> dev.ontask.classify.RuleKind.DOMAIN; else -> dev.ontask.classify.RuleKind.APP },
                            window ?: if (site) dev.ontask.classify.siteDomain(subject) else subject, c.id)
                    }
                })
            }
        }
    }
}
