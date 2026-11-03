package dev.ontask

import dev.ontask.classify.Classifier
import dev.ontask.classify.Rule
import dev.ontask.classify.RuleKind
import dev.ontask.classify.domainOf
import dev.ontask.classify.isAway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClassifierTest {
    private val c = Classifier(emptyList())
    private fun s(app: String? = null, url: String? = null, title: String? = null) = Sample(app = app, url = url, title = title)

    @Test fun builtInAppRule() {
        val r = c.classify(s(app = "Xcode"))
        assertEquals(Category.CORE_WORK, r.category); assertEquals(Level.DEEP, r.level); assertEquals(Source.RULE, r.source)
    }

    @Test fun domainMatchesSubdomainsButNotSuffixLookalikes() {
        assertEquals(Category.VARIABLE, c.classify(s(app = "Google Chrome", url = "https://www.youtube.com/watch?v=1")).category)
        assertEquals(Category.VARIABLE, c.classify(s(url = "https://m.youtube.com/")).category)
        // A more specific built-in site wins over its parent.
        assertEquals(Category.ENTERTAINMENT, c.classify(s(url = "https://music.youtube.com/")).category)
        assertEquals(Category.UNKNOWN, c.classify(s(url = "https://notyoutube.com/")).category)
    }

    @Test fun unknownFallsBackToMetadataMixed() {
        val r = c.classify(s(app = "SomeRandomApp"))
        assertEquals(Category.UNKNOWN, r.category); assertEquals(Level.MIXED, r.level); assertEquals(Source.METADATA, r.source)
    }

    @Test fun userRuleBeatsBuiltInAndReportsUserSource() {
        val user = Rule(1, RuleKind.DOMAIN, "youtube.com", Category.WORK_SUPPORT, user = true)
        val r = Classifier(listOf(user)).classify(s(url = "https://youtube.com/watch"))
        assertEquals(Category.WORK_SUPPORT, r.category); assertEquals(Source.USER_LABEL, r.source); assertEquals(1L, r.ruleId)
    }

    @Test fun titleKeywordBeatsDomainAndIsCaseInsensitive() {
        val user = Rule(2, RuleKind.TITLE, "Acme Onboarding", Category.CORE_WORK, user = true)
        val r = Classifier(listOf(user)).classify(s(url = "https://youtube.com/watch", title = "acme onboarding - part 1"))
        assertEquals(Category.CORE_WORK, r.category)
    }

    @Test fun userDomainRuleBeatsUserAppRule() {
        val rules = listOf(
            Rule(1, RuleKind.APP, "Google Chrome", Category.CORE_WORK, user = true),
            Rule(2, RuleKind.DOMAIN, "reddit.com", Category.ENTERTAINMENT, user = true),
        )
        assertEquals(Category.ENTERTAINMENT, Classifier(rules).classify(s(app = "Google Chrome", url = "https://old.reddit.com/")).category)
        assertEquals(Category.CORE_WORK, Classifier(rules).classify(s(app = "Google Chrome", url = "https://example.org/")).category)
    }

    @Test fun domainParsing() {
        assertEquals("example.com", domainOf("https://www.Example.com:8080/x?y"))
        assertNull(domainOf("not a url")); assertNull(domainOf(null)); assertNull(domainOf("file:///tmp/x"))
    }

    @Test fun awayDetection() {
        assertEquals(true, isAway(Sample(idleSec = 500.0), 180))
        assertEquals(false, isAway(Sample(idleSec = 500.0, mediaPlaying = true), 180)) // watching a video
        assertEquals(false, isAway(Sample(idleSec = 10.0), 180))
        assertEquals(false, isAway(Sample(idleSec = 500.0, callActive = true), 120)) // on a call, not typing
        assertEquals(true, isAway(Sample(locked = true), 180))
        assertEquals(true, isAway(Sample(asleep = true, mediaPlaying = true), 180))
    }
}
