package dev.ontask

import dev.ontask.track.displayAppName
import dev.ontask.ui.timelineLabels
import dev.ontask.ui.timelineWindow
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportUiTest {
    @Test fun timelineZoomsToData() {
        assertEquals(0L to 86_400L, timelineWindow(emptyList()))
        assertEquals(8 * 3600L to 17 * 3600L, timelineWindow(listOf(8 * 3600L + 600 to 12 * 3600L, 13 * 3600L to 16 * 3600L + 60)))
        assertEquals(9 * 3600L to 11 * 3600L, timelineWindow(listOf(9 * 3600L + 60 to 9 * 3600L + 600)))
        assertEquals(listOf("8 AM", "10 AM", "12 PM", "2 PM", "4 PM"), timelineLabels(8 * 3600L, 17 * 3600L).take(5))
    }

    @Test fun ownProcessIsCalledOnTask() {
        assertEquals("OnTask", displayAppName("MainKt"))
        assertEquals("Safari", displayAppName("Safari"))
    }
}
