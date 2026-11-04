package dev.ontask.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.ontask.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Help sort these", right after the midday or evening check-in: sites and apps with no category and pages or videos
 * still Unsure (both devices, the 10 with the most time), each with its screenshot when this Mac took one. Skip for
 * now (or closing it) leaves them for the next check-in.
 */
@Composable
fun SortWindow(app: App, initial: List<App.SortItem>, mode: ThemeMode) {
    val size = DpSize(720.dp, 680.dp)
    Window(
        onCloseRequest = { app.sortClosed() }, title = "OnTask — help sort these", alwaysOnTop = true,
        state = rememberWindowState(size = size, position = centeredOnActiveScreen(size)),
    ) {
        LaunchedEffect(Unit) { bringForwardAlone(window) }
        OnTaskTheme(mode) { Surface(Modifier.fillMaxSize()) { SortContent(app, initial) } }
    }
}

@Composable
private fun SortContent(app: App, initial: List<App.SortItem>) {
    // Sorted rows stay (dimmed, "Sorted as Work") and can still be changed until Done or Skip for now.
    val sorted = remember { androidx.compose.runtime.mutableStateMapOf<Int, dev.ontask.Category>() }
    var big by remember { mutableStateOf<ImageBitmap?>(null) }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Help sort these", style = MaterialTheme.typography.headlineSmall)
        Text("OnTask couldn't tell what these were.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            initial.forEachIndexed { i, it ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SortRow(app, it, sorted[i], onOpen = { img -> big = img }, onPicked = { c -> sorted[i] = c })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ app.sortClosed() }) { Text("Skip for now") }
            Box(Modifier.weight(1f))
            Button({ app.sortClosed() }) { Text("Done") }
        }
    }
    big?.let { img ->
        Window(onCloseRequest = { big = null }, title = "Screenshot", state = rememberWindowState(size = DpSize(1040.dp, 760.dp)), alwaysOnTop = true) {
            Image(img, null, Modifier.fillMaxSize().clickable { big = null }, contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun SortRow(app: App, it: App.SortItem, sortedAs: dev.ontask.Category?, onOpen: (ImageBitmap) -> Unit, onPicked: (dev.ontask.Category) -> Unit) {
    val img = remember(it.shot) {
        it.shot?.let { p -> runCatching { org.jetbrains.skia.Image.makeFromEncoded(java.nio.file.Files.readAllBytes(p)).toComposeImageBitmap() }.getOrNull() }
    }
    val dim = if (sortedAs != null) 0.5f else 1f
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(120.dp, 76.dp).clip(RoundedCornerShape(6.dp)).alpha(dim), contentAlignment = Alignment.Center) {
            if (img != null) Image(img, "Screenshot of ${it.name}", Modifier.fillMaxSize().clickable { onOpen(img) }, contentScale = ContentScale.Crop)
            else Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (it.phone) "📱 picture on\nyour phone" else "No picture", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.weight(1f).alpha(dim)) {
            Text((if (it.phone) "📱 " else "") + it.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            it.ref.title?.takeIf { t -> t != it.name && !it.name.contains(t) }?.let { t -> Text(t, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(fmtDuration(it.seconds) + " · " + (sortedAs?.let { c -> "Sorted as ${c.label}" } ?: if (it.category.variable) "Unsure" else "Unclassified"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CategoryPicker(app, it.ref.subject, sortedAs ?: it.category, {}, item = it.ref.itemKey?.let { k -> k to it.ref.title }, onPicked = onPicked)
    }
}
