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

    @Test
    fun `master playlist 的 RESOLUTION 映射成清晰度`() {
        assertEquals("1080p", MediaUrlDetector.qualityForResolution("1920x1080"))
        assertEquals("720p", MediaUrlDetector.qualityForResolution("1280x720"))
        assertEquals("2160p", MediaUrlDetector.qualityForResolution("3840x2160"))
        assertEquals(1080, MediaUrlDetector.heightOfResolution("1920x1080"))
        assertEquals(0, MediaUrlDetector.heightOfResolution(null))
        assertNull(MediaUrlDetector.qualityForResolution(null))
        assertNull(MediaUrlDetector.qualityForResolution(""))
    }

    @Test
    fun `URL 路径兜底识别清晰度`() {
        assertEquals("1080p", MediaUrlDetector.qualityFromUrlPath("https://cdn/hls/1080p/index.m3u8"))
        assertEquals("720p", MediaUrlDetector.qualityFromUrlPath("https://cdn/720p/seg1.ts?t=1"))
        assertEquals("1080p", MediaUrlDetector.qualityFromUrlPath("https://cdn/1920x1080/index.m3u8"))
        assertEquals("1080p", MediaUrlDetector.qualityFromUrlPath("https://cdn/xxx-1080p.mp4"))
        // 查询串里的数字不算（否则会把签名参数当成清晰度）
        assertNull(MediaUrlDetector.qualityFromUrlPath("https://cdn/v/index.m3u8?h=1080p"))
        // URL 里的 token 常长成 `1234p` 这样，不在常见高度白名单里，不认
        assertNull(MediaUrlDetector.qualityFromUrlPath("https://cdn/a1234p/x.m3u8"))
        assertNull(MediaUrlDetector.qualityFromUrlPath("https://cdn/video/index.m3u8"))
    }

    /** 「m3u8 清晰度识别无效」的核心回归：master playlist 必须展开成多个带清晰度的候选 */
    @Test
    fun `master playlist 展开成多个清晰度候选_最清晰在前`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            high/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            mid/index.m3u8
        """.trimIndent()

        val list = MediaUrlDetector.candidatesForPlaylist(
            master, "https://cdn/hls/master.m3u8", "作品名", "https://page/watch"
        )

        assertEquals(listOf("1080p", "720p", "360p"), list.map { it.quality })
        assertEquals("https://cdn/hls/high/index.m3u8", list[0].url)
        assertEquals("https://cdn/hls/low/index.m3u8", list[2].url)
        assertEquals("m3u8", list[0].ext)
        assertEquals("作品名", list[0].title)
        assertEquals("https://page/watch", list[0].sourcePageUrl)
        assertNull("HLS 候选的大小未知", list[0].size)
    }

    @Test
    fun `media playlist 只给一条候选_清晰度按路径兜底`() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:10.0,
            seg2.ts
        """.trimIndent()

        val list = MediaUrlDetector.candidatesForPlaylist(
            media, "https://cdn/hls/720p/index.m3u8", "作品名", "https://page/watch"
        )

        assertEquals(1, list.size)
        assertEquals("https://cdn/hls/720p/index.m3u8", list[0].url)
        assertEquals("720p", list[0].quality)
    }

    @Test
    fun `标不出清晰度时 master 不展开_只给最清晰的一条`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=5000000
            high/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            low/index.m3u8
        """.trimIndent()

        val list = MediaUrlDetector.candidatesForPlaylist(
            master, "https://cdn/hls/master.m3u8", null, "https://page/watch"
        )

        assertEquals("多行一样的「原画」没有意义，只留最清晰的一条", 1, list.size)
        assertEquals("https://cdn/hls/high/index.m3u8", list[0].url)
    }

    @Test
    fun `非 playlist 文本不产生候选`() {
        assertEquals(
            emptyList<DetectedMedia>(),
            MediaUrlDetector.candidatesForPlaylist("<html>not a playlist</html>", "https://a/x", null, "https://p")
        )
    }
}
