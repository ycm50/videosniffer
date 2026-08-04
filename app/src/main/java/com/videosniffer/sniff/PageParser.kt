package com.videosniffer.sniff

/**
 * 站点页面解析器接口（Route D）。
 * 在 WebView 完成页面加载时，对匹配的站点抓取页面/内嵌数据解析出媒体直链。
 */
interface PageParser {
    /** 当前 URL 是否属于该站点可解析的页面 */
    fun matches(url: String): Boolean

    /**
     * 解析页面，返回媒体资源列表。
     * 解析失败或无资源时返回空列表。
     */
    suspend fun parse(pageUrl: String, title: String?): List<DetectedMedia>
}
