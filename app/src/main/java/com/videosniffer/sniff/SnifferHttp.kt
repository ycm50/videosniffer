package com.videosniffer.sniff

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 嗅探/下载共享的 OkHttp 客户端。
 * 下载请求需携带 UA / Referer 以通过站点防盗链校验。
 *
 * 关键：OkHttp 默认 maxRequestsPerHost=5，同一主机最多 5 个并发连接，
 * 会卡死多线程下载的并发度。这里调大到 64，确保线程数配置真正生效。
 */
object SnifferHttp {

    /** 移动端 UA：部分站点对移动 UA 返回更友好的资源。 */
    const val MOBILE_UA: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 桌面 UA：与「电脑版网页」开关配套，供下载请求使用。 */
    const val DESKTOP_UA: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 嗅探/页面解析用的 UA：**固定移动 UA**，不随「电脑版网页」变化。
     *
     * 解析器（InnerTube 客户端、下载页抓取）靠固定 UA 过防盗链，与页面版本无关；
     * 让它们跟着桌面开关漂移只会降低通过率。
     */
    const val UA: String = MOBILE_UA

    /**
     * 下载请求使用的 UA，跟随「电脑版网页」设置。
     *
     * 单独一份而不是复用 [UA]：站点有时对页面 UA 与媒体请求 UA 做一致性校验，
     * 用户在桌面模式下拿到的直链，用移动 UA 去下反而会被拒。
     */
    @Volatile
    var downloadUserAgent: String = MOBILE_UA

    /** 按开关选择 UA（纯函数，供测试与设置页使用）。 */
    fun uaFor(desktopMode: Boolean): String = if (desktopMode) DESKTOP_UA else MOBILE_UA

    val client: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 64
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
