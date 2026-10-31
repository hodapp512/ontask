package dev.ontask.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** A single SQLite connection guarded by a lock. All access goes through [use]. */
class Db private constructor(private val conn: Connection, val path: Path?) : AutoCloseable {

    @Synchronized
    fun <T> use(block: (Connection) -> T): T = block(conn)

    /** Runs [block] in a transaction. */
    @Synchronized
    fun <T> tx(block: (Connection) -> T): T {
        conn.autoCommit = false
        try {
            val r = block(conn)
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    @Synchronized
    override fun close() = conn.close()

    companion object {
        fun open(path: Path): Db {
            Files.createDirectories(path.parent)
            val fresh = !Files.exists(path)
            val c = DriverManager.getConnection("jdbc:sqlite:$path")
            c.createStatement().use {
                if (fresh) it.execute("PRAGMA auto_vacuum = INCREMENTAL")
                it.execute("PRAGMA journal_mode = WAL")
                it.execute("PRAGMA synchronous = NORMAL")
            }
            return Db(c, path).also { Schema.migrate(it) }
        }

        fun memory(): Db = Db(DriverManager.getConnection("jdbc:sqlite::memory:"), null).also { Schema.migrate(it) }
    }
}

object Schema {
    private val migrations = listOf(
        """
        CREATE TABLE ticks (
            id INTEGER PRIMARY KEY,
            start_ts INTEGER NOT NULL,
            end_ts INTEGER NOT NULL,
            n INTEGER NOT NULL,
            seconds INTEGER NOT NULL,
            app TEXT, bundle TEXT, title TEXT, url TEXT, domain TEXT,
            category TEXT NOT NULL,
            level TEXT NOT NULL,
            source TEXT NOT NULL,
            rule_id INTEGER,
            work_time INTEGER NOT NULL,
            skipped_idle INTEGER NOT NULL DEFAULT 0,
            skipped_unchanged INTEGER NOT NULL DEFAULT 0,
            proc_ms INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX ticks_start ON ticks(start_ts);
        CREATE TABLE hourly (
            hour_ts INTEGER NOT NULL,
            level TEXT NOT NULL,
            category TEXT NOT NULL,
            source TEXT NOT NULL,
            work_time INTEGER NOT NULL,
            subject TEXT NOT NULL,
            seconds INTEGER NOT NULL,
            ticks INTEGER NOT NULL,
            skipped_idle INTEGER NOT NULL DEFAULT 0,
            skipped_unchanged INTEGER NOT NULL DEFAULT 0,
            proc_ms INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (hour_ts, level, category, source, work_time, subject)
        );
        CREATE TABLE rules (
            id INTEGER PRIMARY KEY,
            kind TEXT NOT NULL,
            pattern TEXT NOT NULL,
            category TEXT NOT NULL,
            created_ts INTEGER NOT NULL,
            UNIQUE(kind, pattern)
        );
        CREATE TABLE labels (
            id INTEGER PRIMARY KEY,
            ts INTEGER NOT NULL,
            kind TEXT NOT NULL,
            pattern TEXT NOT NULL,
            previous_category TEXT NOT NULL,
            category TEXT NOT NULL
        );
        CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        """,
        // Phase 2: blocking
        """
        CREATE TABLE work_exceptions (date TEXT PRIMARY KEY, ranges TEXT NOT NULL);
        CREATE TABLE blocklist (domain TEXT PRIMARY KEY, added_ts INTEGER NOT NULL);
        CREATE TABLE unblocks (
            id INTEGER PRIMARY KEY, ts INTEGER NOT NULL, kind TEXT NOT NULL, domain TEXT NOT NULL,
            purpose TEXT, expires_ts INTEGER NOT NULL, ended_ts INTEGER, end_reason TEXT
        );
        CREATE TABLE focus_sessions (
            id INTEGER PRIMARY KEY, start_ts INTEGER NOT NULL, planned_end_ts INTEGER NOT NULL,
            ended_ts INTEGER, completed INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE events (
            id INTEGER PRIMARY KEY, ts INTEGER NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL,
            synced INTEGER NOT NULL DEFAULT 0
        );
        """,
        // Phase 3: planning and AI
        """
        CREATE TABLE priorities (date TEXT NOT NULL, pos INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY (date, pos));
        CREATE TABLE checkins (
            id INTEGER PRIMARY KEY, date TEXT NOT NULL, kind TEXT NOT NULL, ts INTEGER NOT NULL,
            rating INTEGER, helped TEXT, hurt TEXT, change_tomorrow TEXT, message TEXT, sent INTEGER NOT NULL DEFAULT 0,
            UNIQUE(date, kind)
        );
        CREATE TABLE ai_calls (
            id INTEGER PRIMARY KEY, ts INTEGER NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, tier TEXT NOT NULL,
            ms INTEGER NOT NULL, in_tokens INTEGER, out_tokens INTEGER, cost_usd REAL, ok INTEGER NOT NULL
        );
        """,
        """
        ALTER TABLE ticks ADD COLUMN alignment INTEGER;
        ALTER TABLE ticks ADD COLUMN confidence REAL;
        """,
        // Editable categories (built-ins are stored only once changed) and verdicts for Variable pages/videos.
        """
        CREATE TABLE categories (
            name TEXT PRIMARY KEY, label TEXT NOT NULL, level TEXT NOT NULL, variable INTEGER NOT NULL DEFAULT 0,
            deleted INTEGER NOT NULL DEFAULT 0, pos INTEGER NOT NULL DEFAULT 100
        );
        CREATE TABLE verdicts (item TEXT PRIMARY KEY, title TEXT, level TEXT NOT NULL, source TEXT NOT NULL, ts INTEGER NOT NULL);
        """,
        // Why an AI call failed, shown in Diagnostics.
        """
        ALTER TABLE ai_calls ADD COLUMN error TEXT;
        """,
        // Pages and videos on Varies sites: each reading knows its item ("YouTube: …") and the category it was judged
        // to be; verdicts store that category and where the page came from; two matching answers per source make a rule.
        """
        ALTER TABLE ticks ADD COLUMN item TEXT;
        ALTER TABLE ticks ADD COLUMN item_cat TEXT;
        ALTER TABLE verdicts ADD COLUMN category TEXT;
        ALTER TABLE verdicts ADD COLUMN source_key TEXT;
        CREATE TABLE source_rules (source_key TEXT PRIMARY KEY, category TEXT NOT NULL, ts INTEGER NOT NULL);
        """,
    )

    fun migrate(db: Db) = db.use { c ->
        var version = c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }
        while (version < migrations.size) {
            c.autoCommit = false
            try {
                migrations[version].split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { stmt ->
                    c.createStatement().use { it.execute(stmt) }
                }
                version++
                c.createStatement().use { it.execute("PRAGMA user_version = $version") }
                c.commit()
            } catch (e: Throwable) {
                c.rollback(); throw e
            } finally {
                c.autoCommit = true
            }
        }
    }
}
