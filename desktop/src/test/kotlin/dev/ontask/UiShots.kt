package dev.ontask

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ontask.db.Segment
import dev.ontask.db.TickStore
import dev.ontask.ui.OnTaskTheme
import dev.ontask.ui.ThemeMode
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId

/** Renders composables to PNGs under build/ui-shots so layout can be inspected without a display. */
object UiShots {
    val dir: Path = Path.of("build/ui-shots").also { Files.createDirectories(it) }

    fun newApp(): App {
        val tmp = Files.createTempDirectory("ontask-ui")
        System.setProperty("ontask.helper", "/nonexistent")
        val app = App(tmp)
        seed(app)
        return app
    }

    fun seed(app: App, date: LocalDate = LocalDate.now()) {
        val zone = ZoneId.systemDefault()
        val day = date.atStartOfDay(zone).toEpochSecond()
        fun seg(h: Double, mins: Int, cat: Category, name: String, domain: String? = null, src: Source = Source.RULE) {
            val start = day + (h * 3600).toLong()
            Segment(startTs = start, endTs = start + mins * 60L, n = mins * 12, seconds = mins * 60, app = name, bundle = null, title = "Sample window title", url = domain?.let { "https://$it/" },
                domain = domain, category = cat, source = src, ruleId = null, workTime = h in 9.0..17.0, skippedUnchanged = mins * 12 - 1).let { TickStore(app.db).save(listOf(it)) }
        }
        seg(8.5, 25, Category.WORK_SUPPORT, "Slack"); seg(9.0, 70, Category.CORE_WORK, "IntelliJ IDEA"); seg(10.2, 12, Category.ENTERTAINMENT, "Google Chrome", "instagram.com")
        seg(10.4, 55, Category.CORE_WORK, "IntelliJ IDEA"); seg(11.3, 20, Category.WORK_SUPPORT, "Google Chrome", "mail.google.com"); seg(12.0, 45, Category.UNKNOWN, "Mystery", null, Source.METADATA)
        seg(13.0, 90, Category.CORE_WORK, "Visual Studio Code"); seg(14.6, 25, Category.ENTERTAINMENT, "Google Chrome", "youtube.com"); seg(15.1, 80, Category.WORK_SUPPORT, "Google Chrome", "coursera.org")
        seg(17.5, 30, Category.AWAY, "x", null, Source.AWAY)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun shot(name: String, dark: Boolean, width: Int = 1000, height: Int = 1000, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            OnTaskTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { content() }
        }
        try {
            var t = 0L
            repeat(8) { scene.render(t); t += 100_000_000; Thread.sleep(120) }
            val img = scene.render(t)
            val data = img.encodeToData(EncodedImageFormat.PNG)!!
            Files.write(dir.resolve("$name-${if (dark) "dark" else "light"}.png"), data.bytes)
        } finally { scene.close() }
    }
}
