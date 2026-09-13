package com.videosniffer.sniff

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * hanime1.me 下载页解析（Route D，主通道）。
 *
 * 原理：hanime1.me 提供独立下载页 /download?v=<vid>，
 * 页面内 table.download-table 下的 <a download="文件名" href="直链"> 即各清晰度的 mp4 直链，
 * 比通用嗅探更稳定，无需播放即能拿到全部清晰度。
 *
 * 文件名/标题：下载页的 `download` 属性只是「同名文件」写法（形如 `40-1080p.mp4`），
 * 不含作品名。因此这里额外抓取 watch 页的 `<h1>`/`<title>` 作为资源标题，
 * 供下载对话框展示与下载列表命名使用。
 */
object Hanime1DownloadParser : PageParser {

    /**
     * 文件名/标题末尾的清晰度后缀。
     *
     * 必须**捕获数字**（[qualityFromFileName] 取的是 `groupValues[1]`），
     * 且后缀可能带扩展名（`40-1080p.mp4`）也可能不带（`40-1080p`），
     * 故扩展名部分设为可选 —— 这样同一个正则既能给 [qualityFromFileName] 用，
     * 也能给 [stripQualitySuffix] 用。
     */
    private val qualitySuffixRegex =
        Regex("""-(\d+)p(?:\.[A-Za-z0-9]{1,5})?$""", RegexOption.IGNORE_CASE)

    /** 页面标题里的站点名尾巴，如 `作品名 - Hanime1.me` → `作品名` */
    private val siteSuffixRegex = Regex("""\s*[-|｜·]\s*[^-|｜·]*hanime1[^-|｜·]*$""", RegexOption.IGNORE_CASE)

    /** 文件名里的扩展名 */
    private val extensionRegex = Regex("""\.[A-Za-z0-9]{2,5}$""")

    /** 是否为 hanime1.me 的 watch 页面（形如 hanime1.me/watch?v=xxx） */
    override fun matches(url: String): Boolean {
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

    override suspend fun parse(pageUrl: String, title: String?): List<DetectedMedia> =
        parseWatchUrl(pageUrl, title)

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
     * @param title   WebView 上报的网页标题；为空时回退到抓取 watch 页标题
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

                // 下载页自身没有作品名，标题优先取自 watch 页
                val pageTitle = title?.let { cleanTitle(it) }.orEmpty()
                    .ifEmpty { fetchWatchTitle(referer) }.orEmpty()
                    .ifEmpty { cleanTitle(doc.title()) }

                links.mapNotNull { a ->
                    val href = a.attr("href")
                    if (href.isBlank()) return@mapNotNull null
                    val quality = qualityFromUrl(href)
                    // download 属性只是文件名（形如 40-1080p.mp4），去掉扩展名与清晰度后缀，
                    // 只在拿不到作品名时兜底；避免导出时变成 `40-1080p_1080p.mp4`
                    val fileName = stripQualitySuffix(stripExtension(a.attr("download")))
                    val name = pageTitle.ifEmpty { fileName }.ifEmpty { null }

                    val media = MediaUrlDetector.detect(href, referer, name)
                        ?: return@mapNotNull null
                    // 文件名里没带清晰度时（例如链接是 m3u8），保留嗅探到的清晰度，
                    // 不能用 null 覆盖 —— 否则 HLS 变体识别出来的清晰度会被抹掉
                    media.copy(quality = quality ?: media.quality)
                }.distinctBy { it.url }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 抓取 watch 页并提取作品名。
     * hanime1 的 `<h1>` 就是作品标题；取不到则用 `<title>` 兜底（去掉站点尾巴）。
     */
    private fun fetchWatchTitle(watchUrl: String): String? {
        if (watchUrl.isBlank()) return null
        return try {
            val request = Request.Builder()
                .url(watchUrl)
                .header("User-Agent", SnifferHttp.UA)
                .build()
            SnifferHttp.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val doc = Jsoup.parse(resp.body?.string().orEmpty())
                titleFromDocument(doc)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 从 watch 页文档里取作品名。纯函数，便于单元测试。 */
    fun titleFromDocument(doc: Document): String? {
        val heading = doc.selectFirst("h1")
            ?.text()
            ?.let { cleanTitle(it) }
            .orEmpty()
        return heading.ifEmpty { cleanTitle(doc.title()) }.ifEmpty { null }
    }

    /**
     * 从下载直链提取清晰度（形如 `.../xxx-1080p.mp4` → `1080p`）。
     * 纯函数：只用字符串操作取路径末段，不碰 `android.net.Uri`，
     * 因此能在 JVM 单测里直接跑（[Uri] 的桩方法在单测中不可用）。
     */
    fun qualityFromUrl(url: String): String? =
        qualityFromFileName(
            url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        )

    /**
     * 从文件名提取清晰度（`40-1080p.mp4` → `1080p`）。纯函数，便于单元测试。
     */
    fun qualityFromFileName(fileName: String): String? =
        qualitySuffixRegex.find(fileName)
            ?.groupValues?.get(1)
            ?.let { "${it.lowercase()}p" }

    /** 去掉文件名扩展名（`40-1080p.mp4` → `40-1080p`）。纯函数 */
    fun stripExtension(name: String): String = extensionRegex.replace(name.trim(), "")

    /** 去掉文件名/标题末尾的清晰度后缀（`40-1080p` → `40`；`1080p` 原样保留）。纯函数 */
    fun stripQualitySuffix(name: String): String =
        name.replace(qualitySuffixRegex, "").trim().ifEmpty { name.trim() }

    /** 归一化网页标题：压掉多余空白并剥掉站点名尾巴。纯函数 */
    fun cleanTitle(raw: String): String {
        val collapsed = raw.replace(Regex("""\s+"""), " ").trim()
        if (collapsed.isEmpty()) return ""
        val stripped = siteSuffixRegex.replace(collapsed, "").trim()
        return stripped.ifEmpty { collapsed }
    }
}
