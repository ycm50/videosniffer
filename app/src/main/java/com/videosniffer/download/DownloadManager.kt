package com.videosniffer.download

import android.content.Context
import android.content.Intent
import android.os.Environment
import com.videosniffer.core.DownloadService
import com.videosniffer.core.SettingsStore
import com.videosniffer.data.DownloadDbHelper
import com.videosniffer.download.engine.SingleFileDownloader
import com.videosniffer.download.hls.M3U8Downloader
import com.videosniffer.download.export.MediaStoreExporter
import com.videosniffer.sniff.DetectedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载调度器（单例）。
 *
 * 职责：任务入队/排队限流/启停/恢复/取消、协程调度、状态流、数据库持久化、前台服务生命周期。
 *
 * 关键设计：
 * - **任务级限流**：受「同时下载任务数」设置约束，超出部分置 [DownloadState.QUEUED] 排队，
 *   每有任务结束自动补位（[pumpQueue]）。
 * - **结果驱动**：引擎返回 [DownloadOutcome]，据此区分「暂停 / 取消 / 失败」，
 *   不再依赖异常类型猜测用户意图（旧实现会把网络失败误判成取消并删除已下载数据）。
 */
object DownloadManager {

    private lateinit var appContext: Context
    private lateinit var db: DownloadDbHelper
    private lateinit var settingsStore: SettingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks = _tasks.asStateFlow()

    /** 正在执行的协程（键为任务 id），同时也是「占用并发槽位」的凭据。 */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * 已预约（正在执行 launchTask）但尚未写入 [activeJobs] 的任务 id。
     * 生命期只有「进入 pumpQueue」到「launchTask 写完 activeJobs」这一小段，
     * 因此 `starting.size + activeJobs.size` 恰好等于真正占用的槽位数。
     *
     * 注意不能再保留一个「直到任务结束才移除」的集合：那样同一个 id 会同时
     * 计入两个集合，槽位被重复计数，并发数会静默退化成 1。
     */
    private val starting: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 每个运行中任务的停止信号。 */
    private val stopSignals = ConcurrentHashMap<String, Stopped>()

    @Volatile
    private var initialized = false

    /** 需要前台服务保持存活的状态集合（含排队：等待补位的任务也必须保住进程）。 */
    private val aliveStates = setOf(
        DownloadState.QUEUED, DownloadState.PENDING, DownloadState.PROBING,
        DownloadState.DOWNLOADING, DownloadState.EXPORTING
    )

    /**
     * 「进度已冻结」的状态：这些状态下残留的进度回调必须被忽略，
     * 否则会把状态刷回 [DownloadState.DOWNLOADING]（暂停后按钮又跳回「暂停」）。
     */
    private val frozenStates = setOf(
        DownloadState.PAUSED, DownloadState.CANCELED,
        DownloadState.COMPLETED, DownloadState.FAILED, DownloadState.EXPORTING
    )

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        db = DownloadDbHelper(appContext)
        settingsStore = SettingsStore(appContext)
        // 进程被杀后：运行中/排队中任务统一回到 QUEUED，由调度器重新补位。
        // 单文件任务的分片进度存在 shards 表，m3u8 任务的已下载分片存在私有目录，均可续传。
        // 用 runCatching 兜底：本方法会被 Service.onCreate 调用，
        // 若在此抛异常会跳过 startForeground 而触发前台服务启动超时崩溃。
        _tasks.value = runCatching {
            db.loadTasks().map { t ->
                if (t.state in aliveStates) {
                    t.copy(state = DownloadState.QUEUED)
                } else {
                    t
                }
            }
        }.getOrDefault(emptyList())
        initialized = true
        pumpQueue()
    }

    fun settings(): SettingsStore = settingsStore

    /** 入队一个新的下载任务并按并发上限调度 */
    fun enqueue(media: DetectedMedia): String {
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = media.url,
            title = media.title ?: "未命名",
            quality = media.quality,
            type = media.ext,
            threadCount = settingsStore.threadCount,
            sourcePageUrl = media.sourcePageUrl,
            m3u8Url = if (media.ext.equals("m3u8", true)) media.url else null,
            state = DownloadState.QUEUED
        )
        db.insertTask(task)
        _tasks.update { it + task }
        startForegroundService()
        pumpQueue()
        return task.id
    }

    /**
     * 按并发上限启动排队中的任务。
     * 每当任务入队、结束、暂停或取消后调用。
     *
     * 并发要点：槽位在**同步块内**通过 [starting] 预约。若只看 `activeJobs.size`，
     * 在「读取 size」与「launchTask 写入 activeJobs」之间存在窗口，
     * 两个调用方会同时认为有空槽而超限启动。
     */
    @Synchronized
    private fun pumpQueue() {
        if (!initialized) return
        val limit = settingsStore.maxConcurrentTasks.coerceAtLeast(1)
        val toLaunch = mutableListOf<DownloadTask>()

        for (task in _tasks.value) {
            if (activeJobs.size + starting.size >= limit) break
            if (task.state != DownloadState.QUEUED) continue
            if (starting.contains(task.id) || activeJobs.containsKey(task.id)) continue
            starting.add(task.id)
            toLaunch.add(task)
        }
        if (toLaunch.isEmpty()) return

        startForegroundService()
        toLaunch.forEach { launchTask(it) }
    }

    /** 用户点「暂停」：保留进度与文件，稍后可续传。 */
    fun pauseTask(id: String) {
        stopSignals[id]?.requestPause()
        activeJobs[id]?.cancel()
        // 立即反映到 UI，不等协程退出。
        // 必须走「替换副本」：就地改已发布实例会让 StateFlow 判为「没变化」而不发通知，
        // 按钮就永远是「暂停」而不是「继续」。
        mutateTask(id) { t ->
            if (t.state == DownloadState.COMPLETED) t
            else t.copy(state = DownloadState.PAUSED, activeConnections = 0)
        }
        pumpQueue()
    }

    /** 用户点「继续」 */
    fun resumeTask(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.state !in setOf(DownloadState.PAUSED, DownloadState.FAILED)) return

        val old = activeJobs[id]
        if (old != null && old.isActive) {
            // 旧任务仍在退出中：等它真正结束后再入队，避免并发槽位被重复占用
            scope.launch {
                old.join()
                requeue(id)
            }
        } else {
            requeue(id)
        }
    }

    fun retryTask(id: String) {
        resumeTask(id)
    }

    /** 用户点「取消」：删记录 + 删私有临时文件（不删已导出的公共视频）。 */
    fun cancelTask(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        stopSignals[id]?.requestCancel()

        if (activeJobs[id]?.isActive == true) {
            activeJobs[id]?.cancel()
            // 终态由 runTask 的 DownloadOutcome.Stopped 分支统一处理
        } else {
            // 任务处于排队/暂停等未执行状态：直接清理
            removeTaskCompletely(task)
            pumpQueue()
        }
    }

    /** 用户点「删除」：仅删记录与私有临时文件 */
    fun deleteTask(id: String) {
        stopSignals[id]?.requestCancel()
        activeJobs[id]?.cancel()
        val task = _tasks.value.firstOrNull { it.id == id }
        runCatching { db.deleteTask(id) }
        task?.let { deleteFiles(it) }
        _tasks.update { list -> list.filterNot { it.id == id } }
        pumpQueue()
    }

    /** 清空所有已结束（完成/失败）的任务记录；不删除已导出的公共视频文件。 */
    fun clearFinishedTasks() {
        val finished = _tasks.value.filter {
            it.state == DownloadState.COMPLETED || it.state == DownloadState.FAILED
        }
        if (finished.isEmpty()) return
        finished.forEach { task ->
            runCatching { db.deleteTask(task.id) }
            deleteFiles(task)
        }
        val finishedIds = finished.mapTo(mutableSetOf()) { it.id }
        _tasks.update { list -> list.filterNot { it.id in finishedIds } }
    }

    // ---------- 内部：调度 ----------

    private fun requeue(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.state == DownloadState.COMPLETED) return
        // 同样走「替换副本」，理由见 pauseTask
        mutateTask(id) {
            it.copy(state = DownloadState.QUEUED, error = null, activeConnections = 0)
        }
        startForegroundService()
        pumpQueue()
    }

    /**
     * 立即执行一个任务。
     * 调用方须已在 [pumpQueue] 的同步块内把 task.id 放入 [starting]（预约了一个并发槽位）。
     */
    private fun launchTask(task: DownloadTask) {
        val signal = Stopped()
        stopSignals[task.id] = signal
        // 引擎在自己的**私有副本**上就地累加进度（onProgress/activeConnections 都是就地写）。
        // 绝不能把 [_tasks] 里的已发布实例交给引擎：那会污染快照，令 StateFlow 的
        // 相等性判断恒成立而不发通知（详见 [updateTask]）。
        val work = task.copy(state = DownloadState.PENDING, error = null)
        updateTask(work)

        val job = scope.launch { runTask(work, signal) }
        activeJobs[task.id] = job
        // 完成回调先注册、再交还「预约」标记，顺序不能反：
        // 若 job 在赋值前就已同步完成，回调会立刻执行并清掉 starting，
        // 此处的 remove 是幂等的；反之则由此处完成交接。
        job.invokeOnCompletion {
            activeJobs.remove(task.id)
            starting.remove(task.id)
            stopSignals.remove(task.id)
            pumpQueue()
        }
        starting.remove(task.id)
    }

    private suspend fun runTask(task: DownloadTask, signal: Stopped) {
        try {
            task.state = DownloadState.PROBING
            updateTask(task)

            val outcome = when {
                task.type.equals("m3u8", true) ->
                    M3U8Downloader.download(appContext, task, signal) { d, t -> onProgress(task, d, t) }
                else ->
                    SingleFileDownloader.download(appContext, db, task, signal) { d, t -> onProgress(task, d, t) }
            }

            when (outcome) {
                is DownloadOutcome.Stopped -> finishStopped(task)
                is DownloadOutcome.Failed -> finishFailed(task, outcome.error)
                is DownloadOutcome.Success -> {
                    // 导出到公共 Download/
                    task.state = DownloadState.EXPORTING
                    updateTask(task)
                    task.state = if (export(task)) {
                        task.error = null
                        DownloadState.COMPLETED
                    } else {
                        task.error = "导出失败"
                        DownloadState.FAILED
                    }
                    updateTask(task)
                }
            }
        } catch (e: CancellationException) {
            // 用户点「暂停/取消」会 cancel 本协程，引擎里的 ensureActive() 随即抛
            // CancellationException —— 这是**停止**，不是下载失败。
            // 若让它落到下面的 catch(Exception)，界面会显示「失败」而不是「已暂停」，
            // 并丢掉「保留数据可续传」的语义。
            finishStopped(task)
            throw e // 必须继续向上传播：吞掉取消会破坏结构化并发语义
        } catch (e: Exception) {
            // 引擎已不抛异常；这里兜底未预期错误（含协程取消）
            finishFailed(task, e.message ?: e.javaClass.simpleName)
        }
    }

    /** 处理「被用户停止」：按信号区分暂停与取消 */
    private fun finishStopped(task: DownloadTask) {
        val signal = stopSignals[task.id]
        when {
            signal?.cancelRequested == true -> removeTaskCompletely(task)
            signal?.pauseRequested == true -> {
                task.state = DownloadState.PAUSED
                task.activeConnections = 0
                updateTask(task)
            }
            else -> {
                // 无明确信号而停止（例如进程/作业被外部取消）：保守地按暂停处理，保留数据可续传
                task.state = DownloadState.PAUSED
                task.activeConnections = 0
                updateTask(task)
            }
        }
    }

    private fun finishFailed(task: DownloadTask, message: String) {
        // 用户已经请求停止（暂停/取消）时，失败不得覆盖用户的停止意图：
        // 引擎内部有多处 `catch (e: Exception)`，取消期间可能把 CancellationException
        // 吞成一次「分片失败」并正常返回 Failed。此时应按停止处理。
        if (stopSignals[task.id]?.isStopped == true) {
            finishStopped(task)
            return
        }
        task.state = DownloadState.FAILED
        task.error = message
        task.activeConnections = 0
        updateTask(task)
    }

    /** 取消：删记录 + 删文件 + 从列表移除 */
    private fun removeTaskCompletely(task: DownloadTask) {
        deleteFiles(task)
        runCatching { db.deleteTask(task.id) }
        _tasks.update { list -> list.filterNot { it.id == task.id } }
    }

    /**
     * 进度回调（由下载引擎的 ticker 协程每 300ms 调用一次）。
     *
     * **进度单位固定是字节**：mp4 的总量是精确值，m3u8 的总量是「实测码率 × 总时长」的估算值
     * （详见 [DownloadProgress]）。本方法只做搬运，不做换算，避免多套单位混用。
     *
     * 发布值一律从**已发布快照**（按 id 查当前列表元素）派生，而不是从引擎的工作副本派生：
     * 快照里的 state 才是用户看到的真实状态。这样「暂停」落地后，
     * 引擎残余的最后一两次 tick 也不会把状态刷回 DOWNLOADING
     * （工作副本上的 state 不受 [pauseTask] 影响，靠它会误判）。
     */
    private fun onProgress(work: DownloadTask, downloaded: Long, total: Long) {
        // 同步回工作副本，供后续状态迁移（暂停/失败/完成）把最新进度一并落库
        work.downloadedBytes = downloaded
        if (total > 0) work.totalBytes = total
        val id = work.id
        val connections = work.activeConnections
        _tasks.update { list ->
            list.map { t ->
                when {
                    t.id != id -> t
                    t.state in frozenStates -> t
                    else -> t.copy(
                        downloadedBytes = downloaded,
                        totalBytes = if (total > 0) total else t.totalBytes,
                        activeConnections = connections,
                        state = DownloadState.DOWNLOADING,
                    )
                }
            }
        }
    }

    /**
     * 发布单个任务的新状态。
     *
     * 这里有两个都必须踩对的点：
     *
     * 1. 必须用 [_tasks.update]（CAS 循环）而非「读 value → map → 写 value」：
     *    ticker 协程（每 300ms 一次进度）与 runTask 的状态迁移来自不同线程，
     *    非原子写法会互相覆盖，导致状态变更静默丢失。
     *
     * 2. **必须放入 `task.copy()`，绝不能放 `task` 本身**。
     *    `DownloadTask` 是 data class，而 StateFlow 靠**相等性**判重
     *    （`oldState == newState` 时直接返回，不发通知）。若把已发布过的同一实例放回列表，
     *    `List.equals` 逐元素比较时比的是「同一个对象」，恒为 true
     *    → 订阅者**永远收不到任何更新**（表现为：进度条静止、暂停后按钮不变成「继续」、
     *    通知栏百分比不动，只有手动点「刷新」直读 `tasks.value` 才看得到）。
     *    放入副本后，列表里的旧快照保留旧值，值一变就不再相等，通知才发得出去。
     */
    private fun updateTask(task: DownloadTask) {
        runCatching { db.updateTask(task) }
        val snapshot = task.copy()
        _tasks.update { list -> list.map { if (it.id == snapshot.id) snapshot else it } }
    }

    /**
     * 按 id 以「替换副本」的方式修改任务。
     *
     * 不能就地修改 [_tasks] 里已发布的实例 —— 那会让快照被改写，
     * 相等性判断恒成立、通知发不出去（理由详见 [updateTask]）。
     *
     * [transform] 在 CAS 重试循环内可能被执行多次，因此必须是纯函数。
     */
    private fun mutateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        var snapshot: DownloadTask? = null
        _tasks.update { list ->
            list.map { t -> if (t.id == id) transform(t).also { snapshot = it } else t }
        }
        snapshot?.let { runCatching { db.updateTask(it) } }
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
            File(tmpDir, t.id).deleteRecursively()      // m3u8 分片目录
            File(tmpDir, t.id + ".mp4").delete()        // 合并/单文件产物
        }
    }

    private fun startForegroundService() {
        runCatching {
            appContext.startForegroundService(Intent(appContext, DownloadService::class.java))
        }
    }

    // 说明：前台服务的停止由 DownloadService 自身订阅 tasks 后决定
    //（无存活任务即 stopSelf）。此前在调度器里额外做一次「空闲则 stopService」，
    // 会与并发的 enqueue/pumpQueue 竞争，可能把刚启动的服务杀掉，故移除。
}
