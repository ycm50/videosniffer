package com.videosniffer.ui.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.videosniffer.R
import com.videosniffer.download.DownloadManager
import com.videosniffer.download.DownloadProgress
import com.videosniffer.download.DownloadState
import com.videosniffer.download.DownloadTask

/**
 * 下载任务列表适配器。
 *
 * 列表由 [DownloadListFragment] 订阅 [DownloadManager.tasks] 驱动（每 300ms 一次进度），
 * 因此：[notifyDataSetChanged] 会重绘可见项，速度按「最近一次采样」计算以保证平滑。
 */
class DownloadAdapter : RecyclerView.Adapter<DownloadAdapter.VH>() {

    private val items = mutableListOf<DownloadTask>()

    /** id -> (已下载字节, 采样时刻)，用于计算实时速度 */
    private val lastSample = HashMap<String, Pair<Long, Long>>()

    /** id -> 最近一次成功计算出的速度文本（采样过密时沿用，避免读数抖动） */
    private val lastSpeed = HashMap<String, String>()

    fun submitList(list: List<DownloadTask>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle = itemView.findViewById<TextView>(R.id.tv_title)
        private val tvStatus = itemView.findViewById<TextView>(R.id.tv_status)
        private val tvMeta = itemView.findViewById<TextView>(R.id.tv_meta)
        private val tvError = itemView.findViewById<TextView>(R.id.tv_error)
        private val progressBar = itemView.findViewById<ProgressBar>(R.id.progress_bar)
        private val tvProgressPct = itemView.findViewById<TextView>(R.id.tv_progress_pct)
        private val btnPauseResume = itemView.findViewById<Button>(R.id.btn_pause_resume)
        private val btnCancel = itemView.findViewById<Button>(R.id.btn_cancel)
        private val btnRetry = itemView.findViewById<Button>(R.id.btn_retry)
        private val btnOpen = itemView.findViewById<Button>(R.id.btn_open)
        private val btnDelete = itemView.findViewById<Button>(R.id.btn_delete)

        fun bind(task: DownloadTask) {
            val ctx = itemView.context
            tvTitle.text = task.title

            tvStatus.text = statusText(ctx, task.state)
            val isRunning = task.state in setOf(
                DownloadState.PENDING, DownloadState.PROBING, DownloadState.DOWNLOADING
            )
            val isWaiting = task.state == DownloadState.QUEUED
            val isActive = isRunning || isWaiting
            val isM3u8 = task.type.equals("m3u8", true)

            // 进度：单位统一是字节（m3u8 的总量是估算值），计算口径唯一走 DownloadProgress。
            // 总量未知（还没算出估算值 / 源没给 Content-Length）时走不确定动画。
            val computed = DownloadProgress.displayPercent(task)
            val pct: Int
            val indeterminate: Boolean
            when {
                computed >= 0 -> {
                    pct = computed
                    indeterminate = false
                }
                task.state == DownloadState.DOWNLOADING -> {
                    pct = 0
                    indeterminate = true
                }
                else -> {
                    pct = 0
                    indeterminate = false
                }
            }
            progressBar.isIndeterminate = indeterminate
            progressBar.progress = pct
            progressBar.isVisible = task.state != DownloadState.COMPLETED

            tvProgressPct.isVisible = !indeterminate && task.state != DownloadState.COMPLETED
            tvProgressPct.text = ctx.getString(R.string.progress_pct, pct)

            // 描述文本（含实时速度与并发连接数）。
            // 进度单位统一是字节，所以 m3u8 也能显示已下载大小与实时速度
            val quality = task.quality ?: task.type
            val speedSuffix = speedFor(task).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""
            val conn = if (task.state == DownloadState.DOWNLOADING && task.activeConnections > 0) {
                ctx.getString(R.string.format_connections, task.activeConnections)
            } else {
                ""
            }
            val connSuffix = conn.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""
            val sizeText = if (isM3u8) {
                estimatedSize(task)
            } else {
                formatSize(task.downloadedBytes, task.totalBytes)
            }
            val baseMeta = ctx.getString(R.string.meta_download, quality, sizeText, speedSuffix)
            tvMeta.text = "$baseMeta$connSuffix"

            // 失败原因（旧版从不展示，导致用户无从判断）
            tvError.isVisible = task.state == DownloadState.FAILED && !task.error.isNullOrBlank()
            tvError.text = task.error?.let { ctx.getString(R.string.format_error, it) }.orEmpty()

            // 暂停/继续
            btnPauseResume.isVisible = isActive || task.state == DownloadState.PAUSED
            btnPauseResume.isEnabled = task.state != DownloadState.QUEUED
            btnPauseResume.text = ctx.getString(
                if (task.state == DownloadState.PAUSED) R.string.action_resume else R.string.action_pause
            )
            btnPauseResume.setOnClickListener {
                if (task.state == DownloadState.PAUSED) {
                    DownloadManager.resumeTask(task.id)
                } else {
                    DownloadManager.pauseTask(task.id)
                }
            }

            btnCancel.isVisible = isActive || task.state == DownloadState.PAUSED
            btnCancel.setOnClickListener { DownloadManager.cancelTask(task.id) }

            btnRetry.isVisible = task.state == DownloadState.FAILED
            btnRetry.setOnClickListener { DownloadManager.retryTask(task.id) }

            btnOpen.isVisible = task.state == DownloadState.COMPLETED
            btnOpen.setOnClickListener { openFile(itemView.context, task) }

            btnDelete.isVisible = task.state == DownloadState.COMPLETED || task.state == DownloadState.FAILED
            btnDelete.setOnClickListener { DownloadManager.deleteTask(task.id) }
        }
    }

    private fun statusText(context: Context, state: DownloadState): String {
        val res = when (state) {
            DownloadState.QUEUED -> R.string.status_queued
            DownloadState.PENDING -> R.string.status_pending
            DownloadState.PROBING -> R.string.status_probing
            DownloadState.DOWNLOADING -> R.string.status_downloading
            DownloadState.EXPORTING -> R.string.status_exporting
            DownloadState.PAUSED -> R.string.status_paused
            DownloadState.COMPLETED -> R.string.status_completed
            DownloadState.FAILED -> R.string.status_failed
            DownloadState.CANCELED -> R.string.status_canceled
        }
        return context.getString(res)
    }

    /**
     * 实时速度：基于最近一次有效采样与当前值的时间差计算。
     *
     * 注意：间隔不足 [MIN_SAMPLE_INTERVAL_MS] 时**不能**刷新采样参考点，
     * 否则参考点被不断前移，速度永远算不出来（列表每 300ms 刷新一次，
     * 而 bind 可能因滚动等原因更频繁触发）。
     */
    private fun speedFor(task: DownloadTask): String {
        if (task.state != DownloadState.DOWNLOADING) return ""
        val now = System.currentTimeMillis()
        val prev = lastSample[task.id]
        if (prev == null) {
            lastSample[task.id] = task.downloadedBytes to now
            return ""
        }
        val elapsed = now - prev.second
        if (elapsed < MIN_SAMPLE_INTERVAL_MS) return lastSpeed[task.id].orEmpty()

        lastSample[task.id] = task.downloadedBytes to now
        val deltaBytes = task.downloadedBytes - prev.first
        if (deltaBytes <= 0) return lastSpeed[task.id].orEmpty()
        val speed = formatSpeed(deltaBytes * 1000.0 / elapsed)
        lastSpeed[task.id] = speed
        return speed
    }

    private fun formatSize(downloaded: Long, total: Long): String {
        val d = formatBytes(downloaded)
        return if (total > 0) "$d / ${formatBytes(total)}" else d
    }

    /**
     * m3u8 的大小文本。总量不是源给的，而是「实测码率 × playlist 总时长」的**估算值**
     * （见 [DownloadProgress.estimateTotalBytes]），所以加 `~` 标注，避免被当成精确值。
     */
    private fun estimatedSize(task: DownloadTask): String {
        val downloaded = formatBytes(task.downloadedBytes)
        return if (task.totalBytes > 0) {
            "$downloaded / ~${formatBytes(task.totalBytes)}"
        } else {
            downloaded
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024 -> "%.2f GB".format(mb / 1024)
            else -> "%.1f MB".format(mb)
        }
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSec / 1024 / 1024)
            bytesPerSec >= 1024 -> "%.0f KB/s".format(bytesPerSec / 1024)
            else -> "%.0f B/s".format(bytesPerSec)
        }
    }

    private fun openFile(context: Context, task: DownloadTask) {
        val fp = task.filePath ?: return
        if (!fp.startsWith("content://")) {
            Toast.makeText(context, R.string.toast_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(fp), "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, R.string.toast_no_player, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        /** 同一任务两次速度采样之间的最小间隔 */
        const val MIN_SAMPLE_INTERVAL_MS = 300L
    }
}
