package com.videosniffer.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.videosniffer.R
import com.videosniffer.core.SettingsStore
import com.videosniffer.download.DownloadManager

/**
 * 设置页：线程数、同时下载任务数、初始网站。
 * 修改仅在界面预览，点击「保存」才写入设置。
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val settings = DownloadManager.settings()
        val seekThread = view.findViewById<SeekBar>(R.id.seek_thread)
        val tvThread = view.findViewById<TextView>(R.id.tv_thread_value)
        val seekTasks = view.findViewById<SeekBar>(R.id.seek_tasks)
        val tvTasks = view.findViewById<TextView>(R.id.tv_tasks_value)
        val etInitialPage = view.findViewById<EditText>(R.id.et_initial_page)
        val btnSave = view.findViewById<Button>(R.id.btn_save_settings)

        // 初始化显示当前已保存的值
        seekThread.max = SettingsStore.MAX_THREADS - 1
        seekThread.progress = settings.threadCount - 1
        tvThread.text = settings.threadCount.toString()
        seekTasks.progress = settings.maxConcurrentTasks - 1
        tvTasks.text = settings.maxConcurrentTasks.toString()
        etInitialPage.setText(settings.initialPage)

        // 修改只更新预览，不立即写入
        seekThread.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvThread.text = (progress + 1).toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekTasks.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTasks.text = (progress + 1).toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnSave.setOnClickListener {
            settings.threadCount = seekThread.progress + 1
            settings.maxConcurrentTasks = seekTasks.progress + 1
            val url = etInitialPage.text?.toString()?.trim().orEmpty()
            if (url.isNotEmpty()) {
                settings.initialPage = url
            }
            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
    }
}
