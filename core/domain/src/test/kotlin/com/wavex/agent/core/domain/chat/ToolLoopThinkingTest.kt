package com.wavex.agent.core.domain.chat

import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.ToolCall
import com.wavex.agent.core.domain.model.WebSearchOutcome
import com.wavex.agent.core.domain.port.SearchProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回路必须把本轮 assistant 的思考（含签名）留在历史里。
 *
 * Anthropic 协议只要历史里出现 tool_use，就要求同一条 assistant 消息的 thinking 块
 * 原样回传，否则回填那一轮直接 400（真机实测）。network 层还原不了协议语义，
 * 这份数据只能由回路随 assistant 消息带着走。
 */
class ToolLoopThinkingTest {

    private class FakeSearch(private val outcome: WebSearchOutcome) : SearchProvider {
        override val id = "fake"
        override suspend fun search(query: String, limit: Int) = outcome
    }

    private val ok = WebSearchOutcome.Ok(
        listOf(
            com.wavex.agent.core.domain.model.WebSearchResult(
                id = "abc123", index = 1, title = "T", url = "https://e/t", text = "x"
            )
        )
    )

    private val call = ToolCall("toolu_1", "web_search", "{\"query\":\"btc\"}", "btc")

    /** 两轮：第一轮模型要工具（带思考），第二轮收尾 */
    private class ScriptedSender(
        private val rounds: List<RoundOutcome>,
    ) : RoundSender {
        val seen = mutableListOf<List<ChatRequestMessage>>()
        var i = 0
        override suspend fun send(history: List<ChatRequestMessage>): RoundOutcome {
            seen += history
            return rounds[i++]
        }
    }

    @Test
    fun `assistant message carries thinking and signature into the backfill round`() = runTest {
        val sender = ScriptedSender(
            listOf(
                RoundOutcome(
                    toolCalls = listOf(call),
                    thinking = "用户要价格，先搜索",
                    thinkingSignature = "sig_abc",
                ),
                RoundOutcome(toolCalls = emptyList()),
            )
        )
        val loop = ToolLoop(ToolLoopConfig(), FakeSearch(ok))
        val history = mutableListOf(ChatRequestMessage("user", "比特币多少？"))

        val result = loop.run(history, sender)

        assertEquals(ToolLoopResult.Done, result)
        // 第二轮请求里，assistant 必须同时带 tool_use 与 thinking
        val second = sender.seen[1]
        val assistant = second.single { it.role == "assistant" }
        assertEquals(listOf(call), assistant.toolCalls)
        assertEquals("用户要价格，先搜索", assistant.thinking)
        assertEquals("sig_abc", assistant.thinkingSignature)
        // tool 结果紧随其后（协议要求配对）
        val idxA = second.indexOf(assistant)
        assertEquals("tool", second[idxA + 1].role)
    }

    @Test
    fun `no thinking stays null so plain relays get no thinking block`() = runTest {
        val sender = ScriptedSender(
            listOf(RoundOutcome(toolCalls = listOf(call)), RoundOutcome(toolCalls = emptyList()))
        )
        val loop = ToolLoop(ToolLoopConfig(), FakeSearch(ok))
        val history = mutableListOf(ChatRequestMessage("user", "q"))

        loop.run(history, sender)

        val assistant = history.single { it.role == "assistant" }
        assertNull("没思考就不该造出空 thinking 块", assistant.thinking)
        assertNull(assistant.thinkingSignature)
    }

    /** 并行两个工具调用：思考只属于这条 assistant 消息，不该漏进 tool 消息 */
    @Test
    fun `thinking attaches only to the assistant message`() = runTest {
        val c2 = ToolCall("toolu_2", "web_search", "{\"query\":\"eth\"}", "eth")
        val sender = ScriptedSender(
            listOf(
                RoundOutcome(listOf(call, c2), thinking = "想", thinkingSignature = "s"),
                RoundOutcome(emptyList()),
            )
        )
        val loop = ToolLoop(ToolLoopConfig(), FakeSearch(ok))
        val history = mutableListOf(ChatRequestMessage("user", "q"))

        loop.run(history, sender)

        assertTrue(history.filter { it.role == "tool" }.all { it.thinking == null })
        assertEquals(2, history.filter { it.toolCallId != null }.size)
        assertEquals("想", history.single { it.role == "assistant" }.thinking)
    }

    /** 永久失败那轮也要能收尾：收尾请求同样带上思考，否则 Anthropic 仍会 400 */
    @Test
    fun `terminal round keeps thinking on the assistant message`() = runTest {
        val sender = ScriptedSender(
            listOf(
                RoundOutcome(listOf(call), thinking = "想", thinkingSignature = "s"),
                RoundOutcome(emptyList()),
            )
        )
        val loop = ToolLoop(
            ToolLoopConfig(),
            FakeSearch(WebSearchOutcome.Permanent("无额度", "请换搜索源")),
        )
        val history = mutableListOf(ChatRequestMessage("user", "q"))

        val result = loop.run(history, sender)

        assertTrue(result is ToolLoopResult.SearchUnavailable)
        val assistant = history.single { it.role == "assistant" }
        assertEquals("想", assistant.thinking)
        // 最后一次收尾请求里配对完整
        val last = sender.seen.last()
        assertEquals(
            last.filter { it.role == "assistant" }.flatMap { a -> a.toolCalls.map { it.id } }.toSet(),
            last.filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet(),
        )
    }
}
