package com.videosniffer.download.engine

/**
 * 分片进度的落库节流策略。
 *
 * 为什么要节流：分片进度此前只在**分片结束时**写一次库（`finally` 里），
 * 于是一个 1 GB / 8 线程 = 128 MB 的分片，中途进程被杀就整片进度归零、从头再下。
 *
 * 为什么不能每写一块就落库：下载循环的每次 read 只有 256 KB，
 * 每秒可能几十次；SQLite 每次写入都是事务级开销，会把下载线程拖慢。
 *
 * 折中：每 [INTERVAL_MS] 或每 [BYTES] 落一次，最多丢 2 秒 / 16 MB 的进度。
 * 抽成纯函数是为了能单测（时间与字节数都是入参，不碰时钟）。
 */
object ShardProgressThrottle {

    /** 最长多久落一次库 */
    const val INTERVAL_MS = 2_000L

    /** 最多攒多少字节就落一次库 */
    const val BYTES = 16L * 1024 * 1024

    /**
     * @param lastPersistAt     上次落库时刻（毫秒）
     * @param now               当前时刻（毫秒）
     * @param lastPersistedBytes 上次落库时该分片的 finished 值
     * @param finishedBytes     当前该分片的 finished 值
     * @return true 表示该落库了
     */
    fun shouldPersist(
        lastPersistAt: Long,
        now: Long,
        lastPersistedBytes: Long,
        finishedBytes: Long
    ): Boolean {
        // 没有新进度就没必要写（避免空转，也避免把 finished 写回旧值）
        if (finishedBytes <= lastPersistedBytes) return false
        return now - lastPersistAt >= INTERVAL_MS || finishedBytes - lastPersistedBytes >= BYTES
    }
}
