package dev.ontask.track

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class PermState { GRANTED, DENIED, NOT_ASKED, UNKNOWN }

/** System Settings → Privacy & Security panes, by their URL anchor. */
enum class PrivacyPane(val anchor: String) { ACCESSIBILITY("Privacy_Accessibility"), SCREEN_RECORDING("Privacy_ScreenCapture"), AUTOMATION("Privacy_Automation") }

data class BrowserPermission(val bundleId: String, val name: String, val state: PermState)

data class PermissionStatus(val accessibility: PermState, val screenRecording: PermState, val browsers: List<BrowserPermission>) {
    companion object {
        val unknown = PermissionStatus(PermState.UNKNOWN, PermState.UNKNOWN, emptyList())

        fun parse(o: JsonObject?): PermissionStatus {
            if (o == null) return unknown
            fun flag(k: String) = when (o[k]?.jsonPrimitive?.booleanOrNull) { true -> PermState.GRANTED; false -> PermState.NOT_ASKED; null -> PermState.UNKNOWN }
            val browsers = o["automation"]?.jsonArray?.map { it.jsonObject }?.map { b ->
                BrowserPermission(
                    b["bundleId"]?.jsonPrimitive?.contentOrNull ?: "", b["name"]?.jsonPrimitive?.contentOrNull ?: "?",
                    automationState(b["status"]?.jsonPrimitive?.contentOrNull),
                )
            } ?: emptyList()
            return PermissionStatus(flag("accessibility"), flag("screenRecording"), browsers)
        }

        fun automationState(s: String?) = when (s) { "granted" -> PermState.GRANTED; "denied" -> PermState.DENIED; "not_asked" -> PermState.NOT_ASKED; else -> PermState.UNKNOWN }
    }
}

/**
 * Reads and requests macOS privacy permissions through the helper. macOS files them under the *responsible* app:
 * OnTask.app once installed, but the terminal or IDE that started `./gradlew run` during development.
 */
class Permissions(private val helper: HelperClient) {
    /** True when running from the packaged OnTask.app (the jpackage launcher sets this). */
    val installed: Boolean get() = System.getProperty("jpackage.app-path") != null

    /** Restarts the helper first so a grant made in System Settings is picked up. */
    suspend fun status(fresh: Boolean = false): PermissionStatus {
        if (fresh) helper.restart()
        return PermissionStatus.parse(helper.request("permissions"))
    }

    /** Shows the system prompt the first time and adds the app to the list; then opens the pane to switch it on. */
    suspend fun requestAccessibility() { helper.request("request_accessibility"); open(PrivacyPane.ACCESSIBILITY) }

    suspend fun requestScreenRecording() { helper.request("request_screen_recording"); open(PrivacyPane.SCREEN_RECORDING) }

    /** Asks for Automation of one browser (blocks until the user answers the prompt). */
    suspend fun requestAutomation(bundleId: String): PermState =
        PermissionStatus.automationState(helper.request("request_automation", 120_000, mapOf("bundleId" to bundleId))?.get("status")?.jsonPrimitive?.contentOrNull)

    suspend fun open(pane: PrivacyPane) {
        if (helper.request("open_settings", params = mapOf("pane" to pane.anchor)) == null)
            runCatching { ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?${pane.anchor}").start() }
    }
}
