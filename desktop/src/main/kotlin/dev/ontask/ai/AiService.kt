package dev.ontask.ai

import dev.ontask.Category
import dev.ontask.Sample
import dev.ontask.Source
import dev.ontask.classify.domainOf
import dev.ontask.classify.itemKey
import dev.ontask.classify.siteDomain
import dev.ontask.db.Db
import dev.ontask.db.Prefs
import java.io.File

/** Where decisions are made: Kev on this Mac, or TypeSafe's Jev in the cloud (needs an API key). */
enum class AiProvider(val label: String, val local: Boolean) {
    KEV("Kev, on this Mac", true),
    JEV("Jev, TypeSafe cloud", false),
}

data class AiResult(val category: Category, val probability: Double, val source: Source)

class CloudNoticeRequired : Exception("Confirm that Jev may receive page titles and on-screen text before using it")

/**
 * Asks a decision model (Kev or Jev) which category something belongs in. Rules always run first; this only sees what
 * no rule covers, and pages of sites in a Varies category. Answers below [threshold] count as unsure.
 */
class AiService(
    private val prefs: Prefs,
    private val db: Db,
    dataDir: File,
    val client: SystemOneClient = SystemOneClient(),
    val keychain: Keychain = Keychain(),
    val kev: KevServer = KevServer(dataDir),
    val accuracy: AiAccuracy = AiAccuracy(db),
    /** Tests: send every decision to this server instead of Kev or Jev. */
    private val testUrl: String? = null,
) {
    var enabled: Boolean
        get() = prefs.bool("ai.enabled", false)
        set(v) = prefs.set("ai.enabled", v)

    /** User chose metadata-only (e.g. at the 20% battery prompt): no on-screen text is read. */
    var metadataOnly: Boolean
        get() = prefs.bool("ai.metadata_only", false)
        set(v) = prefs.set("ai.metadata_only", v)

    var provider: AiProvider
        get() = runCatching { AiProvider.valueOf(prefs.str("ai.provider", "KEV")) }.getOrDefault(AiProvider.KEV)
        set(v) { prefs.set("ai.provider", v.name); waitUntil = 0 }

    /** Below this probability the model counts as unsure (and, for Varies pages, you're asked). */
    val threshold: Double get() = prefs.str("ai.threshold", "0.6").toDoubleOrNull() ?: 0.6

    fun jevAcknowledged() = prefs.bool("ai.notice.JEV", false)
    fun acknowledgeJev() = prefs.set("ai.notice.JEV", true)
    fun setJevKey(key: String) = keychain.set(JEV_KEY, key).also { waitUntil = 0 }
    fun hasJevKey() = keychain.get(JEV_KEY) != null

    /** The latest reason AI couldn't run; null once it works. */
    @Volatile var lastProblem: String? = null; private set
    /** After a failure, nothing is tried for a few minutes, so a missing model or key doesn't fail every tick. */
    @Volatile private var waitUntil = 0L
    fun retryNow() { waitUntil = 0 }

    /** General labelling: which category is this app, site or page? Null if the model can't run. */
    fun classify(s: Sample, ocr: String?, plan: List<String>): AiResult? {
        val d = decide(describe(s, plan, emptyMap(), ocr), "The person has this open on their computer right now. Which category is it?") ?: return null
        (domainOf(s.url)?.let(::siteDomain) ?: s.app)?.let { accuracy.guessed(it, provider, d.first, System.currentTimeMillis() / 1000) }
        return AiResult(d.first, d.second, if (ocr.isNullOrBlank()) Source.METADATA else Source.OCR)
    }

    /** One page or video on a Varies site: which of your categories is it? [extra] holds looked-up facts (channel, description). */
    fun judgeItem(s: Sample, plan: List<String>, extra: Map<String, String>, screenText: String? = null): AiResult? {
        val d = decide(describe(s, plan, extra, screenText), "The person has this page or video open on their computer. Which category is it?") ?: return null
        accuracy.guessed(itemKey(s.app, s.title, s.url), provider, d.first, System.currentTimeMillis() / 1000)
        return AiResult(d.first, d.second, Source.AI_PAGE)
    }

    /** Whether the last decision on this thread got an answer (sure or not), as opposed to failing or being skipped. */
    private val answered = ThreadLocal.withInitial { false }
    fun lastAnswered(): Boolean = answered.get()

    private fun decide(state: String, instructions: String): Pair<Category, Double>? {
        answered.set(false)
        val cats = Category.assignable.filter { !it.variable }
        if (System.currentTimeMillis() < waitUntil || cats.isEmpty()) return null
        // The model reads the option names, so they're the labels you see (as in the test that chose Kev).
        val byLabel = cats.associateBy { it.label }
        val options: Map<String, String?> = byLabel.mapValues { (_, c) -> DESCRIPTIONS[c.name] }
        val p = provider
        val t0 = System.currentTimeMillis()
        return try {
            val d = when {
                testUrl != null -> client.choose(testUrl, "test", null, state, instructions, options)
                p == AiProvider.KEV -> {
                    if (!kev.installed) throw DecisionException("Kev isn't installed yet. Settings → AI labeling → Install Kev.")
                    if (!kev.ensure()) throw DecisionException("Kev didn't start. Diagnostics shows its log.")
                    kev.touch()
                    client.choose(kev.baseUrl, "kev-latest", null, state, instructions, options)
                }
                else -> {
                    if (!jevAcknowledged()) throw CloudNoticeRequired()
                    val key = keychain.get(JEV_KEY) ?: throw DecisionException("No Jev API key stored. Settings → AI labeling.")
                    client.choose(JEV_URL, "jev-latest", key, state, instructions, options)
                }
            }
            record(p, d.ms, d.inTokens, true)
            lastProblem = null
            answered.set(true)
            byLabel[d.choice]?.let { it to d.probability }
        } catch (e: Exception) {
            record(p, System.currentTimeMillis() - t0, null, false, e.message)
            lastProblem = e.message ?: "The ${p.label} request failed."
            waitUntil = System.currentTimeMillis() + 5 * 60_000L
            null
        }
    }

    private fun record(p: AiProvider, ms: Long, inTok: Int?, ok: Boolean, error: String? = null) = db.use { c ->
        c.prepareStatement("INSERT INTO ai_calls(ts,provider,model,tier,ms,in_tokens,out_tokens,cost_usd,ok,error) VALUES (?,?,?,?,?,?,?,?,?,?)").use {
            it.setLong(1, System.currentTimeMillis() / 1000); it.setString(2, p.name); it.setString(3, if (p == AiProvider.KEV) KevServer.MODEL else "jev-latest"); it.setString(4, "DECIDE")
            it.setLong(5, ms); if (inTok == null) it.setNull(6, java.sql.Types.INTEGER) else it.setInt(6, inTok); it.setNull(7, java.sql.Types.INTEGER)
            it.setDouble(8, if (p == AiProvider.JEV) (inTok ?: 0) * JEV_USD_PER_TOKEN else 0.0); it.setInt(9, if (ok) 1 else 0); it.setString(10, error?.take(300)); it.executeUpdate()
        }
    }

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

    companion object {
        const val JEV_URL = "https://api.typesafe.ai"
        const val JEV_KEY = "typesafe"
        /** TypeSafe's list price: $42 per billion input tokens. */
        const val JEV_USD_PER_TOKEN = 42.0 / 1e9

        /** What the built-in categories mean, for the model. Your own categories are described by their names. */
        val DESCRIPTIONS = mapOf(
            "CORE_WORK" to "doing their job or project, such as building software, its tools, docs and tutorials",
            "WORK_SUPPORT" to "related to work without doing it, such as career, job hunting, professional networking, industry and tech commentary",
            "ENTERTAINMENT" to "fun videos, hobbies, sports, news, politics, social media",
            "PERSONAL_ADMIN" to "home, family, church, errands, buying things they need",
        )

        /** The text the model judges: what's open, anything looked up, today's plan, and (if allowed) on-screen text. */
        fun describe(s: Sample, plan: List<String>, extra: Map<String, String>, screenText: String?): String = buildString {
            appendLine("App: ${s.app ?: "?"}")
            domainOf(s.url)?.let { appendLine("Site: $it") }
            s.title?.let { appendLine("Title: ${it.take(200)}") }
            s.url?.let { appendLine("Address: ${it.take(200)}") }
            extra.forEach { (k, v) -> appendLine("$k: ${v.take(300)}") }
            if (plan.isNotEmpty()) appendLine("Today's plan: " + plan.joinToString("; ").take(500))
            if (!screenText.isNullOrBlank()) { appendLine("Text on screen (may be noisy):"); appendLine(screenText.take(1500)) }
        }
    }
}
