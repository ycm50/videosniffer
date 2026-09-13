package com.videosniffer.sniff

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * YouTube 解析器（Route D）。
 *
 * ## 为什么不能靠「抓 watch 页 HTML 里的 ytInitialPlayerResponse」就完事
 *
 * 1. **很多请求根本拿不到这个变量**：YouTube 对无 Cookie/疑似机器人的请求会返回
 *    精简页或同意墙，`ytInitialPlayerResponse` 要么缺失、要么只有元数据没有 `streamingData`。
 * 2. **拿到的直链往往不可用**：网页端（WEB/TVHTML5）返回的是 `signatureCipher` 而非 `url`，
 *    要还原签名必须下载并运行 YouTube 播放器的混淆 JS（NewPipe 为此内置了 JS 引擎）。
 *    本工程不引入 JS 引擎，因此**带签名的格式一律放弃**。
 * 3. 自 2024 起 YouTube 还要求 **PO Token**（Proof of Origin），它由 BotGuard JS 生成，
 *    同样需要 JS 引擎。
 *
 * ## 本实现的策略
 *
 * 走 YouTube 官方客户端用的 InnerTube 接口 `POST /youtubei/v1/player`，
 * 依次尝试**不经过 JS 播放器**的客户端（`ANDROID` / `IOS` / `VISIONOS`）——
 * 这些客户端历史上直接返回明文 `url`。同时也保留页面内嵌 `ytInitialPlayerResponse`
 * 作为快路径（省一次请求）。
 *
 * **能力边界（务必知悉）**：PO Token 无法生成，因此本通道对相当一部分视频会拿不到可下载直链。
 * 真正可靠的路径是 **Route A 请求级嗅探** —— WebView 里 YouTube 自己的播放器
 * 已经完成了 PO Token、签名与 `n` 参数（限速）的全部处理，它发出的 `videoplayback`
 * 请求天然是可用的直链。本解析器用于「没播放就能列出清晰度」的补充，
 * 拿不到时会静默返回空列表，由 Route A 兜底。
 *
 * ## 不支持的格式
 *
 * - **纯音频流**（`audio/mp4`）：视频下载器里没有意义，直接丢弃。
 * - **非 mp4 容器**（webm/vp9）：导出链路固定写 `video/mp4` 与 `.mp4` 后缀，
 *   塞进 webm 会产出扩展名不符的文件，故只接受 `video/mp4`。
 * - **纯视频流**（adaptiveFormats 里的 video-only）：高清晰度（1080p+）几乎只有这种，
 *   本工程没有封装器（muxer），下下来必然无声 —— 因此**保留但显式标注「无音轨」**，
 *   而不是偷偷给用户一个没声音的文件。
 */
object YouTubeParser : PageParser {

    /**
     * InnerTube 客户端配置。
     *
     * 字段取自 yt-dlp 的 `INNERTUBE_CLIENTS`（其对每个客户端标注了
     * `REQUIRE_JS_PLAYER` 与 PO Token 策略）。这里只挑 `REQUIRE_JS_PLAYER = False`
     * 的客户端 —— 也就是说拿到的流描述里应该直接带明文 `url`。
     */
    private data class Client(
        val clientName: String,
        val clientVersion: String,
        /** 对应 `X-YouTube-Client-Name` 头 */
        val clientId: Int,
        val userAgent: String,
        /** 追加写入 `context.client` 的设备字段 */
        val device: Map<String, Any>,
    )

    /**
     * 尝试顺序按**成功率**排，不按名气排：
     * 1. `VISIONOS` —— yt-dlp 客户端表里唯一「既免 JS 播放器、又没有配置 PO Token 策略」的客户端；
     * 2. `IOS` —— 免 JS，但有 PO Token 策略；
     * 3. `ANDROID` —— 免 JS，但 GVS PO Token 策略为 `required`，最难成功。
     *
     * 第一个成功就返回，因此把最有把握的放最前面能省下最多请求。
     */
    private val clients = listOf(
        Client(
            clientName = "VISIONOS",
            clientVersion = "1.02",
            clientId = 101,
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            device = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "RealityDevice17,1",
                "osName" to "visionOS",
                "osVersion" to "26.5.23O471",
            ),
        ),
        Client(
            clientName = "IOS",
            clientVersion = "21.26.4",
            clientId = 5,
            userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            device = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "18.3.2.22D82",
            ),
        ),
        Client(
            clientName = "ANDROID",
            clientVersion = "21.26.364",
            clientId = 3,
            userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
            device = mapOf("androidSdkVersion" to 30, "osName" to "Android", "osVersion" to "11"),
        ),
    )

    /**
     * `playabilityStatus.status` 里这些值表示「换客户端也没用」：
     * 视频不存在/被删（`ERROR`）、需要登录（`LOGIN_REQUIRED`）、年龄墙（`AGE_CHECK_REQUIRED`）。
     * 三个客户端都不带 Cookie，再试下一个只会白白多发两次 POST。
     *
     * 刻意**不含** `UNPLAYABLE` —— 它常常是客户端/地区维度的限制，换客户端仍有希望。
     */
    private val FATAL_PLAYABILITY = setOf(
        "ERROR", "LOGIN_REQUIRED", "AGE_CHECK_REQUIRED", "CONTENT_CHECK_REQUIRED",
    )

    /** 音频编解码器标识：出现在 codecs 里说明该格式带音轨 */
    private val AUDIO_CODECS = listOf(
        "mp4a", "opus", "vorbis", "ac-3", "ec-3", "flac", "dtse", "dtsc", "dtsx",
    )

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** watch 页未提供 API key 时的兜底（YouTube 网页端长期沿用的公开 key） */
    private const val FALLBACK_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

    override fun matches(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        val isYouTube = host == "youtube.com" ||
            host == "youtu.be" ||
            host.endsWith(".youtube.com")
        return isYouTube && videoIdOf(url) != null
    }

    override suspend fun parse(pageUrl: String, title: String?): List<DetectedMedia> =
        withContext(Dispatchers.IO) {
            try {
                val videoId = videoIdOf(pageUrl) ?: return@withContext emptyList()
                val watchUrl = "https://www.youtube.com/watch?v=$videoId&hl=en"

                val html = fetchText(watchUrl).orEmpty()
                val apiKey = extractYtcfgValue(html, "INNERTUBE_API_KEY") ?: FALLBACK_API_KEY
                val visitorData = extractYtcfgValue(html, "VISITOR_DATA")

                // 快路径：页面内嵌的播放器响应，省一次请求
                val embedded = extractBraceJson(html, "ytInitialPlayerResponse")
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (embedded != null) {
                    val streams = extractStreams(embedded, watchUrl, title)
                    if (streams.isNotEmpty()) return@withContext streams
                }

                // 慢路径：按成功率依次尝试各个免 JS 客户端
                for (client in clients) {
                    val response = callPlayer(videoId, client, apiKey, visitorData) ?: continue
                    val streams = extractStreams(response, watchUrl, title)
                    if (streams.isNotEmpty()) return@withContext streams
                    // 硬失败（下架/需登录/年龄墙）：换客户端也不会变，别再浪费请求
                    if (isFatal(response)) break
                }
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

    // ---------- InnerTube ----------

    /** 请求 `POST /youtubei/v1/player`；网络失败或响应非法返回 null。 */
    private fun callPlayer(
        videoId: String,
        client: Client,
        apiKey: String,
        visitorData: String?,
    ): JSONObject? {
        val visitor = visitorData?.takeIf { it.isNotBlank() }

        val clientContext = JSONObject().apply {
            put("clientName", client.clientName)
            put("clientVersion", client.clientVersion)
            put("hl", "en")
            put("gl", "US")
            put("userAgent", client.userAgent)
            client.device.forEach { (key, value) -> put(key, value) }
            // visitorData 属于 client 上下文，不是顶层字段
            visitor?.let { put("visitorData", it) }
        }
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().put("client", clientContext))
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val builder = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false&key=$apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("X-YouTube-Client-Name", client.clientId.toString())
            .header("X-YouTube-Client-Version", client.clientVersion)
            .header("User-Agent", client.userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", "https://www.youtube.com")
        // 带上 visitor id 能显著降低被当成机器人、返回精简响应的概率
        visitor?.let { builder.header("X-Goog-Visitor-Id", it) }

        return runCatching {
            SnifferHttp.client.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) JSONObject(resp.body?.string().orEmpty()) else null
            }
        }.getOrNull()
    }

    private fun fetchText(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SnifferHttp.UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        SnifferHttp.client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string().orEmpty() else null
        }
    }.getOrNull()

    // ---------- 流清单提取 ----------

    /**
     * 响应是否属于「换客户端也没用」的硬失败（见 [FATAL_PLAYABILITY]）。
     * 用于提前结束客户端轮询。私有：依赖 `org.json`，JVM 单测跑不了。
     */
    private fun isFatal(response: JSONObject): Boolean {
        val status = response.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        return status in FATAL_PLAYABILITY
    }

    /**
     * 从 player 响应提取可下载直链。不可播放、无可解密直链时返回空列表。
     *
     * 合成规则：
     * - 丢弃纯音频格式；
     * - 合流格式（带音轨）优先；
     * - video-only 格式保留，但同清晰度已有合流格式时不重复给出。
     *
     * 纯逻辑（不发起网络请求），便于单元测试。
     */
    fun extractStreams(response: JSONObject, pageUrl: String, title: String?): List<DetectedMedia> {
        val status = response.optJSONObject("playabilityStatus")
        // 非 OK（登录墙/年龄限制/地区限制/下架）时拿不到可用流
        if (status != null && status.optString("status") != "OK") return emptyList()

        val streaming = response.optJSONObject("streamingData") ?: return emptyList()

        val candidates = mutableListOf<DetectedMedia>()
        val muxedFlags = mutableListOf<Boolean>()

        fun collect(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val format = array.optJSONObject(i) ?: continue
                val mime = format.optString("mimeType")
                if (isAudioOnly(mime)) continue
                val media = toMedia(format, mime, pageUrl, title) ?: continue
                candidates.add(media)
                muxedFlags.add(isMuxed(mime))
            }
        }
        collect(streaming.optJSONArray("formats"))
        collect(streaming.optJSONArray("adaptiveFormats"))

        // 同一清晰度既有合流又有纯视频时，只留合流（有声音的那个）
        val muxedQualities = candidates.filterIndexed { index, _ -> muxedFlags[index] }
            .mapNotNullTo(mutableSetOf<String>()) { it.quality }

        return candidates
            .filterIndexed { index, media ->
                // 先取到局部 val 再判空：这样 `!in` 的左值被智能转换成非空 String，
                // 走的是 Set<String>.contains(String) 成员，不依赖可空入参的重载推断
                val quality = media.quality
                muxedFlags[index] || quality == null || quality !in muxedQualities
            }
            .distinctBy { it.url }
    }

    private fun toMedia(
        format: JSONObject,
        mime: String,
        pageUrl: String,
        title: String?,
    ): DetectedMedia? {
        // 导出链路固定写 video/mp4 与 .mp4，非 mp4 容器会产出扩展名不符的文件
        if (!mime.startsWith("video/mp4")) return null

        val url = resolveUrl(format) ?: return null
        val label = qualityFor(format.optInt("itag", -1), format.optString("qualityLabel"))
        // 无音轨的格式必须让用户看得见，否则会以为是下载坏了
        val quality = if (isMuxed(mime)) label else label?.let { "$it（无音轨）" }
        val size = format.optString("contentLength").toLongOrNull()?.takeIf { it > 0 }
        return DetectedMedia(url, quality, size, "mp4", title, pageUrl)
    }

    /**
     * 取可直接下载的地址。
     *
     * `url` 字段是明文直链；`signatureCipher` / `cipher` 携带的签名必须靠
     * YouTube 播放器的 JS 还原，本工程不做 JS 逆向 —— 因此**带签名的一律放弃**
     * （返回 null，该格式被跳过），由 Route A 的请求级嗅探兜底。
     */
    private fun resolveUrl(format: JSONObject): String? {
        format.optString("url").takeIf { it.isNotBlank() }?.let { return it }

        val cipher = format.optString("signatureCipher").takeIf { it.isNotBlank() }
            ?: format.optString("cipher").takeIf { it.isNotBlank() }
            ?: return null

        val parts = parseSignatureCipher(cipher) ?: return null
        // 只有签名缺失（`s` 为空）时才可能无需解签名直接使用
        if (!parts.signature.isNullOrEmpty()) return null
        return parts.url
    }

    // ---------- 纯函数（可单测） ----------

    /**
     * 从各种 YouTube 链接形态里取 11 位视频 ID。
     * 支持 watch?v= / youtu.be/ / shorts/ / embed/ / live/。纯函数。
     */
    fun videoIdOf(url: String): String? {
        for (pattern in VIDEO_ID_PATTERNS) {
            val found = pattern.find(url)?.groupValues?.getOrNull(1)
            if (!found.isNullOrEmpty()) return found
        }
        return null
    }

    private val VIDEO_ID_PATTERNS = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
        Regex("""/embed/([A-Za-z0-9_-]{11})"""),
        Regex("""/live/([A-Za-z0-9_-]{11})"""),
    )

    /** 清晰度：优先用响应自带的 `qualityLabel`，缺失时按 itag 兜底。纯函数。 */
    fun qualityFor(itag: Int, qualityLabel: String?): String? =
        qualityLabel?.takeIf { it.isNotBlank() } ?: MediaUrlDetector.qualityForItag(itag)

    /** 是否只含音频轨。纯函数。 */
    fun isAudioOnly(mimeType: String): Boolean = mimeType.startsWith("audio/")

    /** 是否含视频轨（容器以 `video/` 开头）。纯函数。 */
    fun hasVideo(mimeType: String): Boolean = mimeType.startsWith("video/")

    /** codecs 里是否含音频编解码器。纯函数。 */
    fun hasAudio(mimeType: String): Boolean {
        val codecs = mimeType.substringAfter("codecs=", "").lowercase()
        return AUDIO_CODECS.any { codecs.contains(it) }
    }

    /**
     * 是否为「音视频合流」格式。
     * 只有合流格式下载下来才自带声音；adaptiveFormats 里的 video-only 是无音轨的。纯函数。
     */
    fun isMuxed(mimeType: String): Boolean = hasVideo(mimeType) && hasAudio(mimeType)

    /** `signatureCipher` / `cipher` 的解析结果 */
    data class CipherParts(val url: String, val signature: String?, val sigParam: String)

    /**
     * 解析 `signatureCipher`，形如：
     * `s=CC%3DQ8o2...&sp=sig&url=https%3A%2F%2Frr12...%26itag%3D18`
     *
     * `url` 的值整体做过 URL 编码（内部的 `&` 是 `%26`），所以按 `&` 切分是安全的；
     * 签名值里的 `=` 是 `%3D`，因此只看**第一个** `=` 来分割键值。纯函数。
     */
    fun parseSignatureCipher(raw: String): CipherParts? {
        var url: String? = null
        var signature: String? = null
        var sigParam = "sig"

        for (part in raw.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            when (part.substring(0, eq)) {
                "url" -> url = urlDecode(part.substring(eq + 1))
                "s" -> signature = urlDecode(part.substring(eq + 1))
                "sp" -> sigParam = part.substring(eq + 1)
            }
        }
        return url?.takeIf { it.isNotBlank() }?.let { CipherParts(it, signature, sigParam) }
    }

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    /**
     * 从 HTML 里按标记提取花括号配对的 JSON 文本。
     * 正确处理字符串内的括号与转义引号；未闭合时返回 null。纯函数。
     */
    fun extractBraceJson(html: String, marker: String): String? {
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val start = html.indexOf('{', idx)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * 从 watch 页的 `ytcfg` 里取字符串配置项（如 `INNERTUBE_API_KEY`、`VISITOR_DATA`）。
     * 用正则直接取「键 → 字符串值」，比整段解析 `ytcfg.set({...})` 更稳（页面里有多处）。纯函数。
     *
     * 模式以「转义引号」结尾，故用普通字符串书写而不是 raw string：raw string 的结尾引号
     * 会紧贴结束定界符，三引号归属存在歧义，编译期容易报错。
     */
    fun extractYtcfgValue(html: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"([^\"]*)\"")
            .find(html)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
}
