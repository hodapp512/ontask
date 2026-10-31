package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.ontask.App
import dev.ontask.Level
import dev.ontask.ai.CloudModels
import dev.ontask.ai.InstalledModel
import dev.ontask.ai.ModelCatalog
import dev.ontask.ai.ProviderKind
import dev.ontask.ai.Tier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.WeakHashMap

@Composable
fun AiSettings(app: App) {
    SectionCard("AI labeling", "Optional. Reads on-screen text when the app or site isn't enough. Nothing is kept.") {
        AiSettingsBody(app)
    }
}

@Composable
fun AiSettingsBody(app: App) {
    var enabled by remember { mutableStateOf(app.ai.enabled) }
    var tick by remember { mutableStateOf(0) }
    var interval by remember { mutableStateOf(app.prefs.int("ai.interval_sec", 30)) }
    val local = remember { LocalModels.of(app) }
    LaunchedEffect(Unit) { local.refresh() }
    SwitchSetting("Use AI for unclassified activity", "Off by default. Needs Screen Recording permission to read on-screen text.", enabled) { enabled = it; app.ai.enabled = it }
    if (enabled) {
        SliderSetting("Look at the screen every", "How often to read the screen while something unclassified is in front.", interval, 10..120, "seconds", step = 5) { interval = it; app.prefs.set("ai.interval_sec", it) }
        Tier.entries.forEach { t -> TierPicker(app, t, local) { tick++ } }
        Expander("Advanced: local models") { LocalModelsCard(app, local) }
    }
}

private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1e9)

private fun sameModel(a: String, b: String) = InstalledModel(a, 0).matches(b) || InstalledModel(b, 0).matches(a)

/**
 * What Ollama has installed, plus the one download in progress. Kept per App (not per screen) so a download keeps
 * going, and its progress stays visible, when you leave Settings and come back.
 */
private class LocalModels(private val app: App) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var ollama by mutableStateOf<Boolean?>(null)
    var installed by mutableStateOf<List<InstalledModel>>(emptyList())
    var pulling by mutableStateOf<String?>(null)
    var progress by mutableStateOf(0.0)
    var status by mutableStateOf("")
    var msg by mutableStateOf("")
    @Volatile private var cancel = false

    fun refresh() = scope.launch {
        val up = app.ai.ollama.detect()
        installed = if (up) app.ai.ollama.list() else emptyList()
        ollama = up
    }

    fun find(tag: String) = installed.firstOrNull { sameModel(it.name, tag) }

    fun download(tag: String, label: String = tag) {
        if (pulling != null) return
        cancel = false; pulling = tag; progress = 0.0; status = "starting…"; msg = ""
        scope.launch {
            var err = ""
            val ok = app.ai.ollama.pull(tag, { cancel }) { p, s -> progress = p; status = s; if (s.startsWith("error")) err = s.removePrefix("error: ") }
            msg = when { ok -> "Downloaded $label."; cancel -> "Download of $label stopped."; err.isNotBlank() -> "Download failed: $err"; else -> "Download failed. Is Ollama running?" }
            pulling = null
            refresh()
        }
    }

    fun stop() { cancel = true }

    fun delete(name: String) = scope.launch {
        msg = if (app.ai.ollama.delete(name)) "Deleted $name." else "Couldn't delete $name."
        refresh()
    }

    companion object {
        private val all = WeakHashMap<App, LocalModels>()
        @Synchronized fun of(app: App): LocalModels = all.getOrPut(app) { LocalModels(app) }
    }
}

/** One entry in a model dropdown. [id] null is "Other…" (type an id). */
private data class ModelOption(val id: String?, val label: String, val detail: String = "")

private fun options(kind: ProviderKind, tier: Tier, local: LocalModels): List<ModelOption> = when (kind) {
    ProviderKind.OLLAMA -> {
        val catalog = ModelCatalog.all.map { m ->
            val have = local.find(m.ollamaTag)
            ModelOption(m.ollamaTag, m.label, if (have != null) "installed · ${gb(have.sizeBytes)}" else "download · ~${m.approxGb} GB")
        }
        val others = local.installed.filter { i -> ModelCatalog.all.none { sameModel(i.name, it.ollamaTag) } }.map { i ->
            val textOnly = tier == Tier.VISION && i.capabilities.isNotEmpty() && !i.vision
            ModelOption(i.name, i.name, "installed · ${gb(i.sizeBytes)}" + if (textOnly) " · can't read images" else "")
        }
        catalog + others
    }
    ProviderKind.LLAMACPP -> ModelCatalog.all.map { ModelOption(it.gguf, it.label, "~${it.approxGb} GB, downloaded on first use") }
    else -> CloudModels.of(kind).map { (id, label) -> ModelOption(id, label, id) }
} + ModelOption(null, "Other…", "type a model id")

@Composable
private fun TierPicker(app: App, tier: Tier, local: LocalModels, changed: () -> Unit) {
    var kind by remember(tier) { mutableStateOf(app.ai.providerOf(tier)) }
    var model by remember(tier, kind) { mutableStateOf(app.ai.modelOf(tier)) }
    var notice by remember { mutableStateOf<ProviderKind?>(null) }
    var key by remember { mutableStateOf("") }
    var keyMsg by remember { mutableStateOf("") }
    var custom by remember(tier, kind) { mutableStateOf(false) }
    val opts = options(kind, tier, local)
    val current = opts.firstOrNull { it.id != null && (it.id == model || (kind == ProviderKind.OLLAMA && sameModel(it.id, model))) }
    // Ollama's list loads in the background; only treat an unlisted id as custom once it has.
    val showCustom = custom || (current == null && (kind != ProviderKind.OLLAMA || local.ollama != null))
    fun pick(m: String) { model = m; app.ai.choose(tier, kind, m); changed() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tier.label, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker(kind.label, ProviderKind.entries, { it.label }) { k ->
                if (!app.ai.cloudAcknowledged(k)) notice = k
                else { kind = k; model = app.ai.defaultModel(k); app.ai.choose(tier, k, model); changed() }
            }
            Picker(if (showCustom) "Other…" else current?.label ?: model, opts, { if (it.detail.isBlank()) it.label else "${it.label}  ·  ${it.detail}" }) { o ->
                if (o.id == null) custom = true else { custom = false; pick(o.id) }
            }
        }
        if (showCustom) OutlinedTextField(model, { pick(it.trim()) }, label = { Text("Model id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (kind == ProviderKind.OLLAMA) OllamaModelStatus(tier, model, local)
        if (kind == ProviderKind.LLAMACPP && !app.ai.llama.available)
            Text("llama-server wasn't found. Install Ollama, or put llama-server on your PATH.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        kind.keyAccount?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(key, { key = it }, label = { Text(if (app.ai.hasKey(kind)) "API key stored in Keychain — enter to replace" else "API key (saved to macOS Keychain)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                OutlinedButton({ keyMsg = if (app.ai.setKey(kind, key)) "Saved to Keychain." else "Couldn't save."; key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
            }
            if (keyMsg.isNotBlank()) Text(keyMsg, style = MaterialTheme.typography.bodySmall)
        }
    }
    notice?.let { k ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text("Send data to ${k.label}?") },
            text = { Text("With a cloud provider, text read from your screen (and screenshots when the text model is unsure) is sent to ${k.label} for classification. Nothing is kept by OnTask, but the provider's own policies apply. Local models never leave your Mac.") },
            confirmButton = { Button({ app.ai.acknowledgeCloud(k); kind = k; model = app.ai.defaultModel(k); app.ai.choose(tier, k, model); notice = null; changed() }) { Text("I understand") } },
            dismissButton = { TextButton({ notice = null }) { Text("Cancel") } },
        )
    }
}

/** Under an Ollama tier: whether the chosen model is installed, with Download and its progress. */
@Composable
private fun OllamaModelStatus(tier: Tier, model: String, local: LocalModels) {
    val small = MaterialTheme.typography.bodySmall
    when (local.ollama) {
        null -> Text("Checking Ollama…", style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        false -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ollama isn't running. Start it (or install it from ollama.com), then check again.", Modifier.weight(1f), style = small, color = MaterialTheme.colorScheme.error)
            TextButton({ local.refresh() }) { Text("Check again") }
        }
        true -> {
            val have = local.find(model)
            val pulling = local.pulling
            if (pulling != null && sameModel(pulling, model)) DownloadProgress(local)
            else if (have == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val size = ModelCatalog.all.firstOrNull { sameModel(it.ollamaTag, model) }?.let { " (~${it.approxGb} GB)" } ?: ""
                Text("$model isn't downloaded yet.", Modifier.weight(1f), style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({ local.download(model) }, enabled = pulling == null && model.isNotBlank()) { Text("Download$size") }
            }
            else if (tier == Tier.VISION && have.capabilities.isNotEmpty() && !have.vision)
                Text("$model can't read images. Pick a vision model such as Qwen3-VL 4B for this tier.", style = small, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DownloadProgress(local: LocalModels) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val pct = if (local.progress > 0) " (${(local.progress * 100).toInt()}%)" else ""
            Text("Downloading ${local.pulling}: ${local.status}$pct", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton({ local.stop() }) { Text("Stop") }
        }
        if (local.progress > 0) LinearProgressIndicator(progress = { local.progress.toFloat() }, Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun LocalModelsCard(app: App, local: LocalModels) {
    var refresh by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    KV("Ollama", when (local.ollama) { true -> "running, used for local models"; false -> "not running or not installed"; null -> "checking…" })
    KV("llama.cpp server", if (app.ai.llama.available) "available (${app.ai.llama.binary})" else "not found. Install Ollama, or put llama-server on your PATH")
    Text("One multimodal model can serve both tiers, so only one is loaded. Local models unload shortly after last use.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Recommended", style = MaterialTheme.typography.labelMedium)
    ModelCatalog.all.forEach { m ->
        val have = local.find(m.ollamaTag)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(if (have != null) levelColor(Level.DEEP) else MaterialTheme.colorScheme.outline)
            Column(Modifier.weight(1f)) {
                Text(m.label)
                Text((if (have != null) "installed · ${gb(have.sizeBytes)}" else "not downloaded · ~${m.approxGb} GB") + " · ${m.ollamaTag}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (have != null) OutlinedButton({ confirmDelete = have.name }, enabled = local.pulling == null) { Text("Delete") }
            else OutlinedButton({ local.download(m.ollamaTag, m.label) }, enabled = local.ollama == true && local.pulling == null) { Text("Download") }
        }
    }
    if (local.pulling != null) DownloadProgress(local)
    val others = local.installed.filter { i -> ModelCatalog.all.none { sameModel(i.name, it.ollamaTag) } }
    if (others.isNotEmpty()) Text("Other models in Ollama", style = MaterialTheme.typography.labelMedium)
    others.forEach { o ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${o.name}  (${gb(o.sizeBytes)}${if (o.vision) ", reads images" else ""})", Modifier.weight(1f))
            TextButton({ confirmDelete = o.name }, enabled = local.pulling == null) { Text("Delete") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(local.msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton({ local.refresh(); refresh++ }) { Text("Refresh") }
    }
    val cached = remember(refresh) { app.ai.llama.cached() }
    if (cached.isNotEmpty()) {
        Text("llama.cpp downloads", style = MaterialTheme.typography.labelMedium)
        cached.forEach { c -> Row(verticalAlignment = Alignment.CenterVertically) { Text("${c.name}  (${gb(c.sizeBytes)})", Modifier.weight(1f)); TextButton({ app.ai.llama.deleteCached(c.name); refresh++ }) { Text("Delete") } } }
    }
    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete $name?") },
            text = { Text("This removes the model from Ollama and frees its disk space. You can download it again later.") },
            confirmButton = { Button({ local.delete(name); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
