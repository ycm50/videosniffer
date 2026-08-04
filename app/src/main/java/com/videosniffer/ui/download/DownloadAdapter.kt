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
import com.videosniffer.download.DownloadState
import com.videosniffer.download.DownloadTask

class DownloadAdapter : RecyclerView.Adapter<DownloadAdapter.VH>() {

    private val items = mutableListOf<DownloadTask>()
    private val lastSample = HashMap<String, Pair<Long, Long>>() // id -> (bytes, timeMs)

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
        private val progressBar = itemView.findViewById<ProgressBar>(R.id.progress_bar)
        private val tvProgressPct = itemView.findViewById<TextView>(R.id.tv_progress_pct)
        private val btnPauseResume = itemView.findViewById<Button>(R.id.btn_pause_resume)
        private val btnCancel = itemView.findViewById<Button>(R.id.btn_cancel)
        private val btnRetry = itemView.findViewById<Button>(R.id.btn_retry)
        private val btnOpen = itemView.findViewById<Button>(R.id.btn_open)
        private val btnDelete = itemView.findViewById<Button>(R.id.btn_delete)

        fun bind(task: DownloadTask) {
            tvTitle.text = task.title

            tvStatus.text = statusText(task.state)
            val isActive = task.state in setOf(
                DownloadState.PENDING, DownloadState.PROBING,
                DownloadState.DOWNLOADING, DownloadState.EXPORTING
            )
            val isM3u8 = task.type.equals("m3u8", true)

            // 进度：mp4 按字节；m3u8 按分片数；总大小未知时用不确定进度动画
            val pct: Int
            val indeterminate: Boolean
            if (task.totalBytes > 0) {
                pct = ((task.downloadedBytes * 100L) / task.totalBytes).toInt().coerceIn(0, 100)
                indeterminate = false
            } else if (task.state == DownloadState.DOWNLOADING) {
                pct = 0
                indeterminate = true
            } else {
                pct = 0
                indeterminate = false
            }
            progressBar.isIndeterminate = indeterminate
            progressBar.progress = pct
            progressBar.isVisible = task.state != DownloadState.COMPLETED

            tvProgressPct.isVisible = !indeterminate && task.state != DownloadState.COMPLETED
            tvProgressPct.text = itemView.context.getString(R.string.progress_pct, pct)

            // 描述文本（含实时速度与并发连接数）
            val quality = task.quality ?: task.type
            val speed = if (isM3u8) "" else speedFor(task)
            val speedSuffix = speed.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""
            val conn = if (task.state == DownloadState.DOWNLOADING && task.activeConnections > 0) {
                itemView.context.getString(R.string.format_connections, task.activeConnections)
            } else ""
            val connSuffix = conn.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""
            val baseMeta = if (isM3u8) {
                itemView.context.getString(
                    R.string.meta_m3u8, quality, task.downloadedBytes, task.totalBytes, speedSuffix
                )
            } else {
                "$quality · ${formatSize(task.downloadedBytes, task.totalBytes)}$speedSuffix"
            }
            tvMeta.text = "$baseMeta$connSuffix"

            // 暂停/继续
            btnPauseResume.isVisible = isActive || task.state == DownloadState.PAUSED
            btnPauseResume.text = itemView.context.getString(
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

    private fun statusText(state: DownloadState): String {
        return when (state) {
            DownloadState.PENDING -> "等待中"
            DownloadState.PROBING -> "探测中"
            DownloadState.DOWNLOADING -> "下载中"
            DownloadState.EXPORTING -> "导出中"
            DownloadState.PAUSED -> "已暂停"
            DownloadState.COMPLETED -> "已完成"
            DownloadState.FAILED -> "失败"
            DownloadState.CANCELED -> "已取消"
        }
    }

    private fun speedFor(task: DownloadTask): String {
        if (task.state != DownloadState.DOWNLOADING) return ""
        val now = System.currentTimeMillis()
        val prev = lastSample[task.id]
        lastSample[task.id] = task.downloadedBytes to now
        if (prev == null || now <= prev.second) return "0 KB/s"
        val deltaBytes = task.downloadedBytes - prev.first
        if (deltaBytes <= 0) return "0 KB/s"
        return formatSpeed(deltaBytes * 1000.0 / (now - prev.second))
    }

    private fun formatSize(downloaded: Long, total: Long): String {
        val d = formatBytes(downloaded)
        return if (total > 0) "$d / ${formatBytes(total)}" else d
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
            Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(fp), "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "无可用播放器", Toast.LENGTH_SHORT).show() }
    }
}
