package com.videosniffer.download

/**
 * 下载任务状态。
 *
 * 生命周期：
 * `QUEUED → PENDING → PROBING → DOWNLOADING → EXPORTING → COMPLETED`
 * 中途可进入 `PAUSED`（保留进度与文件，可续传）/ `FAILED`（可重试）/ `CANCELED`（删记录与文件）。
 *
 * QUEUED 表示「已入队但未占用并发槽位」，由 [DownloadManager] 按「同时下载任务数」设置补位启动。
 */
enum class DownloadState {
    QUEUED, PENDING, PROBING, DOWNLOADING, EXPORTING, PAUSED, COMPLETED, FAILED, CANCELED
}

/**
 * 下载任务（单文件 mp4 或 m3u8 流）。
 *
 * @param type mp4 / m3u8
 * @param totalBytes 进度**分母**：总字节数。mp4 来自 `Content-Length`；m3u8 由「实测码率 × 总时长」
 *   **估算**（见 [DownloadProgress.estimateTotalBytes]），0 表示暂时估不出来（UI 走不确定进度）。
 * @param downloadedBytes 进度**分子**：已下载字节数（m3u8 是真实值，且包含**正在下载**的分片，
 *   所以进度是连续的，不是「下完一个分片跳一格」）。百分比一律交给 [DownloadProgress] 计算。
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val quality: String?,
    val type: String,
    val threadCount: Int,
    val sourcePageUrl: String,
    val m3u8Url: String? = null,
    var totalBytes: Long = 0L,
    var downloadedBytes: Long = 0L,
    /** 下载中实际持有的并发连接数（由下载器实时更新） */
    var activeConnections: Int = 0,
    var filePath: String? = null,
    var state: DownloadState = DownloadState.QUEUED,
    var error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
