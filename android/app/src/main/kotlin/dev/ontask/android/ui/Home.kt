package dev.ontask.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import dev.ontask.android.Cloud
import dev.ontask.android.ago
import dev.ontask.android.clock
import dev.ontask.android.dayKey
import dev.ontask.android.firstName
import dev.ontask.android.fmtDate
import dev.ontask.android.num
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** Your day: work hours, today's merged report (Mac and phone), today's plan, the year. */
@Suppress("UNCHECKED_CAST")
@Composable
fun HomeScreen(uid: String, name: String) {
    val today = remember { LocalDate.now() }
    val schedule by remember { Cloud.doc("users/$uid/schedule/current") }.collectAsState(null)
    // The day shown in the day card: today, or an earlier one (◀ ▶, or a tap on the year below).
    var date by remember { mutableStateOf(today) }
    val report by remember(date) { Cloud.doc("users/$uid/reports/${dayKey(date)}") }.collectAsState(null)
    val items by remember(date) { Cloud.doc("users/$uid/reportItems/${dayKey(date)}") }.collectAsState(null)
    val checkin by remember { Cloud.doc("users/$uid/checkins/${dayKey(today)}") }.collectAsState(null)
    val heat by remember { Cloud.doc("users/$uid/heat/year") }.collectAsState(null)
    val devices by remember { Cloud.query(Cloud.db.collection("users/$uid/devices")) }.collectAsState(null)
    val s = schedule?.data
    val ranges = rangesFor(s, today)
    val hour = LocalTime.now().hour
    val hello = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Text("$hello, ${firstName(name)}", style = MaterialTheme.typography.headlineMedium)
            Muted(fmtDate(today, weekday = true) + " · " + if (ranges.isEmpty()) "Day off" else "Work hours " + ranges.joinToString(", ") { "${clock(it.first)}–${clock(it.second)}" })
        }
        val mac = devices?.documents?.map { it.data ?: emptyMap() }?.filter { it["kind"] == "mac" }?.maxByOrNull { it["lastSyncMs"].num() }
        if (mac == null && devices != null) LinkMacCard()
        OCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (date == today) "Today so far" else fmtDate(date, weekday = true), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton({ date = date.minusDays(1) }) { Text("◀") }
                if (date != today) TextButton({ date = today }) { Text("Today") }
                TextButton({ date = date.plusDays(1) }, enabled = date < today) { Text("▶") }
            }
            if (date == today) mac?.let { Muted("💻 ${it["name"] ?: "Mac"} · " + if (it["lastSyncMs"].num() > 0) "updated ${ago(it["lastSyncMs"].num())}" else "not synced yet") }
            val r = report?.data
            if (r == null) Muted(if (date == today) "Nothing tracked yet today." else "Nothing tracked this day.")
            else ReportBody((items?.data?.get("topSites")?.let { r + ("topSites" to it) } ?: r), categoriesOf(s),
                @Suppress("UNCHECKED_CAST") ((items?.data?.get("refs") as? Map<String, Map<String, Any?>>) ?: emptyMap()))
        }
        OCard("Today's plan") {
            PlanList(editable = true)
            Row(Modifier.fillMaxWidth()) { Text("Work hours", Modifier.weight(1f)); Muted(if (ranges.isEmpty()) "Day off" else ranges.joinToString(", ") { "${clock(it.first)}–${clock(it.second)}" }) }
            if (ranges.isNotEmpty()) {
                val done = checkin?.getBoolean("evening") == true
                Row(Modifier.fillMaxWidth()) { Text("Evening check-in", Modifier.weight(1f)); Muted(if (done) "Done ✓" else "Around ${clock(ranges.last().second)}") }
            }
        }
        PatternsCard(uid, ranges)
        HeatCard(heat?.data) { d -> date = d }
    }
}

/** Today's work hours from the shared schedule: the day's exception if any, else its weekday. */
@Suppress("UNCHECKED_CAST")
fun rangesFor(s: Map<String, Any?>?, d: LocalDate): List<Pair<Int, Int>> {
    val ex = (s?.get("exceptions") as? Map<String, List<Map<String, Any?>>>)?.get(d.toString())
    val week = (s?.get("workWeek") as? Map<String, List<Map<String, Any?>>>)?.get(d.dayOfWeek.value.toString())
    return (ex ?: week ?: emptyList()).map { it["startMin"].num().toInt() to it["endMin"].num().toInt() }.sortedBy { it.first }
}

/** Linking the Mac: a one-time code to type into OnTask on the Mac. */
@Composable
private fun LinkMacCard() {
    var code by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(code) { while (code != null) { delay(1000); now = System.currentTimeMillis() } }
    OCard("Connect your Mac") {
        Muted("OnTask on your Mac measures your focus and blocks distractions. Link it to see your whole day here.")
        code?.let { (c, exp) ->
            Text("On your Mac, open OnTask → Settings → Phone & partner, and type:")
            Text(c, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 4.sp))
            val left = ((exp - now) / 1000).coerceAtLeast(0)
            Muted(if (left > 0) "Waiting for your Mac… expires in ${left / 60}:${"%02d".format(left % 60)}" else "This code expired.")
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({ scope.launch { runCatching { Cloud.call("createLinkCode") }.onSuccess { r -> code = (r?.get("code") as String) to r["expiresAt"].num() }.onFailure { err = it.message } } }) {
            Text(if (code == null) "Link my Mac" else "New code")
        }
    }
}


