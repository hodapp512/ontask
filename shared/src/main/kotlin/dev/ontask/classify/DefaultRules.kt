package dev.ontask.classify

import dev.ontask.Category
import dev.ontask.Category.Companion.CORE_WORK
import dev.ontask.Category.Companion.ENTERTAINMENT
import dev.ontask.Category.Companion.OTHER
import dev.ontask.Category.Companion.PERSONAL_ADMIN
import dev.ontask.Category.Companion.VARIABLE
import dev.ontask.Category.Companion.WORK_SUPPORT

/** Simple built-in rules. Users override them with their own (see RuleStore). Ids are negative. */
object DefaultRules {
    private val apps = mapOf(
        CORE_WORK to listOf("Xcode", "IntelliJ IDEA", "IntelliJ IDEA CE", "Android Studio", "Visual Studio Code", "Code", "Cursor",
            "Terminal", "iTerm2", "Warp", "Ghostty", "Zed", "Sublime Text", "PyCharm", "WebStorm", "Claude"),
        WORK_SUPPORT to listOf("Notes", "Reminders", "Numbers", "Pages", "Keynote", "Preview", "Slack", "zoom.us", "Microsoft Teams"),
        // Work or personal: judged window by window (the title names the mailbox or account).
        VARIABLE to listOf("Mail", "Calendar", "Microsoft Outlook"),
        // Moments between things: count as what's around them (see Borrow).
        // OnTask itself is Other: its windows aren't work (and the drift ladder pauses while one is in front).
        OTHER to listOf("OnTask", "dev.ontask.app", "UserNotificationCenter", "loginwindow", "Spotlight", "Dock", "Control Center", "ControlCenter", "Notification Center",
            "NotificationCenter", "Screenshot", "screencaptureui", "SecurityAgent", "CoreServicesUIAgent", "Finder", "Raycast", "Alfred", "Launchpad"),
        PERSONAL_ADMIN to listOf("Messages", "FaceTime", "WhatsApp", "Telegram", "Signal"),
        ENTERTAINMENT to listOf("Spotify", "Music", "TV", "Netflix", "VLC", "Steam", "Discord"),
    )

    /** Android apps by package name (APP rules also match the bundle/package id). */
    private val androidApps = mapOf(
        WORK_SUPPORT to listOf("com.microsoft.teams", "com.Slack", "us.zoom.videomeetings"),
        VARIABLE to listOf("com.google.android.gm", "com.google.android.calendar", "com.samsung.android.calendar", "com.google.android.apps.docs",
            "com.google.android.apps.docs.editors.docs", "com.google.android.apps.docs.editors.sheets", "com.microsoft.office.outlook",
            "com.google.android.youtube", "com.reddit.frontpage", "com.facebook.katana", "com.linkedin.android", "com.medium.reader", "com.substack.app"),
        // OnTask itself is Other, not work (its prompt screens pause the drift instead).
        OTHER to listOf("dev.ontask.android", "com.android.systemui", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.samsung.android.app.galaxyfinder", "com.android.intentresolver"),
        PERSONAL_ADMIN to listOf("com.samsung.android.messaging", "com.google.android.apps.messaging", "com.whatsapp", "org.telegram.messenger",
            "org.thoughtcrime.securesms", "com.samsung.android.dialer", "com.google.android.dialer", "com.android.settings",
            "com.sec.android.app.camera", "com.google.android.apps.maps", "com.samsung.android.app.notes", "com.google.android.keep",
            "com.amazon.mShop.android.shopping", "com.sec.android.gallery3d", "com.google.android.apps.photos"),
        ENTERTAINMENT to listOf("com.instagram.android", "com.zhiliaoapp.musically", "com.twitter.android", "com.netflix.mediaclient", "com.spotify.music",
            "com.disney.disneyplus", "tv.twitch.android.app", "com.discord", "com.snapchat.android", "com.pinterest"),
    )

    /** Websites: see DefaultSites (about 1,200 common sites); browsers' new-tab pages are Other. */
    private val domains = DefaultSites.domains().let { it + (OTHER to listOf("newtab", "new-tab-page")) }

    /** Browser windows by title ("App|Title", see [Rule]): new tabs, the profile picker, OnTask's blocked page. */
    private val titles = mapOf(
        OTHER to BROWSERS.flatMap { b -> listOf("New Tab", "New Incognito Tab", "New Private Tab", "Who's using Chrome?", "Blocked · OnTask", "about:blank").map { "$b|$it" } },
    )

    val all: List<Rule> = buildList {
        var id = -1L
        fun add(kind: RuleKind, map: Map<Category, List<String>>) =
            map.forEach { (cat, items) -> items.forEach { add(Rule(id--, kind, it, cat, user = false)) } }
        add(RuleKind.APP, apps)
        add(RuleKind.APP, androidApps)
        add(RuleKind.DOMAIN, domains)
        add(RuleKind.TITLE, titles)
    }
}
