package dev.ontask.classify

import dev.ontask.Category
import dev.ontask.Level
import dev.ontask.Source
import dev.ontask.db.Db
import java.net.URI

/**
 * The editable list of categories. Built-ins live in code and are stored here only once renamed, changed or deleted;
 * the user's own categories are rows with names like `c_1730000000000`. [load] publishes the result to [Category.registry].
 */
class CategoryStore(private val db: Db) {
    fun load(): List<Category> {
        val rows = db.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT name, label, level, variable, deleted, pos FROM categories ORDER BY pos, rowid").use { rs ->
                    buildList { while (rs.next()) add(Row(rs.getString(1), rs.getString(2), Level.valueOf(rs.getString(3)), rs.getInt(4) == 1, rs.getInt(5) == 1, rs.getInt(6))) }
                }
            }
        }
        val byName = rows.associateBy { it.name }
        val builtIns = Category.builtIns.mapNotNull { b -> val r = byName[b.name]; if (r?.deleted == true) null else r?.toCategory() ?: b }
        val own = rows.filter { it.name.startsWith("c_") && !it.deleted }.map { it.toCategory() }
        val list = builtIns + own
        Category.registry = (list + Category.UNKNOWN + Category.AWAY).associateBy { it.name }
        return list
    }

    /** Earlier default labels you never changed take the new names (e.g. "Core work" → "Work"). */
    fun renameOldDefaults() = db.use { c ->
        c.prepareStatement("UPDATE categories SET label=? WHERE name=? AND label=?").use { ps ->
            Category.oldLabels.forEach { (name, old) -> Category.builtIns.firstOrNull { it.name == name }?.let { ps.setString(1, it.label); ps.setString(2, name); ps.setString(3, old); ps.addBatch() } }
            ps.executeBatch()
        }
    }.also { load() }

    /** Retired built-ins that still have activity or rules filed under them. */
    fun retiredInUse(): List<String> = Category.retired.filter { name ->
        db.use { c -> c.prepareStatement("SELECT EXISTS(SELECT 1 FROM ticks WHERE category=?) OR EXISTS(SELECT 1 FROM rules WHERE category=?) OR EXISTS(SELECT 1 FROM hourly WHERE category=?)").use { ps ->
            repeat(3) { i -> ps.setString(i + 1, name) }; ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) == 1 } } }
    }

    /** Adds a category of the user's own; returns it. */
    fun add(label: String, level: Level, variable: Boolean): Category {
        val name = "c_" + System.currentTimeMillis()
        upsert(Row(name, label.trim(), level, variable, false, 1000))
        load()
        return Category.valueOf(name)
    }

    /** Renames it and/or changes what it counts as; past activity in it is re-counted. */
    fun update(cat: Category, label: String, level: Level, variable: Boolean) {
        upsert(Row(cat.name, label.trim().ifEmpty { cat.label }, level, variable, false, 100))
        load()
        if (!variable) relevel(cat.name, level)
    }

    /**
     * Deletes it. Your rules for it are removed, and everything that was in it is sorted again with the remaining
     * rules ([resort]); whatever nothing matches becomes Unclassified.
     */
    fun delete(cat: Category, resort: (app: String?, bundle: String?, title: String?, url: String?) -> Category) {
        upsert(Row(cat.name, cat.label, cat.level, cat.variable, true, 100))
        load()
        db.tx { c -> c.prepareStatement("DELETE FROM rules WHERE category=?").use { it.setString(1, cat.name); it.executeUpdate() } }
        val ticks = db.use { c ->
            c.prepareStatement("SELECT id, app, bundle, title, url FROM ticks WHERE category=?").use { ps ->
                ps.setString(1, cat.name)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(listOf(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))) } }
            }
        }
        db.tx { c ->
            c.prepareStatement("UPDATE ticks SET category=?, level=?, source=? WHERE id=?").use { ps ->
                ticks.forEach { t ->
                    val to = resort(t[1] as String?, t[2] as String?, t[3] as String?, t[4] as String?)
                    ps.setString(1, to.name); ps.setString(2, to.level.name); ps.setString(3, if (to == Category.UNKNOWN) Source.METADATA.name else Source.RULE.name); ps.setLong(4, t[0] as Long)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            moveHourly(c, cat.name, Category.UNKNOWN.name, Level.MIXED)
        }
    }

    private fun relevel(name: String, level: Level) = db.tx { c ->
        c.prepareStatement("UPDATE ticks SET level=? WHERE category=?").use { it.setString(1, level.name); it.setString(2, name); it.executeUpdate() }
        moveHourly(c, name, name, level)
    }

    /** Hourly summaries are keyed by level and category, so changes merge rows instead of updating them in place. */
    private fun moveHourly(c: java.sql.Connection, from: String, to: String, level: Level) {
        c.prepareStatement(
            """INSERT INTO hourly(hour_ts, level, category, source, work_time, subject, seconds, ticks, skipped_idle, skipped_unchanged, proc_ms)
               SELECT hour_ts, ?, ?, source, work_time, subject, seconds, ticks, skipped_idle, skipped_unchanged, proc_ms FROM hourly WHERE category=? AND NOT (category=? AND level=?)
               ON CONFLICT(hour_ts, level, category, source, work_time, subject) DO UPDATE SET seconds = seconds + excluded.seconds, ticks = ticks + excluded.ticks""",
        ).use { ps -> ps.setString(1, level.name); ps.setString(2, to); ps.setString(3, from); ps.setString(4, to); ps.setString(5, level.name); ps.executeUpdate() }
        c.prepareStatement("DELETE FROM hourly WHERE category=? AND NOT (category=? AND level=?)").use { ps -> ps.setString(1, from); ps.setString(2, to); ps.setString(3, level.name); ps.executeUpdate() }
    }

    private fun upsert(r: Row) = db.use { c ->
        c.prepareStatement(
            """INSERT INTO categories(name, label, level, variable, deleted, pos) VALUES (?,?,?,?,?,?)
               ON CONFLICT(name) DO UPDATE SET label=excluded.label, level=excluded.level, variable=excluded.variable, deleted=excluded.deleted""",
        ).use { ps -> ps.setString(1, r.name); ps.setString(2, r.label); ps.setString(3, r.level.name); ps.setInt(4, if (r.variable) 1 else 0); ps.setInt(5, if (r.deleted) 1 else 0); ps.setInt(6, r.pos); ps.executeUpdate() }
    }

    private data class Row(val name: String, val label: String, val level: Level, val variable: Boolean, val deleted: Boolean, val pos: Int) {
        fun toCategory() = Category(name, label, if (variable) Level.MIXED else level, variable)
    }
}

/**
 * Verdicts for pages and videos on Varies sites: which of your categories each one is. Each is decided once (by a
 * source rule, AI, or you) and remembered. Two matching answers of yours for the same source (a subreddit, Facebook
 * group, channel, publication) make a source rule, so the next page from there is decided without asking.
 */
class Verdicts(private val db: Db) {
    private val cache = HashMap<String, Category?>()

    @Synchronized
    fun get(item: String): Category? = cache.getOrPut(item) {
        db.use { c -> c.prepareStatement("SELECT category FROM verdicts WHERE item=? AND category IS NOT NULL").use { ps -> ps.setString(1, item); ps.executeQuery().use { rs -> if (rs.next()) Category.registry[rs.getString(1)] else null } } }
    }

    /** Saves a verdict and re-counts past time on that page or video; your own answers may create a source rule. */
    @Synchronized
    fun set(item: String, title: String?, cat: Category, source: Source, sourceKey: String?, now: Long) {
        cache[item] = cat
        db.tx { c ->
            c.prepareStatement(
                """INSERT INTO verdicts(item, title, level, category, source, source_key, ts) VALUES (?,?,?,?,?,?,?)
                   ON CONFLICT(item) DO UPDATE SET level=excluded.level, category=excluded.category, source=excluded.source,
                     source_key=COALESCE(excluded.source_key, verdicts.source_key), ts=excluded.ts""",
            ).use { ps -> ps.setString(1, item); ps.setString(2, title); ps.setString(3, cat.level.name); ps.setString(4, cat.name); ps.setString(5, source.name); ps.setString(6, sourceKey); ps.setLong(7, now); ps.executeUpdate() }
            val variable = Category.registry.values.filter { it.variable }.map { it.name }.ifEmpty { listOf("VARIABLE") }
            val ids = c.prepareStatement("SELECT id, app, title, url FROM ticks WHERE category IN (${variable.joinToString(",") { "?" }})").use { ps ->
                variable.forEachIndexed { i, n -> ps.setString(i + 1, n) }
                ps.executeQuery().use { rs -> buildList { while (rs.next()) if (itemKey(rs.getString(2), rs.getString(3), rs.getString(4)) == item && !Pages.isFeed(rs.getString(4))) add(rs.getLong(1)) } }
            }
            c.prepareStatement("UPDATE ticks SET item_cat=?, level=? WHERE id=?").use { ps -> ids.forEach { ps.setString(1, cat.name); ps.setString(2, cat.level.name); ps.setLong(3, it); ps.addBatch() }; ps.executeBatch() }
            if (source == Source.USER_LABEL && sourceKey != null) learn(c, sourceKey, now)
        }
    }

    /** The last two answers of yours for [key] agree → a rule; they disagree → no rule. */
    private fun learn(c: java.sql.Connection, key: String, now: Long) {
        val last = c.prepareStatement("SELECT category FROM verdicts WHERE source_key=? AND source=? AND category IS NOT NULL ORDER BY ts DESC LIMIT 2").use { ps ->
            ps.setString(1, key); ps.setString(2, Source.USER_LABEL.name); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        if (last.size == 2 && last[0] == last[1]) c.prepareStatement("INSERT INTO source_rules(source_key, category, ts) VALUES (?,?,?) ON CONFLICT(source_key) DO UPDATE SET category=excluded.category, ts=excluded.ts")
            .use { ps -> ps.setString(1, key); ps.setString(2, last[0]); ps.setLong(3, now); ps.executeUpdate() }
        else if (last.size == 2) c.prepareStatement("DELETE FROM source_rules WHERE source_key=?").use { ps -> ps.setString(1, key); ps.executeUpdate() }
    }

    fun ruleFor(sourceKey: String?): Category? = sourceKey?.let { k ->
        db.use { c -> c.prepareStatement("SELECT category FROM source_rules WHERE source_key=?").use { ps -> ps.setString(1, k); ps.executeQuery().use { rs -> if (rs.next()) Category.registry[rs.getString(1)] else null } } }
    }

    /** Learned source rules, newest first, for Settings → Categories. */
    fun rules(): List<Pair<String, Category>> = db.use { c ->
        c.createStatement().use { st -> st.executeQuery("SELECT source_key, category FROM source_rules ORDER BY ts DESC").use { rs -> buildList { while (rs.next()) Category.registry[rs.getString(2)]?.let { add(rs.getString(1) to it) } } } }
    }

    fun deleteRule(sourceKey: String) = db.use { c -> c.prepareStatement("DELETE FROM source_rules WHERE source_key=?").use { it.setString(1, sourceKey); it.executeUpdate() } }

    /** Pages and videos on Varies sites seen since [sinceTs] and never decided (not feeds), newest first, as samples to judge. */
    fun unjudged(sinceTs: Long, limit: Int): List<Pair<String, dev.ontask.Sample>> {
        val variable = Category.registry.values.filter { it.variable }.map { it.name }.ifEmpty { listOf("VARIABLE") }
        val rows = db.use { c ->
            c.prepareStatement("SELECT app, title, url FROM ticks WHERE start_ts >= ? AND item_cat IS NULL AND category IN (${variable.joinToString(",") { "?" }}) ORDER BY start_ts DESC").use { ps ->
                ps.setLong(1, sinceTs); variable.forEachIndexed { i, n -> ps.setString(i + 2, n) }
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(dev.ontask.Sample(app = rs.getString(1), title = rs.getString(2), url = rs.getString(3))) } }
            }
        }
        return rows.filter { !Pages.isFeed(it.url) }.map { itemKey(it.app, it.title, it.url) to it }.distinctBy { it.first }.filter { get(it.first) == null }.take(limit)
    }

    @Synchronized
    fun clear(item: String) { cache.remove(item); db.use { c -> c.prepareStatement("DELETE FROM verdicts WHERE item=?").use { it.setString(1, item); it.executeUpdate() } } }
}
