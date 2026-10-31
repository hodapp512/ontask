package dev.ontask

import com.sun.net.httpserver.HttpServer
import dev.ontask.ai.*
import dev.ontask.classify.PageLookup
import dev.ontask.classify.Pages
import dev.ontask.db.Db
import dev.ontask.db.Prefs
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiTest {
    /** A fake System One server (the API Kev and Jev share); [handler] gets the request body and returns the response. */
    private fun server(handler: (String) -> String): Pair<HttpServer, String> {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/v1/systemone") { ex ->
            val out = handler(ex.requestBody.readAllBytes().decodeToString()).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        s.start()
        return s to "http://127.0.0.1:${s.address.port}"
    }
    private fun answer(choice: String, p: Double, other: String = "Personal admin") =
        """{"model":"kev-latest","answers":{"c":{"type":"choice","choice":"$choice","confidence":0.5,"probabilities":{"$choice":$p,"$other":${1 - p}}}},"usage":{"input_tokens":120,"output_tokens":20}}"""

    private fun svc(url: String): AiService { val db = Db.memory(); return AiService(Prefs(db), db, Files.createTempDirectory("ai").toFile(), testUrl = url) }

    @Test fun asksWithYourCategoriesByLabelAndMapsTheAnswerBack() {
        var body = ""
        val (srv, url) = server { body = it; answer("Work", 0.82) }
        try {
            val s = svc(url)
            val r = s.judgeItem(Sample(app = "Safari", title = "Kotlin Coroutines Explained", url = "https://www.youtube.com/watch?v=x"), listOf("Ship phase 3"), mapOf("Channel" to "Kotlin by JetBrains"))!!
            assertEquals(Category.CORE_WORK, r.category); assertEquals(0.82, r.probability); assertEquals(Source.AI_PAGE, r.source)
            // Options are the labels you see (Varies itself isn't one), described for the model; the state carries what was looked up.
            assertTrue("\"Work\"" in body && "\"Work adjacent\"" in body && "\"Varies\"" !in body, body)
            assertTrue("Channel: Kotlin by JetBrains" in body && "Today's plan: Ship phase 3" in body, body)
            val st = s.stats(0).single(); assertEquals(1, st.calls); assertEquals(0, st.failed)
        } finally { srv.stop(0) }
    }

    @Test fun aFailureIsRecordedAndPausesFurtherCalls() {
        var calls = 0
        val (srv, url) = server { calls++; "not json" }
        try {
            val s = svc(url)
            assertNull(s.classify(Sample(app = "Safari", title = "x"), null, emptyList()))
            assertNull(s.classify(Sample(app = "Safari", title = "y"), null, emptyList()))
            assertEquals(1, calls) // backed off after the failure
            assertEquals(1, s.stats(0).single().failed)
            assertTrue(s.lastProblem != null)
            s.retryNow(); s.classify(Sample(app = "Safari", title = "z"), null, emptyList()); assertEquals(2, calls)
        } finally { srv.stop(0) }
    }

    @Test fun defaultsToSmallKevOnThisMac() {
        val db = Db.memory(); val s = AiService(Prefs(db), db, Files.createTempDirectory("ai").toFile())
        assertEquals(AiProvider.KEV, s.provider); assertEquals(KevSize.SMALL, s.kevSize); assertEquals(0.6, s.threshold)
        // Jev needs the cloud notice confirmed first.
        s.provider = AiProvider.JEV; s.enabled = true
        assertNull(s.classify(Sample(app = "Safari", title = "x"), null, emptyList()))
        assertTrue(s.lastProblem!!.contains("Jev"))
    }
}

class PagesTest {
    @Test fun feedsAreTheHomeAndScrollingPagesOnly() {
        listOf("https://www.youtube.com/", "https://www.youtube.com/shorts/abc", "https://www.facebook.com/", "https://www.facebook.com/watch/?v=1",
            "https://www.reddit.com/", "https://www.reddit.com/r/popular/", "https://www.linkedin.com/feed/", "https://vimeo.com/").forEach { assertTrue(Pages.isFeed(it), it) }
        listOf("https://www.youtube.com/watch?v=abc", "https://www.facebook.com/groups/austinlawn/", "https://www.facebook.com/marketplace/item/1/",
            "https://www.reddit.com/r/Kotlin/comments/1/x/", "https://www.linkedin.com/jobs/view/1/", "https://vimeo.com/123").forEach { assertTrue(!Pages.isFeed(it), it) }
    }

    @Test fun sourcesAreSubredditsGroupsChannelsAndPublications() {
        assertEquals("reddit r/firebase", Pages.sourceKey("https://www.reddit.com/r/Firebase/comments/1/x/"))
        assertEquals("facebook group standrewvolunteers", Pages.sourceKey("https://www.facebook.com/groups/standrewvolunteers/"))
        assertEquals("youtube channel Cal Newport", Pages.sourceKey("https://www.youtube.com/watch?v=x", "Cal Newport"))
        assertNull(Pages.sourceKey("https://www.youtube.com/watch?v=x")) // channel unknown
        assertEquals("medium @alexkim", Pages.sourceKey("https://medium.com/@alexkim/offline-first"))
        assertEquals("substack pragmatic", Pages.sourceKey("https://pragmatic.substack.com/p/on-call"))
    }

    @Test fun labelsNameTheSiteOnce() {
        assertEquals("YouTube: Learning Java", Pages.label("youtube.com", "Learning Java - YouTube", "https://www.youtube.com/watch?v=x"))
        assertEquals("YouTube: feed", Pages.label("youtube.com", "YouTube", "https://www.youtube.com/"))
        assertEquals("Facebook: Austin Lawn Care Tips", Pages.label("facebook.com", "(3) Austin Lawn Care Tips | Facebook", "https://www.facebook.com/groups/a/"))
        assertEquals("Vimeo: Designing Calm Technology", Pages.label("vimeo.com", "Designing Calm Technology on Vimeo", "https://vimeo.com/1"))
    }

    @Test fun lookupReadsAPagesOwnDescription() {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/p") { ex ->
            val out = """<html><head><meta property="og:site_name" content="The Pragmatic Engineer"><meta name="description" content="How on-call works at big tech &amp; why"></head></html>""".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        s.start()
        try {
            val facts = PageLookup().lookup("http://127.0.0.1:${s.address.port}/p")
            assertEquals("The Pragmatic Engineer", facts["Publication"]); assertEquals("How on-call works at big tech & why", facts["Description"])
            assertEquals(emptyMap(), PageLookup().lookup("https://www.facebook.com/groups/x/")) // needs signing in: never fetched
        } finally { s.stop(0) }
    }
}

/** Opt-in: with Kev running (ONTASK_KEV_URL=http://127.0.0.1:8010) ./gradlew test --tests '*KevSmoke*' */
class KevSmoke {
    @Test fun decidesViaRealKev() {
        val url = System.getenv("ONTASK_KEV_URL") ?: return
        val db = Db.memory()
        val r = AiService(Prefs(db), db, Files.createTempDirectory("ai").toFile(), testUrl = url)
            .judgeItem(Sample(app = "Google Chrome", url = "https://www.youtube.com/watch?v=abc", title = "Top 50 Funniest Fails of 2026"), emptyList(), emptyMap())
        println("KEV RESULT: $r")
        assertEquals(Category.ENTERTAINMENT, r?.category)
    }
}
