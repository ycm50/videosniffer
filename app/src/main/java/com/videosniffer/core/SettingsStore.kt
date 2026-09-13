package com.videosniffer.core

import android.content.Context

/**
 * 下载设置。
 *
 * 采用 SharedPreferences 而非 DataStore：设置项少、读写同步且无需 Flow，
 * 引入 DataStore 只会多一套协程 API 与依赖，收益不成正比。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 全局线程数：1~32，默认 4 */
    var threadCount: Int
        get() = prefs.getInt(KEY_THREAD_COUNT, DEFAULT_THREAD_COUNT).coerceIn(MIN_THREADS, MAX_THREADS)
        set(value) = prefs.edit().putInt(KEY_THREAD_COUNT, value.coerceIn(MIN_THREADS, MAX_THREADS)).apply()

    /** 同时下载任务上限：1~5，默认 3。超出限制的任务进入排队。 */
    var maxConcurrentTasks: Int
        get() = prefs.getInt(KEY_MAX_TASKS, DEFAULT_MAX_TASKS).coerceIn(MIN_TASKS, MAX_TASKS)
        set(value) = prefs.edit().putInt(KEY_MAX_TASKS, value.coerceIn(MIN_TASKS, MAX_TASKS)).apply()

    /** 启动时浏览器打开的网站地址 */
    var initialPage: String
        get() = prefs.getString(KEY_INITIAL_PAGE, DEFAULT_INITIAL_PAGE) ?: DEFAULT_INITIAL_PAGE
        set(value) = prefs.edit().putString(KEY_INITIAL_PAGE, value).apply()

    /**
     * 电脑版网页：开启后用桌面 UA 与桌面视口加载页面。
     *
     * 只影响 [com.videosniffer.ui.browser.BrowserFragment] 里 WebView 的页面加载，
     * 不影响嗅探/下载请求的 UA（那些请求要的是一致性与防盗链通过率，与页面版本无关）。
     */
    var desktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_MODE, DEFAULT_DESKTOP_MODE)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP_MODE, value).apply()

    companion object {
        const val MIN_THREADS = 1
        const val MAX_THREADS = 32
        const val MIN_TASKS = 1
        const val MAX_TASKS = 5
        const val DEFAULT_INITIAL_PAGE = "https://hanime1.me"
        private const val PREFS_NAME = "hanime1_settings"
        private const val KEY_THREAD_COUNT = "thread_count"
        private const val KEY_MAX_TASKS = "max_concurrent_tasks"
        private const val KEY_INITIAL_PAGE = "initial_page"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val DEFAULT_THREAD_COUNT = 4
        private const val DEFAULT_MAX_TASKS = 3
        private const val DEFAULT_DESKTOP_MODE = false
    }
}
