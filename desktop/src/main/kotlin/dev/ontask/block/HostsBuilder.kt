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

    /** [blocked] = domains to block right now (already minus unblocked ones). Empty = nothing blocked. */
    fun build(blocked: Collection<String>): String = buildString {
        appendLine(SYSTEM)
        appendLine()
        appendLine(BEGIN)
        blocked.toSortedSet().forEach { d -> appendLine("0.0.0.0 $d"); appendLine("0.0.0.0 www.$d") }
        appendLine(END)
    }
}
