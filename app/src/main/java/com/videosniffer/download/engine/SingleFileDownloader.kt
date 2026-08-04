package com.videosniffer.download.engine

import android.content.Context
import android.os.Environment
import com.videosniffer.data.DownloadDbHelper
import com.videosniffer.download.DownloadTask
import com.videosniffer.download.Shard
import com.videosniffer.download.ShardCalculator
import com.videosniffer.sniff.SnifferHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 单文件多线程下载器（mp4 主路径）。
 *
 * - 探测 Range 支持 → 支持则按线程数分片并发下载；不支持则回退单线程整包下载
 * - 各分片经 RandomAccessFile.seek 写入互不重叠区间，无需加锁
 * - 分片进度写回 SQLite，支持断点续传
 * - 通过 stopped 标志 + 协程 ensureActive 实现暂停/取消的协作式停止
 *
 * 进度方案：集中式轮询。分片只往共享 AtomicLong 累加字节数，
 * 由独立 ticker 协程每 300ms 读取并上报，避免多分片回调竞态、进度平滑。
 */
object SingleFileDownloader {

    private const val BUFFER_SIZE = 256 * 1024
    private const val PROGRESS_TICK_MS = 300L

    /**
     * @return true 下载完整；false 被停止或失败（由调用方根据 stopped 区分）
     */
    suspend fun download(
        context: Context,
        db: DownloadDbHelper,
        task: DownloadTask,
        stopped: AtomicBoolean,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        val tmpDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "hanime1_tmp")
        tmpDir.mkdirs()
        val file = File(tmpDir, task.id + ".mp4")
        file.parentFile?.mkdirs()
        task.filePath = file.absolutePath

        val (supportsRange, total) = probe(task.url, task.sourcePageUrl)
        task.totalBytes = total

        val downloaded = AtomicLong(0)
        val activeConn = AtomicInteger(0)

        // 恢复历史分片进度
        val existingShards = db.loadShards(task.id)
        val shards = if (supportsRange && total > 0) {
            existingShards ?: ShardCalculator.split(total, task.threadCount).also {
                db.saveShards(task.id, it)
            }
        } else {
            listOf(Shard(0, 0, total - 1))
        }
        shards.forEach { downloaded.addAndGet(it.finished) }

        return coroutineScope {
            // 集中式轮询发布进度 + 实时连接数
            val ticker = launch(Dispatchers.IO) {
                while (isActive) {
                    task.activeConnections = activeConn.get()
                    onProgress(downloaded.get(), total)
                    delay(PROGRESS_TICK_MS)
                }
            }
            val complete = try {
                if (supportsRange && total > 0) {
                    coroutineScope {
                        shards.forEach { shard ->
                            launch(Dispatchers.IO) {
                                if (stopped.get()) return@launch
                                downloadShard(db, task, file, shard, stopped, downloaded, activeConn)
                            }
                        }
                    }
                    !stopped.get() && downloaded.get() >= total
                } else {
                    downloadFull(task, file, stopped, downloaded, activeConn)
                }
            } finally {
                ticker.cancel()
                task.activeConnections = 0
            }
            if (complete) onProgress(total, total) // 完成时强制 100%
            complete
        }
    }

    /** 单分片：Range 请求 + RandomAccessFile 区间写入，只累加计数器不回调进度 */
    private suspend fun downloadShard(
        db: DownloadDbHelper,
        task: DownloadTask,
        file: File,
        shard: Shard,
        stopped: AtomicBoolean,
        downloaded: AtomicLong,
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
                if (stopped.get()) return

                val req = Request.Builder()
                    .url(task.url)
                    .header("User-Agent", SnifferHttp.UA)
                    .header("Referer", task.sourcePageUrl)
                    .header("Range", "bytes=$offset-${shard.end}")
                    .build()

                activeConn.incrementAndGet()
                try {
                    SnifferHttp.client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IOException("empty body")
                        body.byteStream().use { stream ->
                            var n: Int
                            while (stream.read(buffer).also { n = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                if (stopped.get()) return
                                raf.write(buffer, 0, n)
                                offset += n
                                shard.finished += n
                                downloaded.addAndGet(n.toLong())
                            }
                        }
                    }
                } finally {
                    activeConn.decrementAndGet()
                }
            }
        } finally {
            raf.close()
            db.updateShard(task.id, shard)
        }
    }

    /** 不支持 Range 或长度未知时：单请求整包下载 */
    private suspend fun downloadFull(
        task: DownloadTask,
        file: File,
        stopped: AtomicBoolean,
        downloaded: AtomicLong,
        activeConn: AtomicInteger
    ): Boolean {
        val raf = RandomAccessFile(file, "rw")
        try {
            val req = Request.Builder()
                .url(task.url)
                .header("User-Agent", SnifferHttp.UA)
                .header("Referer", task.sourcePageUrl)
                .build()

            activeConn.incrementAndGet()
            try {
                val resp = SnifferHttp.client.newCall(req).execute()
                try {
                    if (!resp.isSuccessful) return false
                    val body = resp.body ?: return false
                    val buffer = ByteArray(BUFFER_SIZE)
                    body.byteStream().use { stream ->
                        var n: Int
                        while (stream.read(buffer).also { n = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            if (stopped.get()) return false
                            raf.write(buffer, 0, n)
                            downloaded.addAndGet(n.toLong())
                        }
                    }
                } finally {
                    resp.close()
                }
                return !stopped.get()
            } finally {
                activeConn.decrementAndGet()
            }
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
