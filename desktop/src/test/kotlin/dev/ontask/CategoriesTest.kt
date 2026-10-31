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
        store.update(Category.NEWS, "Reading the news", Level.MIXED, variable = false)
        assertEquals("Reading the news", Category.valueOf("NEWS").label)
        // Rules return the edited category, so its new level applies.
        val c = Classifier(emptyList()).classify(Sample(app = "Safari", url = "https://www.cnn.com/x"))
        assertEquals(Level.MIXED, c.level)
    }

    @Test fun deletingResortsThenUnclassified() {
        tick("Safari", "https://reddit.com/r/kotlin", "r/kotlin", Category.ENTERTAINMENT) // a built-in rule knows reddit
        tick("Foo", null, "Something", Category.ENTERTAINMENT) // nothing knows Foo
        store.delete(Category.ENTERTAINMENT) { app, bundle, title, url -> Classifier(emptyList()).classify(Sample(app = app, bundleId = bundle, title = title, url = url)).category }
        assertTrue(Category.ENTERTAINMENT !in Category.assignable)
        assertEquals(listOf("SOCIAL" to "WASTED", "UNKNOWN" to "MIXED"), rows())
        // Built-in rules for the deleted category are skipped.
        assertEquals(Category.UNKNOWN, Classifier(emptyList()).classify(Sample(app = "Spotify")).category)
    }

    @Test fun youtubeVariesAndVerdictsAreRemembered() {
        assertEquals(Category.VARIABLE, Classifier(emptyList()).classify(Sample(app = "Safari", url = "https://www.youtube.com/watch?v=abc&t=10")).category)
        assertEquals("youtube.com/watch?v=abc", itemKey("Safari", "x", "https://m.youtube.com/watch?t=1&v=abc"))
        assertEquals("youtube.com/watch?v=abc", itemKey("Safari", "x", "https://youtu.be/abc"))
        tick("Safari", "https://www.youtube.com/watch?v=abc", "Kotlin coroutines talk", Category.VARIABLE)
        tick("Safari", "https://www.youtube.com/watch?v=zzz", "Funny cats", Category.VARIABLE)
        val v = Verdicts(db)
        assertNull(v.get("youtube.com/watch?v=abc"))
        v.set("youtube.com/watch?v=abc", "Kotlin coroutines talk", work = true, Source.USER_LABEL, 0)
        assertEquals(Level.DEEP, v.get("youtube.com/watch?v=abc"))
        assertEquals(listOf("VARIABLE" to "DEEP", "VARIABLE" to "MIXED"), rows())
    }
}
