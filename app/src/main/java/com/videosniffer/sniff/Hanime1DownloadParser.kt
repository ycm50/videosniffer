package com.videosniffer.sniff

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * hanime1.me 下载页解析（Route D，主通道）。
 *
 * 原理：hanime1.me 提供独立下载页 /download?v=<vid>，
 * 页面内 table.download-table 下的 <a download="文件名" href="直链"> 即各清晰度的 mp4 直链，
 * 比通用嗅探更稳定，无需播放即能拿到全部清晰度。
 */
object Hanime1DownloadParser {

    private val qualityRegex = Regex("""[^/]+-(\d+)p""")

    /** 是否为 hanime1.me 的 watch 页面（形如 hanime1.me/watch?v=xxx） */
    fun isWatchUrl(url: String): Boolean {
        return runCatching {
            val uri = Uri.parse(url)
            uri.host?.contains("hanime1.me") == true &&
                uri.path?.contains("/watch") == true &&
                !uri.getQueryParameter("v").isNullOrEmpty()
        }.getOrDefault(false)
    }

    /** 是否为 hanime1.me 的下载页面（形如 hanime1.me/download?v=xxx），浏览器不应展示它 */
    fun isDownloadUrl(url: String): Boolean {
        return runCatching {
            val uri = Uri.parse(url)
            uri.host?.contains("hanime1.me") == true &&
                uri.path?.contains("/download") == true &&
                !uri.getQueryParameter("v").isNullOrEmpty()
        }.getOrDefault(false)
    }

    /**
     * 解析 watch 页面对应的下载页，返回各清晰度直链列表。
     * 解析失败或无下载链接时返回空列表。
     */
    suspend fun parseWatchUrl(watchUrl: String, title: String?): List<DetectedMedia> {
        val vid = Uri.parse(watchUrl).getQueryParameter("v") ?: return emptyList()
        return parseDownloadPage("https://hanime1.me/download?v=$vid", watchUrl, title)
    }

    /**
     * 直接解析下载页 URL（hanime1.me/download?v=xxx），返回各清晰度直链列表。
     *
     * @param referer 来源页（watch 页），用于防盗链与记录 sourcePageUrl
     */
    suspend fun parseDownloadPage(
        downloadUrl: String,
        referer: String,
        title: String?
    ): List<DetectedMedia> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", SnifferHttp.UA)
                .header("Referer", referer)
                .build()

            SnifferHttp.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val html = resp.body?.string().orEmpty()
                val doc = Jsoup.parse(html)
                val links = doc.select("table.download-table a[download]")

                links.mapNotNull { a ->
                    val href = a.attr("href")
                    if (href.isBlank()) return@mapNotNull null
                    val downloadName = a.attr("download").ifBlank { title }
                    val quality = qualityRegex.find(href)?.groupValues?.get(1)?.let { "${it}p" }

                    val media = MediaUrlDetector.detect(href, referer, downloadName)
                        ?: return@mapNotNull null
                    media.copy(quality = quality)
                }.distinctBy { it.url }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
