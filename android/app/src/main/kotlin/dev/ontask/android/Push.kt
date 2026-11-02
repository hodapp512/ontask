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
        val n = m.notification ?: return
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val built = NotificationCompat.Builder(this, OnTaskApp.CHANNEL)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle(n.title ?: "OnTask").setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body)).setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), built) }
    }
}
