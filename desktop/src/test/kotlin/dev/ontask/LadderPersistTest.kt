package dev.ontask

import dev.ontask.intervene.Ladder
import dev.ontask.intervene.Step
import kotlin.test.Test
import kotlin.test.assertEquals

class LadderPersistTest {
    private fun drift(l: Ladder, from: Long, to: Long, held: Boolean = false) { var t = from; while (t <= to) { l.tick(t, true, true, 0, held); t += 5 } }

    @Test fun restartKeepsTheDrift() {
        val a = Ladder(); drift(a, 0, 330) // past step 2 (2 + 3 min)
        assertEquals(Step.WINDOW, a.step)
        val b = Ladder(); b.restore(a.snapshot(330), 360)
        assertEquals(Step.WINDOW, b.step)
        drift(b, 360, 360 + 320)            // +5 min more: step 3
        assertEquals(Step.PARTNER, b.step)
    }

    @Test fun onTasksOwnWindowPausesTheDrift() {
        val l = Ladder(); drift(l, 0, 100)
        // Two minutes reading prompts: neither ends the drift nor adds to it.
        var t = 105L; while (t <= 225) { l.tick(t, false, true, 0, held = true); t += 5 }
        drift(l, 230, 260)
        assertEquals(Step.PROMPT, l.step)   // 100 + 30 s drifted is past 2 min; the drift wasn't ended
    }

    @Test fun closingWithTheXMovesOnAfterAMinute() {
        val l = Ladder(); l.partnerAuto = true
        drift(l, 0, 125)                      // step 1 at 2 min
        assertEquals(Step.PROMPT, l.step)
        l.dismissed(125); drift(l, 130, 190)  // a minute more: step 2 (not 3 more minutes)
        assertEquals(Step.WINDOW, l.step)
        l.dismissed(190); drift(l, 195, 255)  // a minute more: step 3
        assertEquals(Step.PARTNER, l.step)
    }

    @Test fun tenMinutesElsewhereOnlyPausesTheDrift() {
        val l = Ladder(); drift(l, 0, 100)
        var t = 105L; while (t <= 705) { l.tick(t, false, true, 0); t += 5 } // 10 min in Claude
        drift(l, 710, 740)
        assertEquals(Step.PROMPT, l.step)     // 100 + 30 s: the same drift went on
    }
}
