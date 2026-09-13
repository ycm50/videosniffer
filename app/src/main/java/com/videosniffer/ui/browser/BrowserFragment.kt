package com.videosniffer.ui.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
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
 * 浏览器页：系统 WebView 加载起始站点，并接入资源嗅探（页面解析 + 请求级拦截）。
 * 嗅探到媒体资源时右下角浮动下载按钮出现，点击可选择清晰度。
 *
 * 设置页改了「初始网站」或「电脑版网页」后，切回本 Tab 会由 [syncFromSettings] 立即生效。
 */
class BrowserFragment : Fragment(R.layout.fragment_browser) {

    private val viewModel: BrowserViewModel by viewModels()

    private var webView: WebView? = null
    private var addressBar: EditText? = null
    private var btnBack: ImageButton? = null
    private var btnForward: ImageButton? = null
    private var fabDownload: FloatingActionButton? = null
    private var currentUrl: String = ""
    private var currentTitle: String? = null

    /** 当前 WebView 实际加载的起始页；用于「设置改了初始网站」时自动切换 */
    private var loadedInitialPage: String? = null

    /** 当前 WebView 实际应用的「电脑版」开关值；null 表示尚未应用到任何 WebView */
    private var loadedDesktopMode: Boolean? = null

    /** WebView 的默认（移动版）UA，切回移动版时用它还原 */
    private var defaultUserAgent: String? = null

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
            webView = createWebView().also { container.addView(it) }
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

        // 本页候选全部因体积过小被过滤 → 明确告知，避免用户以为嗅探失效
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.filteredNotice.collect {
                Toast.makeText(
                    requireContext(),
                    R.string.toast_all_candidates_filtered,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        updateNavButtons()
        handleBackPress()
    }

    override fun onResume() {
        super.onResume()
        syncFromSettings()
        webView?.onResume()
    }

    /**
     * Tab 切换走的是 hide/show（不是 replace），所以设置同步有**两个**入口：
     * - [onResume]：正常回到前台的路径；
     * - [onHiddenChanged]：hide/show 一定会回调它，是「切回本 Tab」最可靠的信号
     *   （不依赖隐藏 Fragment 被移到哪个生命周期状态）。
     *
     * 两条路都调 [syncFromSettings]，靠 `loadedXxx` 字段保证幂等 —— 不会重复 reload。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) syncFromSettings()
    }

    /**
     * 把设置页可能改过的两项同步到 WebView：
     * - 初始网站变了 → 直接加载新站点；
     * - 电脑版开关变了 → 换 UA/视口并 reload（UA 只对之后的请求生效，不 reload 拿不到桌面版 HTML）。
     *
     * 两者都变时以「加载新站点」为准（loadUrl 本身就是一次新请求，无需再 reload）。
     */
    private fun syncFromSettings() {
        val wv = webView ?: return
        val settings = DownloadManager.settings()

        val desktop = settings.desktopMode
        val modeChanged = loadedDesktopMode != null && loadedDesktopMode != desktop
        if (modeChanged) {
            applyDesktopMode(wv, desktop)
            loadedDesktopMode = desktop
        }

        val configured = settings.initialPage
        when {
            loadedInitialPage != configured -> {
                loadedInitialPage = configured
                wv.loadUrl(configured)
            }
            modeChanged -> wv.reload()
        }
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
        // 先把默认 UA 记下来，再按设置覆盖 —— 切回移动版时要靠它还原来
        defaultUserAgent = wv.settings.userAgentString
        val desktop = DownloadManager.settings().desktopMode
        applyDesktopMode(wv, desktop)
        loadedDesktopMode = desktop
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
                // Route D：按站点解析媒体直链
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

    /**
     * 应用「电脑版网页」开关。
     *
     * 只动两处，但都是站点真正用来区分移动版/桌面版的信号：
     *
     * 1. `userAgentString` —— 绝大多数站点先看 UA 再决定发哪套 HTML。
     * 2. 视口标志 —— 桌面版页面通常**不写** `<meta name="viewport" content="width=device-width">`，
     *    于是 WebView 用默认的 980px 布局宽度排版；`useWideViewPort` 让这个宽度真正生效，
     *    `loadWithOverviewMode` 再把整页缩放到屏幕内，效果等同桌面浏览器缩小查看。
     *    移动版页有 viewport meta 时内容宽度就等于屏宽，这两个标志不会改变既有表现。
     *
     * 注意：UA 只影响**之后**发出的请求，已加载的页面必须 reload 才会换成桌面版 HTML
     * （调用方负责：见 [onResume]）。
     */
    private fun applyDesktopMode(wv: WebView, desktop: Boolean) {
        val ua = if (desktop) desktopUserAgent(requireContext()) else defaultUserAgent
        wv.settings.apply {
            // 传 null/空串会让 WebView 回到系统默认 UA —— 切回移动版时正好靠这个还原，
            // 万一拿不到默认 UA 也不会把桌面版 UA 永久留在 WebView 上
            userAgentString = ua?.takeIf { it.isNotBlank() }
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    }

    /**
     * 桌面版 UA：Windows Chrome。
     *
     * 刻意**不用**「把默认 UA 里的 `Mobile` 删掉」这种取巧做法 —— 结果里仍然带着
     * `Android` 与 `wv`，站点照样会判定为手机。这里换成真正的桌面 UA，但 Chrome
     * 主版本沿用本机 WebView 的真实版本，不谎报一个比内核还新的版本。
     */
    private fun desktopUserAgent(context: Context): String {
        val major = Regex("""Chrome/(\d+)""")
            .find(WebSettings.getDefaultUserAgent(context))
            ?.groupValues?.getOrNull(1)
            ?: FALLBACK_CHROME_MAJOR
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
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
            val name = media.title?.takeIf { it.isNotBlank() }
                ?: getString(R.string.unnamed_media)
            val q = media.quality ?: getString(R.string.download_original)
            val size = media.size?.let { formatSize(it) } ?: getString(R.string.size_unknown)
            getString(R.string.dialog_quality_item, name, q, size, media.ext)
        }.toTypedArray()
        // 默认选中清晰度最高的一条。旧实现写死「取列表最后一条，若是 1080p 则选它」，
        // 对「最清晰在最前」的列表（HLS 变体就是这样排的）会默认选中最低画质。
        var selected = DetectedMedia.bestIndex(list)

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
        // 入队下载任务（多线程 + 断点续传；超出并发上限会自动排队）
        DownloadManager.enqueue(media)
        val label = media.title?.takeIf { it.isNotBlank() }
            ?: media.quality
            ?: getString(R.string.download_original)
        Toast.makeText(
            requireContext(),
            getString(R.string.toast_enqueued, label),
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

    override fun onDestroyView() {
        // 摘除 WebView 以便跨配置变化复用，不销毁页面状态
        webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        super.onDestroyView()
    }

    private companion object {
        /** 取不到 WebView 真实 Chrome 版本号时的兜底主版本 */
        const val FALLBACK_CHROME_MAJOR = "131"
    }
}
