package com.videosniffer.download.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [M3U8Parser] 纯解析逻辑测试。
 * 覆盖点：master 变体、EXTINF、EXT-X-KEY 继承、EXT-X-MAP、BYTERANGE、相对 URL 解析。
 */
class M3U8ParserTest {

    // ---------- master playlist ----------

    @Test
    fun `master playlist 解析出全部变体`() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            high/index.m3u8
        """.trimIndent()

        val result = M3U8Parser.parse(text, "https://cdn.example.com/hls/master.m3u8")
        assertTrue(result is HlsPlaylist.Master)

        val variants = (result as HlsPlaylist.Master).variants
        assertEquals(2, variants.size)
        assertEquals(1280000, variants[0].bandwidth)
        assertEquals("640x360", variants[0].resolution)
        assertEquals("https://cdn.example.com/hls/low/index.m3u8", variants[0].uri)
        assertEquals("https://cdn.example.com/hls/high/index.m3u8", variants[1].uri)
    }

    // ---------- media playlist ----------

    @Test
    fun `media playlist 解析分段与时长`() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:5
            #EXTINF:9.009,
            seg0.ts
            #EXTINF:9.009,title
            ../other/seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = M3U8Parser.parse(text, "https://cdn.example.com/hls/v1/index.m3u8") as HlsPlaylist.Media
        assertEquals(2, result.segments.size)
        assertEquals(10, result.targetDuration)
        assertEquals(5L, result.mediaSequence)
        assertTrue(result.hasEndList)
        assertTrue(result.segments[0].durationSec > 9.0)
        assertEquals("https://cdn.example.com/hls/v1/seg0.ts", result.segments[0].uri)
        // ../ 上跳一级
        assertEquals("https://cdn.example.com/hls/other/seg1.ts", result.segments[1].uri)
        // 未声明 KEY 时默认不加密
        assertTrue(!result.segments[0].key.isEncrypted)
    }

    @Test
    fun `EXT-X-KEY 对后续分段生效并携带 IV`() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.com/k1",IV=0x0000000000000000000000000000002A
            #EXTINF:4,
            a.ts
            #EXTINF:4,
            b.ts
        """.trimIndent()

        val result = M3U8Parser.parse(text, "https://cdn.example.com/hls/index.m3u8") as HlsPlaylist.Media
        val key = result.segments[0].key
        assertTrue(key.isEncrypted)
        assertEquals("AES-128", key.method)
        assertEquals("https://keys.example.com/k1", key.keyUri)
        assertEquals("0x0000000000000000000000000000002A", key.iv)
        assertEquals("AES-128", result.segments[1].key.method)
    }

    @Test
    fun `METHOD=NONE 会清除之前继承的密钥`() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.com/k1"
            #EXTINF:4,
            a.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            b.ts
        """.trimIndent()

        val result = M3U8Parser.parse(text, "https://cdn.example.com/hls/index.m3u8") as HlsPlaylist.Media
        assertTrue(result.segments[0].key.isEncrypted)
        assertTrue(!result.segments[1].key.isEncrypted)
    }

    @Test
    fun `EXT-X-MAP 被记录并解析相对地址`() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4,
            seg1.m4s
        """.trimIndent()

        val result = M3U8Parser.parse(text, "https://cdn.example.com/hls/v2/index.m3u8") as HlsPlaylist.Media
        val map = result.segments[0].map
        assertNotNull(map)
        assertEquals("https://cdn.example.com/hls/v2/init.mp4", map!!.uri)
        assertEquals("https://cdn.example.com/hls/v2/seg1.m4s", result.segments[0].uri)
    }

    @Test
    fun `EXT-X-MAP 支持 BYTERANGE`() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="all.mp4",BYTERANGE="800@0"
            #EXTINF:4,
            seg1.m4s
        """.trimIndent()

        val result = M3U8Parser.parse(text, "https://cdn.example.com/hls/index.m3u8") as HlsPlaylist.Media
        assertEquals(800L, result.segments[0].map?.byteRange?.length)
        assertEquals(0L, result.segments[0].map?.byteRange?.offset)
    }

    @Test
    fun `EXT-X-BYTERANGE 缺省 offset 时接续上一个区间`() {
        val text = """
            #EXTM3U
            #EXTINF:4,
            #EXT-X-BYTERANGE:100@0
            file.ts
            #EXTINF:4,
            #EXT-X-BYTERANGE:200
            file.ts
        """.trimIndent()

        val result = M3U8Parser.parse(text, "https://cdn.example.com/hls/index.m3u8") as HlsPlaylist.Media
        assertEquals(0L, result.segments[0].byteRange?.offset)
        assertEquals(100L, result.segments[0].byteRange?.length)
        // 第二个区间省略 offset → 从 100 开始
        assertEquals(100L, result.segments[1].byteRange?.offset)
        assertEquals(200L, result.segments[1].byteRange?.length)
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(M3U8Parser.parse("", null))
        assertNull(M3U8Parser.parse("#EXTM3U\n#EXT-X-TARGETDURATION:10", null))
        assertNull(M3U8Parser.parse("<html>not a playlist</html>", null))
    }

    // ---------- 属性列表 / byterange ----------

    @Test
    fun `属性列表解析引号内逗号不被切分`() {
        val attrs = M3U8Parser.parseAttributeList(
            """METHOD=AES-128,URI="https://k.example.com/a,b?c=1",IV=0x01"""
        )
        assertEquals("AES-128", attrs["METHOD"])
        assertEquals("https://k.example.com/a,b?c=1", attrs["URI"])
        assertEquals("0x01", attrs["IV"])
    }

    @Test
    fun `parseByteRange 处理两种写法与非法值`() {
        assertEquals(ByteRange(50, 0), M3U8Parser.parseByteRange("50@0", 0)?.first)
        assertEquals(ByteRange(70, 12), M3U8Parser.parseByteRange("70@12", 999)?.first)
        assertEquals(ByteRange(30, 999), M3U8Parser.parseByteRange("30", 999)?.first)
        // 带引号
        assertEquals(ByteRange(10, 5), M3U8Parser.parseByteRange("\"10@5\"", 0)?.first)
        assertNull(M3U8Parser.parseByteRange("abc", 0))
        assertNull(M3U8Parser.parseByteRange("0", 0))
    }

    // ---------- URL 解析 ----------

    @Test
    fun `resolve 覆盖绝对_根路径_相对与上跳`() {
        val base = "https://cdn.example.com/hls/v1/index.m3u8?token=abc"
        assertEquals("https://other.com/s.ts", M3U8Parser.resolve(base, "https://other.com/s.ts"))
        assertEquals("https://cdn.example.com/root.ts", M3U8Parser.resolve(base, "/root.ts"))
        assertEquals("https://cdn.example.com/hls/v1/seg.ts", M3U8Parser.resolve(base, "seg.ts"))
        assertEquals("https://cdn.example.com/hls/seg.ts", M3U8Parser.resolve(base, "../seg.ts"))
        assertEquals("https://cdn.example.com/hls/x.ts", M3U8Parser.resolve(base, "../../x.ts"))
    }

    @Test
    fun `resolve 在 baseUrl 为空时原样返回`() {
        assertEquals("seg.ts", M3U8Parser.resolve(null, "seg.ts"))
        assertEquals("not-a-url", M3U8Parser.resolve("", "not-a-url"))
    }
}
