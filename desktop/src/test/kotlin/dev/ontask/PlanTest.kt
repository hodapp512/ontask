package dev.ontask

import dev.ontask.db.Db
import dev.ontask.plan.Plan
import dev.ontask.plan.Priorities
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanTest {
    @Test fun splitsByLineAndSentence() {
        assertEquals(listOf("Call Warren", "Figure out scheduling", "Follow up with leads", "Possibly get a haircut"),
            Plan.split("Call Warren. Figure out scheduling. Follow up with leads. Possibly get a haircut."))
        assertEquals(listOf("Client calls & scheduling, and finally work on interview skills"), Plan.split("Client calls & scheduling, and finally work on interview skills"))
        assertEquals(listOf("Finish the proposal", "Review PRs"), Plan.split("- Finish the proposal\n2. Review PRs\n\n"))
    }

    @Test fun tasksCheckOffAndReorder() {
        val p = Priorities(Db.memory()); val d = LocalDate.of(2026, 11, 4)
        p.add(d, "A"); p.add(d, "B"); p.add(d, "C")
        p.toggle(d, p.items(d)[1].id)
        assertEquals(listOf(false, true, false), p.items(d).map { it.done })
        p.move(d, 2, 0)
        assertEquals(listOf("C", "A", "B"), p.forDate(d))
        assertEquals(true, p.items(d)[2].done)
        assertEquals("✓ B", Plan.plainText(p.items(d)).lines()[2])
        assertEquals(d to p.items(d), p.previous(d.plusDays(3)))
    }
}
