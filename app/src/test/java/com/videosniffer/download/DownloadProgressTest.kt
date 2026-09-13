package com.videosniffer.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 进度计算回归测试。
 *
 * 这里钉住三条曾经出过问题的约定：
 * 1. **单位是字节**（早期 m3u8 上报「已下载字节 / 已完成分片的字节」，分母随分子一起长 → 恒 100%）；
 * 2. **下载中不显示 100%**（m3u8 总量是估算值，估小了会提前报完成）；
 * 3. **总量未知时返回 [DownloadProgress.UNKNOWN]**（走不确定动画），不能当成已完成。
 */
class DownloadProgressTest {

    private fun task(
        downloaded: Long = 0,
        total: Long = 0,
        state: DownloadState = DownloadState.DOWNLOADING
    ) = DownloadTask(
        id = "t1",
        url = "https://cdn/x.m3u8",
        title = "作品名",
        quality = "1080p",
        type = "m3u8",
        threadCount = 4,
        sourcePageUrl = "https://page/watch",
        totalBytes = total,
        downloadedBytes = downloaded,
        state = state,
    )

    @Test
    fun `按字节计算百分比`() {
        assertEquals(0, DownloadProgress.percent(0, 1024))
        assertEquals(50, DownloadProgress.percent(512, 1024))
        assertEquals(100, DownloadProgress.percent(1024, 1024))
    }

    @Test
    fun `百分比越界被夹紧`() {
        assertEquals(100, DownloadProgress.percent(4096, 1024))
        assertEquals(0, DownloadProgress.percent(-1, 1024))
    }

    @Test
    fun `总量未知时返回 UNKNOWN 而不是 100`() {
        assertEquals(DownloadProgress.UNKNOWN, DownloadProgress.percent(0, 0))
        assertEquals(DownloadProgress.UNKNOWN, DownloadProgress.percent(123_456, 0))
        assertEquals(DownloadProgress.UNKNOWN, DownloadProgress.percent(123_456, -1))
    }

    /** 回归：m3u8 的总量是估算值，估小了也不能在下载中显示「完成」 */
    @Test
    fun `下载中不显示 100%`() {
        assertEquals(
            "下载中即使估算分母偏小，也只能显示 99%",
            99,
            DownloadProgress.displayPercent(task(downloaded = 2_000, total = 1_000)),
        )
        // 真的一分不差也不提前报完成
        assertEquals(99, DownloadProgress.displayPercent(task(downloaded = 1_000, total = 1_000)))
        // 下载结束后（导出/完成）就是 100%
        assertEquals(
            100,
            DownloadProgress.displayPercent(
                task(downloaded = 1_000, total = 1_000, state = DownloadState.EXPORTING)
            )
        )
    }

    @Test
    fun `总量未知时展示百分比也是 UNKNOWN`() {
        assertEquals(DownloadProgress.UNKNOWN, DownloadProgress.displayPercent(task(downloaded = 999)))
    }

    /**
     * 估算公式：实测码率（已落盘字节 / 已落盘时长）× 总时长。
     * 采样不足时返回 0（= 未知），宁可先走不确定进度也不给假分母。
     */
    @Test
    fun `按实测码率估算总量`() {
        // 10 秒下到 5 MB → 0.5 MB/s；总时长 100 秒 → 约 50 MB
        val estimate = DownloadProgress.estimateTotalBytes(
            bytesDone = 5_000_000,
            doneDurationMs = 10_000,
            totalDurationMs = 100_000,
        )
        assertEquals(50_000_000, estimate)
    }

    @Test
    fun `采样不足或总时长未知时不估算`() {
        assertEquals(
            "已完成时长不足 2 秒，码率噪声太大",
            0,
            DownloadProgress.estimateTotalBytes(5_000_000, 1_000, 100_000),
        )
        assertEquals(
            "不知道总时长（playlist 没有 EXTINF）时无法估算",
            0,
            DownloadProgress.estimateTotalBytes(5_000_000, 10_000, 0),
        )
        assertEquals(0, DownloadProgress.estimateTotalBytes(0, 10_000, 100_000))
    }

    /** 估算值必须 ≥ 已下载字节，否则会出现「已下载 5 MB / 总量 3 MB」这种荒谬显示 */
    @Test
    fun `估算值不小于已下载字节`() {
        assertEquals(
            5_000_000,
            DownloadProgress.estimateTotalBytes(5_000_000, 60_000, 10_000)
        )
    }

    @Test
    fun `大字节数不溢出`() {
        val total = 8L * 1024 * 1024 * 1024 * 1024 // 8 TB
        assertEquals(50, DownloadProgress.percent(total / 2, total))
        // 8 TB / 3600s × 7200s ≈ 16 TB，必须还能算出合理的量级
        val estimate = DownloadProgress.estimateTotalBytes(total, 3_600_000, 7_200_000)
        assertEquals(total * 2, estimate)
    }
}
