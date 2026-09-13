package com.videosniffer.ui.download

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.videosniffer.R
import com.videosniffer.download.DownloadManager
import com.videosniffer.download.DownloadTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 下载管理页：**双通道**刷新。
 *
 * 1. 订阅 [DownloadManager.tasks]（事件驱动）：状态迁移（排队→下载→导出→完成）即时反映；
 * 2. 每 [AUTO_REFRESH_MS] 轮询一次快照（定时驱动）：进度、速度、连接数持续滚动。
 *
 * 为什么两条都留：主修复是让 `DownloadManager` 发布**不可变快照**
 * （此前把已发布的同一实例放回列表，StateFlow 的相等性判重令它永不发通知，
 * 见 `DownloadManager.updateTask` 注释）。状态流修好后通道 1 已足够，
 * 通道 2 是刻意保留的兜底：它直读 `tasks.value`，不依赖任何推送，
 * 万一将来又有哪个环节漏发通知，界面最多滞后 500ms 而不是彻底静止。
 */
class DownloadListFragment : Fragment(R.layout.fragment_downloads) {

    private val adapter = DownloadAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rv_downloads)
        val empty = view.findViewById<TextView>(R.id.tv_empty)
        val btnRefresh = view.findViewById<Button>(R.id.btn_refresh)
        val btnClearFinished = view.findViewById<Button>(R.id.btn_clear_finished)

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        // 手动刷新：立即同步一次并重置轮询计时，用于「从后台回来」的即时反馈
        btnRefresh.setOnClickListener { render(DownloadManager.tasks.value, empty) }

        btnClearFinished.setOnClickListener { DownloadManager.clearFinishedTasks() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // 通道 1：事件驱动（立即）
                launch {
                    DownloadManager.tasks.collect { tasks -> render(tasks, empty) }
                }

                // 通道 2：定时驱动（每 500ms）
                //
                // 刻意**不加**「只在有活跃任务时刷新」的优化：暂停、完成、失败正是
                // 「活跃任务数 = 0」的时刻，加了这个条件就会恰好在该刷新的瞬间跳过重绘
                // （暂停后按钮停在「暂停」不变就是这个原因）。这里的重绘只涉及
                // 可见的几个 ViewHolder，代价远小于一个会漏刷新的判断条件。
                launch {
                    while (isActive) {
                        render(DownloadManager.tasks.value, empty)
                        delay(AUTO_REFRESH_MS)
                    }
                }
            }
        }
    }

    private fun render(tasks: List<DownloadTask>, empty: TextView) {
        adapter.submitList(tasks)
        empty.isVisible = tasks.isEmpty()
    }

    private companion object {
        /** 自动刷新间隔 */
        const val AUTO_REFRESH_MS = 500L
    }
}
