package dev.ontask.track

import dev.ontask.Category
import dev.ontask.Level
import dev.ontask.Sample
import dev.ontask.Source
import dev.ontask.classify.Classification
import dev.ontask.classify.Classifier
import dev.ontask.classify.domainOf
import dev.ontask.classify.isAway
import dev.ontask.db.Segment
import dev.ontask.db.Settings
import dev.ontask.db.TickStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the UI shows live: today's running totals and the current activity. Nothing else is kept in memory. */
data class Live(
    val totals: Map<Level, Long> = emptyMap(),
    val sample: Sample? = null,
    val classification: Classification? = null,
    val away: Boolean = false,
)

/**
 * Turns samples into run-length-merged segments. Only the open segment and a small batch of closed ones are held;
 * [flush] writes them to SQLite.
 */
class Tracker(
    private val store: TickStore,
    private val classifier: () -> Classifier,
    private val settings: () -> Settings,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private var open: Segment? = null
    private var openKey: String? = null
    private var openClassifier: Classifier? = null
    private val closed = ArrayList<Segment>()
    private var lastTs = 0L

    private var totalsDay: LocalDate? = null
    private val totals = HashMap<Level, Long>()
    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live

    /** Seed today's totals (from SQL) at startup so the in-memory counters survive restarts. */
    @Synchronized
    fun seedTotals(day: LocalDate, seeded: Map<Level, Long>) {
        totalsDay = day; totals.clear(); totals.putAll(seeded)
    }

    @Synchronized
    fun onSample(s: Sample, nowSec: Long, procMs: Long): Segment {
        val st = settings()
        val cls = classifier()
        val away = isAway(s, st.awayAfterSec)
        // Away ticks drop app/title/url: nothing useful to record, and they all merge into one segment.
        val key = if (away) "away" else listOf(s.app, s.bundleId, s.title, s.url).joinToString("\u0001")
        val workTime = st.schedule.isWork(nowSec, zone)

        val cur = open
        val extend = cur != null && key == openKey && cls === openClassifier && cur.workTime == workTime &&
            nowSec - lastTs <= 3L * st.tickSec && cur.startTs / 3600 == nowSec / 3600
        val seg: Segment
        if (extend) {
            seg = cur!!
            seg.n++; seg.seconds += st.tickSec; seg.endTs = nowSec + st.tickSec
            if (away) seg.skippedIdle++ else seg.skippedUnchanged++
            seg.procMs += procMs; seg.dirty = true
        } else {
            if (cur != null) closed.add(cur)
            val classification = if (away) Classifier.AWAY else cls.classify(s)
            seg = Segment(
                startTs = nowSec, endTs = nowSec + st.tickSec, n = 1, seconds = st.tickSec,
                app = if (away) null else s.app, bundle = if (away) null else s.bundleId,
                title = if (away) null else s.title, url = if (away) null else s.url,
                domain = if (away) null else domainOf(s.url),
                category = classification.category, source = classification.source, ruleId = classification.ruleId,
                workTime = workTime, skippedIdle = if (away) 1 else 0, procMs = procMs,
            )
            open = seg; openKey = key; openClassifier = cls
        }
        lastTs = nowSec

        val day = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
        if (day != totalsDay) { totalsDay = day; totals.clear() }
        totals.merge(seg.level, st.tickSec.toLong(), Long::plus)
        _live.value = Live(HashMap(totals), if (away) null else s, Classification(seg.category, seg.source, seg.ruleId, seg.level), away)
        return seg
    }

    /** Writes closed segments and the open one (if changed). Safe to call from any thread. */
    @Synchronized
    fun flush() {
        val batch = ArrayList(closed)
        open?.let { batch.add(it) }
        store.save(batch)
        closed.clear()
    }

    /** Applies an AI classification to the open segment (it only ever runs on segments no rule covered). */
    @Synchronized
    fun applyAi(res: dev.ontask.ai.AiResult): Boolean {
        val seg = open ?: return false
        if (seg.source != dev.ontask.Source.METADATA) return false
        val old = seg.level
        seg.category = res.category; seg.source = res.source; seg.alignment = res.alignment; seg.confidence = res.confidence; seg.dirty = true
        if (old != seg.level) { // keep running totals consistent
            totals.merge(old, -seg.seconds.toLong(), Long::plus); totals.merge(seg.level, seg.seconds.toLong(), Long::plus)
            _live.value = _live.value.copy(totals = HashMap(totals), classification = Classification(seg.category, seg.source, seg.ruleId))
        }
        return true
    }

    /** Sets whether the open page or video in a Variable category is work, keeping today's totals consistent. */
    @Synchronized
    fun applyVerdict(level: Level) {
        val seg = open ?: return
        if (!seg.category.variable || seg.levelOverride == level) return
        val old = seg.level
        seg.levelOverride = level; seg.dirty = true
        totals.merge(old, -seg.seconds.toLong(), Long::plus); totals.merge(level, seg.seconds.toLong(), Long::plus)
        _live.value = _live.value.copy(totals = HashMap(totals), classification = Classification(seg.category, seg.source, seg.ruleId, level))
    }

    /** True if the open segment is unclassified and could use AI. */
    @Synchronized
    fun needsAi(): Boolean = open?.let { it.source == dev.ontask.Source.METADATA && it.category == Category.UNKNOWN } ?: false

    /** Forget the open segment, e.g. after rules change so the next tick reclassifies. */
    @Synchronized
    fun invalidate() { openClassifier = null }

    @Synchronized
    fun currentSegment(): Segment? = open
}
