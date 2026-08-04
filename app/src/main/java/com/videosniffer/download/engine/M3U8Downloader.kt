package com.videosniffer.download.engine

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.videosniffer.download.DownloadTask
import com.videosniffer.sniff.SnifferHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * HLS/m3u8 分段下载器（兜底流媒体）。
 * 解析 playlist → 并发下载 .ts 分片 → MPEG-TS 字节级拼接合并。
 * 已完成的 .ts 分片文件作为断点续传依据（.tmp 临时分片不算完成）。
 *
 * 进度方案：集中式轮询。分片协程只更新 completed/anyFailed，
 * 由独立 ticker 协程每 300ms 读取并上报，进度按已下载分片数/总分片数。
 */
object M3U8Downloader {

    private const val PROGRESS_TICK_MS = 300L

    suspend fun download(
        context: Context,
        task: DownloadTask,
        stopped: AtomicBoolean,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        val playlistUrl = task.m3u8Url ?: task.url
        var mediaUrl = playlistUrl
        var playlist = fetch(playlistUrl) ?: return false

        // master playlist → 解析第一个 media playlist
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val sub = firstMediaUri(playlist) ?: return false
            mediaUrl = resolveUrl(playlistUrl, sub)
            playlist = fetch(mediaUrl) ?: return false
        }

        val segments = parseSegments(playlist, mediaUrl)
        if (segments.isEmpty()) return false

        val tmpDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "hanime1_tmp")
        tmpDir.mkdirs()
        val segDir = File(tmpDir, task.id)
        segDir.mkdirs()

        val total = segments.size.toLong()
        val sem = Semaphore(task.threadCount.coerceAtLeast(1))
        val completed = AtomicLong(0)
        val anyFailed = AtomicBoolean(false)
        val activeConn = AtomicInteger(0)

        onProgress(0, total) // 提前告知总分段数

        coroutineScope {
            // 集中式轮询发布进度 + 实时连接数
            val ticker = launch(Dispatchers.IO) {
                while (isActive) {
                    task.activeConnections = activeConn.get()
                    onProgress(completed.get(), total)
                    delay(PROGRESS_TICK_MS)
                }
            }
            try {
                coroutineScope {
                    segments.forEachIndexed { idx, uri ->
                        launch(Dispatchers.IO) {
                            sem.withPermit {
                                if (stopped.get()) return@withPermit
                                val segFile = File(segDir, "$idx.ts")
                                if (segFile.exists() && segFile.length() > 0) {
                                    completed.incrementAndGet()
                                    return@withPermit
                                }
                                activeConn.incrementAndGet()
                                val ok = try {
                                    downloadSegment(
                                        uri, File(segDir, "$idx.ts.tmp"), segFile,
                                        task.sourcePageUrl, stopped
                                    )
                                } finally {
                                    activeConn.decrementAndGet()
                                }
                                if (ok) {
                                    completed.incrementAndGet()
                                } else {
                                    anyFailed.set(true)
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

        if (stopped.get()) return false
        if (anyFailed.get() || completed.get() < segments.size) return false

        onProgress(total, total) // 完成时强制 100%

        val outFile = File(tmpDir, task.id + ".mp4")
        concat(segDir, outFile)
        task.filePath = outFile.absolutePath
        return outFile.exists() && outFile.length() > 0
    }

    private suspend fun downloadSegment(
        url: String,
        tmpFile: File,
        target: File,
        referer: String,
        stopped: AtomicBoolean
    ): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", SnifferHttp.UA)
                .header("Referer", referer)
                .build()

            SnifferHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { out -> input.copyTo(out) }
                }
                if (stopped.get()) {
                    tmpFile.delete()
                    false
                } else {
                    tmpFile.renameTo(target)
                    target.exists() && target.length() > 0
                }
            }
        } catch (_: Exception) {
            tmpFile.delete()
            false
        }
    }

    private fun fetch(url: String): String? {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", SnifferHttp.UA).build()
            SnifferHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 解析所有非注释行作为分片 URI */
    private fun parseSegments(playlist: String, baseUrl: String): List<String> {
        return playlist.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { resolveUrl(baseUrl, it) }
    }

    private fun firstMediaUri(playlist: String): String? {
        return playlist.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun resolveUrl(base: String, uri: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        val baseUri = Uri.parse(base)
        val basePath = (baseUri.path ?: "").substringBeforeLast('/')
        return "${baseUri.scheme}://${baseUri.host}$basePath/$uri"
    }

    /** TS 分片字节级拼接（文件名按序号排序） */
    private fun concat(segDir: File, out: File) {
        FileOutputStream(out).use { outStream ->
            segDir.listFiles()
                ?.filter { it.name.endsWith(".ts") }
                ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
                ?.forEach { f ->
                    FileInputStream(f).use { it.copyTo(outStream) }
                }
        }
    }
}
