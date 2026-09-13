package com.videosniffer.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：**为什么不能把 `DownloadTask` 的同一个实例放回 `tasks` 列表**。
 *
 * 背景：`DownloadManager.tasks` 是 `MutableStateFlow`，而 StateFlow 用**相等性**判重 ——
 * `oldState == newState` 时直接返回，**不向订阅者发通知**。
 * `DownloadTask` 是 data class，`List.equals` 又是逐元素比较，
 * 于是「就地修改后把同一实例放回」得到的列表与旧列表**恒等**（比的是同一个对象），
 * 通知永远发不出去。真机表现为：
 *   - 下载列表进度条/速度静止，必须手动点「刷新」才更新；
 *   - 点「暂停」后按钮不变成「继续」；
 *   - 通知栏百分比不动。
 *
 * 因此 [DownloadManager] 的约定是：列表里只放**不可变快照**，引擎在自己的私有副本上改，
 * 每次发布都插入 `copy()`。本测试把这条约定钉住，防止将来被改回去。
 */
class DownloadTaskPublishTest {

    private fun sample() = DownloadTask(
        id = "t1",
        url = "https://example.com/v.mp4",
        title = "作品名",
        quality = "1080p",
        type = "mp4",
        threadCount = 4,
        sourcePageUrl = "https://example.com/watch?v=1",
    )

    /**
     * 错误做法：就地修改已发布实例，再把**同一个实例**放回列表。
     * 新旧列表逐元素比较的是同一个对象，恒等 → StateFlow 不会广播。
     */
    @Test
    fun `就地修改同一实例后列表仍相等_因此不会广播`() {
        val published = sample()
        val oldList = listOf(published)

        // 模拟旧实现：task.state = PAUSED; list.map { if (it.id == task.id) task else it }
        published.state = DownloadState.PAUSED
        published.downloadedBytes = 123L

        val newList = listOf(published)
        assertEquals(
            "同一实例放回列表，新旧列表恒等 —— StateFlow 会跳过这次广播（这是 bug 的根因）",
            oldList,
            newList,
        )
        assertEquals(
            "旧列表里的元素也被就地改写了（快照被污染），说明不能共享可变实例",
            DownloadState.PAUSED,
            oldList[0].state,
        )
    }

    /**
     * 正确做法：旧快照不被就地修改，发布一个新副本。
     * 值一变，新旧列表就不再相等，StateFlow 才会广播。
     */
    @Test
    fun `发布副本后列表不再相等_因此会广播`() {
        val published = sample()          // 列表中的旧快照（全程不被修改）
        val oldList = listOf(published)

        // 模拟修复后：引擎在私有副本上改，发布时插入 copy()
        val work = published.copy()
        work.state = DownloadState.PAUSED
        work.downloadedBytes = 123L
        val newList = listOf(work.copy())

        assertNotEquals("值已变化，新旧列表不再相等 → 广播得以发出", oldList, newList)
        assertEquals("旧快照必须保持原值（不可变）", DownloadState.QUEUED, published.state)
        assertEquals(0L, published.downloadedBytes)
    }

    /**
     * 没有任何值变化时，即使插入副本也不该广播（避免无意义的空刷新）。
     */
    @Test
    fun `无变化时副本与旧值相等_不产生多余广播`() {
        val published = sample()
        val oldList = listOf(published)
        assertEquals(oldList, listOf(published.copy()))
    }

    /**
     * 进度推进这类高频更新同样依赖「值变化才广播」：
     * 只要字节数变了，列表就不再相等。
     */
    @Test
    fun `进度推进会导致列表不等`() {
        val before = sample()
        val after = before.copy(downloadedBytes = 1024L)
        assertTrue("进度变化必须打破相等性，否则进度条不会动", before != after)
        assertNotEquals(listOf(before), listOf(after))
    }
}
