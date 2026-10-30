package dev.ontask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.ontask.App
import dev.ontask.ai.InstalledModel
import dev.ontask.ai.ModelCatalog
import dev.ontask.ai.ProviderKind
import dev.ontask.ai.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiSettings(app: App) {
    SectionCard("Smarter classification (AI)", "Rules decide first. AI only looks at activity no rule covers, using on-screen text and, if unsure, a screenshot. Screenshots and text are deleted right after.") {
        AiSettingsBody(app)
    }
}

@Composable
fun AiSettingsBody(app: App) {
    var enabled by remember { mutableStateOf(app.ai.enabled) }
    var tick by remember { mutableStateOf(0) }
    var interval by remember { mutableStateOf(app.prefs.int("ai.interval_sec", 30)) }
    SwitchSetting("Use AI for unclassified activity", "Off by default. Needs Screen Recording permission to read on-screen text.", enabled) { enabled = it; app.ai.enabled = it }
    if (enabled) {
        SliderSetting("Look at the screen every", "How often to read the screen while something unclassified is in front.", interval, 10..120, "seconds", step = 5) { interval = it; app.prefs.set("ai.interval_sec", it) }
        Tier.entries.forEach { t -> TierPicker(app, t) { tick++ } }
        Expander("Advanced: local models") { LocalModelsCard(app) }
    }
}

@Composable
private fun TierPicker(app: App, tier: Tier, changed: () -> Unit) {
    var kind by remember(tier) { mutableStateOf(app.ai.providerOf(tier)) }
    var model by remember(tier, kind) { mutableStateOf(app.ai.modelOf(tier)) }
    var notice by remember { mutableStateOf<ProviderKind?>(null) }
    var key by remember { mutableStateOf("") }
    var keyMsg by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tier.label, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker(kind.label, ProviderKind.entries, { it.label }) { k ->
                if (!app.ai.cloudAcknowledged(k)) notice = k
                else { kind = k; model = app.ai.defaultModel(k); app.ai.choose(tier, k, model); changed() }
            }
            OutlinedTextField(model, { model = it; app.ai.choose(tier, kind, it) }, label = { Text("Model") }, singleLine = true, modifier = Modifier.weight(1f))
        }
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

@Composable
private fun LocalModelsCard(app: App) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var ollama by remember { mutableStateOf<Boolean?>(null) }
    var installed by remember { mutableStateOf<List<InstalledModel>>(emptyList()) }
    var progress by remember { mutableStateOf<Pair<String, Double>?>(null) }
    var msg by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) { ollama = app.ai.ollama.detect(); installed = if (ollama == true) app.ai.ollama.list() else emptyList() }
    }
    KV("Ollama", when (ollama) { true -> "detected — used for local models"; false -> "not found"; null -> "checking…" })
    KV("llama.cpp server", if (app.ai.llama.available) "available (${app.ai.llama.binary})" else "not found — install Ollama, or put llama-server on your PATH")
    Text("One multimodal model serves both tiers, so only one is loaded. Local models unload shortly after last use.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ModelCatalog.all.forEach { m ->
        val have = installed.any { it.name == m.ollamaTag || it.name.startsWith(m.ollamaTag.substringBefore(':') + ":" + m.ollamaTag.substringAfter(':')) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { Text(m.label); Text("~${m.approxGb} GB · ${m.ollamaTag}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (have) OutlinedButton({ scope.launch(Dispatchers.IO) { app.ai.ollama.delete(m.ollamaTag); refresh++ } }) { Text("Delete") }
            else OutlinedButton({
                scope.launch(Dispatchers.IO) {
                    progress = m.ollamaTag to 0.0
                    val ok = app.ai.ollama.pull(m.ollamaTag) { p, _ -> progress = m.ollamaTag to p }
                    progress = null; msg = if (ok) "Downloaded ${m.label}." else "Download failed — is Ollama running?"; refresh++
                }
            }, enabled = ollama == true && progress == null) { Text("Download") }
        }
    }
    progress?.let { LinearProgressIndicator(progress = { it.second.toFloat() }, Modifier.fillMaxWidth()) }
    val others = installed.filter { i -> ModelCatalog.all.none { it.ollamaTag == i.name } }
    if (others.isNotEmpty()) Text("Other models in Ollama:", style = MaterialTheme.typography.labelMedium)
    others.forEach { o -> Row(verticalAlignment = Alignment.CenterVertically) { Text("${o.name}  (${"%.1f".format(o.sizeBytes / 1e9)} GB)", Modifier.weight(1f)); TextButton({ scope.launch(Dispatchers.IO) { app.ai.ollama.delete(o.name); refresh++ } }) { Text("Delete") } } }
    if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
    val cached = remember(refresh) { app.ai.llama.cached() }
    if (cached.isNotEmpty()) {
        Text("llama.cpp downloads:", style = MaterialTheme.typography.labelMedium)
        cached.forEach { c -> Row(verticalAlignment = Alignment.CenterVertically) { Text(c.name, Modifier.weight(1f)); TextButton({ app.ai.llama.deleteCached(c.name); refresh++ }) { Text("Delete") } } }
    }
}
