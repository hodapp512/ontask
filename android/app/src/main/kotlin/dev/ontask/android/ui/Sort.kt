package dev.ontask.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.ontask.android.Cloud
import dev.ontask.android.fmtDuration
import dev.ontask.android.num
import dev.ontask.android.track.Shots
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/** One row: a site or app with no category, or a page or video still Unsure, from either device. */
data class SortRow(val name: String, val ref: Map<String, Any?>, val seconds: Long, val phone: Boolean, val shotKey: String)

/**
 * What's waiting to be sorted (today and yesterday, so what you skipped last evening is still there): from each
 * device's private day report, 30 s or more, the 10 with the most time.
 */
@Suppress("UNCHECKED_CAST")
suspend fun sortRows(): List<SortRow> {
    val uid = Cloud.uid ?: return emptyList()
    val days = listOf(LocalDate.now().minusDays(1).toString(), LocalDate.now().toString())
    val docs = runCatching { Cloud.db.collection("users/$uid/deviceReportItems").whereIn("date", days).get().await().documents }.getOrDefault(emptyList())
    val acc = LinkedHashMap<String, SortRow>()
    for (d in docs) {
        val phone = (d.get("device") as? Map<String, Any?>)?.get("kind") == "phone"
        for (s in (d.get("topSites") as? List<Map<String, Any?>>).orEmpty()) {
            val cat = s["category"] as? String
            if (cat != "Unclassified" && cat != "Unsure") continue
            val name = s["name"] as? String ?: continue
            val itemKey = s["itemKey"] as? String
            if (cat == "Unsure" && itemKey == null) continue
            val subject = if (itemKey != null) name.substringBefore(": ") else name
            val key = if (itemKey != null) Shots.itemKey(itemKey) else Shots.siteKey(subject)
            val ref = mapOf("subject" to subject, "itemKey" to itemKey, "title" to s["title"], "categoryId" to s["categoryId"])
            val prev = acc[key]
            acc[key] = SortRow(name, ref, (prev?.seconds ?: 0) + s["seconds"].num(), phone && (prev?.phone ?: true), key)
        }
    }
    return acc.values.filter { it.seconds >= 30 }.sortedByDescending { it.seconds }.take(10)
}

/**
 * "Help sort these", right after the midday or evening check-in, when something is waiting. Each row has its
 * screenshot when this phone took one. Skip for now leaves them for the next check-in.
 */
@Composable
fun SortScreen(initial: List<SortRow>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val uid = Cloud.uid ?: return
    val schedule by remember { Cloud.doc("users/$uid/schedule/current") }.collectAsState(null)
    val categories = categoriesOf(schedule?.data)
    // Sorted rows stay (dimmed, "Sorted as Work") and can still be changed until Done or Skip for now.
    val sorted = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var big by remember { mutableStateOf<ImageBitmap?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Help sort these", style = MaterialTheme.typography.headlineMedium)
        Muted("OnTask couldn't tell what these were.")
        initial.forEach { r ->
            val img = remember(r.shotKey) { Shots.file(ctx, r.shotKey).takeIf { it.exists() }?.let { f -> runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() } }
            val done = sorted[r.shotKey]
            OCard {
                Row(Modifier.alpha(if (done != null) 0.5f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(72.dp, 100.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        if (img != null) Image(img, "Screenshot of ${r.name}", Modifier.size(72.dp, 100.dp).clickable { big = img }, contentScale = ContentScale.Crop)
                        else Text(if (r.phone) "No picture" else "💻 picture\non your Mac", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(Modifier.weight(1f)) {
                        Text((if (!r.phone) "💻 " else "") + r.name, maxLines = 3)
                        Muted(fmtDuration(r.seconds) + " · " + (done?.let { "Sorted as $it" } ?: if (r.ref["itemKey"] != null) "Unsure" else "Unclassified"))
                    }
                }
                if (categories.isNotEmpty()) RefMenu(r.ref, categories) { c -> sorted[r.shotKey] = c.label; if (!c.variable) Shots.delete(ctx, r.shotKey) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("Skip for now") }
            Box(Modifier.weight(1f))
            Button(onClose) { Text("Done") }
        }
    }
    big?.let { img -> Dialog({ big = null }) { Image(img, null, Modifier.fillMaxWidth().clickable { big = null }, contentScale = ContentScale.Fit) } }
}
