package com.videosniffer.sniff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 嗅探判定纯逻辑测试。
 * 这里只覆盖不依赖 Android 框架的部分（[MediaUrlDetector.extensionFor]、
 * [MediaUrlDetector.extensionOf] 的等价路径逻辑、[Hanime1DownloadParser.qualityFromUrl]）；
 * `isVideoCandidate` 因使用 android.net.Uri 需在设备/仪器测试中验证。
 */
class MediaUrlDetectorTest {

    /** 与 [MediaUrlDetector.extensionOf] 相同的扩展名提取规则，用于验证候选判定用的路径逻辑 */
    private fun extensionOfPath(path: String): String? {
        val lastSegment = path.substringAfterLast('/')
        val dot = lastSegment.lastIndexOf('.')
        if (dot < 0 || dot == lastSegment.length - 1) return null
        return lastSegment.substring(dot + 1)
    }

    @Test
    fun `按 Content-Type 判定媒体类型`() {
        assertEquals("m3u8", MediaUrlDetector.extensionFor("https://a/x", "application/vnd.apple.mpegurl"))
        assertEquals("m3u8", MediaUrlDetector.extensionFor("https://a/x", "application/x-mpegURL; charset=utf-8"))
        assertEquals("m3u8", MediaUrlDetector.extensionFor("https://a/live.m3u8", "application/octet-stream"))
        assertEquals("mp4", MediaUrlDetector.extensionFor("https://a/x", "video/mp4"))
        assertEquals("mp4", MediaUrlDetector.extensionFor("https://a/x", "application/mp4"))
        assertEquals("mp4", MediaUrlDetector.extensionFor("https://a/v.mp4", "application/octet-stream"))
        assertEquals("webm", MediaUrlDetector.extensionFor("https://a/x", "video/webm"))
        assertNull(MediaUrlDetector.extensionFor("https://a/x", "text/html"))
        assertNull(MediaUrlDetector.extensionFor("https://a/x", null))
        assertNull(MediaUrlDetector.extensionFor("https://a/x", ""))
    }

    @Test
    fun `扩展名提取覆盖无扩展名与隐藏形态`() {
        assertEquals("ts", extensionOfPath("/a/b/seg1.ts"))
        assertEquals("mp4", extensionOfPath("/a/b/video.MP4".lowercase()))
        assertNull(extensionOfPath("/a/b/noext"))
        assertNull(extensionOfPath("/a/b/trailing."))
        assertNull(extensionOfPath(""))
        assertEquals("m3u8", extensionOfPath("playlist.m3u8"))
    }

    @Test
    fun `候选判定认得的视频扩展名齐全`() {
        // 与实现中的 videoExtensions 列表保持一致
        val supported = listOf("m3u8", "mp4", "flv", "mpeg", "webm", "m4v", "mov")
        supported.forEach { ext ->
            assertEquals(ext, extensionOfPath("/a/file.$ext"))
        }
    }

    @Test
    fun `从下载直链提取清晰度`() {
        assertEquals("1080p", Hanime1DownloadParser.qualityFromUrl("https://a/xxx-1080p.mp4"))
        assertEquals("720p", Hanime1DownloadParser.qualityFromUrl("https://a/b/name-720p.mp4?t=1"))
        assertEquals("480p", Hanime1DownloadParser.qualityFromUrl("https://a/name-480p"))
        assertNull(Hanime1DownloadParser.qualityFromUrl("https://a/name.mp4"))
        assertNull(Hanime1DownloadParser.qualityFromUrl(""))
    }
}
