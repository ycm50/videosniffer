package com.videosniffer.sniff

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pornhub 页面解析器（Route D）。
 *
 * 原理：视频页 `view_video.php` 内嵌 `var flashvars_N = {...}` JSON，
 * 取 `mediaDefinitions[]` 的 `videoUrl`（mp4/m3u8 直链）与 `quality`；
 * 兜底解析 `var qualityItems_N = [...]` JS 变量数组。
 */
object PornhubParser : PageParser {

    private val flashvarsRe = Regex("""var\s+flashvars_\d+\s*=\s*(\{.+?\});""", RegexOption.DOT_MATCHES_ALL)
    private val qualityItemsRe = Regex("""var\s+qualityItems_\d+\s*=\s*(\[.+?\]);""", RegexOption.DOT_MATCHES_ALL)

    override fun matches(url: String): Boolean {
        return runCatching {
            val uri = Uri.parse(url)
            val host = uri.host ?: return@runCatching false
            host.endsWith("pornhub.com") && uri.path?.contains("view_video") == true
        }.getOrDefault(false)
    }

    override suspend fun parse(pageUrl: String, title: String?): List<DetectedMedia> =
        withContext(Dispatchers.IO) {
            try {
                // 需带 platform/age cookie 才会返回真实播放器数据
                val req = Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", SnifferHttp.UA)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header(
                        "Cookie",
                        "platform=pc; age_verified=1; has_visited_www=1"
                    )
                    .build()

                val html = SnifferHttp.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    resp.body?.string().orEmpty()
                }

                val result = mutableListOf<DetectedMedia>()

                // 主通道：flashvars.mediaDefinitions
                flashvarsRe.find(html)?.let { match ->
                    runCatching {
                        val flashvars = JSONObject(match.groupValues[1])
                        val defs = flashvars.optJSONArray("mediaDefinitions") ?: return@runCatching
                        for (i in 0 until defs.length()) {
                            val d = defs.optJSONObject(i) ?: continue
                            val videoUrl = d.optString("videoUrl").takeIf { it.isNotBlank() }
                                ?: continue
                            val quality = d.optString("quality").takeIf { it.isNotBlank() }
                            val ext = when {
                                videoUrl.contains(".m3u8") || videoUrl.contains("m3u8?") -> "m3u8"
                                videoUrl.contains(".mpd") -> "mpd"
                                else -> "mp4"
                            }
                            result.add(DetectedMedia(videoUrl, quality, null, ext, title, pageUrl))
                        }
                    }
                }

                // 兜底：qualityItems JS 变量
                if (result.isEmpty()) {
                    qualityItemsRe.find(html)?.let { match ->
                        runCatching {
                            val arr = JSONArray(match.groupValues[1])
                            for (i in 0 until arr.length()) {
                                val item = arr.optJSONObject(i) ?: continue
                                val url = item.optString("url").takeIf { it.isNotBlank() }
                                    ?: continue
                                result.add(DetectedMedia(url, null, null, "mp4", title, pageUrl))
                            }
                        }
                    }
                }

                result.distinctBy { it.url }
            } catch (_: Exception) {
                emptyList()
            }
        }
}
