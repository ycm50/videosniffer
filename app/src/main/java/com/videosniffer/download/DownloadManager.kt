package com.videosniffer.download

import android.content.Context
import android.content.Intent
import android.os.Environment
import com.videosniffer.core.DownloadService
import com.videosniffer.core.SettingsStore
import com.videosniffer.data.DownloadDbHelper
import com.videosniffer.download.engine.M3U8Downloader
import com.videosniffer.download.engine.SingleFileDownloader
import com.videosniffer.download.export.MediaStoreExporter
import com.videosniffer.sniff.DetectedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 下载调度器（单例）。
 * 负责：任务入队/启停/恢复/取消、协程调度、状态流、数据库持久化、前台服务生命周期。
 */
object DownloadManager {

    private lateinit var appContext: Context
    private lateinit var db: DownloadDbHelper
    private lateinit var settingsStore: SettingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks = _tasks.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val stopFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val pauseOnly = ConcurrentHashMap<String, AtomicBoolean>()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        db = DownloadDbHelper(appContext)
        settingsStore = SettingsStore(appContext)
        // 进程被杀后，运行中任务恢复为可续传的 PAUSED
        _tasks.value = db.loadTasks().map { t ->
            if (t.state in setOf(
                    DownloadState.PENDING, DownloadState.PROBING,
                    DownloadState.DOWNLOADING, DownloadState.EXPORTING
                )
            ) {
                t.copy(state = DownloadState.PAUSED)
            } else {
                t
            }
        }
        initialized = true
    }

    fun settings(): SettingsStore = settingsStore

    /** 入队一个新的下载任务并立即开始 */
    fun enqueue(media: DetectedMedia): String {
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = media.url,
            title = media.title ?: "未命名",
            quality = media.quality,
            type = media.ext,
            threadCount = settingsStore.threadCount,
            sourcePageUrl = media.sourcePageUrl,
            m3u8Url = if (media.ext.equals("m3u8", true)) media.url else null
        )
        db.insertTask(task)
        _tasks.value = _tasks.value + task
        startForegroundService()
        startTask(task.id)
        return task.id
    }

    fun startTask(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (activeJobs.containsKey(id)) return
        startForegroundService()
        stopFlags[id] = AtomicBoolean(false)
        pauseOnly[id] = AtomicBoolean(false)
        task.state = DownloadState.PENDING
        task.error = null
        updateTask(task)

        val job = scope.launch { runTask(task) }
        activeJobs[id] = job
        job.invokeOnCompletion {
            activeJobs.remove(id)
            stopFlags.remove(id)
            pauseOnly.remove(id)
            checkIdle()
        }
    }

    fun pauseTask(id: String) {
        pauseOnly[id] = AtomicBoolean(true)
        stopFlags[id] = AtomicBoolean(true)
        activeJobs[id]?.cancel()
        // 立即反映到 UI，避免等待协程退出
        val task = _tasks.value.firstOrNull { it.id == id }
        if (task != null && task.state != DownloadState.COMPLETED) {
            task.state = DownloadState.PAUSED
            updateTask(task)
        }
    }

    fun resumeTask(id: String) {
        val old = activeJobs[id]
        if (old != null && old.isActive) {
            // 旧任务仍在退出中，等其结束再启动，避免重复调度
            scope.launch {
                old.join()
                startTask(id)
            }
        } else {
            startTask(id)
        }
    }

    fun retryTask(id: String) {
        startTask(id)
    }

    fun cancelTask(id: String) {
        pauseOnly[id] = AtomicBoolean(false)
        stopFlags[id] = AtomicBoolean(true)
        activeJobs[id]?.cancel()
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (!activeJobs.containsKey(id)) {
            // 任务未在运行，直接清理
            task.state = DownloadState.CANCELED
            deleteFiles(task)
            db.deleteTask(id)
            _tasks.value = _tasks.value.filterNot { it.id == id }
        }
    }

    fun deleteTask(id: String) {
        activeJobs[id]?.cancel()
        val task = _tasks.value.firstOrNull { it.id == id }
        db.deleteTask(id)
        task?.let { deleteFiles(it) }
        _tasks.value = _tasks.value.filterNot { it.id == id }
    }

    // ---------- 内部执行 ----------

    private suspend fun runTask(task: DownloadTask) {
        try {
            task.state = DownloadState.PROBING
            updateTask(task)

            val stop = stopFlags[task.id] ?: AtomicBoolean(false)
            val success = when {
                task.type.equals("m3u8", true) ->
                    M3U8Downloader.download(appContext, task, stop) { d, t -> onProgress(task, d, t) }
                else ->
                    SingleFileDownloader.download(appContext, db, task, stop) { d, t -> onProgress(task, d, t) }
            }

            if (!success) {
                when {
                    stop.get() && pauseOnly[task.id]?.get() == true -> {
                        task.state = DownloadState.PAUSED
                        updateTask(task)
                    }
                    stop.get() -> {
                        // 取消：删除记录与文件
                        task.state = DownloadState.CANCELED
                        deleteFiles(task)
                        db.deleteTask(task.id)
                        _tasks.value = _tasks.value.filterNot { it.id == task.id }
                    }
                    else -> {
                        task.state = DownloadState.FAILED
                        task.error = "下载失败"
                        updateTask(task)
                    }
                }
                return
            }

            // 导出到公共 Download/
            task.state = DownloadState.EXPORTING
            updateTask(task)
            task.state = if (export(task)) DownloadState.COMPLETED else {
                task.error = "导出失败"
                DownloadState.FAILED
            }
            updateTask(task)
        } catch (e: CancellationException) {
            if (pauseOnly[task.id]?.get() == true) {
                task.state = DownloadState.PAUSED
                updateTask(task)
            } else {
                task.state = DownloadState.CANCELED
                deleteFiles(task)
                db.deleteTask(task.id)
                _tasks.value = _tasks.value.filterNot { it.id == task.id }
            }
        } catch (e: Exception) {
            task.state = DownloadState.FAILED
            task.error = e.message ?: e.javaClass.simpleName
            updateTask(task)
        }
    }

    private fun onProgress(task: DownloadTask, downloaded: Long, total: Long) {
        // 暂停/取消/完成/失败后，残留的进度回调不应再把状态刷回下载中
        if (task.state in setOf(
                DownloadState.PAUSED, DownloadState.CANCELED,
                DownloadState.COMPLETED, DownloadState.FAILED, DownloadState.EXPORTING
            )
        ) {
            return
        }
        task.downloadedBytes = downloaded
        if (total > 0) task.totalBytes = total
        task.state = DownloadState.DOWNLOADING
        updateTask(task)
    }

    private fun updateTask(task: DownloadTask) {
        runCatching { db.updateTask(task) }
        _tasks.value = _tasks.value.map { if (it.id == task.id) task else it }
    }

    /** 把私有临时文件导出到 /Download/hanime1/，返回是否成功 */
    private fun export(task: DownloadTask): Boolean {
        val src = File(task.filePath ?: return false)
        if (!src.exists()) return false
        val safeTitle = task.title.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val quality = task.quality?.let { "_$it" } ?: ""
        val displayName = "$safeTitle$quality.mp4"
        val uri = MediaStoreExporter.exportToDownloads(appContext, src, displayName) ?: return false
        task.filePath = uri
        return true
    }

    private fun deleteFiles(task: DownloadTask?) {
        // 删除记录时不清除已导出到公共 Download/ 的视频文件（content://），只清理私有临时文件
        task?.let { t ->
            val tmpDir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "hanime1_tmp")
            File(tmpDir, t.id).deleteRecursively()
            File(tmpDir, t.id + ".mp4").delete()
        }
    }

    private fun startForegroundService() {
        runCatching {
            appContext.startForegroundService(Intent(appContext, DownloadService::class.java))
        }
    }

    private fun checkIdle() {
        val hasActive = _tasks.value.any { it.state in setOf(
            DownloadState.PENDING, DownloadState.PROBING,
            DownloadState.DOWNLOADING, DownloadState.EXPORTING
        ) }
        if (!hasActive && activeJobs.isEmpty()) {
            runCatching { appContext.stopService(Intent(appContext, DownloadService::class.java)) }
        }
    }
}
