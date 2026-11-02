package dev.ontask.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class OnTaskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // One channel for everything OnTask sends: reminders, partner messages, the daily report.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "OnTask", NotificationManager.IMPORTANCE_HIGH).apply { description = "Reminders, check-ins and partner messages" },
        )
    }

    companion object { const val CHANNEL = "ontask" }
}
