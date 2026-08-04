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

    /** 移动端 UA：部分站点对移动 UA 返回更友好的资源 */
    val UA: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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
