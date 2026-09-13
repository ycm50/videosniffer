package com.videosniffer.sniff

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 媒体 URL 判定（Route A 请求级拦截的核心逻辑）。
 * 先按扩展名快速筛选，再 HEAD 请求校验 Content-Type，避免把普通资源误判为视频。
 *
 * 纯判定逻辑（[isVideoCandidate] / [extensionOf] / [extensionFor]）不发起网络请求，可直接单测。
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

    /**
     * itag → 清晰度。取自 YouTube 各编码预设的通用对照表。
     * 只列**含视频轨**的 itag：纯音频 itag（140/251…）不在这里，因为视频下载器不提供音频下载。
     * [YouTubeParser] 在拿不到 `qualityLabel` 时也复用本表，避免两处维护。
     */
    private val itagQualities = mapOf(
        // 合流（带声音）
        5 to "240p", 6 to "270p", 17 to "144p", 18 to "360p", 22 to "720p", 34 to "360p",
        35 to "480p", 36 to "240p", 37 to "1080p", 38 to "3072p", 43 to "360p", 44 to "480p",
        45 to "720p", 46 to "1080p", 59 to "480p", 78 to "480p", 82 to "360p", 83 to "480p",
        84 to "720p", 85 to "1080p", 100 to "360p", 101 to "480p", 102 to "720p",
        // 纯视频（adaptiveFormats）
        133 to "240p", 134 to "360p", 135 to "480p", 136 to "720p", 137 to "1080p",
        138 to "2160p", 160 to "144p", 212 to "480p", 213 to "480p", 214 to "720p",
        215 to "720p", 216 to "1080p", 217 to "1080p", 264 to "1440p", 266 to "2160p",
        271 to "1440p", 272 to "4320p", 278 to "144p", 298 to "720p60", 299 to "1080p60",
        302 to "720p60", 303 to "1080p60", 308 to "1440p60", 313 to "2160p",
        315 to "2160p60", 330 to "144p", 331 to "240p", 332 to "360p", 333 to "480p",
        334 to "720p", 335 to "1080p", 336 to "1440p", 337 to "2160p",
        // AV1（mp4 容器）
        394 to "144p", 395 to "240p", 396 to "360p", 397 to "480p", 398 to "720p",
        399 to "1080p", 400 to "1440p", 401 to "2160p", 402 to "4320p",
    )

    /** itag → 清晰度；未知 itag 返回 null。纯函数。 */
    fun qualityForItag(itag: Int): String? = itagQualities[itag]

    /**
     * 归一化 googlevideo 直链：去掉**未被签名**的 `range` 参数。
     *
     * YouTube 播放器按块拉流时会在 URL 上加 `range=起始-结束`，这类 URL 指向的是
     * **片段**而不是整片；拿它当下载地址只会下到一小段。
     * `range` 若出现在 `sparams`（签名覆盖的参数列表）里就不能动，否则签名校验失败；
     * 不在 `sparams` 里则删除是安全的 —— 下载器本来就会自己发 `Range` 头。
     *
     * 纯函数，便于单元测试。
     */
    fun normalizeForDownload(url: String): String {
        if (!url.contains("videoplayback")) return url
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url

        val query = url.substring(queryStart + 1)
        val params = query.split('&')
        if (params.none { it.startsWith("range=") }) return url

        val sparams = params.firstOrNull { it.startsWith("sparams=") }
            ?.substringAfter('=', "")
            .orEmpty()
        // sparams 用 %2C 分隔，个别情况会是裸逗号
        val signed = sparams.split("%2C", ",").any { it.equals("range", ignoreCase = true) }
        if (signed) return url

        // 逐个参数重建查询串，这样 range 处在首位/末位/中间都能得到合法 URL
        val kept = params.filterNot { it.startsWith("range=") }
        if (kept.isEmpty()) return url.substring(0, queryStart)
        return url.substring(0, queryStart + 1) + kept.joinToString("&")
    }

    /** URL 路径上的视频扩展名（小写，不含点）；无扩展名返回 null。纯函数。 */
    fun extensionOf(url: String): String? {
        val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
            .lowercase()
        val lastSegment = path.substringAfterLast('/')
        val dot = lastSegment.lastIndexOf('.')
        if (dot < 0 || dot == lastSegment.length - 1) return null
        return lastSegment.substring(dot + 1)
    }

    /** 请求级快速筛选：仅依据 URL 特征判断是否为候选视频，不发起网络请求。 */
    fun isVideoCandidate(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        // YouTube 播放流（googlevideo videoplayback），URL 无扩展名，靠 host+path 识别。
        // 这里**不再**排除带 range= 的 URL：播放器分块拉流时 URL 上常带 range，
        // 排除掉就永远嗅不到 YouTube。调用方（BrowserViewModel）会先用
        // [normalizeForDownload] 去掉未签名的 range，拿到的仍是整片地址。
        if (host.contains("googlevideo.com") && url.contains("videoplayback")) {
            // SABR（服务端自适应码率）走 UMP POST 协议，其 URL 不能直接 GET 下载
            if (uri.getQueryParameter("sabr") != null) return false
            return true
        }

        val path = uri.path.orEmpty().lowercase()
        if (videoExtensions.any { path.endsWith(".$it") }) return true
        // m3u8 常以 query 形式出现，路径无扩展名
        if (path.contains("m3u8")) return true
        return false
    }

    /**
     * 依据 Content-Type 与 URL 判定媒体类型。纯函数，便于单测。
     *
     * @return 扩展名（m3u8 / mp4 / 其他 video 子类型）；判定不了返回 null
     */
    fun extensionFor(url: String, mime: String?): String? {
        val lowerMime = mime.orEmpty().substringBefore(";").trim().lowercase()
        val lowerUrl = url.lowercase()
        return when {
            lowerMime in m3u8Mimes || lowerUrl.contains("m3u8") -> "m3u8"
            lowerMime in mp4Mimes || lowerUrl.endsWith(".mp4") -> "mp4"
            lowerMime.startsWith("video/") -> lowerMime.removePrefix("video/").takeIf { it.isNotBlank() }
            else -> null
        }
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
                // 纯音频流（mime=audio%2Fmp4，如 itag 140/251）不是视频：
                // 在视频下载器里给出「音频」选项只会误导用户
                if (mime.startsWith("audio/")) return@withContext null
                val ext = when {
                    mime.startsWith("video/mp4") -> "mp4"
                    mime.isBlank() -> "mp4"
                    // webm 等容器不放行：导出链路固定写 MIME video/mp4 与 .mp4 后缀，
                    // 放行只会产出「扩展名与内容不符」的文件（见 YouTubeParser 的同类取舍）
                    else -> return@withContext null
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
                val ext = extensionFor(url, resp.header("Content-Type"))
                    ?: return@withContext null

                // m3u8 需要确认是有效 playlist（防止误抓普通文本）
                if (ext == "m3u8" && !isValidM3U8(url)) {
                    return@withContext null
                }

                val size = resp.header("Content-Length")?.toLongOrNull()
                DetectedMedia(
                    url = url,
                    quality = null,
                    size = size,
                    ext = ext,
                    title = title,
                    sourcePageUrl = sourcePageUrl
                )
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
                // 不用 `return false`：在 use{} 内是非局部返回，会绕过外层 catch
                if (!resp.isSuccessful) {
                    false
                } else {
                    resp.body?.string().orEmpty().contains("#EXTM3U")
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
