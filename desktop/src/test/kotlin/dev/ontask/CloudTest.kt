package dev.ontask

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.ontask.block.Events
import dev.ontask.cloud.*
import dev.ontask.db.Db
import dev.ontask.db.Prefs
import dev.ontask.db.Settings
import dev.ontask.plan.Checkins
import dev.ontask.plan.Habits
import dev.ontask.report.Reports
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.LocalDate
import java.util.Base64
import kotlin.test.*

class CloudTest {
    private fun jwt(uid: String): String {
        val b = { s: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray()) }
        return "${b("""{"alg":"none"}""")}.${b("""{"user_id":"$uid"}""")}."
    }

    private class Fake {
        val requests = ArrayList<Triple<String, String, String>>() // method, path, body
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var reactionsResponse = "[]"
        init {
            server.createContext("/") { ex: HttpExchange ->
                val body = ex.requestBody.readAllBytes().decodeToString()
                requests.add(Triple(ex.requestMethod, ex.requestURI.path + (ex.requestURI.rawQuery?.let { "?$it" } ?: ""), body))
                val out = when {
                    ex.requestURI.path.contains("signInWithCustomToken") -> """{"idToken":"${jwtFor("uid-1")}","refreshToken":"r1","expiresIn":"3600"}"""
                    ex.requestURI.path.endsWith(":runQuery") -> reactionsResponse
                    else -> "{}"
                }.toByteArray()
                ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
            }
            server.start()
        }
        fun jwtFor(uid: String): String { val b = { s: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray()) }; return "${b("""{"alg":"none"}""")}.${b("""{"user_id":"$uid"}""")}." }
        val port get() = server.address.port
    }

    @Test fun firestoreEncodingRoundTrips() {
        val o = buildJsonObject {
            put("s", "x"); put("n", 5); put("d", 1.5); put("b", true); put("z", JsonNull)
            put("m", buildJsonObject { put("k", "v") }); put("a", buildJsonArray { add(buildJsonObject { put("i", 1) }); add(JsonPrimitive("t")) })
        }
        val enc = FsValue.encodeFields(o)
        assertEquals("5", enc["n"]!!.jsonObject["integerValue"]!!.jsonPrimitive.content) // ints are strings in the REST format
        assertEquals(o, FsValue.decodeFields(enc))
    }

    @Test fun syncSendsEventsReportAndSchedule_andPullsReactions() {
        val fake = Fake()
        try {
            val db = Db.memory(); val prefs = Prefs(db); val events = Events(db)
            prefs.set("cloud.project", "demo-ontask"); prefs.set("cloud.emulator", false)
            // Point the client at the fake server by overriding config URLs via a factory.
            val sync = CloudSync(prefs, events, Reports(db), { Settings() }, Habits(prefs), Checkins(db),
                clientFactory = { cfg -> CloudClient(TestCfg(cfg, fake.port)) })
            events.emit("work_hours_change", 1000, "reason" to "Doctor", "removed_minutes" to 120)
            events.emit("checkin", 1001, "message" to "Today: good")
            Habits(prefs).add("Morning prayer", "06:30")

            assertFalse(sync.syncOnce(2000)) // not linked yet
            sync.linkThisMac("custom-token")
            fake.reactionsResponse = """[{"document":{"fields":{"kind":{"stringValue":"proud"},"reply":{"stringValue":"Nice"},"ts":{"integerValue":"5000"},"trackedUid":{"stringValue":"uid-1"}}}}]"""
            assertTrue(sync.syncOnce(2000, force = true), sync.lastError ?: "")

            val posts = fake.requests.filter { it.first == "POST" && it.second.contains("/users/uid-1/events") }
            assertEquals(2, posts.size)
            assertTrue(posts[0].third.contains("Doctor") && posts[0].third.contains("work_hours_change"))
            assertTrue(fake.requests.any { it.first == "PATCH" && it.second.contains("/users/uid-1/reports/${LocalDate.now()}") })
            val sched = fake.requests.first { it.first == "PATCH" && it.second.contains("/schedule/current") }.third
            assertTrue("Morning prayer" in sched && "workWeek" in sched)
            assertTrue(events.pending().isEmpty()) // marked synced
            assertEquals(listOf("proud"), sync.reactions().map { it.kind })

            // Second pass: nothing new -> no event posts, schedule not re-sent.
            val before = fake.requests.size
            assertTrue(sync.syncOnce(2100))
            assertTrue(fake.requests.drop(before).none { it.second.contains("/schedule/current") || it.second.contains("/events") })
        } finally { fake.server.stop(0) }
    }

    @Test fun reportPayloadHasNoRawActivity() {
        val db = Db.memory(); val prefs = Prefs(db)
        val app = UiShots.newApp()
        val sync = CloudSync(prefs, Events(db), Reports(app.db), { Settings() }, Habits(prefs), Checkins(db))
        val p = sync.reportPayload(app.reports.daily(LocalDate.now()), 1L).toString()
        assertTrue("totals" in p && "timeline" in p && "topSites" in p)
        assertFalse("Sample window title" in p) // titles never leave the Mac
    }

    @Test fun updateCheckComparesVersions() {
        assertTrue(UpdateCheck.isNewer("v1.2.0", "1.1.9")); assertTrue(UpdateCheck.isNewer("2.0", "1.9.9")); assertFalse(UpdateCheck.isNewer("v1.0.0", "1.0.0")); assertFalse(UpdateCheck.isNewer("0.9", "1.0.0"))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/repos/me/ontask/releases/latest") { ex ->
            val out = """{"tag_name":"v1.3.0","html_url":"https://example.test/r","body":"notes","assets":[{"name":"OnTask-1.3.0.dmg","browser_download_url":"https://example.test/d.dmg"}]}""".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        server.start()
        try {
            val u = UpdateCheck("http://127.0.0.1:${server.address.port}").check("me/ontask", "1.0.0")!!
            assertEquals("1.3.0", u.version); assertEquals("https://example.test/d.dmg", u.dmgUrl)
            assertNull(UpdateCheck("http://127.0.0.1:${server.address.port}").check("me/ontask", "1.3.0"))
            assertNull(UpdateCheck("http://127.0.0.1:1").check("me/ontask", "1.0.0")) // unreachable -> quiet
        } finally { server.stop(0) }
    }
}

/** Routes every cloud URL to a local fake server. */
class TestCfg(base: CloudConfig, private val port: Int) : CloudConfig(base.projectId, base.apiKey, base.region, base.emulator, base.webUrl) {
    override val firestoreBase get() = "http://127.0.0.1:$port/v1/projects/$projectId/databases/(default)/documents"
    override val authSignIn get() = "http://127.0.0.1:$port/identitytoolkit/signInWithCustomToken"
    override val authRefresh get() = "http://127.0.0.1:$port/securetoken/token"
    override fun callable(name: String) = "http://127.0.0.1:$port/fn/$name"
}

/** Opt-in end-to-end against the Firebase emulators: ONTASK_E2E_FILE=/tmp/ontask-e2e.json (see firebase/e2e/pair.mjs). */
class EmulatorE2E {
    @Test fun macEventsReachThePartnerThroughCloudFunctions() {
        val file = System.getenv("ONTASK_E2E_FILE") ?: return
        val cfg = Json.parseToJsonElement(java.io.File(file).readText()).jsonObject
        val db = Db.memory(); val prefs = Prefs(db); val events = Events(db)
        prefs.set("cloud.project", "demo-ontask"); prefs.set("cloud.emulator", true); prefs.set("cloud.api_key", "demo")
        val sync = CloudSync(prefs, events, Reports(db), { Settings() }, Habits(prefs), Checkins(db), clientFactory = { CloudClient(it, null) })
        sync.linkWithCode(cfg["linkCode"]!!.jsonPrimitive.content)
        events.emit("work_unblock", 1000, "domain" to "youtube.com", "minutes" to 45, "purpose" to "Acme onboarding videos")
        events.emit("work_hours_change", 1001, "reason" to "Dentist", "meaning" to "Blocking off for 1h.")
        assertTrue(sync.syncOnce(2000, force = true), sync.lastError ?: "sync failed")

        // The Cloud Function turns events into messages on the link; poll the emulator for them.
        val http = java.net.http.HttpClient.newHttpClient()
        val url = "http://127.0.0.1:8080/v1/projects/demo-ontask/databases/(default)/documents/links/${cfg["linkId"]!!.jsonPrimitive.content}/messages"
        var texts = emptyList<String>()
        repeat(30) {
            val body = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).header("Authorization", "Bearer owner").build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body()
            texts = Regex("\"text\":\\s*\\{\\s*\"stringValue\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(body).map { it.groupValues[1] }.toList()
            if (texts.size >= 2) return@repeat
            Thread.sleep(500)
        }
        println("PARTNER MESSAGES: $texts")
        assertTrue(texts.any { "Acme onboarding videos" in it }, "work unblock message missing: $texts")
        assertTrue(texts.any { "Dentist" in it }, "work-hours message missing: $texts")
    }
}
