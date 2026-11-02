package dev.ontask.classify

import dev.ontask.Category
import dev.ontask.Category.Companion.CORE_WORK
import dev.ontask.Category.Companion.ENTERTAINMENT
import dev.ontask.Category.Companion.PERSONAL_ADMIN
import dev.ontask.Category.Companion.VARIABLE
import dev.ontask.Category.Companion.WORK_SUPPORT

/** Simple built-in rules. Users override them with their own (see RuleStore). Ids are negative. */
object DefaultRules {
    private val apps = mapOf(
        CORE_WORK to listOf("Xcode", "IntelliJ IDEA", "IntelliJ IDEA CE", "Android Studio", "Visual Studio Code", "Code", "Cursor",
            "Terminal", "iTerm2", "Warp", "Ghostty", "Zed", "Sublime Text", "PyCharm", "WebStorm", "Claude"),
        WORK_SUPPORT to listOf("Mail", "Calendar", "Notes", "Reminders", "Numbers", "Pages", "Keynote", "Finder", "Preview",
            "Slack", "zoom.us", "Microsoft Teams"),
        PERSONAL_ADMIN to listOf("Messages", "FaceTime", "WhatsApp", "Telegram", "Signal"),
        ENTERTAINMENT to listOf("Spotify", "Music", "TV", "Netflix", "VLC", "Steam", "Discord"),
    )

    /** Android apps by package name (APP rules also match the bundle/package id). */
    private val androidApps = mapOf(
        WORK_SUPPORT to listOf("com.google.android.gm", "com.google.android.calendar", "com.samsung.android.calendar", "com.microsoft.teams",
            "com.Slack", "us.zoom.videomeetings", "com.google.android.apps.docs", "com.google.android.apps.docs.editors.docs",
            "com.google.android.apps.docs.editors.sheets", "com.microsoft.office.outlook", "dev.ontask.android"),
        PERSONAL_ADMIN to listOf("com.samsung.android.messaging", "com.google.android.apps.messaging", "com.whatsapp", "org.telegram.messenger",
            "org.thoughtcrime.securesms", "com.samsung.android.dialer", "com.google.android.dialer", "com.android.settings",
            "com.sec.android.app.camera", "com.google.android.apps.maps", "com.samsung.android.app.notes", "com.google.android.keep",
            "com.amazon.mShop.android.shopping", "com.sec.android.gallery3d", "com.google.android.apps.photos"),
        VARIABLE to listOf("com.google.android.youtube", "com.reddit.frontpage", "com.facebook.katana", "com.linkedin.android", "com.medium.reader", "com.substack.app"),
        ENTERTAINMENT to listOf("com.instagram.android", "com.zhiliaoapp.musically", "com.twitter.android", "com.netflix.mediaclient", "com.spotify.music",
            "com.disney.disneyplus", "tv.twitch.android.app", "com.discord", "com.snapchat.android", "com.pinterest"),
    )

    private val domains = mapOf(
        CORE_WORK to listOf("github.com", "gitlab.com", "stackoverflow.com", "localhost", "console.firebase.google.com",
            "developer.apple.com", "kotlinlang.org", "docs.oracle.com", "claude.ai"),
        WORK_SUPPORT to listOf("mail.google.com", "calendar.google.com", "docs.google.com", "drive.google.com", "notion.so", "atlassian.net",
            "slack.com", "teams.microsoft.com", "meet.google.com", "zoom.us", "wikipedia.org", "coursera.org", "udemy.com", "khanacademy.org", "edx.org"),
        // Could be work or a time sink: judged page by page (feeds count as time-wasting).
        VARIABLE to listOf("youtube.com", "vimeo.com", "reddit.com", "facebook.com", "linkedin.com", "medium.com", "substack.com"),
        ENTERTAINMENT to listOf("netflix.com", "twitch.tv", "hulu.com", "disneyplus.com", "spotify.com", "instagram.com", "x.com", "twitter.com",
            "tiktok.com", "threads.net", "news.ycombinator.com", "cnn.com", "nytimes.com", "bbc.com", "foxnews.com", "theguardian.com", "washingtonpost.com"),
        PERSONAL_ADMIN to listOf("amazon.com", "ebay.com", "etsy.com", "walmart.com", "target.com", "web.whatsapp.com"),
    )

    val all: List<Rule> = buildList {
        var id = -1L
        fun add(kind: RuleKind, map: Map<Category, List<String>>) =
            map.forEach { (cat, items) -> items.forEach { add(Rule(id--, kind, it, cat, user = false)) } }
        add(RuleKind.APP, apps)
        add(RuleKind.APP, androidApps)
        add(RuleKind.DOMAIN, domains)
    }
}
