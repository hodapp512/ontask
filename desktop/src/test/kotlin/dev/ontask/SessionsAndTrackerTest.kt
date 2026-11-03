package dev.ontask

import dev.ontask.classify.Classifier
import dev.ontask.classify.TimeRange
import dev.ontask.classify.WorkSchedule
import dev.ontask.classify.WorkWeek
import dev.ontask.db.Db
import dev.ontask.db.Settings
import dev.ontask.db.TickStore
import dev.ontask.report.Run
import dev.ontask.report.Sessions
import dev.ontask.track.Tracker
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionsAndTrackerTest {
    private val min = 60L

    @Test fun glancesUnderFifteenSecondsJoinTheStretchAroundThem() {
        // 1:46 on Nov 3: Claude, 5 s of the YouTube feed, Claude again → one Claude stretch listing the glance.
        val claude = Category.CORE_WORK
        val runs = listOf(
            Run(0, 100, Level.DEEP, mapOf("Claude" to 100L), claude),
            Run(100, 105, Level.WASTED, mapOf("YouTube: feed" to 5L), Category.ENTERTAINMENT),
            Run(105, 300, Level.DEEP, mapOf("Claude" to 195L), claude),
            Run(300, 400, Level.AWAY),
            Run(400, 410, Level.WASTED, mapOf("x.com" to 10L), Category.ENTERTAINMENT), // after away: joins the next one
            Run(410, 500, Level.MIXED, mapOf("Mail" to 90L), Category.WORK_SUPPORT),
        )
        val out = Sessions.absorbShort(runs)
        assertEquals(listOf(0L to 300L, 300L to 400L, 400L to 500L), out.map { it.start to it.end })
        assertEquals(295L, out[0].activity["Claude"])
        assertEquals(listOf("YouTube: feed"), out[0].absorbed.flatMap { it.activity.keys })
        assertEquals(Level.MIXED, out[2].level); assertEquals(listOf("x.com"), out[2].absorbed.flatMap { it.activity.keys })
        // Nothing to join: a lone glance stays.
        assertEquals(1, Sessions.absorbShort(listOf(Run(0, 5, Level.WASTED))).size)
    }

    @Test fun deepSessionNeedsTwentyFiveUninterruptedMinutes() {
        val runs = listOf(
            Run(0, 20 * min, Level.DEEP), Run(20 * min, 30 * min, Level.MIXED), // 30 min deep+mixed -> session
            Run(30 * min, 32 * min, Level.WASTED),
            Run(32 * min, 50 * min, Level.DEEP), // only 18 min
            Run(50 * min, 60 * min, Level.AWAY),
            Run(60 * min, 90 * min, Level.MIXED), // mixed only, no deep -> not a session
        )
        val s = Sessions.deepSessions(runs)
        assertEquals(1, s.size); assertEquals(30 * min, s[0].seconds)
        assertEquals(1, Sessions.distractionEvents(runs))
    }

    @Test fun gapBreaksSession() {
        val s = Sessions.deepSessions(listOf(Run(0, 15 * min, Level.DEEP), Run(20 * min, 40 * min, Level.DEEP)))
        assertTrue(s.isEmpty())
    }

    @Test fun mergeJoinsAdjacentSameLevel() {
        val m = Sessions.merge(listOf(Run(0, 5, Level.DEEP), Run(5, 10, Level.DEEP), Run(10, 15, Level.WASTED), Run(100, 105, Level.WASTED)))
        assertEquals(3, m.size); assertEquals(10L, m[0].end)
    }

    @Test fun workWeekTemplate() {
        val w = WorkSchedule(WorkWeek(mapOf(1 to listOf(TimeRange(9 * 60, 17 * 60)))))
        val mon10 = java.time.ZonedDateTime.of(2026, 10, 26, 10, 0, 0, 0, ZoneOffset.UTC).toEpochSecond()
        val mon17 = java.time.ZonedDateTime.of(2026, 10, 26, 17, 0, 0, 0, ZoneOffset.UTC).toEpochSecond()
        val tue10 = mon10 + 86_400
        assertTrue(w.isWork(mon10, ZoneOffset.UTC)); assertFalse(w.isWork(mon17, ZoneOffset.UTC)); assertFalse(w.isWork(tue10, ZoneOffset.UTC))
    }

    @Test fun trackerMergesIdenticalTicksAndSplitsOnChange() {
        val db = Db.memory()
        val cls = Classifier(emptyList())
        val tr = Tracker(TickStore(db), { cls }, { Settings(tickSec = 5, schedule = WorkSchedule(WorkWeek())) }, ZoneOffset.UTC)
        val t0 = 1_800_000_000L - 1_800_000_000L % 3600 + 10 // early in an hour
        val xcode = Sample(app = "Xcode", idleSec = 1.0)
        repeat(4) { tr.onSample(xcode, t0 + it * 5, 3) }
        tr.onSample(Sample(app = "Xcode", idleSec = 999.0), t0 + 20, 3) // away
        tr.onSample(Sample(app = "Xcode", idleSec = 999.0), t0 + 25, 3)
        tr.flush()
        val rows = db.use { c -> c.createStatement().use { st ->
            st.executeQuery("SELECT n, seconds, level, skipped_unchanged, skipped_idle, proc_ms, app FROM ticks ORDER BY start_ts").use { rs ->
                buildList { while (rs.next()) add(listOf(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getString(7))) }
            } } }
        assertEquals(listOf(listOf(4, 20, "DEEP", 3, 0, 12, "Xcode"), listOf(2, 10, "AWAY", 0, 2, 6, null)), rows)
        assertEquals(20L, tr.live.value.totals[Level.DEEP]); assertEquals(10L, tr.live.value.totals[Level.AWAY])
    }
}
