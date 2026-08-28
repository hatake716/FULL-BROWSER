package io.github.hatake716.fullbrowser

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_SESSION = "session"
        const val TAG = "FB"
    }
}
