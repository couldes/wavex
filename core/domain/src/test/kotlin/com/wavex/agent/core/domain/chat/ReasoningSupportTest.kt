package com.wavex.agent.core.domain.chat

import com.wavex.agent.core.domain.model.ApiProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「真实生效态」的行为钉子。
 *
 * 这组测试守的是一类具体的用户困惑：设置页选了「高」，但模型只支持开/关，
 * 请求体里发的是 `enable_thinking: true` —— 界面若照抄设置值就是在骗人。
 * 因此断言全部打在 [EffectiveReasoning.label] 上（对话页状态条渲染的就是它）。
 */
class ReasoningSupportTest {

    // ---------- 支持形态判定 ----------

    @Test
    fun `toggle family models are detected by prefix`() {
        assertEquals(ReasoningSupport.TOGGLE, reasoningSupportOf("glm-4.6", ApiProtocol.OPENAI))
        assertEquals(ReasoningSupport.TOGGLE, reasoningSupportOf("qwen3-max", ApiProtocol.OPENAI))
        assertEquals(ReasoningSupport.TOGGLE, reasoningSupportOf("kimi-k2-0905-preview", ApiProtocol.OPENAI))
    }

    @Test
    fun `prefix match is case insensitive`() {
        assertEquals(ReasoningSupport.TOGGLE, reasoningSupportOf("GLM-5", ApiProtocol.OPENAI))
        assertEquals(ReasoningSupport.MODEL_DECIDED, reasoningSupportOf("DeepSeek-V4", ApiProtocol.OPENAI))
    }

    @Test
    fun `deepseek is model decided`() {
        assertEquals(ReasoningSupport.MODEL_DECIDED, reasoningSupportOf("deepseek-chat", ApiProtocol.OPENAI))
    }

    @Test
    fun `effort style models are tiered`() {
        assertEquals(ReasoningSupport.TIERED, reasoningSupportOf("gpt-5.2", ApiProtocol.OPENAI))
        assertEquals(ReasoningSupport.TIERED, reasoningSupportOf("claude-sonnet-4-5", ApiProtocol.OPENAI))
    }

    /**
     * Anthropic 协议是预算制：即使模型名恰好是 glm/deepseek（中转站转发到 Anthropic 端点），
     * 也走 budget_tokens 的档位语义，不能按 OpenAI 的开/关族处理。
     */
    @Test
    fun `anthropic protocol is always tiered regardless of model name`() {
        assertEquals(ReasoningSupport.TIERED, reasoningSupportOf("deepseek-v4-pro", ApiProtocol.ANTHROPIC))
        assertEquals(ReasoningSupport.TIERED, reasoningSupportOf("glm-4.6", ApiProtocol.ANTHROPIC))
    }

    // ---------- 生效态推导 ----------

    @Test
    fun `tiered model keeps the selected rung`() {
        val e = EffectiveReasoning.preview("high", "gpt-5.2", ApiProtocol.OPENAI)
        assertEquals("high", e.level)
        assertEquals("高", e.label)
        assertTrue(e.applied)
    }

    /** 核心断言：开/关族选「高」，实际生效的只有「开」 */
    @Test
    fun `toggle family shows on instead of the selected rung`() {
        val e = EffectiveReasoning.preview("high", "qwen3-max", ApiProtocol.OPENAI)
        assertEquals("on", e.level)
        assertEquals("开", e.label)
        assertTrue(e.applied)
        // 三个不同档位在开/关族上收敛到同一个值：这是能力差异，不是 bug
        assertEquals(
            EffectiveReasoning.preview("low", "qwen3-max", ApiProtocol.OPENAI).level,
            EffectiveReasoning.preview("xhigh", "qwen3-max", ApiProtocol.OPENAI).level,
        )
    }

    @Test
    fun `toggle family with default rung sends nothing`() {
        val e = EffectiveReasoning.preview("", "glm-4.6", ApiProtocol.OPENAI)
        assertNull(e.level)
        assertEquals("关闭", e.label)
        assertFalse(e.applied)
    }

    /** 模型决定型：不管选什么都不发，界面必须说「模型决定」而不是显示所选档位 */
    @Test
    fun `model decided family reports model decides and never applies`() {
        val e = EffectiveReasoning.preview("xhigh", "deepseek-chat", ApiProtocol.OPENAI)
        assertNull(e.level)
        assertEquals("模型决定", e.label)
        assertFalse(e.applied)
        assertTrue(e.detail.contains("模型名"))
    }

    @Test
    fun `dropped round reports degraded even when a rung was requested`() {
        val e = EffectiveReasoning.preview("high", "gpt-5.2", ApiProtocol.OPENAI)
            .copy(dropped = true, level = null)
        assertEquals("已降级", e.label)
        assertFalse(e.applied)
    }

    @Test
    fun `label of unknown stored value falls back to the raw value`() {
        // 持久层归一化会拦掉未知值；这里只保证 labelOf 自身不抛、不吞
        assertEquals("bogus", ReasoningLevels.labelOf("bogus"))
        assertEquals("默认", ReasoningLevels.labelOf(""))
        assertEquals("极高", ReasoningLevels.labelOf("xhigh"))
    }
}
