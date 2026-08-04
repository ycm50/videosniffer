package com.videosniffer.core

import android.content.Context

/**
 * 下载设置（SharedPreferences 持久化）。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 全局线程数：1~32，默认 4 */
    var threadCount: Int
        get() = prefs.getInt(KEY_THREAD_COUNT, DEFAULT_THREAD_COUNT).coerceIn(MIN_THREADS, MAX_THREADS)
        set(value) = prefs.edit().putInt(KEY_THREAD_COUNT, value.coerceIn(MIN_THREADS, MAX_THREADS)).apply()

    /** 同时下载任务上限 */
    var maxConcurrentTasks: Int
        get() = prefs.getInt(KEY_MAX_TASKS, DEFAULT_MAX_TASKS).coerceIn(1, 5)
        set(value) = prefs.edit().putInt(KEY_MAX_TASKS, value.coerceIn(1, 5)).apply()

    /** 启动时浏览器打开的网站地址 */
    var initialPage: String
        get() = prefs.getString(KEY_INITIAL_PAGE, DEFAULT_INITIAL_PAGE) ?: DEFAULT_INITIAL_PAGE
        set(value) = prefs.edit().putString(KEY_INITIAL_PAGE, value).apply()

    companion object {
        const val MIN_THREADS = 1
        const val MAX_THREADS = 32
        const val DEFAULT_INITIAL_PAGE = "https://hanime1.me"
        private const val PREFS_NAME = "hanime1_settings"
        private const val KEY_THREAD_COUNT = "thread_count"
        private const val KEY_MAX_TASKS = "max_concurrent_tasks"
        private const val KEY_INITIAL_PAGE = "initial_page"
        private const val DEFAULT_THREAD_COUNT = 4
        private const val DEFAULT_MAX_TASKS = 3
    }
}
