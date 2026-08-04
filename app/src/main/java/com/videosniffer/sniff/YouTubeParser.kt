package com.videosniffer.sniff

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube 页面解析器（Route D，尽力而为）。
 *
 * 原理：watch 页 HTML 内嵌 `ytInitialPlayerResponse` JSON，
 * 取其 `streamingData.formats` / `adaptiveFormats` 中的直连 `url`（带清晰度）。
 * 签名/nsig/POT 加密的格式不做 JS 逆向（WebView 播放时的请求级嗅探兜底）。
 */
object YouTubeParser : PageParser {

    private val itagQualities = mapOf(
        18 to "360p", 22 to "720p", 37 to "1080p", 137 to "1080p", 138 to "2160p",
        140 to "音频128k", 251 to "音频160k", 278 to "216p", 242 to "240p", 243 to "360p",
        244 to "480p", 247 to "720p", 248 to "1080p", 271 to "1440p", 313 to "2160p"
    )

    override fun matches(url: String): Boolean {
        return runCatching {
            val uri = Uri.parse(url)
            val host = uri.host ?: return@runCatching false
            (host == "youtube.com" || host.endsWith(".youtube.com")) &&
                uri.path?.contains("/watch") == true
        }.getOrDefault(false)
    }

    override suspend fun parse(pageUrl: String, title: String?): List<DetectedMedia> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", SnifferHttp.UA)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                val html = SnifferHttp.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    resp.body?.string().orEmpty()
                }

                val playerResponseJson = extractBraceJson(html, "ytInitialPlayerResponse")
                    ?: return@withContext emptyList()
                val json = JSONObject(playerResponseJson)
                val streaming = json.optJSONObject("streamingData")
                    ?: return@withContext emptyList()

                val result = mutableListOf<DetectedMedia>()
                fun collect(arr: JSONArray?) {
                    if (arr == null) return
                    for (i in 0 until arr.length()) {
                        val f = arr.optJSONObject(i) ?: continue
                        val url = f.optString("url").takeIf { it.isNotBlank() } ?: continue
                        val quality = f.optString("qualityLabel").takeIf { it.isNotBlank() }
                            ?: itagQualities[f.optInt("itag", -1)]
                        val size = f.optLong("contentLength").takeIf { it > 0 }
                        result.add(DetectedMedia(url, quality, size, "mp4", title, pageUrl))
                    }
                }

                collect(streaming.optJSONArray("formats"))
                collect(streaming.optJSONArray("adaptiveFormats"))
                result.distinctBy { it.url }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** 从 HTML 中按标记提取花括号配对的 JSON 文本（正确处理字符串内的括号） */
    private fun extractBraceJson(html: String, marker: String): String? {
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
                inString -> {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
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
}
