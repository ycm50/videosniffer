package com.videosniffer.download

/**
 * 把「网络类失败」与「真正的失败」分开。
 *
 * 为什么不能只靠 ConnectivityManager 判断：真机实测（设备常驻 VPN）中，飞行模式下
 * `activeNetwork` 仍然是那条 VPN，并且照样声称有 `NET_CAPABILITY_INTERNET` ——
 * 于是「断网 → 暂停」永远不成立，用户看到的是「失败 + 重试」。
 *
 * 所以再加一层基于错误文本的判定：超时、连接被重置、域名解析失败这类错误**都是可恢复的**，
 * 应当保留进度并置为「已暂停」，让用户联网后点一下继续；而 HTTP 404/403、
 * 「不支持的加密方式」「分片为空」这类是确定性失败，必须如实报错。
 *
 * 纯函数，单测覆盖。
 */
object DownloadFailureClassifier {

    /** 这些片段出现时，认为失败是网络瞬断导致的（可恢复）。 */
    private val TRANSIENT_MARKERS = listOf(
        "timeout",
        "timed out",
        "connect",
        "connection",
        "network",
        "unreachable",
        "reset",
        "broken pipe",
        "eof",
        "unable to resolve host",
        "unknownhost",
        "ssl",
        "handshake",
        "socket",
        "i/o",
        "ioexception",
        "stream was reset"
    )

    /**
     * @param message 失败原因（引擎拼出来的简短文本）
     * @return true 表示这是网络瞬断类失败，调用方应置为「暂停」而不是「失败」
     */
    fun isTransientNetworkFailure(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lower = message.lowercase()
        // 只看「有没有网络类特征」：裸的 "HTTP 404" 不含任何特征词 → 判为确定性失败；
        // 而 "connect failed: HTTP 502 upstream" 含 connect → 判为可恢复（上游网关问题）。
        return TRANSIENT_MARKERS.any { lower.contains(it) }
    }
}
