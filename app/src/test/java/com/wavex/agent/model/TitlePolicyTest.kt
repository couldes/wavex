package com.wavex.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标题演化策略的行为钉子（方案乙：首题 + 里程碑演化）：
 * - 首轮完成后生成首题；此后每新增 EVOLVE_EVERY 条用户消息用最近几轮重新生成；
 * - 手动改名后永不自动覆盖；未达里程碑不动；超长消息截断。
 */
class TitlePolicyTest {

    private fun exchange(i: Int) = listOf(
        ChatMessage(text = "问题$i", fromUser = true),
        ChatMessage(text = "回答$i", fromUser = false)
    )

    @Test
    fun `手动改名后永不演化`() {
        val msgs = exchange(0)
        assertNull(
            TitlePolicy.transcript(
                titleIsUserDefined = true, title = "旧标题", messages = msgs, lastTitleUserCount = 0
            )
        )
    }

    @Test
    fun `首轮完成用首问首答生成首题`() {
        val msgs = listOf(ChatMessage(text = "帮我写周报", fromUser = true), ChatMessage(text = "好的，以下是…", fromUser = false))
        assertEquals(
            "用户：帮我写周报\n助手：好的，以下是…",
            TitlePolicy.transcript(false, "新对话", msgs, lastTitleUserCount = 0)
        )
    }

    @Test
    fun `没有消息时不生成`() {
        assertNull(TitlePolicy.transcript(false, "新对话", emptyList(), lastTitleUserCount = 0))
    }

    @Test
    fun `未到里程碑不演化`() {
        val msgs = exchange(0) + exchange(1) + ChatMessage(text = "问2", fromUser = true)
        assertNull(TitlePolicy.transcript(false, "周报", msgs, lastTitleUserCount = 0))
    }

    @Test
    fun `达到里程碑用最近几轮演化`() {
        val msgs = (0 until 8).flatMap { exchange(it) }   // 8 问 8 答
        val t = TitlePolicy.transcript(false, "旧标题", msgs, lastTitleUserCount = 0)!!
        assertTrue(t.contains("问题7"))     // 最近一轮必在窗口内
        assertTrue(t.contains("问题5"))     // 3 轮窗口 = 最近 6 条
        assertFalse(t.contains("问题0"))    // 早期轮次不进窗口
    }

    @Test
    fun `手动改名计数无关紧要`() {
        // 手动改名是硬闸门：即使攒够了里程碑也不动
        val msgs = (0 until 8).flatMap { exchange(it) }
        assertNull(TitlePolicy.transcript(true, "我起的名", msgs, lastTitleUserCount = 2))
    }

    @Test
    fun `超长消息按条截断`() {
        val long = "x".repeat(1000)
        val t = TitlePolicy.transcript(false, "新对话", listOf(ChatMessage(text = long, fromUser = true)), 0)!!
        assertEquals(300, t.count { it == 'x' })   // PER_MESSAGE_MAX
    }

    @Test
    fun `演化后的基线推进使下一里程碑再等 EVOLVE_EVERY 条`() {
        // 8 条用户消息、基线 2：差值 6 恰好触发；基线 3：差值 5 不触发
        val msgs = (0 until 8).flatMap { exchange(it) }
        assertNull(TitlePolicy.transcript(false, "旧标题", msgs, lastTitleUserCount = 3))
        val t = TitlePolicy.transcript(false, "旧标题", msgs, lastTitleUserCount = 2)
        assertTrue(t != null)
    }
}
