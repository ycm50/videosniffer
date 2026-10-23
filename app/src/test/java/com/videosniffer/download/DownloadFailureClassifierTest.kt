package com.videosniffer.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「断网 → 暂停」的判据。设备常驻 VPN 时 `ConnectivityManager` 仍会报有网络，
 * 因此必须能按错误文本把「可恢复的网络瞬断」与「确定性失败」分开。
 */
class DownloadFailureClassifierTest {

    @Test
    fun `超时与连接重置属于可恢复`() {
        listOf(
            "connect timed out",
            "timeout",
            "Connection reset",
            "broken pipe",
            "unable to resolve host \"cdn.example.com\"",
            "java.net.UnknownHostException",
            "SSL handshake aborted",
            "stream was reset: CANCEL"
        ).forEach { msg ->
            assertTrue("expected transient: $msg", DownloadFailureClassifier.isTransientNetworkFailure(msg))
        }
    }

    @Test
    fun `HTTP 状态码属于确定性失败`() {
        listOf("HTTP 404", "HTTP 403", "HTTP 500").forEach { msg ->
            assertFalse("expected fatal: $msg", DownloadFailureClassifier.isTransientNetworkFailure(msg))
        }
    }

    @Test
    fun `业务性失败不会被误判为网络问题`() {
        listOf(
            "分片为空",
            "不支持的加密方式：SAMPLE-AES",
            "合并结果为空",
            "下载不完整（100/200）",
            "密钥长度异常(8)",
            "分片缺失"
        ).forEach { msg ->
            assertFalse("expected fatal: $msg", DownloadFailureClassifier.isTransientNetworkFailure(msg))
        }
    }

    @Test
    fun `空原因不当作网络问题`() {
        assertFalse(DownloadFailureClassifier.isTransientNetworkFailure(null))
        assertFalse(DownloadFailureClassifier.isTransientNetworkFailure(""))
        assertFalse(DownloadFailureClassifier.isTransientNetworkFailure("   "))
    }

    @Test
    fun `带上下文的 HTTP 错误仍按网络问题处理`() {
        // 例如 "HTTP 502 connect failed"：既有状态码又有连接失败
        assertFalse(DownloadFailureClassifier.isTransientNetworkFailure("HTTP 502"))
        assertTrue(
            DownloadFailureClassifier.isTransientNetworkFailure("connect failed: HTTP 502 upstream")
        )
    }
}
