package dev.ontask.classify

import dev.ontask.Category
import dev.ontask.Category.Companion.CORE_WORK
import dev.ontask.Category.Companion.WORK_SUPPORT
import dev.ontask.Category.Companion.LEARNING
import dev.ontask.Category.Companion.COMMUNICATION
import dev.ontask.Category.Companion.ENTERTAINMENT
import dev.ontask.Category.Companion.SOCIAL
import dev.ontask.Category.Companion.NEWS
import dev.ontask.Category.Companion.SHOPPING
import dev.ontask.Category.Companion.VARIABLE

/** Simple built-in rules. Users override them with their own (see RuleStore). Ids are negative. */
object DefaultRules {
    private val apps = mapOf(
        CORE_WORK to listOf("Xcode", "IntelliJ IDEA", "IntelliJ IDEA CE", "Android Studio", "Visual Studio Code", "Code", "Cursor",
            "Terminal", "iTerm2", "Warp", "Ghostty", "Zed", "Sublime Text", "PyCharm", "WebStorm", "Claude"),
        WORK_SUPPORT to listOf("Mail", "Calendar", "Notes", "Reminders", "Numbers", "Pages", "Keynote", "Finder", "Preview"),
        COMMUNICATION to listOf("Slack", "Messages", "zoom.us", "Microsoft Teams", "FaceTime", "Discord", "WhatsApp", "Telegram", "Signal"),
        ENTERTAINMENT to listOf("Spotify", "Music", "TV", "Netflix", "VLC", "Steam"),
    )

    private val domains = mapOf(
        CORE_WORK to listOf("github.com", "gitlab.com", "stackoverflow.com", "localhost", "console.firebase.google.com",
            "developer.apple.com", "kotlinlang.org", "docs.oracle.com", "claude.ai"),
        WORK_SUPPORT to listOf("mail.google.com", "calendar.google.com", "docs.google.com", "drive.google.com", "notion.so", "atlassian.net"),
        LEARNING to listOf("wikipedia.org", "coursera.org", "udemy.com", "khanacademy.org", "edx.org"),
        COMMUNICATION to listOf("slack.com", "web.whatsapp.com", "teams.microsoft.com", "meet.google.com", "zoom.us"),
        // Could be a tutorial or a time sink: judged video by video.
        VARIABLE to listOf("youtube.com", "vimeo.com", "medium.com", "substack.com"),
        ENTERTAINMENT to listOf("netflix.com", "twitch.tv", "hulu.com", "disneyplus.com", "spotify.com"),
        SOCIAL to listOf("facebook.com", "instagram.com", "x.com", "twitter.com", "reddit.com", "tiktok.com", "linkedin.com", "threads.net"),
        NEWS to listOf("news.ycombinator.com", "cnn.com", "nytimes.com", "bbc.com", "foxnews.com", "theguardian.com", "washingtonpost.com"),
        SHOPPING to listOf("amazon.com", "ebay.com", "etsy.com", "walmart.com", "target.com"),
    )

    val all: List<Rule> = buildList {
        var id = -1L
        fun add(kind: RuleKind, map: Map<Category, List<String>>) =
            map.forEach { (cat, items) -> items.forEach { add(Rule(id--, kind, it, cat, user = false)) } }
        add(RuleKind.APP, apps)
        add(RuleKind.DOMAIN, domains)
    }
}
