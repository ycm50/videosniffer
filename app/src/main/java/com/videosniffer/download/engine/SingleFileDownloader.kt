package com.videosniffer.download.engine

import android.content.Context
import android.os.Environment
import com.videosniffer.data.DownloadDbHelper
import com.videosniffer.download.DownloadOutcome
import com.videosniffer.download.DownloadTask
import com.videosniffer.download.Shard
import com.videosniffer.download.ShardCalculator
import com.videosniffer.download.Stopped
import com.videosniffer.sniff.SnifferHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 单文件多线程下载器（mp4 主路径）。
 *
 * - 探测 Range 支持 → 支持则按线程数分片并发下载；不支持则回退单线程整包下载
 * - 各分片经 RandomAccessFile.seek 写入互不重叠区间，无需加锁
 * - 分片进度写回 SQLite，支持断点续传
 * - 单分片失败按退避重试；**失败以 [DownloadOutcome.Failed] 返回，不抛异常**
 *   （旧实现抛 IOException 穿过 coroutineScope 后会被误判为用户取消并删档）
 *
 * 进度方案：集中式轮询。分片只往共享计数器累加字节数，
 * 由独立 ticker 协程每 300ms 读取并上报，避免多分片回调竞态、进度平滑。
 */
object SingleFileDownloader {

    private const val BUFFER_SIZE = 256 * 1024
    private const val PROGRESS_TICK_MS = 300L
    private const val MAX_SHARD_ATTEMPTS = 3
    private const val RETRY_BACKOFF_MS = 800L

    suspend fun download(
        context: Context,
        db: DownloadDbHelper,
        task: DownloadTask,
        stopped: Stopped,
        onProgress: (Long, Long) -> Unit
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val tmpDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "hanime1_tmp")
        tmpDir.mkdirs()
        val file = File(tmpDir, task.id + ".mp4")
        file.parentFile?.mkdirs()
        task.filePath = file.absolutePath

        val (supportsRange, total) = probe(task.url, task.sourcePageUrl)
        task.totalBytes = total

        val activeConn = AtomicInteger(0)
        val failedError = ConcurrentHashMap<Int, String>()

        // 恢复历史分片进度。
        // 分片区间一旦生成就必须稳定，否则已下载字节会错位；但线程数被改小后，
        // 沿用旧分片表只会取到前 N 个区间，剩余区间永远不下载 —— 因此这里必须校验。
        val existingShards = db.loadShards(task.id)
        val reusable = if (existingShards != null && shardsMatch(existingShards, total, task.threadCount)) {
            existingShards
        } else {
            null
        }
        val shards = if (supportsRange && total > 0) {
            reusable ?: ShardCalculator.split(total, task.threadCount).also {
                db.saveShards(task.id, it)
            }
        } else {
            listOf(Shard(Shard.INDEX_STRIDE, 0, total - 1))
        }
        // 断点续传已完成的字节数（只统计一次，避免与本次累加重复计数）
        val baseFinished = shards.sumOf { it.finished }
        val sessionBytes = AtomicLong(0)
        fun currentDownloaded(): Long = baseFinished + sessionBytes.get()

        coroutineScope {
            val ticker = launch(Dispatchers.IO) {
                while (isActive) {
                    task.activeConnections = activeConn.get()
                    onProgress(currentDownloaded(), total)
                    delay(PROGRESS_TICK_MS)
                }
            }

            try {
                if (supportsRange && total > 0) {
                    coroutineScope {
                        shards.forEach { shard ->
                            launch(Dispatchers.IO) {
                                if (stopped.isStopped) return@launch
                                if (shard.finished < shard.length) {
                                    downloadShardWithRetry(
                                        db, task, file, shard, stopped,
                                        sessionBytes, activeConn, failedError
                                    )
                                }
                            }
                        }
                    }
                } else {
                    downloadFull(task, file, stopped, sessionBytes, activeConn, failedError)
                }
            } finally {
                ticker.cancel()
                task.activeConnections = 0
            }
        }

        if (stopped.isStopped) return@withContext DownloadOutcome.Stopped

        failedError.values.firstOrNull()?.let {
            return@withContext DownloadOutcome.Failed("分片下载失败：$it")
        }

        val downloaded = currentDownloaded()
        val complete = if (total > 0) downloaded >= total else downloaded > 0
        if (!complete) {
            return@withContext DownloadOutcome.Failed("下载不完整（$downloaded/$total）")
        }

        onProgress(total, total) // 完成时强制 100%
        DownloadOutcome.Success(downloaded)
    }

    /**
     * 历史分片表能否直接复用：总长与线程数都必须与当前一致。
     * 不一致（用户改了线程数、或文件大小变了）就重新规划分片并重下剩余部分。
     */
    private fun shardsMatch(existing: List<Shard>, total: Long, threadCount: Int): Boolean {
        val recordedThreads = ShardCalculator.threadCountOf(existing) ?: return false
        if (recordedThreads != threadCount.coerceAtLeast(1)) return false
        val covered = existing.maxOfOrNull { it.end + 1 } ?: return false
        return covered == total
    }

    /** 带退避重试的分片下载；最终失败记录原因但不抛异常。 */
    private suspend fun downloadShardWithRetry(
        db: DownloadDbHelper,
        task: DownloadTask,
        file: File,
        shard: Shard,
        stopped: Stopped,
        sessionBytes: AtomicLong,
        activeConn: AtomicInteger,
        failedError: MutableMap<Int, String>
    ) {
        var lastError: String? = null
        var attempt = 0
        while (attempt < MAX_SHARD_ATTEMPTS) {
            if (stopped.isStopped) return
            attempt++
            if (attempt > 1) delay(RETRY_BACKOFF_MS * (attempt - 1))
            try {
                downloadShard(db, task, file, shard, stopped, sessionBytes, activeConn)
                if (stopped.isStopped || shard.finished >= shard.length) return
                lastError = "进度未完成（${shard.finished}/${shard.length}）"
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        if (!stopped.isStopped && lastError != null) {
            failedError[shard.index] = lastError!!
        }
    }

    /** 单分片：Range 请求 + RandomAccessFile 区间写入，只累加计数器不回调进度 */
    private suspend fun downloadShard(
        db: DownloadDbHelper,
        task: DownloadTask,
        file: File,
        shard: Shard,
        stopped: Stopped,
        sessionBytes: AtomicLong,
        activeConn: AtomicInteger
    ) {
        if (shard.finished >= shard.length) return
        // 用 "rw" 而非 "rwd"：rwd 每次写入同步落盘，会序列化多线程磁盘 IO 拖垮速度
        val raf = RandomAccessFile(file, "rw")
        try {
            var offset = shard.start + shard.finished
            raf.seek(offset)
            val buffer = ByteArray(BUFFER_SIZE)
            while (offset <= shard.end) {
                currentCoroutineContext().ensureActive()
                if (stopped.isStopped) return

                val req = Request.Builder()
                    .url(task.url)
                    .header("User-Agent", SnifferHttp.UA)
                    .header("Referer", task.sourcePageUrl)
                    .header("Range", "bytes=$offset-${shard.end}")
                    .build()

                activeConn.incrementAndGet()
                try {
                    // 停止判定放在 use{} 内部时必须用标志位而不能 `return`：
                    // 后者是非局部返回，会跳过本 finally 的 decrementAndGet。
                    var aborted = false
                    SnifferHttp.client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IOException("响应为空")
                        body.byteStream().use { stream ->
                            var n: Int
                            while (stream.read(buffer).also { n = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                if (stopped.isStopped) {
                                    aborted = true
                                    break
                                }
                                raf.write(buffer, 0, n)
                                offset += n
                                shard.finished += n
                                sessionBytes.addAndGet(n.toLong())
                            }
                        }
                    }
                    if (aborted) return
                } finally {
                    activeConn.decrementAndGet()
                }
            }
        } finally {
            raf.close()
            runCatching { db.updateShard(task.id, shard) }
        }
    }

    /** 不支持 Range 或长度未知时：单请求整包下载 */
    private suspend fun downloadFull(
        task: DownloadTask,
        file: File,
        stopped: Stopped,
        sessionBytes: AtomicLong,
        activeConn: AtomicInteger,
        failedError: MutableMap<Int, String>
    ) {
        var lastError: String? = null
        var attempt = 0
        while (attempt < MAX_SHARD_ATTEMPTS) {
            if (stopped.isStopped) return
            attempt++
            if (attempt > 1) delay(RETRY_BACKOFF_MS * (attempt - 1))
            try {
                // 每次尝试都从零开始，故本次累计字节同步清零，避免重复计数
                sessionBytes.set(0)
                val ok = downloadFullOnce(task, file, stopped, sessionBytes, activeConn)
                if (ok) return
                lastError = "HTTP 请求失败"
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        if (!stopped.isStopped && lastError != null) failedError[0] = lastError!!
    }

    private suspend fun downloadFullOnce(
        task: DownloadTask,
        file: File,
        stopped: Stopped,
        sessionBytes: AtomicLong,
        activeConn: AtomicInteger
    ): Boolean {
        // 整包重下：截断已有内容，避免与本次追加混在一起
        RandomAccessFile(file, "rw").use { it.setLength(0) }
        val raf = RandomAccessFile(file, "rw")
        try {
            val req = Request.Builder()
                .url(task.url)
                .header("User-Agent", SnifferHttp.UA)
                .header("Referer", task.sourcePageUrl)
                .build()

            activeConn.incrementAndGet()
            try {
                // 注意：不能在 use{} 内直接 `return false` —— 那是非局部返回，
                // 会跳过本 finally 里的 decrementAndGet，导致并发连接数虚高。
                val ok = SnifferHttp.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        false
                    } else {
                        val body = resp.body
                        if (body == null) {
                            false
                        } else {
                            val buffer = ByteArray(BUFFER_SIZE)
                            var aborted = false
                            body.byteStream().use { stream ->
                                var n: Int
                                while (stream.read(buffer).also { n = it } != -1) {
                                    currentCoroutineContext().ensureActive()
                                    if (stopped.isStopped) {
                                        aborted = true
                                        break
                                    }
                                    raf.write(buffer, 0, n)
                                    sessionBytes.addAndGet(n.toLong())
                                }
                            }
                            !aborted
                        }
                    }
                }
                if (!ok) return false
            } finally {
                activeConn.decrementAndGet()
            }
            return !stopped.isStopped
        } finally {
            raf.close()
        }
    }

    /** 探测：请求 Range: bytes=0-0，判断是否支持 Range 并获取总大小 */
    private fun probe(url: String, referer: String): Pair<Boolean, Long> {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", SnifferHttp.UA)
                .header("Referer", referer)
                .header("Range", "bytes=0-0")
                .build()

            SnifferHttp.client.newCall(req).execute().use { resp ->
                val contentRange = resp.header("Content-Range")
                if (resp.code == 206 && contentRange != null) {
                    val total = contentRange.substringAfter('/').toLongOrNull()
                    if (total != null && total > 0) {
                        true to total
                    } else {
                        false to (resp.header("Content-Length")?.toLongOrNull() ?: -1L)
                    }
                } else {
                    false to (resp.header("Content-Length")?.toLongOrNull() ?: -1L)
                }
            }
        } catch (_: Exception) {
            false to -1L
        }
    }
}
