package dev.ontask

import dev.ontask.ai.AiAccuracy
import dev.ontask.ai.AiProvider
import dev.ontask.db.Db
import kotlin.test.Test
import kotlin.test.assertEquals

class AiAccuracyTest {
    @Test fun scoresEachGuessOnceAgainstYourAnswer() {
        val a = AiAccuracy(Db.memory())
        val now = 1_793_700_000L
        a.guessed("reddit.com", AiProvider.KEV, Category.ENTERTAINMENT, now)
        a.guessed("page-1", AiProvider.KEV, Category.CORE_WORK, now)
        a.guessed("page-2", AiProvider.JEV, Category.CORE_WORK, now)
        a.answered("reddit.com", Category.ENTERTAINMENT, now + 10) // match
        a.answered("page-1", Category.PERSONAL_ADMIN, now + 10)   // miss
        a.answered("page-1", Category.CORE_WORK, now + 20)        // already scored: ignored
        a.answered("never-guessed", Category.CORE_WORK, now + 20) // no AI answer: ignored
        a.answered("page-2", Category.CORE_WORK, now + 30)
        val w = a.weekly(now + 60)
        assertEquals(listOf(AiProvider.KEV to (2 to 1), AiProvider.JEV to (1 to 1)).sortedBy { it.first }, w.map { it.provider to (it.checked to it.matched) })
    }
}
