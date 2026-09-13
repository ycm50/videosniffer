package com.videosniffer.sniff

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DetectedMedia] 的纯逻辑测试：清晰度标签 → 高度，以及清晰度对话框的默认选中项。
 *
 * 背景：旧实现写死「默认取列表最后一条，若存在 `1080p` 则选它」。
 * HLS master 展开后是「最清晰在前」（1080p 在第一条），于是默认选中项会落在**最低画质**上；
 * 只有恰好存在 `1080p` 这条硬编码时才对。改为按高度取最大后与列表顺序无关。
 */
class DetectedMediaTest {

    private fun media(quality: String?, url: String = "https://cdn/x.m3u8") =
        DetectedMedia(url, quality, null, "m3u8", "作品名", "https://page/watch")

    @Test
    fun `清晰度标签解析出高度`() {
        assertEquals(1080, DetectedMedia.heightOfQuality("1080p"))
        assertEquals(720, DetectedMedia.heightOfQuality("720p"))
        // YouTube 的 60fps 标签
        assertEquals(2160, DetectedMedia.heightOfQuality("2160p60"))
        // YouTube 无音轨标注
        assertEquals(1080, DetectedMedia.heightOfQuality("1080p（无音轨）"))
        assertEquals(0, DetectedMedia.heightOfQuality(null))
        assertEquals(0, DetectedMedia.heightOfQuality(""))
        assertEquals(0, DetectedMedia.heightOfQuality("原画"))
    }

    @Test
    fun `默认选中清晰度最高的一条`() {
        // HLS 变体展开后的顺序：最清晰在前
        assertEquals(
            0,
            DetectedMedia.bestIndex(listOf(media("1080p"), media("720p"), media("480p")))
        )
        // 升序排列同样要落到 1080p
        assertEquals(
            2,
            DetectedMedia.bestIndex(listOf(media("480p"), media("720p"), media("1080p")))
        )
        // 同高度取靠后的一条（1080p60 优于 1080p）
        assertEquals(1, DetectedMedia.bestIndex(listOf(media("1080p"), media("1080p60"))))
    }

    @Test
    fun `判不出清晰度时退回最后一条`() {
        assertEquals(
            2,
            DetectedMedia.bestIndex(listOf(media("原画"), media(null), media("")))
        )
        assertEquals(-1, DetectedMedia.bestIndex(emptyList()))
    }
}
