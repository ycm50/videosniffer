package com.videosniffer.download

/**
 * 下载任务状态。
 * PAUSED 保留进度与文件可续传；CANCELED 删除记录与文件。
 */
enum class DownloadState {
    PENDING, PROBING, DOWNLOADING, EXPORTING, PAUSED, COMPLETED, FAILED, CANCELED
}

/**
 * 下载任务（单文件 mp4 或 m3u8 流）。
 *
 * @param type mp4 / m3u8
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
    var state: DownloadState = DownloadState.PENDING,
    var error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
