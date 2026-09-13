package com.videosniffer.download.hls

import android.content.Context
import android.os.Environment
import com.videosniffer.download.DownloadOutcome
import com.videosniffer.download.DownloadTask
import com.videosniffer.download.Stopped
import com.videosniffer.sniff.SnifferHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * HLS/m3u8 分段下载器。
 *
 * 相比旧实现的改进：
 * 1. **AES-128 解密**：解析 `#EXT-X-KEY`，对加密分片做 AES-128-CBC 解密后再落盘。
 *    旧实现把加密分片直接拼接，产出无法播放的文件却标记「已完成」。
 * 2. **fMP4 / `#EXT-X-MAP`**：把初始化段作为第 0 个分片参与拼接，支持 fMP4 流。
 * 3. **不抛异常**：失败以 [DownloadOutcome.Failed] 返回，避免被误判为用户取消而删档。
 * 4. **单分片重试**：网络抖动按退避重试，不再因一个分片失败就整体作废。
 * 5. **进度按字节**：优先使用分片实际字节数，未知时退化为分片计数。
 */
object M3U8Downloader {

    private const val PROGRESS_TICK_MS = 300L
    private const val MAX_SEGMENT_ATTEMPTS = 3
    private const val RETRY_BACKOFF_MS = 800L
    private const val COPY_BUFFER = 256 * 1024

    suspend fun download(
        context: Context,
        task: DownloadTask,
        stopped: Stopped,
        onProgress: (Long, Long) -> Unit
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val playlistUrl = task.m3u8Url ?: task.url

        val mediaPlaylist = try {
            resolveMediaPlaylist(playlistUrl)
        } catch (e: Exception) {
            return@withContext DownloadOutcome.Failed("m3u8 解析失败：${e.message ?: e.javaClass.simpleName}")
        } ?: return@withContext DownloadOutcome.Failed("m3u8 无有效分片")

        val segments = mediaPlaylist.segments
        if (segments.isEmpty()) return@withContext DownloadOutcome.Failed("m3u8 无有效分片")

        // 不支持的加密方式必须显式失败，否则会静默产出损坏文件
        val unsupported = segments.map { it.key.method }
            .firstOrNull { !HlsCrypto.isSupported(it) }
        if (unsupported != null) {
            return@withContext DownloadOutcome.Failed("不支持的加密方式：$unsupported")
        }

        val jobs = buildJobs(mediaPlaylist)

        val tmpDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "hanime1_tmp")
        tmpDir.mkdirs()
        val workDir = File(tmpDir, task.id)
        workDir.mkdirs()

        val total = jobs.size.toLong()
        val knownBytes = AtomicLong(0)
        val doneBytes = AtomicLong(0)
        val doneJobs = AtomicInteger(0)

        val semaphore = Semaphore(task.threadCount.coerceAtLeast(1))
        val activeConn = AtomicInteger(0)
        val failedError = ConcurrentHashMap<String, String>()
        val keyCache = ConcurrentHashMap<String, ByteArray>()

        coroutineScope {
            val ticker = launch(Dispatchers.IO) {
                while (isActive) {
                    task.activeConnections = activeConn.get()
                    val totalBytes = knownBytes.get()
                    if (totalBytes > 0) {
                        // 已获知字节数：按字节上报，进度比按分片计数平滑
                        onProgress(doneBytes.get(), totalBytes)
                    } else {
                        // 字节数未知（源未给 Content-Length）：退化为分片计数
                        onProgress(doneJobs.get().toLong(), total)
                    }
                    delay(PROGRESS_TICK_MS)
                }
            }

            try {
                coroutineScope {
                    jobs.forEach { job ->
                        launch(Dispatchers.IO) {
                            if (stopped.isStopped) return@launch
                            semaphore.withPermit {
                                if (stopped.isStopped) return@withPermit
                                val target = File(workDir, job.fileName)
                                // 断点续传：已存在的完整分片直接复用（内容已解密）
                                if (target.exists() && target.length() > 0) {
                                    knownBytes.addAndGet(target.length())
                                    doneBytes.addAndGet(target.length())
                                    doneJobs.incrementAndGet()
                                    return@withPermit
                                }

                                var lastError: String? = null
                                var attempt = 0
                                while (attempt < MAX_SEGMENT_ATTEMPTS) {
                                    if (stopped.isStopped) return@withPermit
                                    attempt++
                                    if (attempt > 1) delay(RETRY_BACKOFF_MS * (attempt - 1))
                                    activeConn.incrementAndGet()
                                    try {
                                        val result = fetchAndPrepare(job, keyCache)
                                        writeAtomically(target, result)
                                        // result.size 是 Int，AtomicLong.addAndGet 需要 Long
                                        knownBytes.addAndGet(result.size.toLong())
                                        doneBytes.addAndGet(result.size.toLong())
                                        doneJobs.incrementAndGet()
                                        lastError = null
                                        break
                                    } catch (e: Exception) {
                                        lastError = e.message ?: e.javaClass.simpleName
                                    } finally {
                                        activeConn.decrementAndGet()
                                    }
                                }
                                if (lastError != null) {
                                    failedError.putIfAbsent(job.fileName, lastError!!)
                                }
                            }
                        }
                    }
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
        if (!allJobsPresent(jobs, workDir)) {
            return@withContext DownloadOutcome.Failed("分片缺失")
        }

        val totalKnown = knownBytes.get()
        if (totalKnown <= 0) return@withContext DownloadOutcome.Failed("分片为空")
        onProgress(totalKnown, totalKnown)

        val outFile = File(tmpDir, task.id + ".mp4")
        try {
            concat(jobs, workDir, outFile)
        } catch (e: Exception) {
            outFile.delete()
            return@withContext DownloadOutcome.Failed("合并失败：${e.message ?: e.javaClass.simpleName}")
        }

        if (!outFile.exists() || outFile.length() <= 0) {
            return@withContext DownloadOutcome.Failed("合并结果为空")
        }
        task.filePath = outFile.absolutePath
        DownloadOutcome.Success(outFile.length())
    }

    // ---------- playlist ----------

    /**
     * 取媒体层 playlist：master 则选码率最高的变体再拉一次。
     */
    private suspend fun resolveMediaPlaylist(playlistUrl: String): HlsPlaylist.Media? {
        val text = fetch(playlistUrl) ?: throw IOException("playlist 请求失败")
        return when (val parsed = M3U8Parser.parse(text, playlistUrl)) {
            is HlsPlaylist.Media -> parsed
            is HlsPlaylist.Master -> {
                // 优先最高带宽，其次最高分辨率
                val best = parsed.variants.maxWithOrNull(
                    compareBy({ it.bandwidth }, { resolutionRank(it.resolution) })
                ) ?: return null
                val subText = fetch(best.uri) ?: throw IOException("子 playlist 请求失败")
                val sub = M3U8Parser.parse(subText, best.uri)
                if (sub is HlsPlaylist.Media) sub else null
            }
            null -> null
        }
    }

    private fun resolutionRank(resolution: String?): Int =
        resolution?.substringAfter('x', "")?.toIntOrNull() ?: 0

    // ---------- jobs ----------

    /** 一个待处理分片：init 段或媒体分片，含解密所需信息。 */
    private class Job(
        val fileName: String,
        val uri: String,
        val key: HlsKey,
        val ivSeed: Long,
        val byteRange: ByteRange?
    )

    /**
     * 按播放顺序构建任务列表：`#EXT-X-MAP` 初始化段在前，其后是媒体分片。
     * 相同 URI 的 init 段只保留一份（避免重复拼接）。
     *
     * 文件名前缀（`init_` < `seg_`）即拼接顺序，故不需要额外的序号字段。
     */
    private fun buildJobs(playlist: HlsPlaylist.Media): List<Job> {
        val jobs = mutableListOf<Job>()
        val seenMaps = mutableSetOf<String>()
        var initIndex = 0

        // init 段（fMP4）
        playlist.segments.forEach { segment ->
            val map = segment.map ?: return@forEach
            val dedupKey = map.uri + "#" + (map.byteRange?.toString() ?: "")
            if (seenMaps.add(dedupKey)) {
                jobs.add(
                    Job(
                        fileName = "init_${initIndex.toString().padStart(5, '0')}.bin",
                        uri = map.uri,
                        // init 段本身不加密（RFC 8216：MAP 不参与 EXT-X-KEY 加密）
                        key = HlsKey.NONE,
                        ivSeed = 0L,
                        byteRange = map.byteRange
                    )
                )
                initIndex++
            }
        }

        playlist.segments.forEachIndexed { segmentIndex, segment ->
            jobs.add(
                Job(
                    fileName = "seg_${segmentIndex.toString().padStart(6, '0')}.bin",
                    uri = segment.uri,
                    key = segment.key,
                    ivSeed = playlist.mediaSequence + segmentIndex,
                    byteRange = segment.byteRange
                )
            )
        }
        return jobs
    }

    // ---------- transfer ----------

    /**
     * 拉取分片原始数据并解密，返回可直接落盘的字节。
     * 任何失败都抛异常（由调用方重试并汇总）。
     */
    private suspend fun fetchAndPrepare(job: Job, keyCache: MutableMap<String, ByteArray>): ByteArray {
        val request = buildRequest(job)
        val raw = SnifferHttp.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("响应为空")
            body.bytes()
        }
        if (raw.isEmpty()) throw IOException("分片为空")

        if (!job.key.isEncrypted) return raw

        if (!job.key.method.equals(HlsCrypto.METHOD_AES_128, true)) {
            throw IOException("不支持的加密方式 ${job.key.method}")
        }
        val keyUri = job.key.keyUri ?: throw IOException("缺少密钥地址")
        val key = keyCache[keyUri] ?: fetchKey(keyUri).also { keyCache[keyUri] = it }
        val iv = HlsCrypto.parseIv(job.key.iv) ?: HlsCrypto.ivFromSequence(job.ivSeed)
        return HlsCrypto.decrypt(raw, key, iv)
    }

    private fun buildRequest(job: Job): Request {
        val builder = Request.Builder()
            .url(job.uri)
            .header("User-Agent", SnifferHttp.UA)
        job.byteRange?.let { range ->
            builder.header("Range", "bytes=${range.offset}-${range.offset + range.length - 1}")
        }
        return builder.build()
    }

    private fun fetchKey(keyUri: String): ByteArray {
        val request = Request.Builder()
            .url(keyUri)
            .header("User-Agent", SnifferHttp.UA)
            .build()
        return SnifferHttp.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("密钥请求失败 HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw IOException("密钥为空")
            if (bytes.size != 16) throw IOException("密钥长度异常(${bytes.size})")
            bytes
        }
    }

    /** 先写 .tmp 再原子改名，避免中断留下半截文件被当作「已完成」。 */
    private fun writeAtomically(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { it.write(data) }
            if (tmp.length() <= 0) throw IOException("写入为空")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("重命名失败")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun jobPresent(job: Job, workDir: File): Boolean {
        val f = File(workDir, job.fileName)
        return f.exists() && f.length() > 0
    }

    /** 全部分片（含 init 段）都已落盘 */
    private fun allJobsPresent(jobs: List<Job>, workDir: File): Boolean =
        jobs.all { jobPresent(it, workDir) }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", SnifferHttp.UA)
                .build()
            SnifferHttp.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 按 jobs 顺序拼接（文件名带序号，排序稳定）。 */
    private fun concat(jobs: List<Job>, workDir: File, out: File) {
        FileOutputStream(out).use { outStream ->
            val buffer = ByteArray(COPY_BUFFER)
            jobs.forEach { job ->
                val f = File(workDir, job.fileName)
                if (!f.exists()) throw IOException("缺少分片 ${job.fileName}")
                FileInputStream(f).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        outStream.write(buffer, 0, n)
                    }
                }
            }
        }
    }
}
