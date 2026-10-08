package com.antigravity.ide

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.antigravity.ide.agy.AgyService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(
            AgyService.CHANNEL_ID,
            "Server Antigravity",
            NotificationManager.IMPORTANCE_LOW
        )
        ch.description = "Status server Antigravity yang berjalan di latar belakang"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
