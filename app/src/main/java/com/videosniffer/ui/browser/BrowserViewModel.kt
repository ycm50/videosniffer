package com.videosniffer.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videosniffer.sniff.DetectedMedia
import com.videosniffer.sniff.Hanime1DownloadParser
import com.videosniffer.sniff.MediaUrlDetector
import com.videosniffer.sniff.PornhubParser
import com.videosniffer.sniff.YouTubeParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 浏览器页 ViewModel：
 * 汇总嗅探到的媒体资源（Route D 下载页解析 + Route A 请求级检测），
 * 以 StateFlow 暴露给 UI 控制下载按钮显隐与清晰度选择。
 */
class BrowserViewModel : ViewModel() {

    private val _mediaList = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val mediaList = _mediaList.asStateFlow()

    /** 一次性事件：请求弹出清晰度选择对话框 */
    private val _downloadRequest = MutableSharedFlow<List<DetectedMedia>>(extraBufferCapacity = 1)
    val downloadRequest = _downloadRequest.asSharedFlow()

    /**
     * 页面加载完成回调（主线程）。
     * 按站点分发到对应解析器，取回媒体直链（Route D）。
     */
    fun onPageFinished(url: String, title: String?) {
        val parser = when {
            Hanime1DownloadParser.isWatchUrl(url) -> null
            YouTubeParser.matches(url) -> YouTubeParser
            PornhubParser.matches(url) -> PornhubParser
            else -> return
        }
        viewModelScope.launch {
            val parsed = if (parser != null) {
                parser.parse(url, title)
            } else {
                Hanime1DownloadParser.parseWatchUrl(url, title)
            }
            if (parsed.isNotEmpty()) {
                _mediaList.value = merge(_mediaList.value, parsed)
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
                _mediaList.value = merge(_mediaList.value, parsed)
                _downloadRequest.emit(parsed)
            }
        }
    }

    /**
     * WebView 请求级 URL 回调（后台线程）。
     * 扩展名快速命中后，HEAD 校验 Content-Type；仅作为下载页解析失败时的兜底。
     */
    fun onUrlObserved(url: String, sourcePageUrl: String, title: String?) {
        if (!MediaUrlDetector.isVideoCandidate(url)) return
        viewModelScope.launch {
            val media = MediaUrlDetector.detect(url, sourcePageUrl, title) ?: return@launch
            _mediaList.value = merge(_mediaList.value, listOf(media))
        }
    }

    fun clear() {
        _mediaList.value = emptyList()
    }

    /** 按 URL 去重合并，新资源追加 */
    private fun merge(current: List<DetectedMedia>, incoming: List<DetectedMedia>): List<DetectedMedia> {
        val existingUrls = current.mapTo(mutableSetOf()) { it.url }
        val merged = current.toMutableList()
        incoming.forEach {
            if (existingUrls.add(it.url)) merged.add(it)
        }
        return merged
    }
}
