package dev.ontask.android

import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The levels as the Mac shows them: Focused, Mixed, Wasted, Away. */
enum class Lvl(val key: String, val label: String, val color: Color) {
    DEEP("DEEP", "Focused", Color(0xFF2E9E5B)), MIXED("MIXED", "Mixed", Color(0xFFE0A526)),
    WASTED("WASTED", "Wasted", Color(0xFFD64545)), AWAY("AWAY", "Away", Color(0xFF9AA5B1));
    companion object { fun of(short: String?) = entries.firstOrNull { it.key.first().toString() == short } ?: AWAY }
}

fun fmtDuration(sec: Long): String {
    val h = sec / 3600; val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m" else "${sec}s"
}

private val clockFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
fun clock(min: Int): String = LocalTime.of((min / 60) % 24, min % 60).format(clockFmt)

/** Seconds after the day's start → the clock time that day (right on daylight-saving days). */
fun clockAt(sec: Long, dayStart: Long?, tz: String?): String =
    if (dayStart != null) Instant.ofEpochSecond(dayStart + sec).atZone(tz?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()).toLocalTime().format(clockFmt)
    else clock((sec / 60).toInt())

/** "Nov 2, 2026", or "Monday, Nov 2, 2026". */
fun fmtDate(d: LocalDate, weekday: Boolean = false): String = d.format(DateTimeFormatter.ofPattern(if (weekday) "EEEE, MMM d, yyyy" else "MMM d, yyyy", Locale.US))

fun dayKey(d: LocalDate = LocalDate.now()): String = d.toString()

fun firstName(n: String?): String = n?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.ifBlank { null } ?: "Friend"

fun ago(ms: Long): String {
    val s = (System.currentTimeMillis() - ms) / 1000
    return when { s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> "${s / 86400} d ago" }
}

val REACTIONS = listOf("thumbs_up" to "👍", "proud" to "Proud of you", "call_me" to "Call me when you can", "take_a_walk" to "Take a walk")
val MESSAGE_TYPES = listOf("binge_alert" to "Struggling alerts", "unblock_alert" to "Unblocks during work hours", "checkin" to "Daily report", "work_hours_change" to "Work-hours changes", "work_unblock" to "Work unblocks")

/** Numbers from Firestore come back as Long or Double. */
fun Any?.num(): Long = (this as? Number)?.toLong() ?: 0L
