package fr.cgaspard.lumixphotos

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class LumixApp : Application() {

    val session by lazy { CameraSession(this) }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(DownloadService.CHANNEL_PROGRESS, "Téléchargement en cours", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(DownloadService.CHANNEL_DONE, "Téléchargement terminé", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }
}
