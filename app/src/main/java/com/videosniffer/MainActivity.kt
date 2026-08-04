package com.videosniffer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.videosniffer.download.DownloadManager
import com.videosniffer.ui.browser.BrowserFragment
import com.videosniffer.ui.download.DownloadListFragment
import com.videosniffer.ui.settings.SettingsFragment

class MainActivity : AppCompatActivity() {

    private var currentTag: String = TAG_BROWSER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadManager.init(this)
        requestNotificationPermission()
        setContentView(R.layout.activity_main)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_browser -> switchTab(TAG_BROWSER, ::BrowserFragment)
                R.id.nav_downloads -> switchTab(TAG_DOWNLOADS, ::DownloadListFragment)
                R.id.nav_settings -> switchTab(TAG_SETTINGS, ::SettingsFragment)
            }
            true
        }

        if (savedInstanceState == null) {
            switchTab(TAG_BROWSER, ::BrowserFragment)
            bottomNav.selectedItemId = R.id.nav_browser
        } else {
            currentTag = savedInstanceState.getString(STATE_CURRENT_TAG, TAG_BROWSER)
            // Fragment 由 FragmentManager 恢复，无需重复 add
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CURRENT_TAG, currentTag)
    }

    /** Android 13+ 请求通知权限（下载进度通知依赖） */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
            }
        }
    }

    /**
     * 切换底部 Tab：隐藏当前 Fragment，显示目标 Fragment。
     * 采用 hide/show 而非 replace，保证 WebView 页面状态不丢失。
     */
    private inline fun switchTab(tag: String, factory: () -> Fragment) {
        val fm = supportFragmentManager
        val ft = fm.beginTransaction()
        fm.findFragmentByTag(currentTag)?.let { ft.hide(it) }
        val target = fm.findFragmentByTag(tag) ?: factory().also {
            ft.add(R.id.fragment_container, it, tag)
        }
        if (target.isHidden) ft.show(target)
        ft.commit()
        currentTag = tag
    }

    companion object {
        private const val TAG_BROWSER = "browser"
        private const val TAG_DOWNLOADS = "downloads"
        private const val TAG_SETTINGS = "settings"
        private const val STATE_CURRENT_TAG = "current_tag"
        private const val REQ_NOTIFICATION = 100
    }
}
