package dev.ontask.ai

/** API keys live in the macOS Keychain (via the `security` tool), never in the database, settings or repo. */
class Keychain(private val service: String = "dev.ontask") {
    fun get(account: String): String? = run("find-generic-password", "-s", service, "-a", account, "-w")?.trim()?.ifEmpty { null }

    /** Note: the key is passed as a command-line argument to `security` for a moment (visible to same-user `ps`). */
    fun set(account: String, secret: String): Boolean = run("add-generic-password", "-U", "-s", service, "-a", account, "-w", secret) != null

    fun delete(account: String) { run("delete-generic-password", "-s", service, "-a", account) }

    private fun run(vararg args: String): String? = try {
        val p = ProcessBuilder(listOf("/usr/bin/security") + args).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText(); p.errorStream.readBytes()
        if (p.waitFor() == 0) out else null
    } catch (e: Exception) { null }
}
