package dev.ontask

import com.sun.net.httpserver.HttpServer
import dev.ontask.ai.*
import dev.ontask.db.Db
import dev.ontask.db.Prefs
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiTest {
    private fun server(handler: (String) -> String): Pair<HttpServer, String> {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = StringBuilder()
        s.createContext("/v1/chat/completions") { ex ->
            val req = ex.requestBody.readAllBytes().decodeToString(); seen.append(req)
            val out = handler(req).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        s.start()
        return s to "http://127.0.0.1:${s.address.port}/v1"
    }
    private fun reply(content: String) = """{"choices":[{"message":{"content":${kotlinx.serialization.json.JsonPrimitive(content)}}}],"usage":{"prompt_tokens":100,"completion_tokens":10}}"""

    private fun svc(): AiService { val db = Db.memory(); return AiService(Prefs(db), db, Files.createTempDirectory("ai").toFile()) }

    @Test fun parsesJsonEvenWithProseAndFences() {
        val r = AiService.parse("Sure!\n```json\n{\"category\":\"core_work\",\"alignment\":3,\"confidence\":0.9}\n```", Source.OCR)!!
        assertEquals(Category.CORE_WORK, r.category); assertEquals(3, r.alignment); assertEquals(0.9, r.confidence)
        assertNull(AiService.parse("no json", Source.OCR)); assertNull(AiService.parse("{\"category\":\"nonsense\"}", Source.OCR))
        assertNull(AiService.parse("{\"category\":\"AWAY\"}", Source.OCR)) // AI may not pick Away
    }

    @Test fun textTierClassifiesAndRecordsCall() {
        val (srv, url) = server { reply("""{"category":"LEARNING","alignment":2,"confidence":0.85}""") }
        try {
            val s = svc()
            val cfg = ProviderConfig(ProviderKind.OPENAI, "m", "k", url)
            val r = s.classify(Sample(app = "Safari", title = "Kotlin docs"), "some page text", listOf("Ship phase 3"), emptyList(), cfgOverride = mapOf(Tier.TEXT to cfg))!!
            assertEquals(Category.LEARNING, r.category); assertEquals(Source.OCR, r.source)
            val st = s.stats(0).single(); assertEquals(1, st.calls); assertTrue(st.costUsd > 0)
        } finally { srv.stop(0) }
    }

    @Test fun lowConfidenceFallsBackToVisionTierWithImage() {
        var visionBody = ""
        val (text, tUrl) = server { reply("""{"category":"NEWS","confidence":0.3}""") }
        val (vis, vUrl) = server { visionBody = it; reply("""{"category":"CORE_WORK","alignment":3,"confidence":0.9}""") }
        try {
            val s = svc()
            val r = s.classify(Sample(app = "X"), "text", emptyList(), emptyList(), screenshots = { listOf("QUJD") },
                cfgOverride = mapOf(Tier.TEXT to ProviderConfig(ProviderKind.OLLAMA, "t", null, tUrl), Tier.VISION to ProviderConfig(ProviderKind.OLLAMA, "v", null, vUrl)))!!
            assertEquals(Source.VISION, r.source); assertEquals(Category.CORE_WORK, r.category)
            assertTrue("data:image/png;base64,QUJD" in visionBody)
        } finally { text.stop(0); vis.stop(0) }
    }

    @Test fun cloudProviderNeedsAcknowledgement() {
        val s = svc(); s.choose(Tier.TEXT, ProviderKind.OPENAI, "gpt-4o-mini")
        assertFailsWith<CloudNoticeRequired> { s.config(Tier.TEXT) }
        s.acknowledgeCloud(ProviderKind.OPENAI)
        assertTrue(s.cloudAcknowledged(ProviderKind.OPENAI)); assertTrue(s.cloudAcknowledged(ProviderKind.OLLAMA))
    }

    @Test fun promptIncludesRulesContextAndFewShots() {
        val p = AiService.userPrompt(Sample(app = "Chrome", url = "https://www.example.com/x", title = "T"), "ocr text", listOf("P1"), listOf(FewShot("DOMAIN", "example.com", Category.NEWS)))
        assertTrue("example.com" in p && "P1" in p && "ocr text" in p && "NEWS" in p)
    }
}

/** Opt-in: ONTASK_OLLAMA_MODEL=qwen2.5:0.5b ./gradlew test --tests '*OllamaSmoke*' */
class OllamaSmoke {
    @Test fun classifiesViaRealOllama() {
        val model = System.getenv("ONTASK_OLLAMA_MODEL") ?: return
        val db = Db.memory()
        val s = AiService(Prefs(db), db, Files.createTempDirectory("ai").toFile())
        assertTrue(s.ollama.detect())
        val r = s.classify(Sample(app = "Google Chrome", url = "https://www.youtube.com/watch?v=abc", title = "Funny cat compilation"), "Funny cat compilation 10 hours. Subscribe. Comments", listOf("Finish the report"), emptyList(),
            cfgOverride = mapOf(Tier.TEXT to ProviderConfig(ProviderKind.OLLAMA, model)))
        println("OLLAMA RESULT: $r")
        assertTrue(r != null, "model returned unparseable output")
    }
}
