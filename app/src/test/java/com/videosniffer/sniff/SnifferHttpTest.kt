package com.videosniffer.sniff

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 下载 UA 与「电脑版网页」开关的关系。
 *
 * 嗅探/解析用的 UA 必须固定（解析器靠它过防盗链），只有下载用的那份跟随开关 ——
 * 两者混用会让「桌面模式拿到的直链」用移动 UA 去下，被站点拒掉。
 */
class SnifferHttpTest {

    @Test
    fun `桌面开关决定下载 UA`() {
        assertEquals(SnifferHttp.DESKTOP_UA, SnifferHttp.uaFor(true))
        assertEquals(SnifferHttp.MOBILE_UA, SnifferHttp.uaFor(false))
    }

    @Test
    fun `嗅探 UA 恒为移动 UA`() {
        assertEquals(SnifferHttp.MOBILE_UA, SnifferHttp.UA)
    }

    @Test
    fun `桌面 UA 不含移动标识`() {
        // 有些站点看到 Mobile 字样就返回移动版页面/资源
        assertEquals(false, SnifferHttp.DESKTOP_UA.contains("Mobile"))
    }
}
