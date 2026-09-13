package com.videosniffer.sniff

import android.net.Uri
import com.videosniffer.download.hls.HlsPlaylist
import com.videosniffer.download.hls.HlsVariant
import com.videosniffer.download.hls.M3U8Parser
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

    /** `RESOLUTION=1920x1080` → 1080（用于变体排序）。纯函数，判不出返回 0。 */
    fun heightOfResolution(resolution: String?): Int =
        resolution?.substringAfter('x', "")?.trim()?.toIntOrNull() ?: 0

    /** `RESOLUTION=1920x1080` → `1080p`。纯函数，判不出返回 null。 */
    fun qualityForResolution(resolution: String?): String? =
        heightOfResolution(resolution).takeIf { it > 0 }?.let { "${it}p" }

    /** `1920x1080` 形态（URL 路径里也常见，如 `.../1920x1080/index.m3u8`） */
    private val urlResolutionRegex = Regex("""(?<![0-9])(\d{3,4})x(\d{3,4})(?![0-9])""")

    /** `1080p` / `720p60` 形态 */
    private val urlHeightRegex =
        Regex("""(?<![0-9])(\d{3,4})p(?:\d{1,3})?(?![0-9a-zA-Z])""", RegexOption.IGNORE_CASE)

    /**
     * 3~4 位数字 + `p` 的组合在 URL 里太常见（签名 token、时间戳都能碰上，如 `/a1234p/`），
     * 故只认这些真实存在的清晰度高度。
     */
    private val commonHeights = setOf(144, 240, 360, 480, 540, 720, 1080, 1440, 2160, 4320)

    /**
     * 从 URL 路径推断清晰度：`/hls/1080p/index.m3u8` → `1080p`、`/1920x1080/x.ts` → `1080p`、
     * `xxx-720p.mp4` → `720p`。纯函数（只用字符串操作，不碰 [Uri]，可直接 JVM 单测）；
     * 判不出返回 null。
     *
     * 用途：master playlist 没给 `RESOLUTION`、或本来就是 media playlist（分辨率信息不可得）时兜底 ——
     * 大量 CDN 会把清晰度写进目录名或文件名。
     */
    fun qualityFromUrlPath(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        urlResolutionRegex.find(path)?.let { match ->
            val height = match.groupValues[2].toIntOrNull() ?: 0
            if (height in commonHeights) return "${height}p"
        }
        val height = urlHeightRegex.find(path)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (height in commonHeights) "${height}p" else null
    }

    /**
     * 校验真实类型，返回该 URL 对应的**全部**下载候选。
     *
     * HLS master playlist 会**展开成多个候选**（每个 `#EXT-X-STREAM-INF` 变体一条，清晰度取
     * `RESOLUTION`，如 `1080p`）—— 这就是「m3u8 也能识别/选择清晰度」的落地方式。
     * 此前实现对这个位置**恒填 `quality = null`**，于是不论 master 里有几个清晰度，
     * 对话框里都只显示一条「原画」，即「m3u8 清晰度识别无效」。
     *
     * 单个 URL 最多两次请求：HEAD 取类型 + 一次 GET 取 playlist 文本（顺带完成
     * 「是不是真 playlist」的校验）。HEAD 失败时 m3u8 仍可凭 URL 与 GET 结果成功识别。
     */
    suspend fun detectAll(
        url: String,
        sourcePageUrl: String,
        title: String?
    ): List<DetectedMedia> = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(url)
            val host = uri.host.orEmpty().lowercase()

            // YouTube 流：mime/itag 在 query 里，直接解析，避免 HEAD 被反爬拒
            if (host.contains("googlevideo.com")) {
                return@withContext youtubeMedia(uri, url, title, sourcePageUrl)?.let { listOf(it) }
                    ?: emptyList()
            }

            var mime: String? = null
            var size: Long? = null
            // HEAD 失败不致命：m3u8 可以靠 URL 特征 + playlist 内容自证
            val headOk = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .method("HEAD", null)
                    .header("User-Agent", SnifferHttp.UA)
                    .header("Referer", sourcePageUrl)
                    .build()
                SnifferHttp.client.newCall(request).execute().use { resp ->
                    mime = resp.header("Content-Type")
                    size = resp.header("Content-Length")?.toLongOrNull()
                }
                true
            }.getOrDefault(false)

            val ext = extensionFor(url, mime) ?: return@withContext emptyList()

            if (ext == "m3u8") {
                val text = fetchPlaylistText(url) ?: return@withContext emptyList()
                return@withContext candidatesForPlaylist(text, url, title, sourcePageUrl)
            }

            // 非 m3u8：以 HEAD 成功为准。拿不到类型说明资源不可达，给候选只会在下载时才失败
            if (!headOk) return@withContext emptyList()
            listOf(
                DetectedMedia(
                    url = url,
                    quality = qualityFromUrlPath(url),
                    size = size,
                    ext = ext,
                    title = title,
                    sourcePageUrl = sourcePageUrl
                )
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 单条候选版本，供 hanime1 下载页这类「一个链接 = 一个候选」的场景使用。
     * HLS master 会先展开，这里取最清晰的那条（展开结果已按带宽/高度降序）。
     */
    suspend fun detect(url: String, sourcePageUrl: String, title: String?): DetectedMedia? =
        detectAll(url, sourcePageUrl, title).firstOrNull()

    /** YouTube googlevideo 直链 → 候选；纯音频流与非 mp4 容器返回 null。 */
    private fun youtubeMedia(
        uri: Uri,
        url: String,
        title: String?,
        sourcePageUrl: String
    ): DetectedMedia? {
        val mime = uri.getQueryParameter("mime")?.lowercase().orEmpty()
        // 纯音频流（mime=audio%2Fmp4，如 itag 140/251）不是视频：
        // 在视频下载器里给出「音频」选项只会误导用户
        if (mime.startsWith("audio/")) return null
        val ext = when {
            mime.startsWith("video/mp4") -> "mp4"
            mime.isBlank() -> "mp4"
            // webm 等容器不放行：导出链路固定写 MIME video/mp4 与 .mp4 后缀，
            // 放行只会产出「扩展名与内容不符」的文件（见 YouTubeParser 的同类取舍）
            else -> return null
        }
        val itag = uri.getQueryParameter("itag")?.toIntOrNull()
        return DetectedMedia(url, itag?.let { itagQualities[it] }, null, ext, title, sourcePageUrl)
    }

    /**
     * playlist 文本 → 候选列表。**纯函数**（只做解析与字符串处理，无 IO），便于单测。
     *
     * - master：每个变体一条，按「带宽降序、同带宽按高度降序」排（最清晰在前），
     *   与 [com.videosniffer.download.hls.M3U8Downloader] 自己选变体的口径一致；
     * - media：单条，清晰度按 URL 路径兜底推断。
     *
     * 一个清晰度都标不出来时**不做展开**：多行一模一样的「原画」只会让人选错，
     * 此时给出最清晰的那条即可（下载内容与整片下载完全一致）。
     */
    fun candidatesForPlaylist(
        text: String,
        playlistUrl: String,
        title: String?,
        sourcePageUrl: String
    ): List<DetectedMedia> = when (val parsed = M3U8Parser.parse(text, playlistUrl)) {
        is HlsPlaylist.Master -> {
            val expanded = parsed.variants
                .distinctBy { it.uri }
                .sortedWith(
                    compareByDescending<HlsVariant> { it.bandwidth }
                        .thenByDescending { heightOfResolution(it.resolution) }
                )
                .map { variant ->
                    DetectedMedia(
                        url = variant.uri,
                        quality = qualityForResolution(variant.resolution)
                            ?: qualityFromUrlPath(variant.uri),
                        size = null,
                        ext = "m3u8",
                        title = title,
                        sourcePageUrl = sourcePageUrl
                    )
                }
            when {
                expanded.size <= 1 -> expanded
                expanded.all { it.quality == null } -> listOf(expanded.first())
                else -> expanded
            }
        }

        is HlsPlaylist.Media -> listOf(
            DetectedMedia(
                url = playlistUrl,
                quality = qualityFromUrlPath(playlistUrl),
                size = null,
                ext = "m3u8",
                title = title,
                sourcePageUrl = sourcePageUrl
            )
        )

        null -> emptyList()
    }

    /** 拉取 playlist 文本；请求失败、响应为空或不含 `#EXTM3U`（不是 playlist）时返回 null */
    private fun fetchPlaylistText(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SnifferHttp.UA)
            .build()
        SnifferHttp.client.newCall(request).execute().use { resp ->
            val body = if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            body.takeIf { it.contains("#EXTM3U") }
        }
    } catch (_: Exception) {
        null
    }
}
