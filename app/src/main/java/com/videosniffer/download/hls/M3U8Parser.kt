package com.videosniffer.download.hls

/**
 * HLS 解密信息（来自 `#EXT-X-KEY`）。
 *
 * @param method  `NONE` / `AES-128` / `SAMPLE-AES`
 * @param keyUri  密钥地址（`METHOD=NONE` 时为 null）
 * @param iv      显式 IV（hex，可含 `0x` 前缀）；为 null 时由媒体序号推导
 */
data class HlsKey(
    val method: String,
    val keyUri: String?,
    val iv: String?
) {
    val isEncrypted: Boolean get() = !method.equals("NONE", true)

    companion object {
        val NONE = HlsKey("NONE", null, null)
    }
}

/** `#EXT-X-MAP`：fMP4 分片所需的初始化段（init segment）。 */
data class HlsMap(val uri: String, val byteRange: ByteRange?)

/** `#EXT-X-BYTERANGE`：分片在某资源内的字节区间（length 必填，offset 缺省接续上一个）。 */
data class ByteRange(val length: Long, val offset: Long)

/** 一个媒体分片。 */
data class HlsSegment(
    val uri: String,
    val durationSec: Double,
    val key: HlsKey,
    val map: HlsMap?,
    val byteRange: ByteRange?
)

/** master playlist 中的一个码率变体。 */
data class HlsVariant(
    val uri: String,
    val bandwidth: Int,
    val resolution: String?
)

/**
 * 解析结果：要么是 master playlist（含变体列表），要么是 media playlist（含分片列表）。
 */
sealed class HlsPlaylist {
    data class Master(val variants: List<HlsVariant>) : HlsPlaylist()
    data class Media(
        val segments: List<HlsSegment>,
        val targetDuration: Int,
        /** 首个分片的媒体序号，用于无显式 IV 时推导 AES IV */
        val mediaSequence: Long,
        /** 含 `#EXT-X-ENDLIST` 则为点播（VOD）；否则可能是直播 */
        val hasEndList: Boolean
    ) : HlsPlaylist()
}

/**
 * m3u8 文本解析（纯函数，无 IO，便于单元测试）。
 *
 * 支持：master playlist（`#EXT-X-STREAM-INF`）、`#EXT-X-KEY`（加密继承）、
 * `#EXT-X-MAP`（fMP4 初始化段）、`#EXT-X-BYTERANGE`、`#EXT-X-MEDIA-SEQUENCE`、
 * `#EXT-X-TARGETDURATION`、`#EXT-X-ENDLIST`。
 *
 * 相对 URI 依据 baseUrl 解析；baseUrl 为 null 或非法时原样保留。
 */
object M3U8Parser {

    private const val TAG_STREAM_INF = "#EXT-X-STREAM-INF"
    private const val TAG_KEY = "#EXT-X-KEY"
    private const val TAG_MAP = "#EXT-X-MAP"
    private const val TAG_BYTERANGE = "#EXT-X-BYTERANGE"
    private const val TAG_MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE"
    private const val TAG_TARGET_DURATION = "#EXT-X-TARGETDURATION"
    private const val TAG_ENDLIST = "#EXT-X-ENDLIST"
    private const val TAG_INF = "#EXTINF"

    fun parse(text: String, baseUrl: String?): HlsPlaylist? {
        val lines = text.lineSequence()
            .map { it.trim().removeSuffix("\r") }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return null
        // 容错：真实源偶尔缺 BOM/首行标记，只要有已知标签就继续解析
        if (lines.none { it.startsWith("#EXTM3U") || it.startsWith("#EXT") }) return null

        return if (lines.any { it.startsWith(TAG_STREAM_INF) }) {
            parseMaster(lines, baseUrl)
        } else {
            parseMedia(lines, baseUrl)
        }
    }

    // ---------- master ----------

    private fun parseMaster(lines: List<String>, baseUrl: String?): HlsPlaylist? {
        val variants = mutableListOf<HlsVariant>()
        lines.forEachIndexed { index, line ->
            if (!line.startsWith(TAG_STREAM_INF)) return@forEachIndexed
            val attrs = parseAttributeList(line.substringAfter(':', ""))
            val uri = lines.getOrNull(index + 1)?.takeIf { !it.startsWith("#") }
                ?: return@forEachIndexed
            variants.add(
                HlsVariant(
                    uri = resolve(baseUrl, uri),
                    bandwidth = attrs["BANDWIDTH"]?.toIntOrNull() ?: 0,
                    resolution = attrs["RESOLUTION"]
                )
            )
        }
        return if (variants.isEmpty()) null else HlsPlaylist.Master(variants)
    }

    // ---------- media ----------

    private fun parseMedia(lines: List<String>, baseUrl: String?): HlsPlaylist? {
        val segments = mutableListOf<HlsSegment>()
        var currentKey = HlsKey.NONE
        var currentMap: HlsMap? = null
        var pendingDuration = 0.0
        var pendingByteRange: ByteRange? = null
        var targetDuration = 0
        var mediaSequence = 0L
        var hasEndList = false
        // BYTERANGE 的 offset 缺省时接续「上一个分片在该资源中的结束位置」
        var lastByteRangeEnd = 0L

        lines.forEach { line ->
            when {
                line.startsWith(TAG_INF) -> {
                    // #EXTINF:<duration>,<title>
                    pendingDuration = line.substringAfter(':', "")
                        .substringBefore(',')
                        .trim()
                        .toDoubleOrNull() ?: 0.0
                }

                line.startsWith(TAG_KEY) -> {
                    val attrs = parseAttributeList(line.substringAfter(':', ""))
                    val method = attrs["METHOD"] ?: "NONE"
                    currentKey = if (method.equals("NONE", true)) {
                        HlsKey.NONE
                    } else {
                        HlsKey(
                            method = method,
                            keyUri = attrs["URI"]?.let { resolve(baseUrl, it) },
                            iv = attrs["IV"]
                        )
                    }
                }

                line.startsWith(TAG_MAP) -> {
                    val attrs = parseAttributeList(line.substringAfter(':', ""))
                    val uri = attrs["URI"]
                    if (!uri.isNullOrBlank()) {
                        // 显式取 Pair 的 first，避免 a?.let { f() }?.first 的优先级歧义
                        val mapRange = attrs["BYTERANGE"]?.let { spec ->
                            parseByteRange(spec, 0L)?.first
                        }
                        currentMap = HlsMap(uri = resolve(baseUrl, uri), byteRange = mapRange)
                    }
                }

                line.startsWith(TAG_BYTERANGE) -> {
                    val spec = line.substringAfter(':', "").trim()
                    val parsed = parseByteRange(spec, lastByteRangeEnd)
                    if (parsed != null) {
                        pendingByteRange = parsed.first
                        lastByteRangeEnd = parsed.second
                    }
                }

                line.startsWith(TAG_MEDIA_SEQUENCE) ->
                    mediaSequence = line.substringAfter(':', "").trim().toLongOrNull() ?: 0L

                line.startsWith(TAG_TARGET_DURATION) ->
                    targetDuration = line.substringAfter(':', "").trim().toIntOrNull() ?: 0

                line.startsWith(TAG_ENDLIST) -> hasEndList = true

                line.startsWith("#") -> Unit // 其余标签忽略（含 #EXT-X-DISCONTINUITY 等）

                else -> {
                    segments.add(
                        HlsSegment(
                            uri = resolve(baseUrl, line),
                            durationSec = pendingDuration,
                            key = currentKey,
                            map = currentMap,
                            byteRange = pendingByteRange
                        )
                    )
                    pendingDuration = 0.0
                    pendingByteRange = null
                }
            }
        }

        return if (segments.isEmpty()) null
        else HlsPlaylist.Media(segments, targetDuration, mediaSequence, hasEndList)
    }

    // ---------- helpers ----------

    /**
     * 解析 `#EXT-X-BYTERANGE` / `#EXT-X-MAP:BYTERANGE` 的取值。
     * 形如 `"12345"`（缺 offset，接续 prevEnd）或 `"12345@6789"`。
     *
     * @return (区间, 该区间结束后的绝对偏移) —— 供后续缺省 offset 接续
     */
    fun parseByteRange(spec: String, previousEnd: Long): Pair<ByteRange, Long>? {
        val cleaned = spec.trim().trim('"')
        if (cleaned.isEmpty()) return null
        val lengthPart = cleaned.substringBefore('@').trim()
        val length = lengthPart.toLongOrNull() ?: return null
        if (length <= 0) return null
        val offset = cleaned.substringAfter('@', "").trim().toLongOrNull() ?: previousEnd
        return ByteRange(length, offset) to (offset + length)
    }

    /** 解析 `KEY=VALUE,KEY="VALUE"` 形式的属性列表，值内的逗号不参与切分。 */
    fun parseAttributeList(input: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val length = input.length
        var i = 0
        while (i < length) {
            // 跳过分隔符与空白
            while (i < length && (input[i] == ',' || input[i].isWhitespace())) i++
            if (i >= length) break

            val keyStart = i
            while (i < length && input[i] != '=' && input[i] != ',') i++
            if (i >= length || input[i] != '=') {
                // 没有 '='：跳过这一段
                while (i < length && input[i] != ',') i++
                continue
            }
            val key = input.substring(keyStart, i).trim()
            i++ // 跳过 '='

            val value = if (i < length && input[i] == '"') {
                i++
                val sb = StringBuilder()
                while (i < length && input[i] != '"') {
                    sb.append(input[i])
                    i++
                }
                if (i < length) i++ // 跳过收尾引号
                sb.toString()
            } else {
                val valueStart = i
                while (i < length && input[i] != ',') i++
                input.substring(valueStart, i).trim()
            }
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }

    /**
     * 相对 URI 解析。不做完整 RFC 3986 运算，而是覆盖 HLS 实际出现的三种形态：
     * 绝对 URL、根路径 `/x/y.ts`、同目录相对路径 `../a/b.ts` 与 `seg1.ts`。
     */
    fun resolve(baseUrl: String?, uri: String): String {
        val ref = uri.trim()
        if (ref.isEmpty()) return ref
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) return ref
        if (baseUrl.isNullOrBlank()) return ref

        val schemeEnd = baseUrl.indexOf("://")
        if (schemeEnd <= 0) return ref
        val scheme = baseUrl.substring(0, schemeEnd)
        val authorityStart = schemeEnd + 3
        val authorityEnd = baseUrl.indexOf('/', authorityStart).let { if (it < 0) baseUrl.length else it }
        val authority = baseUrl.substring(authorityStart, authorityEnd)

        if (ref.startsWith("//")) return "$scheme:$ref"
        if (ref.startsWith("/")) return "$scheme://$authority$ref"

        val rawPath = if (authorityEnd < baseUrl.length) baseUrl.substring(authorityEnd) else "/"
        val basePath = rawPath.substringBefore('?').substringBefore('#')
        val dir = basePath.substringBeforeLast('/', "")
        val merged = if (dir.isEmpty()) "/$ref" else "$dir/$ref"
        return "$scheme://$authority${normalizePath(merged)}"
    }

    /** 消解路径中的 `.` 与 `..` 段（保留查询串原样）。 */
    private fun normalizePath(path: String): String {
        val queryIdx = path.indexOfFirst { it == '?' || it == '#' }
        val purePath = if (queryIdx >= 0) path.substring(0, queryIdx) else path
        val tail = if (queryIdx >= 0) path.substring(queryIdx) else ""

        val out = ArrayDeque<String>()
        purePath.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(part)
            }
        }
        val trailingSlash = purePath.endsWith("/")
        val joined = out.joinToString("/")
        return buildString {
            append('/')
            append(joined)
            if (trailingSlash && joined.isNotEmpty()) append('/')
            append(tail)
        }
    }
}
