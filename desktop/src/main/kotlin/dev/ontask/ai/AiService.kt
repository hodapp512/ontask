package dev.ontask.ai

import dev.ontask.Category
import dev.ontask.Sample
import dev.ontask.Source
import dev.ontask.classify.domainOf
import dev.ontask.db.Db
import dev.ontask.db.Prefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

enum class Tier(val label: String) { TEXT("Text tier (reads on-screen text)"), VISION("Vision tier (looks at a screenshot)") }

data class AiResult(val category: Category, val alignment: Int?, val confidence: Double, val source: Source)

data class FewShot(val kind: String, val pattern: String, val category: Category)

class CloudNoticeRequired(val kind: ProviderKind) : Exception("Confirm that ${kind.label} may receive on-screen text before using it")

/**
 * Provider/model selection per tier, the classification prompt, and bookkeeping (calls, latency, estimated cost).
 * Rules always run first; this only sees what no rule covers.
 */
class AiService(
    private val prefs: Prefs,
    private val db: Db,
    dataDir: File,
    val llm: LlmClient = LlmClient(),
    val keychain: Keychain = Keychain(),
    val ollama: OllamaAdmin = OllamaAdmin(),
    val llama: LlamaServer = LlamaServer(dataDir),
) {
    var enabled: Boolean
        get() = prefs.bool("ai.enabled", false)
        set(v) = prefs.set("ai.enabled", v)

    /** User chose metadata-only (e.g. at the 20% battery prompt). */
    var metadataOnly: Boolean
        get() = prefs.bool("ai.metadata_only", false)
        set(v) = prefs.set("ai.metadata_only", v)

    private fun prefix(t: Tier) = "ai.${t.name.lowercase()}"

    fun providerOf(t: Tier): ProviderKind =
        prefs.get("${prefix(t)}.provider")?.let { runCatching { ProviderKind.valueOf(it) }.getOrNull() }
            ?: if (ollama.detect()) ProviderKind.OLLAMA else ProviderKind.LLAMACPP

    fun modelOf(t: Tier): String = prefs.get("${prefix(t)}.model") ?: defaultModel(providerOf(t))

    fun choose(t: Tier, kind: ProviderKind, model: String) { prefs.set("${prefix(t)}.provider", kind.name); prefs.set("${prefix(t)}.model", model) }

    fun defaultModel(kind: ProviderKind): String = when (kind) {
        ProviderKind.OLLAMA -> ModelCatalog.all.first { it.default }.ollamaTag
        ProviderKind.LLAMACPP -> ModelCatalog.all.first { it.default }.gguf
        ProviderKind.CLAUDE, ProviderKind.OPENAI, ProviderKind.GEMINI -> CloudModels.of(kind).first().first
    }

    fun cloudAcknowledged(kind: ProviderKind) = kind.local || prefs.bool("ai.notice.${kind.name}", false)
    fun acknowledgeCloud(kind: ProviderKind) = prefs.set("ai.notice.${kind.name}", true)

    fun setKey(kind: ProviderKind, key: String): Boolean = kind.keyAccount?.let { keychain.set(it, key) } ?: false
    fun hasKey(kind: ProviderKind) = kind.keyAccount?.let { keychain.get(it) } != null

    fun config(t: Tier): ProviderConfig {
        val kind = providerOf(t)
        if (!cloudAcknowledged(kind)) throw CloudNoticeRequired(kind)
        val key = kind.keyAccount?.let { keychain.get(it) ?: throw LlmException("No API key stored for ${kind.label}") }
        return ProviderConfig(kind, modelOf(t), key)
    }

    // ---- classification ---------------------------------------------------------------------------------

    fun classify(s: Sample, ocrText: String?, priorities: List<String>, shots: List<FewShot>, screenshots: (() -> List<String>?)? = null, cfgOverride: Map<Tier, ProviderConfig> = emptyMap()): AiResult? {
        val textCfg = cfgOverride[Tier.TEXT] ?: config(Tier.TEXT)
        var res = call(Tier.TEXT, textCfg, s, ocrText, priorities, shots, null)
        // Vision fallback when metadata + text are ambiguous.
        if ((res == null || res.confidence < 0.6) && screenshots != null) {
            val imgs = screenshots()
            if (!imgs.isNullOrEmpty()) {
                val visionCfg = cfgOverride[Tier.VISION] ?: config(Tier.VISION)
                call(Tier.VISION, visionCfg, s, ocrText, priorities, shots, imgs)?.let { res = it }
            }
        }
        return res
    }

    /** The latest reason AI couldn't run (model not downloaded, Ollama stopped, request failed); null once it works. */
    @Volatile var lastProblem: String? = null; private set
    /** After a failure, a model isn't tried again for a few minutes, so a missing model doesn't fail every 30 seconds. */
    private val backoff = HashMap<String, Long>()
    private val backoffMs = 5 * 60_000L

    private fun waiting(cfg: ProviderConfig): Boolean = (backoff["${cfg.kind}/${cfg.model}"] ?: 0) > System.currentTimeMillis()

    private fun failed(cfg: ProviderConfig, e: Exception) {
        backoff["${cfg.kind}/${cfg.model}"] = System.currentTimeMillis() + backoffMs
        val m = e.message.orEmpty()
        lastProblem = when {
            cfg.kind == ProviderKind.OLLAMA && ("not found" in m || "404" in m) -> "${cfg.model} isn't downloaded in Ollama yet. Settings → AI labeling → Download."
            cfg.kind == ProviderKind.OLLAMA && ("Connection refused" in m || "ConnectException" in m || "Network" in m) -> "Ollama isn't running. Open the Ollama app, or pick another provider in Settings → AI labeling."
            else -> m.ifBlank { "The ${cfg.kind.label} request failed." }
        }
    }

    private fun call(tier: Tier, cfg: ProviderConfig, s: Sample, ocr: String?, priorities: List<String>, shots: List<FewShot>, images: List<String>?): AiResult? {
        if (waiting(cfg)) return null
        if (cfg.kind == ProviderKind.LLAMACPP && !llama.ensure(cfg.model)) { record(cfg, tier, 0, null, null, false, "Couldn't start the built-in model"); lastProblem = "Couldn't start the built-in model ${cfg.model}."; return null }
        if (cfg.kind == ProviderKind.LLAMACPP) llama.touch()
        val ok: LlmResult? = try {
            llm.chat(cfg, SYSTEM, userPrompt(s, ocr, priorities, shots), images ?: emptyList(), maxTokens = 80)
        } catch (e: LlmException) { record(cfg, tier, 0, null, null, false, e.message); failed(cfg, e); null }
        if (ok != null) lastProblem = null
        if (ok == null) return null
        record(cfg, tier, ok.ms, ok.inTokens, ok.outTokens, true)
        return parse(ok.text, if (images != null) Source.VISION else Source.OCR)
    }

    private fun record(cfg: ProviderConfig, tier: Tier, ms: Long, inTok: Int?, outTok: Int?, ok: Boolean, error: String? = null) = db.use { c ->
        c.prepareStatement("INSERT INTO ai_calls(ts,provider,model,tier,ms,in_tokens,out_tokens,cost_usd,ok,error) VALUES (?,?,?,?,?,?,?,?,?,?)").use {
            it.setLong(1, System.currentTimeMillis() / 1000); it.setString(2, cfg.kind.name); it.setString(3, cfg.model); it.setString(4, tier.name)
            it.setLong(5, ms); if (inTok == null) it.setNull(6, java.sql.Types.INTEGER) else it.setInt(6, inTok)
            if (outTok == null) it.setNull(7, java.sql.Types.INTEGER) else it.setInt(7, outTok)
            it.setDouble(8, Pricing.estimate(cfg.kind, inTok, outTok)); it.setInt(9, if (ok) 1 else 0); it.setString(10, error?.take(300)); it.executeUpdate()
        }
    }

    companion object {
        /** Built from the current categories, which the user can edit. */
        val SYSTEM: String get() = """
            You classify what a person is doing on their computer right now. Reply with ONLY a JSON object:
            {"category": one of [${Category.assignable.filter { !it.variable }.joinToString(", ") { "${it.name} (${it.label})" }}], "alignment": 1-3, "confidence": 0.0-1.0}
            alignment: 1 = unrelated to their stated priorities, 2 = neutral/supporting, 3 = directly advances a priority.
            Use the examples the person has labelled before as guidance.
        """.trimIndent()

        fun userPrompt(s: Sample, ocr: String?, priorities: List<String>, shots: List<FewShot>): String = buildString {
            appendLine("App: ${s.app ?: "?"}")
            domainOf(s.url)?.let { appendLine("Site: $it") }
            s.title?.let { appendLine("Window title: ${it.take(200)}") }
            if (priorities.isNotEmpty()) appendLine("Today's plan: " + priorities.joinToString("; "))
            if (shots.isNotEmpty()) { appendLine("Previously labelled by the person:"); shots.forEach { appendLine("- ${it.kind.lowercase()} ${it.pattern} = ${it.category.name}") } }
            if (!ocr.isNullOrBlank()) { appendLine("Visible text on screen (may be noisy):"); appendLine(ocr.take(1500)) }
        }

        /** Tolerates prose or code fences around the JSON. */
        fun parse(text: String, source: Source): AiResult? {
            val i = text.indexOf('{'); val j = text.lastIndexOf('}')
            if (i < 0 || j <= i) return null
            val o = runCatching { Json.parseToJsonElement(text.substring(i, j + 1)) as JsonObject }.getOrNull() ?: return null
            val name = o["category"]?.jsonPrimitive?.content?.trim() ?: return null
            val cat = Category.assignable.firstOrNull { it.name.equals(name, true) || it.label.equals(name, true) } ?: return null
            val align = o["alignment"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..3 }
            val conf = o["confidence"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.5
            return AiResult(cat, align, conf, source)
        }
    }

    /**
     * For Variable categories: is this particular page or video work, given today's plan? WORK / NOT_WORK, or null
     * when the model is unsure or unavailable (then the person is asked).
     */
    fun judgeWork(s: Sample, plan: List<String>): Boolean? = runCatching {
        val sys = "Decide whether the person is working or not, from one page or video they have open. Reply with ONLY a JSON object: " +
            "{\"work\": true|false, \"confidence\": 0.0-1.0}. Tutorials, documentation, talks and research related to their plan or job count as work; " +
            "entertainment, music, gaming, vlogs, news and unrelated videos do not."
        val user = buildString {
            domainOf(s.url)?.let { appendLine("Site: $it") }
            s.title?.let { appendLine("Title: ${it.take(200)}") }
            s.url?.let { appendLine("Address: ${it.take(200)}") }
            if (plan.isNotEmpty()) appendLine("Today's plan: " + plan.joinToString("; ").take(500))
        }
        val cfg = config(Tier.TEXT)
        if (waiting(cfg)) return null
        val text = try { llm.chat(cfg, sys, user, emptyList(), maxTokens = 40).text } catch (e: LlmException) { failed(cfg, e); return null }
        val o = Json.parseToJsonElement(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)) as JsonObject
        val conf = o["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.5
        o["work"]?.jsonPrimitive?.booleanOrNull?.takeIf { conf >= 0.7 }
    }.getOrNull()

    /** One short, honest sentence of context for a partner message. Best effort: null if the model is unavailable. */
    fun contextLine(what: String, driftMin: Long, priorities: List<String>): String? = runCatching {
        llm.chat(config(Tier.TEXT), "Write ONE short first-person sentence (max 20 words), honest and calm, for a message to an accountability partner. No greeting, no emoji.",
            "I have been on $what for $driftMin minutes instead of working. My plan for today: ${priorities.joinToString("; ").ifEmpty { "not set" }}.", maxTokens = 50, timeoutSec = 15).text.trim().lines().first()
    }.getOrNull()

    // ---- diagnostics ------------------------------------------------------------------------------------

    /** [avgMs] covers successful calls only; [lastError] is the most recent failure's reason, if recorded. */
    data class CallStats(val provider: String, val model: String, val tier: String, val calls: Int, val avgMs: Double, val failed: Int, val costUsd: Double, val lastError: String? = null)

    fun stats(sinceTs: Long): List<CallStats> = db.use { c ->
        c.prepareStatement(
            """SELECT provider, model, tier, COUNT(*), COALESCE(AVG(CASE WHEN ok=1 THEN ms END), 0), SUM(1-ok), SUM(cost_usd),
                      (SELECT error FROM ai_calls e WHERE e.provider=a.provider AND e.model=a.model AND e.tier=a.tier AND e.ok=0 AND e.ts >= ? ORDER BY e.ts DESC LIMIT 1)
               FROM ai_calls a WHERE ts >= ? GROUP BY provider, model, tier""",
        ).use { ps ->
            ps.setLong(1, sinceTs); ps.setLong(2, sinceTs)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(CallStats(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getDouble(5), rs.getInt(6), rs.getDouble(7), rs.getString(8))) } }
        }
    }

    fun recentLabels(limit: Int = 5): List<FewShot> = db.use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT kind,pattern,category FROM labels ORDER BY id DESC LIMIT $limit").use { rs ->
                buildList { while (rs.next()) add(FewShot(rs.getString(1), rs.getString(2), Category.valueOf(rs.getString(3)))) }
            }
        }
    }
}
