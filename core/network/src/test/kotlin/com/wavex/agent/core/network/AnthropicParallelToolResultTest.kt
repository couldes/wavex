package com.wavex.agent.core.network

import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 并行工具调用的回填形状（真机 400 抓出来的）。
 *
 * 模型一条 assistant 消息里可以同时请求多个 web_search（实测 DeepSeek 会
 * 「中文搜一次 + 英文搜一次」）。Anthropic 要求这些 tool_use 的 tool_result
 * **全部挤在同一条紧随其后的 user 消息里**；一条结果一个 user 消息会被判成
 * 「tool_use 后面没有配对结果」直接 400。
 *
 * OpenAI 协议没这个约束（每条 tool 消息自带 tool_call_id，天然一条一角色），
 * 所以这是 Anthropic 专用的合并。
 */
class AnthropicParallelToolResultTest {

    private fun call(id: String, q: String) =
        ToolCall(id, "web_search", """{"query":"$q"}""", q)

    @Test
    fun `consecutive tool results merge into one user message`() {
        val payload = RequestPayloads.anthropicPayload(
            model = "claude-x",
            history = listOf(
                ChatRequestMessage("user", "比特币多少？"),
                ChatRequestMessage(
                    "assistant", "",
                    toolCalls = listOf(call("call_00", "btc"), call("call_01", "bitcoin")),
                    thinking = "两种语言各搜一次", thinkingSignature = "sig-1",
                ),
                ChatRequestMessage("tool", """{"items":[1]}""", toolCallId = "call_00"),
                ChatRequestMessage("tool", """{"items":[2]}""", toolCallId = "call_01"),
            ),
            reasoningEffort = null,
            webSearch = true,
        )
        val messages = payload.getJSONArray("messages")
        assertEquals("两条工具结果应合成一条 user 消息", 3, messages.length())
        val results = messages.getJSONObject(2).getJSONArray("content")
        assertEquals(2, results.length())
        assertEquals("tool_result", results.getJSONObject(0).getString("type"))
        assertEquals("call_00", results.getJSONObject(0).getString("tool_use_id"))
        assertEquals("call_01", results.getJSONObject(1).getString("tool_use_id"))
        assertEquals("user", messages.getJSONObject(2).getString("role"))
    }

    /** 单个结果不该被合并逻辑改坏（最常见路径） */
    @Test
    fun `single tool result stays a single user message`() {
        val payload = RequestPayloads.anthropicPayload(
            model = "claude-x",
            history = listOf(
                ChatRequestMessage("assistant", "", toolCalls = listOf(call("c1", "q"))),
                ChatRequestMessage("tool", "{}", toolCallId = "c1"),
            ),
            reasoningEffort = null,
            webSearch = true,
        )
        val messages = payload.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals(1, messages.getJSONObject(1).getJSONArray("content").length())
    }

    /**
     * 两轮工具之间不能跨轮合并：第二轮的 tool 结果必须留在
     * 它自己那条 user 消息里，否则又变成孤儿配对。
     */
    @Test
    fun `tool results from different rounds stay separate`() {
        val payload = RequestPayloads.anthropicPayload(
            model = "claude-x",
            history = listOf(
                ChatRequestMessage("assistant", "", toolCalls = listOf(call("a1", "q1"))),
                ChatRequestMessage("tool", "{}", toolCallId = "a1"),
                ChatRequestMessage("user", "再查一个"),
                ChatRequestMessage("assistant", "", toolCalls = listOf(call("b1", "q2"))),
                ChatRequestMessage("tool", "{}", toolCallId = "b1"),
            ),
            reasoningEffort = null,
            webSearch = true,
        )
        val messages = payload.getJSONArray("messages")
        // assistant / user(results) / user / assistant / user(results)
        assertEquals(5, messages.length())
        assertEquals(1, messages.getJSONObject(1).getJSONArray("content").length())
        assertEquals("a1", messages.getJSONObject(1).getJSONArray("content")
            .getJSONObject(0).getString("tool_use_id"))
        assertEquals("b1", messages.getJSONObject(4).getJSONArray("content")
            .getJSONObject(0).getString("tool_use_id"))
    }

    /** 合并后仍要保持正文块与结果块的相对顺序（穿插的普通消息不被吞） */
    @Test
    fun `non tool message between results breaks the merge`() {
        val payload = RequestPayloads.anthropicPayload(
            model = "claude-x",
            history = listOf(
                ChatRequestMessage("assistant", "", toolCalls = listOf(call("a1", "q"))),
                ChatRequestMessage("tool", "{}", toolCallId = "a1"),
                ChatRequestMessage("assistant", "中间插一条正文"),
                ChatRequestMessage("tool", "{}", toolCallId = "a2"),
            ),
            reasoningEffort = null,
            webSearch = true,
        )
        val messages = payload.getJSONArray("messages")
        assertEquals(4, messages.length())
    }

    /** OpenAI 协议一条一角色，别把合并逻辑串过去（合并会让 tool_call_id 丢失配对） */
    @Test
    fun `openai payload keeps one tool message per result`() {
        val payload = RequestPayloads.openAiPayload(
            model = "gpt-x",
            history = listOf(
                ChatRequestMessage(
                    "assistant", "",
                    toolCalls = listOf(call("c1", "a"), call("c2", "b"))
                ),
                ChatRequestMessage("tool", "{}", toolCallId = "c1"),
                ChatRequestMessage("tool", "{}", toolCallId = "c2"),
            ),
            reasoningEffort = null,
            webSearch = true,
        )
        val messages = payload.getJSONArray("messages")
        // 工具结果各占一条 role=tool（OpenAI 语义），不合并
        assertEquals(3, messages.length())
        assertEquals("tool", messages.getJSONObject(1).getString("role"))
        assertEquals("c1", messages.getJSONObject(1).getString("tool_call_id"))
        assertEquals("c2", messages.getJSONObject(2).getString("tool_call_id"))
    }
}
