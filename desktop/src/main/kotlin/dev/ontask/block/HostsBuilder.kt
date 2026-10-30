package dev.ontask.block

object HostsBuilder {
    private const val BEGIN = "# --- OnTask begin ---"
    private const val END = "# --- OnTask end ---"

    /** macOS defaults that must survive because the daemon replaces the whole file. */
    private val SYSTEM = """
        127.0.0.1 localhost
        255.255.255.255 broadcasthost
        ::1 localhost
    """.trimIndent()

    private const val ADS_BEGIN = "# --- OnTask ad blocking begin ---"
    private const val ADS_END = "# --- OnTask ad blocking end ---"

    /**
     * [blocked] = domains to block right now (already minus unblocked ones). Empty = nothing blocked.
     * [ads] = ready-made `0.0.0.0 host` lines from the ad-blocking list (empty when ad blocking is off).
     */
    fun build(blocked: Collection<String>, ads: List<String> = emptyList()): String = buildString {
        appendLine(SYSTEM)
        appendLine()
        appendLine(BEGIN)
        blocked.toSortedSet().forEach { d -> appendLine("0.0.0.0 $d"); appendLine("0.0.0.0 www.$d") }
        appendLine(END)
        if (ads.isNotEmpty()) {
            val own = blocked.flatMap { listOf("0.0.0.0 $it", "0.0.0.0 www.$it") }.toSet()
            appendLine()
            appendLine(ADS_BEGIN)
            ads.forEach { if (it !in own) appendLine(it) }
            appendLine(ADS_END)
        }
    }
}
