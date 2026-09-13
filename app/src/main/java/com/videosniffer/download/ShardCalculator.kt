package com.videosniffer.download

/**
 * 单个分片：区间 [start, end] 与已下载字节数。
 *
 * [index] 编码了「创建时使用的线程数」，用于断点续传时校验：
 * `index / INDEX_STRIDE` 为线程数，`index % INDEX_STRIDE` 为分片序号。
 *
 * 为什么要编码线程数：分片区间一旦生成就必须保持稳定，否则已下载的字节会错位。
 * 但若用户在续传前把线程数改小，只按「旧分片数」取前 N 个分片会漏下载剩余区间；
 * 因此续传时需要知道当初的分片数，并在线程数变小时重新规划分片。
 */
data class Shard(
    val index: Int,
    val start: Long,
    val end: Long,
    var finished: Long = 0L
) {
    /** 分片序号（同一分片表内从 0 开始） */
    val ordinal: Int get() = index % INDEX_STRIDE

    val length: Long get() = end - start + 1

    companion object {
        /** index 的编码步长：>= 线程数上限(32)，留足余量 */
        const val INDEX_STRIDE = 1000
    }
}

/**
 * 分片区间计算。按线程数均分，最后一片吃到文件尾。
 */
object ShardCalculator {

    fun split(totalBytes: Long, threadCount: Int): List<Shard> {
        val n = threadCount.coerceAtLeast(1)
        if (totalBytes <= 0 || n <= 1) {
            return listOf(Shard(encodeIndex(1, 0), 0, totalBytes - 1))
        }
        val average = totalBytes / n
        return (0 until n).map { i ->
            val start = average * i
            val end = if (i == n - 1) totalBytes - 1 else start + average - 1
            Shard(encodeIndex(n, i), start, end)
        }
    }

    private fun encodeIndex(threadCount: Int, ordinal: Int): Int =
        threadCount * Shard.INDEX_STRIDE + ordinal

    /** 从分片表还原当初的线程数；空表或旧格式返回 null。 */
    fun threadCountOf(shards: List<Shard>): Int? {
        val first = shards.firstOrNull() ?: return null
        val count = first.index / Shard.INDEX_STRIDE
        val remainder = first.index % Shard.INDEX_STRIDE
        // 旧版本分片 index 是 0..N 的裸序号：count 会是 0，视为无法判定
        if (count <= 0) return null
        // 首片序号应为 0，否则数据异常
        if (remainder != 0) return null
        return count
    }
}
