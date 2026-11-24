package dev.ontask

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashGuardTest {
    private fun guard() = CrashGuard(Files.createTempDirectory("guard").resolve("crashes.txt"))

    @Test fun thirdStopInTenMinutesStopsRestarting() {
        val g = guard()
        assertFalse(g.record(1_000)); assertFalse(g.record(1_010)); assertTrue(g.record(1_020))
    }

    @Test fun spreadOutStopsKeepRestarting() {
        val g = guard()
        assertFalse(g.record(1_000)); assertFalse(g.record(1_400)); assertFalse(g.record(1_700))
    }

    @Test fun stayingUpForgetsEarlierStops() {
        val g = guard()
        g.record(1_000); g.record(1_010); g.clear()
        assertFalse(g.record(1_020))
    }

    @Test fun survivesRelaunchesThroughTheFile() {
        val f = Files.createTempDirectory("guard").resolve("crashes.txt")
        CrashGuard(f).record(1_000); CrashGuard(f).record(1_005)
        assertTrue(CrashGuard(f).record(1_010))
    }
}
