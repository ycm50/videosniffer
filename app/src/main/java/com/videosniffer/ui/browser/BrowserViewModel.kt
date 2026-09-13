package com.videosniffer.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videosniffer.sniff.DetectedMedia
import com.videosniffer.sniff.Hanime1DownloadParser
import com.videosniffer.sniff.MediaUrlDetector
import com.videosniffer.sniff.PageParser
import com.videosniffer.sniff.PornhubParser
import com.videosniffer.sniff.YouTubeParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 浏览器页 ViewModel：
 * 汇总嗅探到的媒体资源（Route D 页面解析 + Route A 请求级检测），
 * 以 StateFlow 暴露给 UI 控制下载按钮显隐与清晰度选择。
 *
 * 去重与限流：
 * - `observedUrls` 保证同一 URL 只做一次 HEAD 校验；
 * - `probeSemaphore` 给并发校验设上限，避免页面里大量 .mp4 资源把请求打爆；
 * - Route A 的候选 URL 先攒 600ms 再批量校验，避开页面加载期的请求风暴。
 */
class BrowserViewModel : ViewModel() {

    private val _mediaList = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val mediaList = _mediaList.asStateFlow()

    /** 一次性事件：请求弹出清晰度选择对话框 */
    private val _downloadRequest = MutableSharedFlow<List<DetectedMedia>>(extraBufferCapacity = 1)
    val downloadRequest = _downloadRequest.asSharedFlow()

    /** 一次性事件：本页候选全部因过小被过滤（提示用户，避免「什么都没发生」） */
    private val _filteredNotice = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val filteredNotice = _filteredNotice.asSharedFlow()

    /** Route D 解析器。hanime1 放最后作为兜底（其 matches 已限定站点，不会误伤）。 */
    private val parsers: List<PageParser> = listOf(YouTubeParser, PornhubParser, Hanime1DownloadParser)

    /** 已提交过 HEAD 校验的 URL（含成功与失败），避免重复请求 */
    private val observedUrls: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** 已加入列表的 URL */
    private val acceptedUrls: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Route A 校验并发上限 */
    private val probeSemaphore = Semaphore(MAX_CONCURRENT_PROBES)

    /** 本页已发起的 HEAD 校验数（配额用尽后不再新增） */
    private val probesLaunched = AtomicInteger(0)

    /** 本页因体积过小被丢弃的候选数 */
    private val rejectedThisPage = AtomicInteger(0)

    /** 本页是否至少产出了一个可用候选 */
    @Volatile
    private var pageAccepted = false

    private val pendingUrls = mutableListOf<String>()
    private var flushJob: Job? = null

    /**
     * 页面加载完成回调（主线程）。
     * 按站点分发到对应解析器，取回媒体直链（Route D）。
     */
    fun onPageFinished(url: String, title: String?) {
        // 新页面：重置 Route A 的单页校验配额。
        // 必须放在这里而不只是 onPageStarted —— SPA（history.pushState）与同文档跳转
        // 不会触发 onPageStarted，配额若只在导航时重置会永久耗尽，之后再也嗅不到资源。
        probesLaunched.set(0)
        rejectedThisPage.set(0)
        pageAccepted = false
        val parser = parsers.firstOrNull { it.matches(url) } ?: return
        viewModelScope.launch {
            val parsed = parser.parse(url, title)
            if (parsed.isNotEmpty()) {
                addMedia(parsed)
                notifyIfAllFiltered()
            }
        }
    }

    /**
     * 用户点击了下载页链接（hanime1.me/download?v=xxx）。
     * 不展示下载页，直接解析并触发清晰度选择对话框。
     */
    fun onDownloadPageRequested(url: String, referer: String, title: String?) {
        viewModelScope.launch {
            val parsed = Hanime1DownloadParser.parseDownloadPage(url, referer, title)
            if (parsed.isNotEmpty()) {
                addMedia(parsed)
                _downloadRequest.emit(parsed)
            }
        }
    }

    /**
     * WebView 请求级 URL 回调（在 WebView 的请求线程上被高频调用）。
     *
     * 这里只做「廉价字符串预筛 + 去重入队」，**不做 Uri 解析**：精确判定与 HEAD 校验
     * 都推迟到 flush 协程，避免占用 WebView 请求线程拖慢页面资源加载。
     * 预筛是有必要的 —— 否则每张图/每个 CSS 的 URL 都要进一次同步集合，开销反而更大。
     */
    fun onUrlObserved(url: String, sourcePageUrl: String, title: String?) {
        if (!looksLikeMedia(url)) return
        // YouTube 播放器会带 range= 分块拉同一个流；先归一化成整片地址，
        // 否则同一个视频的不同片段会被当成多个不同资源（还会各自发一次 HEAD）
        val normalized = MediaUrlDetector.normalizeForDownload(url)
        val enqueued = synchronized(observedUrls) {
            when {
                observedUrls.size >= MAX_OBSERVED_URLS -> false
                observedUrls.add(normalized) -> {
                    pendingUrls.add(normalized)
                    true
                }
                else -> false
            }
        }
        if (enqueued) scheduleFlush(sourcePageUrl, title)
    }

    fun clear() {
        _mediaList.value = emptyList()
        synchronized(observedUrls) {
            observedUrls.clear()
            acceptedUrls.clear()
            pendingUrls.clear()
        }
        flushJob?.cancel()
        flushJob = null
        probesLaunched.set(0)
        rejectedThisPage.set(0)
        pageAccepted = false
    }

    // ---------- 内部 ----------

    /**
     * 攒一小段时间再统一校验，避免页面加载期的请求风暴。
     *
     * 采用「循环排空」而非「一次性 drain」：flush 期间新入队的 URL 会在下一轮被处理，
     * 不存在因协程已完成而漏掉尾批的竞态。
     */
    private fun scheduleFlush(sourcePageUrl: String, title: String?) {
        if (flushJob?.isActive == true) return
        flushJob = viewModelScope.launch {
            while (true) {
                delay(FLUSH_DELAY_MS)
                val batch = synchronized(observedUrls) {
                    if (pendingUrls.isEmpty()) {
                        null
                    } else {
                        val copy = pendingUrls.toList()
                        pendingUrls.clear()
                        copy
                    }
                } ?: break

                batch.forEach { url ->
                    // 在协程里做纯判定，快速筛掉非视频资源，避免无谓的 HEAD 请求
                    if (MediaUrlDetector.isVideoCandidate(url)) {
                        launchProbe(url, sourcePageUrl, title)
                    }
                }
            }
        }
    }

    private fun launchProbe(url: String, sourcePageUrl: String, title: String?) {
        // 单页配额：HLS/DASH 播放会产生成百上千个分片 URL，逐个 HEAD 等于对站点做请求放大。
        // 配额在每次页面加载完成（onPageFinished）与 clear() 时重置。
        if (probesLaunched.get() >= MAX_PROBES_PER_PAGE) return
        probesLaunched.incrementAndGet()
        viewModelScope.launch {
            probeSemaphore.withPermit {
                doProbe(url, sourcePageUrl, title)
            }
        }
    }

    /** 注意是 suspend：内部调用的 MediaUrlDetector.detectAll 会发起 HEAD/GET 请求 */
    private suspend fun doProbe(url: String, sourcePageUrl: String, title: String?) {
        // 可能不止一条：HLS master playlist 会展开成多个清晰度候选（1080p/720p/480p…）
        val media = MediaUrlDetector.detectAll(url, sourcePageUrl, title)
        if (media.isEmpty()) return
        addMedia(media)
        notifyIfAllFiltered()
    }

    /** 廉价预筛：只做大小写归一与子串匹配，不解析 URL。 */
    private fun looksLikeMedia(url: String): Boolean {
        val lower = url.lowercase()
        return MEDIA_HINTS.any { lower.contains(it) }
    }

    /**
     * 过滤 + 按 URL 去重合并，新资源追加。
     *
     * [_mediaList] 会被多个 probe 协程并发写入，因此整体加锁，保证「过滤-合并-追加」
     * 三步不会互相覆盖（读改写而非 `update{}`：CAS 重试会让去重与变更判定被重复执行）。
     *
     * 同一 URL 已经存在时**只升级信息**（补上清晰度/大小/标题），不重复追加。
     * 这个分支不是理论情况：播放器通常先请求单码率 playlist / 分片、再请求 master，
     * 两者会落在同一批 probe 里并发校验 —— 谁先完成不确定。
     * 若不做升级，master 展开出来的「1080p/720p/480p」里会有一条退化成「原画」。
     */
    private fun addMedia(incoming: List<DetectedMedia>) {
        val fresh = incoming.filter { it.isUsable() }
        val rejected = incoming.size - fresh.size
        if (rejected > 0) rejectedThisPage.addAndGet(rejected)
        if (fresh.isEmpty()) return

        synchronized(observedUrls) {
            val merged = _mediaList.value.toMutableList()
            var changed = false

            fresh.forEach { media ->
                val index = merged.indexOfFirst { it.url == media.url }
                if (index < 0) {
                    acceptedUrls.add(media.url)
                    merged.add(media)
                    changed = true
                } else {
                    // 不用 null 覆盖已有信息（例如先到的那条没有清晰度）
                    val old = merged[index]
                    val upgraded = old.copy(
                        quality = old.quality ?: media.quality,
                        size = old.size ?: media.size,
                        title = old.title ?: media.title
                    )
                    if (upgraded != old) {
                        merged[index] = upgraded
                        changed = true
                    }
                }
            }
            if (!changed) return

            _mediaList.value = merged
            pageAccepted = true
        }
    }

    /**
     * 本页所有候选都被体积阈值过滤掉时提示一次。
     * 只在「已经有候选被拒」且「至今没有任何可用候选」时发事件 —— 避免正片与
     * 广告同页时也弹提示。
     */
    private fun notifyIfAllFiltered() {
        if (rejectedThisPage.get() > 0 && !pageAccepted) {
            _filteredNotice.tryEmit(Unit)
        }
    }

    /**
     * 是否为「可用」的下载候选：已知大小小于 [MIN_MEDIA_BYTES] 的丢弃。
     *
     * 站点在下载页/播放页会插入广告与预告的短 mp4（0.1~0.7 MB），
     * 它们在清晰度列表里和正片混在一起极易误选。
     * 大小未知（无 Content-Length）时保留 —— 宁可多留一个，也不误杀正片。
     */
    private fun DetectedMedia.isUsable(): Boolean =
        size == null || size >= MIN_MEDIA_BYTES

    private companion object {
        const val MAX_CONCURRENT_PROBES = 4
        const val MAX_OBSERVED_URLS = 300

        /** 单页最多发起多少次 HEAD 校验，防请求放大 */
        const val MAX_PROBES_PER_PAGE = 60
        const val FLUSH_DELAY_MS = 600L

        /** 小于该大小的候选直接丢弃（广告/预告短 mp4） */
        const val MIN_MEDIA_BYTES = 1024L * 1024L

        /** 廉价预筛关键词：覆盖各站点常见的媒体 URL 形态 */
        val MEDIA_HINTS = listOf("m3u8", ".mp4", ".ts", "videoplayback", ".flv", ".webm", ".m4s")
    }
}
