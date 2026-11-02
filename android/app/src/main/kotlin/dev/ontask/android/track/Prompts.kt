package dev.ontask.android.track

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.firestore.ListenerRegistration
import dev.ontask.Level
import dev.ontask.android.Cloud
import dev.ontask.android.OnTaskApp
import dev.ontask.android.R
import dev.ontask.android.num
import dev.ontask.intervene.Breaks
import dev.ontask.intervene.Ladder
import dev.ontask.intervene.Step
import java.time.LocalDate
import java.time.LocalTime

/**
 * Drift prompts on the phone, in work hours, the same way as on the Mac: after 25+ minutes of focus (counted by the
 * Mac, shared in users/{uid}/state/breaks) a minute on something time-wasting asks "Is this a break?"; otherwise
 * two minutes bring "It looks like you're drifting". Step 1 is a heads-up notification you can't swipe away; steps
 * 2 and 3 are a full-screen OnTask screen. A break or lunch started here is shared, so the Mac stays quiet too.
 */
class Prompts(private val svc: AccessibilityService) {
    val ladder = Ladder()
    @Volatile var state: Map<String, Any?> = emptyMap(); private set
    private var reg: ListenerRegistration? = null
    private var shownStep = Step.NONE
    private var activityClosedAt = 0L
    private var lastBreakEnd = 0L
    @Volatile var lastWasted: Pair<String, String?>? = null // what you were on: (key for "I'm working", label)

    fun start(uid: String) {
        if (reg != null) return
        reg = Cloud.db.document("users/$uid/state/breaks").addSnapshotListener { s, _ -> state = s?.data ?: emptyMap() }
    }

    fun stop() { reg?.remove(); reg = null; checkinRegs.forEach { it.remove() } }

    @Suppress("UNCHECKED_CAST")
    private val active: Map<String, Any?>? get() = (state["active"] as? Map<String, Any?>)?.takeIf { it["until"].num() > now() }
    val onBreak: Boolean get() = active != null
    val focusedSec: Long get() = state["focusedSec"].num()
    val earnedSec: Long get() = Breaks.earnedFor(focusedSec)

    private fun now() = System.currentTimeMillis() / 1000

    /** One tick: [level] of what's in front (null = screen off). */
    fun tick(now: Long, level: Level?, work: Boolean) {
        // A break that ran out: "That's 5 minutes. Back to work?" once, and drifting on escalates at once.
        val a = (state["active"] as? Map<*, *>)
        val until = a?.get("until").num()
        if (a != null && until in (lastBreakEnd + 1)..now) { lastBreakEnd = until; ladder.breakOver(now); breakOverNotice(a["kind"] == "LUNCH") }
        ladder.cfg = ladder.cfg.copy(step1WastedSec = if (earnedSec > 0) 60 else 120)
        val iv = ladder.tick(now, level == Level.WASTED, work && !onBreak && level != null, 0)
        if (iv != null) show(iv.step)
        if (ladder.step == Step.NONE && shownStep != Step.NONE) clear()
        // A full-screen step closed with Back: it comes back after 30 seconds while you're still drifting.
        if (shownStep.n >= 2 && activityClosedAt > 0 && now - activityClosedAt >= 30 && level == Level.WASTED) { activityClosedAt = 0; launchScreen(shownStep) }
    }

    private fun show(step: Step) {
        shownStep = step
        if (step == Step.PROMPT) stepOneNotification() else { NotificationManagerCompat.from(svc).cancel(NOTE); launchScreen(step) }
    }

    fun screenClosed() { activityClosedAt = now() }

    private fun launchScreen(step: Step) {
        svc.startActivity(Intent(svc, PromptActivity::class.java).putExtra("step", step.n).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun action(name: String, label: String) = NotificationCompat.Action(0, label,
        PendingIntent.getBroadcast(svc, name.hashCode(), Intent(svc, PromptActions::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE))

    private fun stepOneNotification() {
        val earned = earnedSec / 60
        val b = NotificationCompat.Builder(svc, OnTaskApp.CHANNEL).setSmallIcon(R.drawable.ic_notify)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER).setOngoing(true).setOnlyAlertOnce(false)
        if (earned > 0) {
            b.setContentTitle("Is this a break?").setContentText("You've focused ${focusedSec / 60} min, so you've got $earned minutes.")
                .addAction(action(BREAK, "Yes, $earned-min break")).addAction(action(WORKING, "I'm working")).addAction(action(BACK, "Back to work"))
        } else {
            val toEarn = (25 * 60 - focusedSec).coerceAtLeast(0) / 60
            b.setContentTitle("It looks like you're drifting.").setStyle(NotificationCompat.BigTextStyle().bigText(
                "Is this what you meant to be doing right now?" + if (toEarn > 0) "\nYou've focused ${focusedSec / 60} min since your last break. $toEarn more earns a 5-minute break." else ""))
                .addAction(action(BACK, "Back to work")).addAction(action(WORKING, "I'm working"))
            if (lunchOffered()) b.addAction(action(LUNCH, "Lunch"))
        }
        runCatching { NotificationManagerCompat.from(svc).notify(NOTE, b.build()) }
    }

    private fun breakOverNotice(lunch: Boolean) {
        val n = NotificationCompat.Builder(svc, OnTaskApp.CHANNEL).setSmallIcon(R.drawable.ic_notify).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentTitle(if (lunch) "Lunch is over. Back to work?" else "Break's over. Back to work?")
            .addAction(action(BACK, "Back to work")).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(svc).notify(NOTE, n) }
    }

    // ---- Bedtime -----------------------------------------------------------------------------------------------

    private var night: dev.ontask.plan.Bedtime.NightState? = null

    /**
     * Bedtime on the phone: while you're using it past bedtime, the same reminders as the Mac (every 15 minutes,
     * firmer each time; from the third one a full screen that waits 20 seconds).
     */
    fun bedtimeTick(now: Long, inUse: Boolean) {
        val min = PhoneRules.schedule?.get("bedtimeMin")?.num()?.toInt()?.takeIf { it >= 0 } ?: return
        val b = dev.ontask.plan.Bedtime(min)
        val n = b.due(now, inUse, night) ?: return
        night = dev.ontask.plan.Bedtime.NightState(n.night, n.level, now, night?.takeIf { it.night == n.night }?.snoozeUntil ?: 0)
        val title = if (n.level == 1) "It's bedtime." else "You're ${n.minutesPast} minutes past bedtime."
        val text = when (n.level) { 1 -> "Time to put the phone down. Whatever's left will still be there tomorrow."; 2 -> "Still up? Late nights borrow from tomorrow."; else -> "Reminder number ${n.level} tonight. Put the phone down and get some sleep." }
        val note = NotificationCompat.Builder(svc, OnTaskApp.CHANNEL).setSmallIcon(R.drawable.ic_notify).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentTitle(title).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(action(BED, "Going to bed")).addAction(action(SNOOZE, "10 more minutes")).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(svc).notify(BED_NOTE, note) }
        if (n.lockSec > 0) svc.startActivity(Intent(svc, PromptActivity::class.java).putExtra("bedtime", "$title\n$text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "Going to bed" just closes the reminder (no more tonight); "10 more minutes": the next one in 10. */
    fun bedtimeAnswer(bed: Boolean) {
        night = night?.let { it.copy(snoozeUntil = now() + if (bed) 6 * 3600 else 600) }
        NotificationManagerCompat.from(svc).cancel(BED_NOTE); PromptActivity.closeAll()
    }

    // ---- Check-ins -------------------------------------------------------------------------------------------

    @Volatile private var rhythm: Map<String, Any?> = emptyMap()
    @Volatile private var checkins: Map<String, Any?> = emptyMap()
    @Volatile private var phoneCheckins: Map<String, Any?> = emptyMap()
    @Volatile private var macActiveMs = 0L
    private var checkinRegs: List<ListenerRegistration> = emptyList()
    private var checkinDay = ""
    private var lastCheckinNote = 0L

    private fun listenCheckins(uid: String) {
        val day = LocalDate.now().toString()
        if (day == checkinDay) return
        checkinRegs.forEach { it.remove() }; checkinDay = day
        checkinRegs = listOf(
            Cloud.db.document("users/$uid/state/rhythm").addSnapshotListener { s, _ -> rhythm = s?.data ?: emptyMap() },
            Cloud.db.document("users/$uid/checkins/$day").addSnapshotListener { s, _ -> checkins = s?.data ?: emptyMap() },
            Cloud.db.document("users/$uid/phoneCheckins/$day").addSnapshotListener { s, _ -> phoneCheckins = s?.data ?: emptyMap() },
            Cloud.db.collection("users/$uid/devices").whereEqualTo("kind", "mac").addSnapshotListener { s, _ -> macActiveMs = s?.documents?.maxOfOrNull { it.get("lastActiveMs").num() } ?: 0 },
        )
    }

    /** The check-in that's due today and not done on either device, or null. */
    private fun dueCheckin(): String? {
        val due = (rhythm["due"] as? String)?.takeIf { it.isNotBlank() && rhythm["date"] == LocalDate.now().toString() } ?: return null
        val done = when (due) {
            "PLANNING", "PLANNING_SHORT" -> !(checkins["plan"] as? String).isNullOrBlank() || phoneCheckins["planning"] != null
            "MIDDAY" -> checkins["midday"] != null || phoneCheckins["midday"] != null
            else -> checkins["evening"] == true || phoneCheckins["evening"] != null
        }
        return due.takeIf { !done }
    }

    /**
     * Offers the due check-in here when you're on the phone and haven't used the Mac for 5 minutes: a notification
     * that opens it (again every 20 minutes until it's done on either device).
     */
    fun checkinTick(inUse: Boolean) {
        Cloud.uid?.let { listenCheckins(it) }
        val due = dueCheckin()
        if (due == null) { NotificationManagerCompat.from(svc).cancel(CHECKIN_NOTE); return }
        val now = System.currentTimeMillis()
        if (!inUse || now - macActiveMs < 5 * 60_000 || now - lastCheckinNote < 20 * 60_000) return
        lastCheckinNote = now
        val open = PendingIntent.getActivity(svc, 7004, Intent(svc, dev.ontask.android.MainActivity::class.java).putExtra(dev.ontask.android.MainActivity.CHECKIN, due)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when (due) { "MIDDAY" -> "Midday check-in"; "EVENING" -> "Evening check-in"; else -> "Plan your day" }
        val n = NotificationCompat.Builder(svc, OnTaskApp.CHANNEL).setSmallIcon(R.drawable.ic_notify).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentTitle(title).setContentText("A couple of minutes, right here on your phone.").setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(svc).notify(CHECKIN_NOTE, n) }
    }

    fun lunchOffered(): Boolean = state["lunchDay"] != LocalDate.now().toString() && LocalTime.now().let { it.hour in 11..13 }

    fun clear() { shownStep = Step.NONE; activityClosedAt = 0; NotificationManagerCompat.from(svc).cancel(NOTE); PromptActivity.closeAll() }

    /** "Back to work": a short grace, and the phone goes to its home screen (away from what you were on). */
    fun backToWork() { ladder.backToWork(now()); clear(); svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }

    /** "I'm working": what you were on counts as work from now on (a rule or verdict, as on the Mac). */
    fun working() { ladder.workConfirmed(); clear(); (svc as? Tracker)?.markWork() }

    /** A break of the earned length, or lunch (45 min): shared, so prompts stay quiet on the Mac too. */
    fun startBreak(lunch: Boolean) {
        val uid = Cloud.uid ?: return
        val start = now(); val until = start + if (lunch) 45 * 60 else earnedSec.coerceAtLeast(300)
        val doc = mutableMapOf<String, Any?>("active" to mapOf("kind" to if (lunch) "LUNCH" else "BREAK", "start" to start, "until" to until),
            "by" to Cloud.deviceId(svc), "updatedAt" to System.currentTimeMillis(), "focusedSec" to 0L)
        if (lunch) doc["lunchDay"] = LocalDate.now().toString()
        Cloud.db.document("users/$uid/state/breaks").set(doc, com.google.firebase.firestore.SetOptions.merge())
        state = state + doc
        ladder.reset(); clear()
    }

    /** Lets your partner know (in work hours): the same alert as the Mac's step 3, through the server. */
    fun messagePartner(text: String) {
        val uid = Cloud.uid ?: return
        Cloud.db.collection("users/$uid/events").add(mapOf("type" to "binge_alert", "ts" to System.currentTimeMillis(), "payload" to mapOf("message" to text)))
    }

    companion object {
        const val NOTE = 7002
        const val BED_NOTE = 7003
        const val CHECKIN_NOTE = 7004
        const val BED = "dev.ontask.android.BED"; const val SNOOZE = "dev.ontask.android.SNOOZE"
        const val BACK = "dev.ontask.android.BACK"; const val WORKING = "dev.ontask.android.WORKING"
        const val BREAK = "dev.ontask.android.BREAK"; const val LUNCH = "dev.ontask.android.LUNCH"
    }
}

/** The step-1 notification's buttons. */
class PromptActions : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = Tracker.running?.prompts ?: return
        when (i.action) {
            Prompts.BACK -> p.backToWork()
            Prompts.WORKING -> p.working()
            Prompts.BREAK -> p.startBreak(lunch = false)
            Prompts.LUNCH -> p.startBreak(lunch = true)
            Prompts.BED -> p.bedtimeAnswer(bed = true)
            Prompts.SNOOZE -> p.bedtimeAnswer(bed = false)
        }
    }
}
