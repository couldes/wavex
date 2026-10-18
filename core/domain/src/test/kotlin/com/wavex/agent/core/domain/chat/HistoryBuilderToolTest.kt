package com.wavex.agent.core.domain.chat

import com.wavex.agent.core.domain.model.ChatMessage
import com.wavex.agent.core.domain.model.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryBuilderToolTest {

    private val loader = ContentLoader { null }

    private fun call(id: String = "call_1", query: String = "btc") =
        ToolCall(id, "web_search", """{"query":"$query"}""", query)

    @Test
    fun `assistant tool calls survive into request history`() = runTest {
        val result = HistoryBuilder.build(
            listOf(
                ChatMessage(text = "price?", fromUser = true),
                ChatMessage(text = "", fromUser = false, toolCalls = listOf(call())),
                ChatMessage(text = """{"items":[]}""", fromUser = false, toolCallId = "call_1"),
            ),
            loader,
        )
        assertEquals(3, result.history.size)
        assertEquals("assistant", result.history[1].role)
        assertEquals(1, result.history[1].toolCalls.size)
        assertEquals("call_1", result.history[1].toolCalls[0].id)
        assertEquals("tool", result.history[2].role)
        assertEquals("call_1", result.history[2].toolCallId)
    }

    @Test
    fun `orphan tool_use gets a synthetic tool_result`() = runTest {
        // 搜索途中 App 被杀：assistant 的 tool_use 落盘了，结果没落盘。
        // 直接把孤儿 tool_use 发给上游会被 400，必须补一条合成结果
        val result = HistoryBuilder.build(
            listOf(
                ChatMessage(text = "price?", fromUser = true),
                ChatMessage(text = "", fromUser = false, toolCalls = listOf(call())),
            ),
            loader,
        )
        val toolMsg = result.history.last()
        assertEquals("tool", toolMsg.role)
        assertEquals("call_1", toolMsg.toolCallId)
        assertTrue(toolMsg.text.contains("error"))
    }

    @Test
    fun `orphan tool result without tool_use is dropped`() = runTest {
        val result = HistoryBuilder.build(
            listOf(
                ChatMessage(text = "price?", fromUser = true),
                ChatMessage(text = """{"items":[]}""", fromUser = false, toolCallId = "call_1"),
            ),
            loader,
        )
        assertTrue(result.history.none { it.role == "tool" })
    }

    @Test
    fun `empty assistant text with tool calls is not skipped`() = runTest {
        // 既有「空消息跳过」规则不能吃掉纯工具调用的 assistant 消息
        val result = HistoryBuilder.build(
            listOf(
                ChatMessage(text = "price?", fromUser = true),
                ChatMessage(text = "", fromUser = false, toolCalls = listOf(call())),
            ),
            loader,
        )
        assertEquals(3, result.history.size)
        assertTrue(result.history.any { it.toolCalls.isNotEmpty() })
    }

    @Test
    fun `tool call messages keep their order`() = runTest {
        val result = HistoryBuilder.build(
            listOf(
                ChatMessage(text = "q", fromUser = true),
                ChatMessage(
                    text = "", fromUser = false,
                    toolCalls = listOf(call("call_a", "btc"), call("call_b", "eth")),
                ),
                ChatMessage(text = "{}", fromUser = false, toolCallId = "call_a"),
                ChatMessage(text = "{}", fromUser = false, toolCallId = "call_b"),
            ),
            loader,
        )
        assertEquals(listOf("user", "assistant", "tool", "tool"), result.history.map { it.role })
        assertEquals(listOf("call_a", "call_b"), result.history.filter { it.role == "tool" }.map { it.toolCallId })
    }

    @Test
    fun `synthetic tool result goes right after its declaring assistant message`() = runTest {
        // 生产里持久化的助手消息同时带 toolCalls 和最终正文（工具结果不进消息树），
        // 所以下一轮必然出现「有 tool_use 无结果」。补的合成结果必须紧跟声明它的
        // assistant 消息——追加到历史末尾会排在后续用户消息之后，上游直接 400
        val result = HistoryBuilder.build(
            listOf(
                ChatMessage(text = "btc?", fromUser = true),
                ChatMessage(
                    text = "Bitcoin is 108432 USD",
                    fromUser = false,
                    toolCalls = listOf(call()),
                ),
                ChatMessage(text = "and eth?", fromUser = true),
            ),
            loader,
        )
        assertEquals(
            listOf("user", "assistant", "tool", "user"),
            result.history.map { it.role },
        )
        assertEquals("call_1", result.history[2].toolCallId)
    }

    @Test
    fun `plain messages carry no tool data`() = runTest {
        val result = HistoryBuilder.build(
            listOf(ChatMessage(text = "hi", fromUser = true)),
            loader,
        )
        val msg = result.history.single()
        assertTrue(msg.toolCalls.isEmpty())
        assertNull(msg.toolCallId)
    }
}
