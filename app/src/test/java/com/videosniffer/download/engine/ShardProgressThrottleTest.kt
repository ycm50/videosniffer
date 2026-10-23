package com.videosniffer.download.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分片进度落库节流：既不能每次都写（拖慢下载），也不能只在分片结束时写（中断就丢一大片进度）。
 */
class ShardProgressThrottleTest {

    @Test
    fun `时间未到且字节不足时不落库`() {
        assertFalse(
            ShardProgressThrottle.shouldPersist(
                lastPersistAt = 1_000,
                now = 1_500,
                lastPersistedBytes = 100,
                finishedBytes = 200
            )
        )
    }

    @Test
    fun `到时间就落库`() {
        assertTrue(
            ShardProgressThrottle.shouldPersist(
                lastPersistAt = 0,
                now = ShardProgressThrottle.INTERVAL_MS,
                lastPersistedBytes = 0,
                finishedBytes = 1
            )
        )
    }

    @Test
    fun `攒够字节就落库`() {
        assertTrue(
            ShardProgressThrottle.shouldPersist(
                lastPersistAt = 0,
                now = 1,
                lastPersistedBytes = 0,
                finishedBytes = ShardProgressThrottle.BYTES
            )
        )
    }

    @Test
    fun `没有新进度时不落库`() {
        // 时间早过了，但 finished 没变 → 写库没有意义（还会把旧值写回去）
        assertFalse(
            ShardProgressThrottle.shouldPersist(
                lastPersistAt = 0,
                now = 10_000,
                lastPersistedBytes = 500,
                finishedBytes = 500
            )
        )
        // finished 回退（分片表被重新规划）时同样不写
        assertFalse(
            ShardProgressThrottle.shouldPersist(
                lastPersistAt = 0,
                now = 10_000,
                lastPersistedBytes = 500,
                finishedBytes = 400
            )
        )
    }

    @Test
    fun `两个条件任一满足即可`() {
        // 字节先到、时间没到
        assertTrue(
            ShardProgressThrottle.shouldPersist(
                lastPersistAt = 1_000,
                now = 1_100,
                lastPersistedBytes = 0,
                finishedBytes = ShardProgressThrottle.BYTES * 2
            )
        )
        // 时间先到、字节没到
        assertTrue(
            ShardProgressThrottle.shouldPersist(
                lastPersistAt = 1_000,
                now = 1_000 + ShardProgressThrottle.INTERVAL_MS,
                lastPersistedBytes = 0,
                finishedBytes = 1
            )
        )
    }
}
