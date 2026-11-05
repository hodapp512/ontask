package dev.ontask.android.track

import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import dev.ontask.Category
import dev.ontask.Level
import dev.ontask.Source
import dev.ontask.android.Cloud
import dev.ontask.android.num
import dev.ontask.classify.Classifier
import dev.ontask.classify.Rule
import dev.ontask.classify.RuleKind
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The account's shared settings, live from Firestore: categories, rules, learned source rules, verdicts and work
 * hours. The phone classifies with exactly what the Mac uses, and writes its own answers back the same way.
 */
object PhoneRules {
    @Volatile var classifier = Classifier(emptyList()); private set
    @Volatile private var verdicts: Map<String, String> = emptyMap()
    @Volatile private var sourceRules: Map<String, String> = emptyMap()
    @Volatile var schedule: Map<String, Any?>? = null; private set
    private var regs: List<ListenerRegistration> = emptyList()
    private var rules: List<Rule> = emptyList()
    private var startedFor: String? = null

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun start(uid: String) {
        if (startedFor == uid) return
        regs.forEach { it.remove() }; startedFor = uid
        regs = listOf(
            Cloud.db.document("users/$uid/sync/categories").addSnapshotListener { s, _ ->
                loadCategories((s?.get("items") as? Map<String, Map<String, Any?>>) ?: emptyMap())
                rebuild()
            },
            Cloud.db.document("users/$uid/sync/rules").addSnapshotListener { s, _ ->
                val items = (s?.get("items") as? Map<String, Any?>) ?: emptyMap()
                // Rules added or changed (from the Mac, the web or this phone; all of them when the phone starts): the
                // phone's recorded time follows them.
                val before = lastRuleItems ?: emptyMap()
                items.filter { (k, v) -> before[k] != v }.forEach { (k, v) ->
                    val kind = runCatching { RuleKind.valueOf(k.substringBefore('|')) }.getOrNull() ?: return@forEach
                    val cat = Category.registry[v as? String ?: return@forEach] ?: return@forEach
                    runCatching { onRuleChanged(kind, k.substringAfter('|'), cat) }
                }
                lastRuleItems = items
                rules = items.entries.mapIndexedNotNull { i, (k, v) ->
                    val kind = runCatching { RuleKind.valueOf(k.substringBefore('|')) }.getOrNull() ?: return@mapIndexedNotNull null
                    Rule(i + 1L, kind, k.substringAfter('|'), Category.valueOf(v as? String ?: return@mapIndexedNotNull null), user = true)
                }
                rebuild()
            },
            Cloud.db.document("users/$uid/sync/sourceRules").addSnapshotListener { s, _ ->
                sourceRules = ((s?.get("items") as? Map<String, Any?>) ?: emptyMap()).mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
            },
            Cloud.db.collection("users/$uid/verdicts").addSnapshotListener { s, _ ->
                verdicts = s?.documents?.mapNotNull { d -> d.getString("item")?.let { it to (d.getString("category") ?: return@mapNotNull null) } }?.toMap() ?: verdicts
            },
            Cloud.db.document("users/$uid/schedule/current").addSnapshotListener { s, _ -> schedule = s?.data },
        )
    }

    /** Same as the Mac's CategoryStore: built-ins with your edits, plus your own categories. */
    private fun loadCategories(rows: Map<String, Map<String, Any?>>) {
        fun cat(name: String, r: Map<String, Any?>) = Category(name, r["label"] as? String ?: name,
            if (r["variable"] == true) Level.MIXED else runCatching { Level.valueOf(r["level"] as String) }.getOrDefault(Level.MIXED), r["variable"] == true)
        val builtIns = Category.builtIns.mapNotNull { b -> val r = rows[b.name]; if (r?.get("deleted") == true) null else r?.let { cat(b.name, it) } ?: b }
        val own = rows.filter { it.key.startsWith("c_") && it.value["deleted"] != true }.map { cat(it.key, it.value) }
        Category.registry = (builtIns + own + Category.UNKNOWN + Category.AWAY).associateBy { it.name }
    }

    private fun rebuild() { classifier = Classifier(rules) }

    private var lastRuleItems: Map<String, Any?>? = null
    /** Set by the tracker: re-sort the recorded time a rule covers. */
    @Volatile var onRuleChanged: (RuleKind, String, Category) -> Unit = { _, _, _ -> }

    fun verdict(item: String): Category? = verdicts[item]?.let { Category.registry[it] }
    fun sourceRule(key: String?): Category? = key?.let { sourceRules[it] }?.let { Category.registry[it] }

    /** Whether [ts] (epoch seconds) is in today's work hours (the day's exception, else its weekday). */
    @Suppress("UNCHECKED_CAST")
    fun isWork(ts: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val t = Instant.ofEpochSecond(ts).atZone(zone)
        val m = t.hour * 60 + t.minute
        return rangesFor(schedule, t.toLocalDate()).any { m >= it.first && m < it.second }
    }

    @Suppress("UNCHECKED_CAST")
    fun rangesFor(s: Map<String, Any?>?, d: LocalDate): List<Pair<Int, Int>> {
        val ex = (s?.get("exceptions") as? Map<String, List<Map<String, Any?>>>)?.get(d.toString())
        val week = (s?.get("workWeek") as? Map<String, List<Map<String, Any?>>>)?.get(d.dayOfWeek.value.toString())
        return (ex ?: week ?: emptyList()).map { it["startMin"].num().toInt() to it["endMin"].num().toInt() }.sortedBy { it.first }
    }

    /** Files an app or site under [category] for every device: a rule in sync/rules (the Mac relabels its history). */
    fun saveRule(kind: RuleKind, pattern: String, category: String) {
        val uid = Cloud.uid ?: return
        val ref = Cloud.db.document("users/$uid/sync/rules")
        ref.update(FieldPath.of("items", "${kind.name}|$pattern"), category, "updatedAt", System.currentTimeMillis())
            .addOnFailureListener { ref.set(mapOf("items" to mapOf("${kind.name}|$pattern" to category)), SetOptions.merge()) }
    }

    /** One page or video on a Varies site is [category] (a verdict, shared with the Mac). */
    fun saveVerdict(device: String, item: String, title: String?, category: String, source: Source, sourceKey: String?) {
        val uid = Cloud.uid ?: return
        verdicts = verdicts + (item to category)
        val id = MessageDigest.getInstance("SHA-1").digest(item.toByteArray()).joinToString("") { "%02x".format(it) }
        Cloud.db.document("users/$uid/verdicts/$id").set(buildMap {
            put("item", item); title?.let { put("title", it) }; put("category", category); put("source", source.name)
            sourceKey?.let { put("sourceKey", it) }; put("ts", System.currentTimeMillis() / 1000); put("by", device)
        })
    }
}
