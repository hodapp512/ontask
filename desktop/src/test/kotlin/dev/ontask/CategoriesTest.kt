package dev.ontask

import dev.ontask.classify.CategoryStore
import dev.ontask.classify.Classifier
import dev.ontask.classify.Verdicts
import dev.ontask.classify.itemKey
import dev.ontask.db.Db
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoriesTest {
    private val db = Db.memory()
    private val store = CategoryStore(db).also { it.load() }

    @AfterTest fun resetRegistry() { CategoryStore(Db.memory()).load() }

    private fun tick(app: String?, url: String?, title: String?, cat: Category) = db.use { c ->
        c.prepareStatement("INSERT INTO ticks(start_ts,end_ts,n,seconds,app,url,domain,title,category,level,source,work_time) VALUES (0,60,1,60,?,?,?,?,?,?,'RULE',1)").use { ps ->
            ps.setString(1, app); ps.setString(2, url); ps.setString(3, url?.let { dev.ontask.classify.domainOf(it) }); ps.setString(4, title)
            ps.setString(5, cat.name); ps.setString(6, cat.level.name); ps.executeUpdate()
        }
    }
    private fun rows() = db.use { c -> c.createStatement().executeQuery("SELECT category, level FROM ticks ORDER BY id").use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } } }

    @Test fun addRenameAndRelevel() {
        val client = store.add("Client work", Level.DEEP, variable = false)
        assertTrue(client in Category.assignable)
        store.update(Category.PERSONAL_ADMIN, "Shopping & errands", Level.WASTED, variable = false)
        assertEquals("Shopping & errands", Category.valueOf("PERSONAL_ADMIN").label)
        // Rules return the edited category, so its new level applies.
        val c = Classifier(emptyList()).classify(Sample(app = "Safari", url = "https://www.amazon.com/x"))
        assertEquals(Level.WASTED, c.level)
    }

    @Test fun deletingResortsThenUnclassified() {
        tick("Safari", "https://github.com/x", "x", Category.ENTERTAINMENT) // a built-in rule knows github
        tick("Foo", null, "Something", Category.ENTERTAINMENT) // nothing knows Foo
        store.delete(Category.ENTERTAINMENT) { app, bundle, title, url -> Classifier(emptyList()).classify(Sample(app = app, bundleId = bundle, title = title, url = url)).category }
        assertTrue(Category.ENTERTAINMENT !in Category.assignable)
        assertEquals(listOf("CORE_WORK" to "DEEP", "UNKNOWN" to "MIXED"), rows())
        // Built-in rules for the deleted category are skipped.
        assertEquals(Category.UNKNOWN, Classifier(emptyList()).classify(Sample(app = "Spotify")).category)
    }

    @Test fun betweenThingsIsOtherAndBrowserWindowsAreNamedByTitle() {
        val c = Classifier(emptyList())
        assertEquals(Category.OTHER, c.classify(Sample(app = "Google Chrome", title = "New Tab - Google Chrome - Dennis", url = "chrome://newtab/")).category)
        assertEquals(Category.OTHER, c.classify(Sample(app = "UserNotificationCenter")).category)
        assertEquals(Category.OTHER, c.classify(Sample(app = "Google Chrome", title = "Who's using Chrome?")).category)
        assertEquals("Google Chrome – Gmail", dev.ontask.classify.browserWindowSubject("Google Chrome", "Inbox (557) - me@gmail.com - Gmail - Google Chrome - Dennis", null))
        assertEquals("Google Chrome|Gmail", dev.ontask.classify.browserWindowPattern("Google Chrome – Gmail"))
        val rule = dev.ontask.classify.Rule(1, dev.ontask.classify.RuleKind.TITLE, "Google Chrome|Gmail", Category.PERSONAL_ADMIN, user = true)
        assertEquals(Category.PERSONAL_ADMIN, Classifier(listOf(rule)).classify(Sample(app = "Google Chrome", title = "Inbox (3) - me@gmail.com - Gmail")).category)
        assertEquals(Category.UNKNOWN, Classifier(listOf(rule)).classify(Sample(app = "Safari", title = "Inbox (3) - me@gmail.com - Gmail")).category)
    }

    @Test fun otherBorrowsWhatCameBeforeForTwoMinutes() {
        val b = Borrow()
        b.solid(100, Level.WASTED)
        assertEquals(Level.WASTED, b.level(110, Category.OTHER, away = false))
        assertEquals(Level.WASTED, b.level(225, Category.OTHER, away = false))
        assertNull(b.level(235, Category.OTHER, away = false)) // 2 minutes on it: Neutral
        val fresh = Borrow()
        assertNull(fresh.level(10, Category.OTHER, away = false)) // nothing before it
    }

    @Test fun searchesAreJudgedOneByOne() {
        val brave = dev.ontask.classify.Rule(1, dev.ontask.classify.RuleKind.DOMAIN, "search.brave.com", Category.ENTERTAINMENT, user = true)
        val c = Classifier(listOf(brave))
        assertEquals(Category.VARIABLE, c.classify(Sample(app = "Chrome", url = "https://search.brave.com/search?q=excalidraw+or+mermaid")).category)
        assertEquals(Category.VARIABLE, c.classify(Sample(app = "Chrome", url = "https://www.google.com/search?q=news&sca=1")).category)
        assertEquals(Category.ENTERTAINMENT, c.classify(Sample(app = "Chrome", url = "https://search.brave.com/settings")).category)
        // Email, calendars and documents can be work or personal: judged one by one.
        assertEquals(Category.VARIABLE, Classifier(emptyList()).classify(Sample(app = "Chrome", url = "https://docs.google.com/document/d/1")).category)
        assertEquals(Category.WORK_SUPPORT, Classifier(emptyList()).classify(Sample(app = "Chrome", url = "https://slack.com/x")).category)
        assertEquals("google.com/search?q=excalidraw or mermaid", itemKey("Chrome", "x", "https://www.google.com/search?q=Excalidraw+or++mermaid&oq=x"))
        assertEquals("duckduckgo.com/search?q=news", itemKey("Chrome", "x", "https://duckduckgo.com/?q=news"))
        assertEquals("Google search: news", dev.ontask.classify.Pages.label("google.com", "news - Google Search", "https://www.google.com/search?q=news"))
    }

    @Test fun youtubeVariesAndVerdictsAreRemembered() {
        assertEquals(Category.VARIABLE, Classifier(emptyList()).classify(Sample(app = "Safari", url = "https://www.youtube.com/watch?v=abc&t=10")).category)
        assertEquals("youtube.com/watch?v=abc", itemKey("Safari", "x", "https://m.youtube.com/watch?t=1&v=abc"))
        assertEquals("youtube.com/watch?v=abc", itemKey("Safari", "x", "https://youtu.be/abc"))
        tick("Safari", "https://www.youtube.com/watch?v=abc", "Kotlin coroutines talk", Category.VARIABLE)
        tick("Safari", "https://www.youtube.com/watch?v=zzz", "Funny cats", Category.VARIABLE)
        val v = Verdicts(db)
        assertNull(v.get("youtube.com/watch?v=abc"))
        assertEquals(listOf("youtube.com/watch?v=zzz", "youtube.com/watch?v=abc"), v.unjudged(0, 10).map { it.first }.sorted().reversed())
        v.set("youtube.com/watch?v=abc", "Kotlin coroutines talk", Category.CORE_WORK, Source.USER_LABEL, null, 0)
        assertEquals(Category.CORE_WORK, v.get("youtube.com/watch?v=abc"))
        // The reading keeps its Varies category; its level (and its own category) come from the verdict.
        assertEquals(listOf("VARIABLE" to "DEEP", "VARIABLE" to "MIXED"), rows())
        assertEquals(listOf("youtube.com/watch?v=zzz"), v.unjudged(0, 10).map { it.first })
    }

    @Test fun twoMatchingAnswersForASourceMakeARule() {
        val v = Verdicts(db)
        val key = "reddit r/firebase"
        v.set("reddit.com/r/firebase/comments/1", "Rules question", Category.CORE_WORK, Source.USER_LABEL, key, 1)
        assertNull(v.ruleFor(key)) // one answer isn't enough
        v.set("reddit.com/r/firebase/comments/2", "Functions v2", Category.CORE_WORK, Source.USER_LABEL, key, 2)
        assertEquals(Category.CORE_WORK, v.ruleFor(key))
        assertEquals(listOf(key to Category.CORE_WORK), v.rules())
        // AI verdicts never teach; disagreeing answers of yours remove the rule.
        v.set("reddit.com/r/firebase/comments/3", "Meme", Category.ENTERTAINMENT, Source.AI_PAGE, key, 3)
        assertEquals(Category.CORE_WORK, v.ruleFor(key))
        v.set("reddit.com/r/firebase/comments/4", "Meme 2", Category.ENTERTAINMENT, Source.USER_LABEL, key, 4)
        assertNull(v.ruleFor(key))
        v.set("reddit.com/r/firebase/comments/5", "Meme 3", Category.ENTERTAINMENT, Source.USER_LABEL, key, 5)
        assertEquals(Category.ENTERTAINMENT, v.ruleFor(key))
        v.deleteRule(key); assertNull(v.ruleFor(key))
    }
}
