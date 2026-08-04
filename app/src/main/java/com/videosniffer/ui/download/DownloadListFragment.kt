package com.videosniffer.ui.download

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.videosniffer.R
import com.videosniffer.download.DownloadManager

/**
 * 下载管理页：任务列表 + 手动刷新按钮。
 * 不再自动订阅进度更新，点「刷新」时读取当前任务快照。
 */
class DownloadListFragment : Fragment(R.layout.fragment_downloads) {

    private val adapter = DownloadAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rv_downloads)
        val empty = view.findViewById<TextView>(R.id.tv_empty)
        val btnRefresh = view.findViewById<Button>(R.id.btn_refresh)

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        fun refresh() {
            val tasks = DownloadManager.tasks.value
            adapter.submitList(tasks)
            empty.isVisible = tasks.isEmpty()
        }

        btnRefresh.setOnClickListener { refresh() }
        refresh() // 初始加载一次
    }
}
