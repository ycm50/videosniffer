package com.videosniffer.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [ShardCalculator] 分片区间与 [Shard.length] 测试。 */
class ShardCalculatorTest {

    @Test
    fun `整除时各分片等长且覆盖全部字节`() {
        val shards = ShardCalculator.split(100, 4)
        assertEquals(4, shards.size)
        assertEquals(0L, shards[0].start)
        assertEquals(24L, shards[0].end)
        assertEquals(25L, shards[1].start)
        assertEquals(49L, shards[1].end)
        assertEquals(75L, shards[3].start)
        assertEquals(99L, shards[3].end) // 最后一片吃到文件尾
        shards.forEach { assertEquals(25L, it.length) }
    }

    @Test
    fun `不整除时最后一片吃掉余数`() {
        val shards = ShardCalculator.split(10, 3)
        assertEquals(3, shards.size)
        assertEquals(3L, shards[0].length)
        assertEquals(3L, shards[1].length)
        assertEquals(4L, shards[2].length) // 10 - 3 - 3
        assertEquals(9L, shards[2].end)
    }

    @Test
    fun `所有分片首尾相接且不重叠`() {
        val total = 1000L
        val shards = ShardCalculator.split(total, 7)
        var expectedStart = 0L
        shards.forEach { shard ->
            assertEquals(expectedStart, shard.start)
            expectedStart = shard.end + 1
        }
        assertEquals(total, expectedStart)
        assertEquals(total, shards.sumOf { it.length })
    }

    @Test
    fun `单线程或非法输入退化为整段`() {
        assertEquals(1, ShardCalculator.split(100, 1).size)
        assertEquals(99L, ShardCalculator.split(100, 1)[0].end)

        assertEquals(1, ShardCalculator.split(100, 0).size)
        assertEquals(1, ShardCalculator.split(0, 4).size)
        assertEquals(1, ShardCalculator.split(-5, 4).size)
    }

    @Test
    fun `未知长度时整段分片的区间与长度`() {
        // 长度未知(-1)时引擎会退化为单线程整包下载，此时区间为 [0, -2]、length 为 -1
        val single = ShardCalculator.split(-1, 4).single()
        assertEquals(0L, single.start)
        assertEquals(-2L, single.end)
        // 引擎判定「是否需要下载」用的比较是 finished < length，故这里必须为负
        assertEquals(-1L, single.length)
    }

    @Test
    fun `index 编码线程数并可还原`() {
        val shards = ShardCalculator.split(1000, 8)
        assertEquals(8, shards.size)
        // 首片编码为 8*1000+0，末片为 8*1000+7
        assertEquals(8000, shards[0].index)
        assertEquals(8007, shards[7].index)
        assertEquals(0, shards[0].ordinal)
        assertEquals(7, shards[7].ordinal)
        assertEquals(8, ShardCalculator.threadCountOf(shards))
    }

    @Test
    fun `threadCountOf 对旧格式或异常数据返回 null`() {
        // 旧版本用裸序号 0,1,2 作为 index → 无法判定线程数
        val legacy = listOf(Shard(0, 0, 99), Shard(1, 100, 199))
        assertNull(ShardCalculator.threadCountOf(legacy))
        // 首片序号不为 0 → 数据异常
        assertNull(ShardCalculator.threadCountOf(listOf(Shard(4001, 0, 99))))
        // 空表
        assertNull(ShardCalculator.threadCountOf(emptyList()))
    }

    @Test
    fun `单线程 split 也带线程数编码`() {
        val shards = ShardCalculator.split(100, 1)
        assertEquals(1, shards.size)
        assertEquals(1000, shards[0].index)
        assertEquals(1, ShardCalculator.threadCountOf(shards))
    }
}
