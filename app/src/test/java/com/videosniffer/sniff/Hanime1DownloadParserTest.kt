package com.videosniffer.sniff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Hanime1DownloadParser] 纯函数测试。
 *
 * 注意：本测试运行在 JVM 上，`android.net.Uri` 不可用，
 * 因此只覆盖不依赖 Uri 的纯函数（文件名清洗、标题归一）。
 */
class Hanime1DownloadParserTest {

    // ---------- qualityFromFileName ----------

    @Test
    fun `qualityFromFileName 提取后缀清晰度`() {
        assertEquals("1080p", Hanime1DownloadParser.qualityFromFileName("40-1080p.mp4"))
        assertEquals("720p", Hanime1DownloadParser.qualityFromFileName("40-720p.mp4"))
        assertEquals("360p", Hanime1DownloadParser.qualityFromFileName("40-360p"))
    }

    @Test
    fun `qualityFromFileName 大小写归一`() {
        assertEquals("1080p", Hanime1DownloadParser.qualityFromFileName("40-1080P.mp4"))
    }

    @Test
    fun `qualityFromFileName 无后缀返回 null`() {
        assertNull(Hanime1DownloadParser.qualityFromFileName("video.mp4"))
        // 只有 `1080p` 而没有 `-` 分隔时不认为是清晰度后缀
        assertNull(Hanime1DownloadParser.qualityFromFileName("1080p.mp4"))
        assertNull(Hanime1DownloadParser.qualityFromFileName(""))
    }

    // ---------- stripExtension ----------

    @Test
    fun `stripExtension 去掉扩展名`() {
        assertEquals("40-1080p", Hanime1DownloadParser.stripExtension("40-1080p.mp4"))
        assertEquals("video", Hanime1DownloadParser.stripExtension("video.m3u8"))
        assertEquals("no-ext", Hanime1DownloadParser.stripExtension("no-ext"))
    }

    // ---------- stripQualitySuffix ----------

    @Test
    fun `stripQualitySuffix 去掉清晰度尾巴`() {
        assertEquals("40", Hanime1DownloadParser.stripQualitySuffix("40-1080p"))
        assertEquals("作品名", Hanime1DownloadParser.stripQualitySuffix("作品名-720p"))
    }

    @Test
    fun `stripQualitySuffix 不误删裸清晰度`() {
        // `1080p` 没有 `-` 前缀，整段就是名字，必须原样保留，不能变成空串
        assertEquals("1080p", Hanime1DownloadParser.stripQualitySuffix("1080p"))
        assertEquals("作品名", Hanime1DownloadParser.stripQualitySuffix("作品名"))
        assertEquals("作品名", Hanime1DownloadParser.stripQualitySuffix("  作品名  "))
    }

    // ---------- cleanTitle ----------

    @Test
    fun `cleanTitle 剥掉站点名尾巴`() {
        assertEquals("作品名", Hanime1DownloadParser.cleanTitle("作品名 - Hanime1.me"))
        assertEquals("作品名", Hanime1DownloadParser.cleanTitle("作品名 | hanime1.me"))
    }

    @Test
    fun `cleanTitle 只剥最后一个分隔段`() {
        // 站点尾巴只占最后一个分隔段，前面的 `- H動漫 -` 保留，不做过度剥离
        assertEquals("作品名 - H動漫", Hanime1DownloadParser.cleanTitle("作品名 - H動漫 - Hanime1.me"))
    }

    @Test
    fun `cleanTitle 压缩空白`() {
        assertEquals("作品 名", Hanime1DownloadParser.cleanTitle("  作品\n\t名  "))
    }

    @Test
    fun `cleanTitle 无站点尾巴时原样返回`() {
        assertEquals("作品名", Hanime1DownloadParser.cleanTitle("作品名"))
        assertEquals("", Hanime1DownloadParser.cleanTitle("   "))
    }

    @Test
    fun `cleanTitle 不误删名字里含 hanime1 但无分隔符的情况`() {
        // 名字本身就是 hanime1xxx，没有分隔符，不应被当成站点尾巴剥掉
        assertEquals("hanime1fan", Hanime1DownloadParser.cleanTitle("hanime1fan"))
    }
}
