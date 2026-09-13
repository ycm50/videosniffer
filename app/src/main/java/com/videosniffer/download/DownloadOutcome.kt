package com.videosniffer.download

/**
 * 一次下载尝试的结果。
 *
 * 引入它的原因：此前引擎在失败时直接抛 `IOException`，异常穿过多分片 `coroutineScope`
 * 时极易被 `CancellationException` 掩盖，调用方只能靠 try/catch 猜测意图，
 * 导致「网络失败」被误判成「用户取消」并删除已下载数据。
 * 现在引擎只返回明确结果，调用方按 [Stopped] 标志决定 PAUSED / CANCELED / FAILED。
 */
sealed interface DownloadOutcome {

    /** 下载完整，可进入导出阶段。 */
    data class Success(val totalBytes: Long) : DownloadOutcome

    /** 被用户暂停或取消（由 [Stopped] 区分）。 */
    data object Stopped : DownloadOutcome

    /**
     * 真实失败（网络/校验/不支持的特性）。
     * @param error 面向用户的简短原因
     */
    data class Failed(val error: String) : DownloadOutcome
}

/** 任务级停止信号：显式区分「暂停」与「取消」两种用户意图。 */
class Stopped {
    @Volatile
    var pauseRequested: Boolean = false
        private set

    @Volatile
    var cancelRequested: Boolean = false
        private set

    /** 是否已请求停止（暂停或取消任一）。 */
    val isStopped: Boolean get() = pauseRequested || cancelRequested

    fun requestPause() {
        pauseRequested = true
    }

    fun requestCancel() {
        cancelRequested = true
    }
}
