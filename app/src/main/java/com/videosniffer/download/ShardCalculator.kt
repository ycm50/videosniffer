package com.videosniffer.download

/** 单个分片：区间 [start, end] 与已下载字节数 */
data class Shard(
    val index: Int,
    val start: Long,
    val end: Long,
    var finished: Long = 0L
) {
    val length: Long get() = end - start + 1
}

/**
 * 分片区间计算。按线程数均分，最后一片吃到文件尾。
 */
object ShardCalculator {

    fun split(totalBytes: Long, threadCount: Int): List<Shard> {
        val n = threadCount.coerceAtLeast(1)
        if (totalBytes <= 0 || n <= 1) {
            return listOf(Shard(0, 0, totalBytes - 1))
        }
        val average = totalBytes / n
        return (0 until n).map { i ->
            val start = average * i
            val end = if (i == n - 1) totalBytes - 1 else start + average - 1
            Shard(i, start, end)
        }
    }
}
