package com.videosniffer.download.hls

import android.content.Context
import android.os.Environment
import com.videosniffer.download.DownloadOutcome
import com.videosniffer.download.DownloadProgress
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
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
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
 * 5. **进度按字节连续上报**：边读边累加字节（不再 `body.bytes()` 一次性读完），总量用
 *    「已落盘字节 / 已落盘时长」实测码率 × playlist 总时长估算（见 [DownloadProgress]）。
 *    早年用过「分片计数」，粒度太粗 —— 一个大分片要下几分钟，进度条一直不动。
 * 6. **流式解密落盘**：分片不再整段进内存（见 [fetchSegmentToFile]）。
 * 7. **请求带 Referer**：防盗链站点的分片/密钥/playlist 都要校验来源页，
 *    此前只有单文件下载带了 Referer，m3u8 一律 403。
 * 8. **进度不重复计数**：重试同一分片时按「本次已读的最大值」计，而不是累加，
 *    否则重试几次后「已下载」会超过视频实际大小。
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
        val referer = task.sourcePageUrl

        val mediaPlaylist = try {
            resolveMediaPlaylist(playlistUrl, referer)
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

        /**
         * 每个分片「本次会话最多读到过多少字节」。
         *
         * 用 max 而不是累加：重试会把同一个分片从头再读一遍，累加会让进度虚高
         * （下完 1 GB 的视频却显示已下载 1.2 GB），也会让估算的百分比提前冲到 99%。
         */
        val jobBytes = ConcurrentHashMap<String, AtomicLong>()

        /** 记一个分片的进度：只升不降。 */
        fun noteJobBytes(fileName: String, bytes: Long) {
            if (bytes <= 0) return
            jobBytes.getOrPut(fileName) { AtomicLong(0) }.updateAndGet { maxOf(it, bytes) }
        }

        // 已落盘字节 + 这些分片的播放时长：用来算实测码率，进而估算总字节数
        val writtenBytes = AtomicLong(0)
        val writtenDurationMs = AtomicLong(0)
        val totalDurationMs = jobs.sumOf { it.durationMs }

        val semaphore = Semaphore(task.threadCount.coerceAtLeast(1))
        val activeConn = AtomicInteger(0)
        val failedError = ConcurrentHashMap<String, String>()
        val keyCache = ConcurrentHashMap<String, ByteArray>()

        coroutineScope {
            val ticker = launch(Dispatchers.IO) {
                while (isActive) {
                    task.activeConnections = activeConn.get()
                    // 进度单位是**字节**：分子是真实已读字节，分母是按实测码率估算的总字节数。
                    // 采样不足（或 playlist 没有时长信息）时总量为 0 → UI 走不确定动画，
                    // 绝不显示假的 100%（那正是最早的 bug）。
                    onProgress(
                        jobBytes.values.sumOf { it.get() },
                        DownloadProgress.estimateTotalBytes(
                            writtenBytes.get(),
                            writtenDurationMs.get(),
                            totalDurationMs
                        )
                    )
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
                                    val length = target.length()
                                    writtenBytes.addAndGet(length)
                                    // 也计入「已读」：续传任务的进度条从上次的位置接着走，不从 0 开始
                                    noteJobBytes(job.fileName, length)
                                    writtenDurationMs.addAndGet(job.durationMs)
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
                                        // 边读边累加到进度计数器：分片往往有好几 MB，
                                        // 等下完再报就是「一格一格跳」，大分片时看起来像卡住不动
                                        var attemptRead = 0L
                                        fetchSegmentToFile(job, referer, keyCache, target) { read ->
                                            attemptRead += read
                                            noteJobBytes(job.fileName, attemptRead)
                                        }
                                        writtenBytes.addAndGet(target.length())
                                        writtenDurationMs.addAndGet(job.durationMs)
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

        val totalWritten = writtenBytes.get()
        if (totalWritten <= 0) return@withContext DownloadOutcome.Failed("分片为空")
        // 全部分片落盘：直接上报真实总大小 → 进度精确收尾到 100%
        //（估算分母偏小/偏大都靠这一步兜住，配合 DownloadProgress.displayPercent 的 99% 上限）
        onProgress(totalWritten, totalWritten)

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
    private suspend fun resolveMediaPlaylist(playlistUrl: String, referer: String): HlsPlaylist.Media? {
        val text = fetch(playlistUrl, referer) ?: throw IOException("playlist 请求失败")
        return when (val parsed = M3U8Parser.parse(text, playlistUrl)) {
            is HlsPlaylist.Media -> parsed
            is HlsPlaylist.Master -> {
                // 优先最高带宽，其次最高分辨率
                val best = parsed.variants.maxWithOrNull(
                    compareBy({ it.bandwidth }, { resolutionRank(it.resolution) })
                ) ?: return null
                val subText = fetch(best.uri, referer) ?: throw IOException("子 playlist 请求失败")
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
        val byteRange: ByteRange?,
        /** 分片时长（毫秒）。init 段为 0；用于把「已下载字节」换算成整片进度 */
        val durationMs: Long
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
                        byteRange = map.byteRange,
                        // init 段没有播出时长，不计入进度换算的时长口径
                        durationMs = 0L
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
                    byteRange = segment.byteRange,
                    durationMs = (segment.durationSec * 1000.0).toLong().coerceAtLeast(0L)
                )
            )
        }
        return jobs
    }

    // ---------- transfer ----------

    /**
     * 拉取一个分片并**流式**解密写入 [target]。
     *
     * 旧实现用 `ByteArrayOutputStream` 把整个分片收进内存、再 `decrypt` 复制一份：
     * 峰值 ≈ 2 × 分片大小 × 并发数（8 线程 × 10 MB ≈ 160 MB+），低内存机型会 OOM。
     * 现在密文边读边解边写，内存占用只剩一个 [COPY_BUFFER]。
     *
     * 任何失败都抛异常（由调用方重试并汇总）。
     *
     * @param onBytesRead 每读到一段就回调其长度；进度条靠它连续推进
     */
    private fun fetchSegmentToFile(
        job: Job,
        referer: String,
        keyCache: MutableMap<String, ByteArray>,
        target: File,
        onBytesRead: (Long) -> Unit
    ) {
        val request = buildRequest(job, referer)
        val tmp = File(target.parentFile, target.name + ".part")
        try {
            SnifferHttp.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("响应为空")

                val key: ByteArray? = if (job.key.isEncrypted) {
                    if (!job.key.method.equals(HlsCrypto.METHOD_AES_128, true)) {
                        throw IOException("不支持的加密方式 ${job.key.method}")
                    }
                    val keyUri = job.key.keyUri ?: throw IOException("缺少密钥地址")
                    keyCache[keyUri] ?: fetchKey(keyUri, referer).also { keyCache[keyUri] = it }
                } else {
                    null
                }

                body.byteStream().use { raw ->
                    // 计数包在解密之前：进度按「网络上真正读到的字节」计，
                    // 解密后的长度会因填充少 1~16 字节，和进度分母口径不一致。
                    val counted = CountingInputStream(raw, onBytesRead)
                    val source: InputStream = if (key != null) {
                        val iv = HlsCrypto.parseIv(job.key.iv) ?: HlsCrypto.ivFromSequence(job.ivSeed)
                        HlsCrypto.decryptStream(counted, key, iv)
                    } else {
                        counted
                    }
                    FileOutputStream(tmp).use { out -> source.copyTo(out, COPY_BUFFER) }
                }
            }
            if (tmp.length() <= 0L) throw IOException("分片为空")
            // 先写 .part 再原子改名，避免中断留下半截文件被当作「已完成」
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("重命名失败")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun buildRequest(job: Job, referer: String): Request {
        val builder = Request.Builder()
            .url(job.uri)
            .header("User-Agent", SnifferHttp.downloadUserAgent)
        // 防盗链：分片与密钥都要带来源页，缺了就是 403
        if (referer.isNotBlank()) builder.header("Referer", referer)
        job.byteRange?.let { range ->
            builder.header("Range", "bytes=${range.offset}-${range.offset + range.length - 1}")
        }
        return builder.build()
    }

    private fun fetchKey(keyUri: String, referer: String): ByteArray {
        val builder = Request.Builder()
            .url(keyUri)
            .header("User-Agent", SnifferHttp.downloadUserAgent)
        if (referer.isNotBlank()) builder.header("Referer", referer)
        return SnifferHttp.client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("密钥请求失败 HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw IOException("密钥为空")
            if (bytes.size != 16) throw IOException("密钥长度异常(${bytes.size})")
            bytes
        }
    }

    /** 统计读到的原始字节数（解密前的密文长度），用于进度上报。 */
    private class CountingInputStream(
        delegate: InputStream,
        private val onRead: (Long) -> Unit
    ) : FilterInputStream(delegate) {

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) onRead(1L)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) onRead(n.toLong())
            return n
        }
    }

    private fun jobPresent(job: Job, workDir: File): Boolean {
        val f = File(workDir, job.fileName)
        return f.exists() && f.length() > 0
    }

    /** 全部分片（含 init 段）都已落盘 */
    private fun allJobsPresent(jobs: List<Job>, workDir: File): Boolean =
        jobs.all { jobPresent(it, workDir) }

    private suspend fun fetch(url: String, referer: String): String? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", SnifferHttp.downloadUserAgent)
            if (referer.isNotBlank()) builder.header("Referer", referer)
            SnifferHttp.client.newCall(builder.build()).execute().use { resp ->
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
