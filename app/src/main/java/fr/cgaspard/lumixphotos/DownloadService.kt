package fr.cgaspard.lumixphotos

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Service de premier plan pendant un téléchargement : notification de progression, et verrous
 * pour que ni le processeur ni le Wi-Fi ne s'endorment écran éteint. Le travail lui-même est
 * fait par [CameraSession].
 */
class DownloadService : Service() {

    private val scope = MainScope()
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val session get() = (application as LumixApp).session

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            session.cancelDownload()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, progressNotification(session.state.value.download), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        acquireLocks()
        if (observer == null) {
            observer = scope.launch {
                session.state
                    // Une mise à jour par Mo au plus : inutile de redessiner la notification plus souvent
                    .map { s -> s.download?.let { Triple(it.index, (it.doneBytes + it.bytes) / 1_000_000, it.retrying) } to s.download }
                    .distinctUntilChanged { a, b -> a.first == b.first }
                    .collect { (_, download) ->
                        if (download == null) {
                            finish()
                        } else {
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, progressNotification(download))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        session.state.value.message?.let { message ->
            val done = Notification.Builder(this, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Lumix Photos")
                .setContentText(message.lineSequence().first())
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(DONE_NOTIFICATION_ID, done)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun progressNotification(d: DownloadProgress?): Notification {
        val builder = Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(
                Notification.Action.Builder(
                    null, "Annuler",
                    PendingIntent.getService(
                        this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build()
            )
        if (d == null) return builder.setContentTitle("Préparation du téléchargement…").setProgress(0, 0, true).build()
        val done = d.doneBytes + d.bytes
        val text = d.retrying ?: buildString {
            append(formatSize(done))
            if (d.plannedBytes > 0) append(" / ${formatSize(d.plannedBytes)}")
            if (d.bytesPerSecond > 0 && d.plannedBytes > done) {
                append(" · reste ~${formatDuration((d.plannedBytes - done) / d.bytesPerSecond)}")
            }
        }
        return builder
            .setContentTitle("${d.index}/${d.count} · ${d.fileName}")
            .setContentText(text)
            .setProgress(1000, (d.totalFraction * 1000).toInt(), d.plannedBytes <= 0 && d.totalBytes <= 0)
            .build()
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF reste le seul mode efficace écran éteint
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumix:download")
                .apply { acquire(6 * 60 * 60 * 1000L) }
        }
        if (wifiLock == null) {
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lumix:download")
                .apply { acquire() }
        }
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_PROGRESS = "download_progress"
        const val CHANNEL_DONE = "download_done"
        private const val NOTIFICATION_ID = 1
        private const val DONE_NOTIFICATION_ID = 2
        private const val ACTION_CANCEL = "fr.cgaspard.lumixphotos.CANCEL"
    }
}
