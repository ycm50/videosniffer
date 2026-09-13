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
import com.videosniffer.download.DownloadProgress
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

    /** 上一次实际提交的通知内容，用于跳过无变化的重建 */
    private var lastNotificationText: String? = null
    private var lastNotificationProgress: Int = Int.MIN_VALUE

    override fun onCreate() {
        super.onCreate()
        // START_STICKY 下系统可能在没有 Activity 的情况下重建本服务（进程被杀后）。
        // 此时 DownloadManager 尚未初始化，若不在这里兜底：
        //   - pumpQueue() 会因 !initialized 直接返回，排队任务永远不启动；
        //   - tasks 仍是空列表 → 下面的收集器立刻 stopSelf()，服务刚起来就自杀。
        // init() 幂等，重复调用无副作用。
        DownloadManager.init(this)
        createChannel()
        startAsForeground(getString(R.string.notification_preparing), -1)
        scope.launch {
            DownloadManager.tasks.collectLatest { tasks ->
                // 排队中也要保持前台服务（否则等待补位的任务会被系统回收）
                val active = tasks.firstOrNull {
                    it.state in setOf(
                        DownloadState.QUEUED, DownloadState.PENDING, DownloadState.PROBING,
                        DownloadState.DOWNLOADING, DownloadState.EXPORTING
                    )
                }
                if (active == null) {
                    // 无存活任务（含无任务）时由本收集器统一停服，
                    // 不再由调度器并发判断，避免把刚启动的服务杀掉
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
        val waiting = task.state == DownloadState.QUEUED || task.state == DownloadState.PENDING
        // 进度一律走 DownloadProgress（单位是字节；m3u8 的总量是估算值，下载中不显示 100%），
        // 不要在这里另写一份百分比公式 —— 两个入口各算一套迟早会不一致
        val pct = if (waiting) {
            DownloadProgress.UNKNOWN
        } else {
            DownloadProgress.displayPercent(task)
        }
        val text = when {
            waiting -> getString(R.string.notification_queued, task.title)
            task.state == DownloadState.EXPORTING ->
                getString(R.string.notification_exporting, task.title)
            else -> getString(
                R.string.notification_downloading, task.title, if (pct >= 0) "$pct%" else ""
            )
        }
        // 进度每 300ms 上报一次，但百分比通常一两秒才变一格。
        // 内容没变就跳过：否则会在主线程上每秒重建三次通知（startForeground 有实际开销）。
        if (text == lastNotificationText && pct == lastNotificationProgress) return
        lastNotificationText = text
        lastNotificationProgress = pct

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
