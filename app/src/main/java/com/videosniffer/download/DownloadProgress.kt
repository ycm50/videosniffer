package com.videosniffer.download

/**
 * 进度计算的**唯一入口**（下载列表与通知栏共用）。
 *
 * ## 单位约定：一律是字节
 * 两个引擎上报的都是 [DownloadTask.downloadedBytes] / [DownloadTask.totalBytes] 两个**字节数**：
 * - 单文件（mp4）：`Content-Length` 就是精确总量；
 * - m3u8：已下载字节是真实的（边读边报），总量由实测码率 × 总时长**估算**，见 [estimateTotalBytes]。
 *
 * ## 为什么不让 m3u8 按「已完成分片数 / 总分片数」算百分比
 * 分片粒度太粗：一个大分片要下几分钟，进度条会长时间纹丝不动（用户看到的就是「进度条不更新」）；
 * 而且部分源只有几个大分片，一格就是 20%~33%。字节是唯一能连续反映速度的量。
 *
 * ## 历史 bug（两次）
 * 1. 上报「已下载字节 / 已知字节」，而「已知字节」只累计**已完成**分片，分母随分子一起增长
 *    → 百分比从第一次上报起恒为 100%。
 * 2. 改成「分片计数」后，分片少/分片大的源进度条几乎不动。
 *
 * 因此：单位固定在字节，估算公式与展示口径集中在本文件，回归由 `DownloadProgressTest` 钉住。
 */
object DownloadProgress {

    /** 总数未知（[DownloadTask.totalBytes] 为 0 或负数）→ 无法计算百分比。 */
    const val UNKNOWN = -1

    /**
     * 估算总字节数前要求的最少已完成时长。采样太短时算出的码率噪声很大，
     * 分母会离谱地偏大或偏小，宁可先显示不确定进度。
     */
    private const val MIN_SAMPLE_DURATION_MS = 2000L

    /**
     * @param downloaded 已下载**字节数**
     * @param total      总**字节数**；0 表示未知
     * @return 夹紧到 0..100 的百分比；[total] <= 0 时返回 [UNKNOWN]
     */
    fun percent(downloaded: Long, total: Long): Int {
        if (total <= 0) return UNKNOWN
        if (downloaded <= 0) return 0
        if (downloaded >= total) return 100
        return (downloaded * 100L / total).toInt().coerceIn(0, 100)
    }

    /**
     * 用于展示的百分比：**下载中永远不显示 100%**。
     *
     * m3u8 的总字节数是估算值，估小了会让百分比提前到 100%（就成了「一直是 100%」的老 bug）。
     * 全部落盘后引擎会强制上报「已下载 = 总量」，完成态自然回到 100%。
     */
    fun displayPercent(task: DownloadTask): Int {
        val pct = percent(task.downloadedBytes, task.totalBytes)
        return if (pct == 100 && task.state == DownloadState.DOWNLOADING) 99 else pct
    }

    /**
     * 估算 HLS 总分片字节数：用「已落盘字节 / 这些分片的播放时长」得到**实测码率**，
     * 再乘 playlist 的总时长。
     *
     * 前几个分片算出的码率就能收敛（HLS 多为 CBR），且随着已落盘分片变多会自动修正；
     * 采样不足（已完成时长 < [MIN_SAMPLE_DURATION_MS]）或不知道总时长时返回 0（= 未知）。
     *
     * @return 估算的总字节数；返回值**不小于** [bytesDone]，避免出现「已下载 > 总量」的荒谬显示
     */
    fun estimateTotalBytes(bytesDone: Long, doneDurationMs: Long, totalDurationMs: Long): Long {
        if (bytesDone <= 0 || totalDurationMs <= 0) return 0
        if (doneDurationMs < MIN_SAMPLE_DURATION_MS) return 0
        val estimate = bytesDone.toDouble() * totalDurationMs / doneDurationMs
        return estimate.toLong().coerceAtLeast(bytesDone)
    }
}
