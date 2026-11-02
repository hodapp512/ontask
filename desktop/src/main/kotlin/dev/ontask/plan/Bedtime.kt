package dev.ontask.plan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Bedtime reminders. From [bedtimeMin] (minutes after midnight, e.g. 22:30) until [wakeMin] the next morning (5 AM)
 * is "the night". At bedtime, if you're at the computer, one reminder; after that, another every [repeatMin] minutes
 * while you're still at it, whatever you're doing (working late counts too; "I'm working" buys 30 minutes). Each one
 * is firmer: from the third on, the window can't be closed for [lockSec] seconds.
 */
class Bedtime(
    val bedtimeMin: Int,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val wakeMin: Int = 5 * 60,
    private val repeatMin: Int = 15,
) {
    data class Nudge(val night: LocalDate, val level: Int, val minutesPast: Int, val lockSec: Int)

    /** Per night: how many reminders so far, when the last one came, and until when you snoozed. */
    data class NightState(val night: LocalDate, val count: Int = 0, val lastTs: Long = 0, val snoozeUntil: Long = 0)

    private fun minuteOfDay(now: Long) = Instant.ofEpochSecond(now).atZone(zone).let { it.hour * 60 + it.minute }

    /** The night [now] belongs to (the date bedtime fell on), or null outside bedtime-to-morning. */
    fun nightOf(now: Long): LocalDate? {
        val date = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val m = minuteOfDay(now)
        val afterBedtime = if (bedtimeMin >= wakeMin) m >= bedtimeMin else m in bedtimeMin until wakeMin
        return when {
            bedtimeMin >= wakeMin && afterBedtime -> date
            bedtimeMin >= wakeMin && m < wakeMin -> date.minusDays(1)
            bedtimeMin < wakeMin && afterBedtime -> date.minusDays(1) // bedtime after midnight belongs to the evening before
            else -> null
        }
    }

    /** Minutes since bedtime on [night]. */
    fun minutesPast(night: LocalDate, now: Long): Int {
        val bed = night.plusDays(if (bedtimeMin < wakeMin) 1 else 0).atStartOfDay(zone).toEpochSecond() + bedtimeMin * 60L
        return ((now - bed) / 60).toInt().coerceAtLeast(0)
    }

    /** The reminder to show now, if any. [active]: you're at the computer (not Away). */
    fun due(now: Long, active: Boolean, state: NightState?): Nudge? {
        val night = nightOf(now) ?: return null
        val s = state?.takeIf { it.night == night } ?: NightState(night)
        if (!active || now < s.snoozeUntil) return null
        if (s.count > 0 && now - s.lastTs < repeatMin * 60L) return null
        val level = s.count + 1
        return Nudge(night, level, minutesPast(night, now), lockSec = if (level >= 3) 20 else 0)
    }

    companion object {
        const val DEFAULT_MIN = 21 * 60 + 30
    }
}
