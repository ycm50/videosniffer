package com.videosniffer.sniff

/**
 * 嗅探到的媒体资源。
 *
 * @param url           媒体直链（mp4 / m3u8）
 * @param quality       清晰度（如 1080p）：
 *                      hanime1 下载页从文件名取；YouTube 从 itag/qualityLabel 取；
 *                      **HLS 从 master playlist 的 `RESOLUTION` 取**；都取不到时按 URL 路径兜底；
 *                      仍判不出为 null（对话框里显示「原画」）
 * @param size          文件大小（字节），HEAD 请求获得；m3u8 通常为 null
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
) {
    companion object {

        /**
         * 清晰度标签里的高度：`1080p` → 1080、`2160p60` → 2160、`720p（无音轨）` → 720。
         * 判不出返回 0。纯函数（不依赖 Android 框架），便于单测。
         */
        private val heightRegex =
            Regex("""(?<![0-9])(\d{3,4})p(?:\d{1,3})?(?![0-9a-zA-Z])""", RegexOption.IGNORE_CASE)

        /** 清晰度标签 → 高度像素；判不出返回 0。纯函数。 */
        fun heightOfQuality(quality: String?): Int =
            quality?.let { heightRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

        /**
         * 清晰度选择对话框的默认选中项：**清晰度最高**的一条（高度最大，同高度取靠后者）。
         * 所有候选都判不出清晰度时退回最后一条，与旧行为一致。
         *
         * 旧实现写死「取最后一条，若存在 1080p 则选它」，一旦列表是「最清晰在最前」
         * （HLS 变体展开后就是这个顺序），就会默认选中最低画质。
         *
         * @return 列表为空时返回 -1
         */
        fun bestIndex(list: List<DetectedMedia>): Int {
            if (list.isEmpty()) return -1
            var best = -1
            var bestHeight = 0
            list.forEachIndexed { index, media ->
                val height = heightOfQuality(media.quality)
                // >=：同高度取靠后的一条，贴合「同清晰度里后出现的更优」的既有习惯
                if (height > 0 && height >= bestHeight) {
                    bestHeight = height
                    best = index
                }
            }
            return if (best >= 0) best else list.size - 1
        }
    }
}
