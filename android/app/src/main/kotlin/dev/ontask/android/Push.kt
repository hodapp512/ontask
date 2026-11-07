package dev.ontask.android

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Push from OnTask's server. In the background Android shows notification pushes itself; this shows them while the
 * app is open, and keeps this phone's token on the account when it changes.
 */
class Push : FirebaseMessagingService() {
    override fun onNewToken(token: String) { Cloud.saveToken(token) }

    override fun onMessageReceived(m: RemoteMessage) {
        val type = m.data["type"] ?: ""
        val title = m.data["title"] ?: m.notification?.title ?: "OnTask"
        val body = m.data["body"] ?: m.notification?.body ?: return
        val (id, ttlMin) = slot(type, m.data)
        val open = PendingIntent.getActivity(this, id, Intent(this, MainActivity::class.java)
            .apply { if (type == "evening_checkin_phone" || type == "checkin_reminder") putExtra(MainActivity.CHECKIN, "EVENING") }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val built = NotificationCompat.Builder(this, OnTaskApp.CHANNEL)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true)
            .setTimeoutAfter(ttlMin * 60_000L).build()
        runCatching { NotificationManagerCompat.from(this).notify(id, built) }
    }

    companion object {
        /** Server check-in reminders share one slot; the phone clears it once the check-in is done. */
        const val CHECKIN_PUSH = 7006

        /**
         * Where each kind of push goes and how long it stays: the same kind replaces the older one (one slot), and each
         * dismisses itself once it no longer applies.
         */
        fun slot(type: String, data: Map<String, String>): Pair<Int, Long> = when (type) {
            "evening_checkin_phone", "checkin_reminder" -> CHECKIN_PUSH to 180L
            "bedtime" -> 7003 to 60L
            "habit" -> (7100 + ((data["habitId"] ?: "").hashCode() and 0xff)) to 60L
            "reaction" -> 7007 to 12 * 60L
            // As a partner: the newest of each kind, per person you support; the daily report stays a day.
            "checkin" -> (7200 + ((data["linkId"] ?: "") + type).hashCode().and(0xfff)) to 24 * 60L
            else -> (7200 + ((data["linkId"] ?: "") + type).hashCode().and(0xfff)) to 12 * 60L
        }
    }
}
