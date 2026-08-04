package com.videosniffer.sniff

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 媒体 URL 判定（Route A 请求级拦截的核心逻辑）。
 * 先按扩展名快速筛选，再 HEAD 请求校验 Content-Type，避免把普通资源误判为视频。
 */
object MediaUrlDetector {

    private val videoExtensions = listOf("m3u8", "mp4", "flv", "mpeg", "webm", "m4v", "mov")

    private val m3u8Mimes = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
    )

    private val mp4Mimes = setOf("video/mp4", "application/mp4", "video/h264")

    private val itagQualities = mapOf(
        18 to "360p", 22 to "720p", 37 to "1080p", 137 to "1080p", 138 to "2160p",
        140 to "音频128k", 251 to "音频160k", 242 to "240p", 243 to "360p",
        244 to "480p", 247 to "720p", 248 to "1080p", 271 to "1440p", 313 to "2160p"
    )

    /** 请求级快速筛选：仅依据 URL 特征判断是否为候选视频，不发起网络请求 */
    fun isVideoCandidate(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        // YouTube 播放流（googlevideo videoplayback），URL 无扩展名，靠 host+path 识别。
        // 排除带 range= 的 DASH 分片 URL（那是片段不是整片）
        if (host.contains("googlevideo.com") && url.contains("videoplayback") &&
            uri.getQueryParameter("range") == null
        ) {
            return true
        }

        val path = uri.path.orEmpty().lowercase()
        if (videoExtensions.any { path.endsWith(".$it") }) return true
        // m3u8 常以 query 形式出现，路径无扩展名
        if (path.contains("m3u8")) return true
        return false
    }

    /**
     * 校验真实类型，返回 DetectedMedia；非视频或请求失败返回 null。
     * m3u8 站点常返回 application/octet-stream，需兜底按 URL 判断。
     */
    suspend fun detect(
        url: String,
        sourcePageUrl: String,
        title: String?
    ): DetectedMedia? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(url)
            val host = uri.host.orEmpty().lowercase()

            // YouTube 流：mime/itag 在 query 里，直接解析，避免 HEAD 被反爬拒
            if (host.contains("googlevideo.com")) {
                val mime = uri.getQueryParameter("mime")?.lowercase().orEmpty()
                val ext = when {
                    mime.contains("webm") -> "webm"
                    else -> "mp4"
                }
                val itag = uri.getQueryParameter("itag")?.toIntOrNull()
                val quality = itag?.let { itagQualities[it] }
                return@withContext DetectedMedia(url, quality, null, ext, title, sourcePageUrl)
            }

            val request = Request.Builder()
                .url(url)
                .method("HEAD", null)
                .header("User-Agent", SnifferHttp.UA)
                .header("Referer", sourcePageUrl)
                .build()

            SnifferHttp.client.newCall(request).execute().use { resp ->
                val mime = resp.header("Content-Type")?.substringBefore(";")?.lowercase().orEmpty()
                val lowerUrl = url.lowercase()

                val ext = when {
                    mime in m3u8Mimes || lowerUrl.contains("m3u8") -> "m3u8"
                    mime in mp4Mimes || lowerUrl.endsWith(".mp4") -> "mp4"
                    mime.startsWith("video/") -> mime.removePrefix("video/")
                    else -> return@withContext null
                }

                // m3u8 需要确认是有效 playlist（防止误抓普通文本）
                if (ext == "m3u8" && !isValidM3U8(url)) {
                    return@withContext null
                }

                val size = resp.header("Content-Length")?.toLongOrNull()
                DetectedMedia(url = url, quality = null, size = size, ext = ext, title = title, sourcePageUrl = sourcePageUrl)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 下载 m3u8 文本，检查是否以 #EXTM3U 开头 */
    private fun isValidM3U8(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", SnifferHttp.UA)
                .build()
            SnifferHttp.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body?.string().orEmpty()
                body.contains("#EXTM3U")
            }
        } catch (_: Exception) {
            false
        }
    }
}
