package com.videosniffer.sniff

/**
 * 嗅探到的媒体资源。
 *
 * @param url           媒体直链（mp4 / m3u8）
 * @param quality       清晰度（如 1080p），来自 hanime1 下载页解析
 * @param size          文件大小（字节），HEAD 请求获得
 * @param ext           扩展名/类型（mp4 / m3u8 / ...）
 * @param title         来源页面标题 / 下载文件名
 * @param sourcePageUrl 触发嗅探的页面 URL
 */
data class DetectedMedia(
    val url: String,
    val quality: String?,
    val size: Long?,
    val ext: String,
    val title: String?,
    val sourcePageUrl: String
)
