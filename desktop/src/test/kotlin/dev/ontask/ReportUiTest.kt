package dev.ontask

import dev.ontask.track.displayAppName
import dev.ontask.ui.timelineLabels
import dev.ontask.ui.timelineTicks
import dev.ontask.ui.timelineWindow
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportUiTest {
    @Test fun timelineZoomsToData() {
        assertEquals(0L to 86_400L, timelineWindow(emptyList()))
        assertEquals(8 * 3600L to 17 * 3600L, timelineWindow(listOf(8 * 3600L + 600 to 12 * 3600L, 13 * 3600L to 16 * 3600L + 60)))
        assertEquals(9 * 3600L to 11 * 3600L, timelineWindow(listOf(9 * 3600L + 60 to 9 * 3600L + 600)))
        // Away overnight and a 2-minute wake at 3 AM don't stretch the day back to midnight.
        val night = listOf(0L to 2 * 3600L + 360, 3 * 3600L to 3 * 3600L + 120, 8 * 3600L + 600 to 12 * 3600L)
        assertEquals(8 * 3600L to 12 * 3600L, timelineWindow(night, listOf(true, false, false)))
        assertEquals(listOf("8 AM", "10 AM", "12 PM", "2 PM", "4 PM"), timelineLabels(8 * 3600L, 17 * 3600L).take(5))
        // Zoomed in on 25 minutes: 5-minute ticks at round times.
        assertEquals(listOf("2:45", "2:50", "2:55", "3 PM", "3:05"), timelineTicks(14 * 3600L + 42 * 60, 15 * 3600L + 7 * 60).map { it.second })
        assertEquals(listOf("8 AM", "10 AM", "12 PM", "2 PM", "4 PM"), timelineTicks(8 * 3600L, 17 * 3600L).map { it.second })
    }

    @Test fun ownProcessIsCalledOnTask() {
        assertEquals("OnTask", displayAppName("MainKt"))
        assertEquals("Safari", displayAppName("Safari"))
    }
}

class TimelineActivityTest {
    @Test fun mergedStretchesKeepWhatYouDid() {
        val m = dev.ontask.report.Sessions.merge(listOf(
            dev.ontask.report.Run(0, 60, Level.WASTED, mapOf("youtube.com" to 60)),
            dev.ontask.report.Run(60, 90, Level.WASTED, mapOf("reddit.com" to 30)),
            dev.ontask.report.Run(90, 300, Level.WASTED, mapOf("youtube.com" to 210)),
        ))
        assertEquals(1, m.size)
        assertEquals(listOf("youtube.com" to 270L, "reddit.com" to 30L), m[0].top())
    }
}

class RelabelTest {
    @Test fun correctingALabelFixesHistory() {
        val db = dev.ontask.db.Db.memory()
        val rules = dev.ontask.db.RuleStore(db)
        db.use { c ->
            c.createStatement().executeUpdate("""INSERT INTO ticks(start_ts,end_ts,n,seconds,app,domain,category,level,source,work_time,proc_ms)
                VALUES (0,60,12,60,'Safari','youtube.com','ENTERTAINMENT','WASTED','RULE',1,1), (60,120,12,60,'Xcode',NULL,'UNKNOWN','MIXED','METADATA',1,1)""")
        }
        assertEquals(1, rules.relabelHistory(dev.ontask.classify.RuleKind.DOMAIN, "youtube.com", dev.ontask.Category.WORK_SUPPORT))
        assertEquals(1, rules.relabelHistory(dev.ontask.classify.RuleKind.APP, "Xcode", dev.ontask.Category.CORE_WORK))
        val levels = db.use { c -> c.createStatement().executeQuery("SELECT level FROM ticks ORDER BY start_ts").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
        assertEquals(listOf("MIXED", "DEEP"), levels) // Work adjacent counts as Mixed by default
    }
}
