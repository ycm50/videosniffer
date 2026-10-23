package com.videosniffer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videosniffer.sniff.MediaUrlDetector
import com.videosniffer.sniff.YouTubeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 依赖 `android.net.Uri` 的判定 —— 这类逻辑在 JVM 单测里 `Uri` 是桩，只能放到仪器测试。
 *
 * 运行：
 * ```bash
 * ./gradlew connectedDebugAndroidTest
 * ```
 * CI 不跑这一层（没有模拟器）；它用于真机回归「嗅探会不会把非视频当视频」。
 */
@RunWith(AndroidJUnit4::class)
class UriDependentTest {

    // ---------- isVideoCandidate ----------

    @Test
    fun 扩展名命中的 URL 视为候选() {
        assertTrue(MediaUrlDetector.isVideoCandidate("https://example.com/a/b/clip.mp4"))
        assertTrue(MediaUrlDetector.isVideoCandidate("https://example.com/live/index.m3u8?token=1"))
        assertTrue(MediaUrlDetector.isVideoCandidate("https://example.com/x.webm"))
    }

    @Test
    fun 普通资源不是候选() {
        assertFalse(MediaUrlDetector.isVideoCandidate("https://example.com/a.jpg"))
        assertFalse(MediaUrlDetector.isVideoCandidate("https://example.com/style.css"))
        assertFalse(MediaUrlDetector.isVideoCandidate("https://example.com/index.html"))
    }

    @Test
    fun YouTube 播放流按 host 与路径识别() {
        val url = "https://rr1---sn-a5mekn6s.googlevideo.com/videoplayback?itag=18&range=0-1023"
        assertTrue(MediaUrlDetector.isVideoCandidate(url))
    }

    @Test
    fun SABR 流被排除() {
        // 服务端自适应码率走 UMP POST，直接 GET 拿不到完整媒体
        val url = "https://rr1---sn-x.googlevideo.com/videoplayback?sabr=1&itag=18"
        assertFalse(MediaUrlDetector.isVideoCandidate(url))
    }

    // ---------- normalizeForDownload ----------

    @Test
    fun 未签名的 range 参数被移除() {
        val url = "https://rr1.googlevideo.com/videoplayback?range=0-1023&sparams=id&itag=18"
        val normalized = MediaUrlDetector.normalizeForDownload(url)

        assertFalse(normalized.contains("range="))
        assertTrue(normalized.contains("sparams=id"))
        assertTrue(normalized.contains("itag=18"))
    }

    @Test
    fun range 在 sparams 里时保持不变() {
        // 签名覆盖了 range，删掉会导致签名校验失败
        val url = "https://rr1.googlevideo.com/videoplayback?range=0-1023&sparams=id%2Crange"
        assertEquals(url, MediaUrlDetector.normalizeForDownload(url))
    }

    @Test
    fun 非 googlevideo 链接原样返回() {
        val url = "https://example.com/video.mp4?range=0-100"
        assertEquals(url, MediaUrlDetector.normalizeForDownload(url))
    }

    // ---------- 扩展名与类型 ----------

    @Test
    fun 扩展名提取忽略大小写与查询串() {
        assertEquals("mp4", MediaUrlDetector.extensionOf("https://example.com/A.MP4?x=1"))
        assertEquals(null, MediaUrlDetector.extensionOf("https://example.com/noext"))
    }

    @Test
    fun 按 ContentType 判定扩展名() {
        assertEquals("m3u8", MediaUrlDetector.extensionFor("https://e/x", "application/vnd.apple.mpegurl"))
        assertEquals("mp4", MediaUrlDetector.extensionFor("https://e/x", "video/mp4"))
        assertEquals("webm", MediaUrlDetector.extensionFor("https://e/x", "video/webm"))
        assertEquals(null, MediaUrlDetector.extensionFor("https://e/x", "text/html"))
    }

    // ---------- YouTubeParser.matches ----------

    @Test
    fun YouTube 站点匹配() {
        assertTrue(YouTubeParser.matches("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(YouTubeParser.matches("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun 非 YouTube 站点不匹配() {
        assertFalse(YouTubeParser.matches("https://hanime1.me/watch?v=1"))
        assertFalse(YouTubeParser.matches("https://example.com/youtube.com/fake"))
    }
}
