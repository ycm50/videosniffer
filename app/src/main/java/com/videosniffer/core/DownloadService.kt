package com.videosniffer.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.videosniffer.MainActivity
import com.videosniffer.R
import com.videosniffer.download.DownloadManager
import com.videosniffer.download.DownloadState
import com.videosniffer.download.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 下载前台服务：下载期间保持进程存活并展示进度通知。
 * 下载状态由 [DownloadManager] 驱动，本服务仅负责通知展示与自停。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground(getString(R.string.notification_preparing), -1)
        scope.launch {
            DownloadManager.tasks.collectLatest { tasks ->
                val active = tasks.firstOrNull {
                    it.state in setOf(
                        DownloadState.PENDING, DownloadState.PROBING,
                        DownloadState.DOWNLOADING, DownloadState.EXPORTING
                    )
                }
                if (active == null) {
                    stopSelf()
                } else {
                    updateNotification(active)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(text: String, progress: Int) {
        val notification = buildNotification(text, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(task: DownloadTask) {
        val pct = if (task.totalBytes > 0) {
            ((task.downloadedBytes * 100L) / task.totalBytes).toInt().coerceIn(0, 100)
        } else {
            -1
        }
        val text = if (task.state == DownloadState.EXPORTING) {
            getString(R.string.notification_exporting, task.title)
        } else {
            getString(R.string.notification_downloading, task.title, if (pct >= 0) "$pct%" else "")
        }
        val notification = buildNotification(text, pct)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (progress >= 0) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "download"
        private const val NOTIFICATION_ID = 1001
    }
}
