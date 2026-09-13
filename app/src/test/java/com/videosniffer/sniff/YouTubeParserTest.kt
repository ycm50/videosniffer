package com.videosniffer.sniff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [YouTubeParser] 与 [MediaUrlDetector] 中 YouTube 相关纯函数的测试。
 *
 * 注意：本测试跑在 JVM 上，`android.net.Uri` 与 `org.json` 都不可用，
 * 因此只覆盖不依赖它们的分支 —— 这也是把解析逻辑拆成纯函数的直接收益。
 */
class YouTubeParserTest {

    // ---------- videoIdOf ----------

    @Test
    fun `videoIdOf 支持常见链接形态`() {
        val id = "dQw4w9WgXcQ"
        assertEquals(id, YouTubeParser.videoIdOf("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, YouTubeParser.videoIdOf("https://www.youtube.com/watch?list=PL1&v=$id&t=30"))
        assertEquals(id, YouTubeParser.videoIdOf("https://youtu.be/$id"))
        assertEquals(id, YouTubeParser.videoIdOf("https://youtu.be/$id?t=42"))
        assertEquals(id, YouTubeParser.videoIdOf("https://www.youtube.com/shorts/$id"))
        assertEquals(id, YouTubeParser.videoIdOf("https://www.youtube.com/embed/$id"))
        assertEquals(id, YouTubeParser.videoIdOf("https://m.youtube.com/live/$id"))
    }

    @Test
    fun `videoIdOf 对非法链接返回 null`() {
        assertNull(YouTubeParser.videoIdOf("https://www.youtube.com/results?search_query=abc"))
        assertNull(YouTubeParser.videoIdOf("https://example.com/watch?v=abc"))
        // 长度不是 11 位
        assertNull(YouTubeParser.videoIdOf("https://www.youtube.com/watch?v=tooshort"))
    }

    // ---------- mimeType 分类 ----------

    // 用普通字符串而不是 raw string：内容以引号结尾，raw string 的结束引号会歧义
    private val muxed18 = "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\""
    private val videoOnly137 = "video/mp4; codecs=\"avc1.640028\""
    private val audioOnly140 = "audio/mp4; codecs=\"mp4a.40.2\""
    private val muxedWebm = "video/webm; codecs=\"vp9, opus\""
    private val av1VideoOnly = "video/mp4; codecs=\"av01.0.12M.08\""

    @Test
    fun `识别合流与纯视频`() {
        assertTrue(YouTubeParser.isMuxed(muxed18))
        assertTrue("vp9 + opus 也是合流", YouTubeParser.isMuxed(muxedWebm))

        assertFalse("avc1 单独出现 = 无音轨", YouTubeParser.isMuxed(videoOnly137))
        assertFalse("av01 单独出现 = 无音轨", YouTubeParser.isMuxed(av1VideoOnly))
        assertFalse(YouTubeParser.isMuxed(audioOnly140))

        assertTrue(YouTubeParser.hasVideo(muxed18))
        assertTrue(YouTubeParser.hasVideo(videoOnly137))
        assertFalse("audio/ 容器没有视频轨", YouTubeParser.hasVideo(audioOnly140))

        assertTrue(YouTubeParser.hasAudio(muxed18))
        assertTrue(YouTubeParser.hasAudio(audioOnly140))
        assertFalse(YouTubeParser.hasAudio(videoOnly137))

        assertTrue(YouTubeParser.isAudioOnly(audioOnly140))
        assertFalse(YouTubeParser.isAudioOnly(muxed18))
    }

    @Test
    fun `avc1 不会被误判成音频编码 ac-3`() {
        // 子串匹配的经典陷阱：avc1 里没有 "ac-3"，这里把结论钉住
        assertFalse(YouTubeParser.hasAudio("video/mp4; codecs=\"avc1.640028\""))
    }

    // ---------- qualityFor ----------

    @Test
    fun `qualityFor 优先用 qualityLabel`() {
        assertEquals("1080p", YouTubeParser.qualityFor(137, "1080p"))
        // 响应给了更精确的标签时以它为准（例如 1080p60）
        assertEquals("1080p60", YouTubeParser.qualityFor(137, "1080p60"))
        assertEquals("720p", YouTubeParser.qualityFor(22, "720p"))
    }

    @Test
    fun `qualityFor 回退到 itag 表`() {
        assertEquals("1080p", YouTubeParser.qualityFor(137, null))
        assertEquals("360p", YouTubeParser.qualityFor(18, ""))
        assertEquals("2160p", YouTubeParser.qualityFor(313, null))
        assertNull(YouTubeParser.qualityFor(99999, null))
    }

    // ---------- parseSignatureCipher ----------

    @Test
    fun `parseSignatureCipher 解析出 url 签名与参数名`() {
        val cipher = "s=CC%3DQ8o2zpxwirVyNq_miGGr282CaNsFf&sp=sig&url=" +
            "https%3A%2F%2Frr12---sn-3c27sn7d.googlevideo.com%2Fvideoplayback%3Fitag%3D18%26mime%3Dvideo%252Fmp4"

        val parts = YouTubeParser.parseSignatureCipher(cipher)
        requireNotNull(parts)
        assertEquals("sig", parts.sigParam)
        assertEquals("CC=Q8o2zpxwirVyNq_miGGr282CaNsFf", parts.signature)
        assertEquals(
            "https://rr12---sn-3c27sn7d.googlevideo.com/videoplayback?itag=18&mime=video%2Fmp4",
            parts.url,
        )
    }

    @Test
    fun `parseSignatureCipher 签名缺失时仅返回 url`() {
        val parts = YouTubeParser.parseSignatureCipher(
            "sp=sig&url=https%3A%2F%2Fexample.com%2Fv%3Fitag%3D18"
        )
        requireNotNull(parts)
        assertNull(parts.signature)
        assertEquals("https://example.com/v?itag=18", parts.url)
    }

    @Test
    fun `parseSignatureCipher 默认签名参数名为 sig`() {
        val parts = YouTubeParser.parseSignatureCipher("s=abc&url=https%3A%2F%2Fexample.com")
        requireNotNull(parts)
        assertEquals("sig", parts.sigParam)
    }

    @Test
    fun `parseSignatureCipher 无 url 时返回 null`() {
        assertNull(YouTubeParser.parseSignatureCipher("s=abc&sp=sig"))
        assertNull(YouTubeParser.parseSignatureCipher(""))
    }

    // ---------- extractBraceJson ----------

    @Test
    fun `extractBraceJson 取到完整对象`() {
        val html = """<script>var ytInitialPlayerResponse = {"a":1,"b":{"c":2}};</script>"""
        assertEquals(
            """{"a":1,"b":{"c":2}}""",
            YouTubeParser.extractBraceJson(html, "ytInitialPlayerResponse"),
        )
    }

    @Test
    fun `extractBraceJson 不把字符串里的括号当结构`() {
        // 字符串里出现 { } 与转义引号，必须按字符串规则跳过
        val html = """x = {"k":"a{b}c\"d}","n":1}"""
        assertEquals("""{"k":"a{b}c\"d}","n":1}""", YouTubeParser.extractBraceJson(html, "x ="))
    }
    @Test
    fun `extractBraceJson 标记缺失或未闭合返回 null`() {
        assertNull(YouTubeParser.extractBraceJson("nothing here", "ytInitialPlayerResponse"))
        assertNull(YouTubeParser.extractBraceJson("a = {\"k\":1", "a ="))
    }

    // ---------- extractYtcfgValue ----------

    @Test
    fun `extractYtcfgValue 取出字符串配置`() {
        val html = """ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyTest","VISITOR_DATA":"CgtoABC","N":1});"""
        assertEquals("AIzaSyTest", YouTubeParser.extractYtcfgValue(html, "INNERTUBE_API_KEY"))
        assertEquals("CgtoABC", YouTubeParser.extractYtcfgValue(html, "VISITOR_DATA"))
        assertNull(YouTubeParser.extractYtcfgValue(html, "NOT_THERE"))
    }

    @Test
    fun `extractYtcfgValue 忽略非字符串值`() {
        val html = """{"INNERTUBE_API_KEY":123}"""
        assertNull(YouTubeParser.extractYtcfgValue(html, "INNERTUBE_API_KEY"))
    }

    // ---------- MediaUrlDetector 的 YouTube 相关纯函数 ----------

    @Test
    fun `qualityForItag 覆盖合流与纯视频`() {
        assertEquals("360p", MediaUrlDetector.qualityForItag(18))
        assertEquals("720p", MediaUrlDetector.qualityForItag(22))
        assertEquals("1080p", MediaUrlDetector.qualityForItag(137))
        assertEquals("2160p60", MediaUrlDetector.qualityForItag(315))
        assertNull(MediaUrlDetector.qualityForItag(140))
    }

    @Test
    fun `normalizeForDownload 去掉未签名的 range 参数`() {
        val base = "https://rr1---sn-x.googlevideo.com/videoplayback?id=abc&itag=18" +
            "&mime=video%2Fmp4&sparams=expire%2Citag%2Cmime&sig=XYZ"
        val ranged = "$base&range=0-1048575"

        assertEquals("未签名的 range 必须去掉，否则只会下到一小段", base, MediaUrlDetector.normalizeForDownload(ranged))
    }

    @Test
    fun `normalizeForDownload 保留已签名的 range 参数`() {
        // sparams 里含 range → 删掉会让签名校验失败，必须原样保留
        val url = "https://rr1---sn-x.googlevideo.com/videoplayback?id=abc&range=0-999" +
            "&sparams=expire%2Crange%2Citag&sig=XYZ"
        assertEquals(url, MediaUrlDetector.normalizeForDownload(url))
    }

    @Test
    fun `normalizeForDownload 处理 range 位于首位的情况`() {
        val result = MediaUrlDetector.normalizeForDownload(
            "https://rr1---sn-x.googlevideo.com/videoplayback?range=0-999&itag=18&sparams=itag"
        )
        assertEquals(
            "首位的 ?range= 删掉后应留下合法 URL（不能出现 ?&）",
            "https://rr1---sn-x.googlevideo.com/videoplayback?itag=18&sparams=itag",
            result,
        )
    }

    @Test
    fun `normalizeForDownload 不动非 googlevideo 链接`() {
        val url = "https://example.com/video.mp4?range=0-100"
        assertEquals(url, MediaUrlDetector.normalizeForDownload(url))
    }

    @Test
    fun `normalizeForDownload 无 range 时原样返回`() {
        val url = "https://rr1---sn-x.googlevideo.com/videoplayback?id=abc&itag=18"
        assertEquals(url, MediaUrlDetector.normalizeForDownload(url))
    }
}
