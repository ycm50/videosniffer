package com.videosniffer.ui.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.videosniffer.R
import com.videosniffer.download.DownloadManager
import com.videosniffer.sniff.DetectedMedia
import com.videosniffer.sniff.Hanime1DownloadParser
import kotlinx.coroutines.launch

/**
 * 浏览器页：系统 WebView 加载 hanime1.me，并接入资源嗅探（下载页解析 + 请求级拦截）。
 * 嗅探到媒体资源时右下角浮动下载按钮出现，点击可选择清晰度。
 */
class BrowserFragment : Fragment(R.layout.fragment_browser) {

    private val viewModel: BrowserViewModel by viewModels()

    private var webView: WebView? = null
    private var addressBar: EditText? = null
    private var btnBack: ImageButton? = null
    private var btnForward: ImageButton? = null
    private var fabDownload: FloatingActionButton? = null
    private var currentUrl: String = HOME_URL
    private var currentTitle: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        addressBar = view.findViewById(R.id.address_bar)
        btnBack = view.findViewById(R.id.btn_back)
        btnForward = view.findViewById(R.id.btn_forward)
        fabDownload = view.findViewById(R.id.fab_download)
        val btnRefresh = view.findViewById<ImageButton>(R.id.btn_refresh)

        // 复用或新建 WebView
        val container = view.findViewById<FrameLayout>(R.id.web_container)
        val existing = webView
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            container.addView(existing)
        } else {
            webView = createWebView().also {
                container.addView(it)
                // 打开设置的初始网站（默认 hanime1.me）
                it.loadUrl(DownloadManager.settings().initialPage)
            }
        }

        btnBack?.setOnClickListener { webView?.goBack() }
        btnForward?.setOnClickListener { webView?.goForward() }
        btnRefresh?.setOnClickListener { webView?.reload() }
        addressBar?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigateTo(addressBar?.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        fabDownload?.setOnClickListener {
            val list = viewModel.mediaList.value
            if (list.isNotEmpty()) showQualityDialog(list)
        }

        // 嗅探结果 → 控制下载按钮显隐（StateFlow 用协程收集）
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.mediaList.collect { list ->
                fabDownload?.isVisible = list.isNotEmpty()
            }
        }

        // 点击下载页 → 直接弹出清晰度选择
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.downloadRequest.collect { list ->
                if (list.isNotEmpty()) showQualityDialog(list)
            }
        }

        updateNavButtons()
        handleBackPress()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(requireContext())
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString()
                // 点击 hanime1 下载页：不展示 WebView，直接解析并弹清晰度选择
                if (!url.isNullOrEmpty() && Hanime1DownloadParser.isDownloadUrl(url)) {
                    viewModel.onDownloadPageRequested(url, currentUrl, currentTitle)
                    return true
                }
                // 其余页内导航：留在当前 WebView 中打开
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                url?.let {
                    currentUrl = it
                    addressBar?.setText(it)
                }
                updateNavButtons()
                // 进入新页面：清空上一页的嗅探结果，待 onPageFinished 重新分析当前页
                viewModel.clear()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                url?.let { addressBar?.setText(it) }
                updateNavButtons()
                // Route D：hanime1 watch 页解析下载链接
                url?.let { viewModel.onPageFinished(it, currentTitle) }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                // Route A：请求级兜底嗅探（后台线程调用，仅 URL 快速筛选）
                val url = request?.url?.toString()
                if (!url.isNullOrEmpty() && request.method.equals("GET", true)) {
                    viewModel.onUrlObserved(url, currentUrl, currentTitle)
                }
                return null // 不拦截，交给 WebView 正常加载
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                updateNavButtons()
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                currentTitle = title
            }
        }
        return wv
    }

    private fun updateNavButtons() {
        btnBack?.isEnabled = webView?.canGoBack() == true
        btnForward?.isEnabled = webView?.canGoForward() == true
    }

    private fun navigateTo(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val url = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        }
        webView?.loadUrl(url)
    }

    private fun showQualityDialog(list: List<DetectedMedia>) {
        if (list.isEmpty()) return
        val items = list.map { media ->
            val q = media.quality ?: getString(R.string.download_original)
            val size = media.size?.let { formatSize(it) } ?: "?"
            "$q · $size · ${media.ext}"
        }.toTypedArray()
        var selected = list.indices.last
        list.indexOfFirst { it.quality == "1080p" }.takeIf { it >= 0 }?.let { selected = it }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_quality_title)
            .setSingleChoiceItems(items, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.dialog_start_download) { _, _ ->
                onDownloadSelected(list[selected])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onDownloadSelected(media: DetectedMedia) {
        // 入队下载任务（多线程 + 断点续传）
        DownloadManager.enqueue(media)
        Toast.makeText(
            requireContext(),
            "已加入下载：${media.quality ?: getString(R.string.download_original)}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024 -> "%.2f GB".format(mb / 1024)
            else -> "%.1f MB".format(mb)
        }
    }

    private fun handleBackPress() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView?.canGoBack() == true) {
                        webView?.goBack()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroyView() {
        // 摘除 WebView 以便跨配置变化复用，不销毁页面状态
        webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        super.onDestroyView()
    }

    companion object {
        private const val HOME_URL = "https://hanime1.me"
    }
}
