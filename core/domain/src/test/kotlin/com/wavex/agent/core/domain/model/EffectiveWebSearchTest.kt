package com.wavex.agent.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联网真实状态的钉子。
 *
 * 输入栏胶囊只表达意图，这里守的是「意图 ≠ 生效」的三种形态：
 * 搜索源没配、上游拒掉 tools、声明发了但模型没搜。
 */
class EffectiveWebSearchTest {

    @Test
    fun `disabled shows closed`() {
        val w = EffectiveWebSearch.preview(enabled = false, sourceReady = true)
        assertEquals("关闭", w.label)
        assertFalse(w.active)
        assertFalse(w.toolDeclared)
    }

    /** 开关亮着但搜索源缺失：必须显式说「未配置」，不能装作已联网 */
    @Test
    fun `enabled without source is not configured and declares nothing`() {
        val w = EffectiveWebSearch.preview(enabled = true, sourceReady = false)
        assertEquals("未配置", w.label)
        assertFalse(w.active)
        assertFalse(w.toolDeclared)
        assertTrue(w.detail.contains("未配置搜索源"))
    }

    @Test
    fun `enabled with source declares the tool and waits`() {
        val w = EffectiveWebSearch.preview(enabled = true, sourceReady = true)
        assertEquals("待命", w.label)
        assertTrue(w.active)
        assertTrue(w.toolDeclared)
    }

    @Test
    fun `searches are reported with a count`() {
        val w = EffectiveWebSearch.preview(enabled = true, sourceReady = true).copy(searches = 2)
        assertEquals("已搜 2 次", w.label)
        assertTrue(w.active)
    }

    @Test
    fun `any failed search surfaces as failure instead of a count`() {
        val w = EffectiveWebSearch.preview(enabled = true, sourceReady = true)
            .copy(searches = 2, allSucceeded = false)
        assertEquals("搜索失败", w.label)
        assertTrue(w.detail.contains("失败项"))
    }

    /** 上游拒绝 tools 声明：开关仍开着（意图没变），但本轮确实没联网 */
    @Test
    fun `dropped round is inactive even though the switch is on`() {
        val w = EffectiveWebSearch.preview(enabled = true, sourceReady = true)
            .copy(dropped = true, toolDeclared = false, searches = 0)
        assertEquals("已降级", w.label)
        assertFalse(w.active)
        assertTrue(w.enabled)
    }
}
