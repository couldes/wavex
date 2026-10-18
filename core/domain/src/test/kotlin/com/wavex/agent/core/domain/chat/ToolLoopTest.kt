package com.wavex.agent.core.domain.chat

import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.ToolCall
import com.wavex.agent.core.domain.model.WebSearchOutcome
import com.wavex.agent.core.domain.model.WebSearchResult
import com.wavex.agent.core.domain.port.SearchProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopTest {

    private class FakeSearch(var outcome: WebSearchOutcome) : SearchProvider {
        override val id = "fake"
        val queries = mutableListOf<String>()
        override suspend fun search(query: String, limit: Int): WebSearchOutcome {
            queries += query
            return outcome
        }
    }

    private fun ok(vararg ids: String) = WebSearchOutcome.Ok(
        ids.mapIndexed { i, id ->
            WebSearchResult(id, i + 1, "T$id", "https://$id.example", "text $id")
        }
    )

    private fun loop(search: SearchProvider?, maxRounds: Int = 5) = ToolLoop(
        ToolLoopConfig(maxRounds = maxRounds), search,
        onToolStarted = {}, onToolFinished = { },
    )

    /** 记录每轮收到的历史与轮数；script[i] 是第 i 轮返回的工具调用 */
    private class Script(vararg val rounds: List<ToolCall>) : RoundSender {
        val histories = mutableListOf<List<ChatRequestMessage>>()
        var calls = 0
        override suspend fun send(history: List<ChatRequestMessage>): List<ToolCall> {
            histories += history.toList()
            val out = rounds.getOrElse(calls) { emptyList() }
            calls++
            return out
        }
    }

    private fun call(query: String, id: String = "call_1") =
        ToolCall(id, "web_search", """{"query":"$query"}""", query)

    private val history = mutableListOf(ChatRequestMessage("user", "btc?"))

    @Test
    fun `no tool calls finishes immediately`() = runTest {
        val search = FakeSearch(ok("a1b2c3"))
        val sender = Script(emptyList())
        val result = loop(search).run(history, sender)

        assertTrue(result is ToolLoopResult.Done)
        assertEquals(1, sender.calls)
        assertTrue(search.queries.isEmpty())
    }

    @Test
    fun `one tool call then done`() = runTest {
        val search = FakeSearch(ok("a1b2c3", "d4e5f6"))
        val sender = Script(listOf(call("btc")), emptyList())
        val result = loop(search).run(history, sender)

        assertTrue(result is ToolLoopResult.Done)
        assertEquals(2, sender.calls)
        assertEquals(listOf("btc"), search.queries)
        // 第二轮历史：user + assistant(toolCalls) + tool
        val second = sender.histories[1]
        assertEquals(3, second.size)
        assertEquals("assistant", second[1].role)
        assertEquals("call_1", second[1].toolCalls.single().id)
        assertEquals("tool", second[2].role)
        assertEquals("call_1", second[2].toolCallId)
        assertTrue(second[2].text.contains("a1b2c3"))
        assertTrue(second[2].text.contains("d4e5f6"))
    }

    @Test
    fun `two parallel calls both execute and backfill`() = runTest {
        val search = FakeSearch(ok("a1b2c3"))
        val sender = Script(
            listOf(call("btc", "call_a"), call("eth", "call_b")),
            emptyList(),
        )
        loop(search).run(history, sender)

        assertEquals(listOf("btc", "eth"), search.queries)
        val second = sender.histories[1]
        val toolMsgs = second.filter { it.role == "tool" }
        assertEquals(2, toolMsgs.size)
        assertEquals(setOf("call_a", "call_b"), toolMsgs.mapNotNull { it.toolCallId }.toSet())
    }

    @Test
    fun `round limit stops the loop`() = runTest {
        val search = FakeSearch(ok("a1b2c3"))
        // 每轮都请求工具：maxRounds=2 应在第 2 轮后收手
        val sender = Script(
            listOf(call("q1")), listOf(call("q2")), listOf(call("q3")), listOf(call("q4")),
        )
        val result = loop(search, maxRounds = 2).run(history, sender)

        assertTrue(result is ToolLoopResult.RoundLimit)
        assertEquals(2, (result as ToolLoopResult.RoundLimit).rounds)
        assertEquals(2, search.queries.size)
    }

    @Test
    fun `permanent failure stops loop and reports reason`() = runTest {
        val search = FakeSearch(WebSearchOutcome.Permanent("凭据无效", "请检查搜索源配置"))
        val sender = Script(listOf(call("btc")), emptyList())
        val result = loop(search).run(history, sender)

        assertTrue(result is ToolLoopResult.SearchUnavailable)
        assertTrue((result as ToolLoopResult.SearchUnavailable).reason.contains("凭据无效"))
        // 失败结果也要回填，且带 terminal 标记
        val toolMsg = sender.histories[1].last { it.role == "tool" }
        assertTrue(toolMsg.text.contains("terminal"))
    }

    @Test
    fun `transient failure backfills retryable and continues`() = runTest {
        val search = FakeSearch(WebSearchOutcome.Transient("timeout"))
        val sender = Script(listOf(call("btc")), emptyList())
        val result = loop(search).run(history, sender)

        // 瞬时失败不终止回路：模型有机会自己决定重搜
        assertTrue(result is ToolLoopResult.Done)
        assertEquals(2, sender.calls)
        val toolMsg = sender.histories[1].last { it.role == "tool" }
        assertTrue(toolMsg.text.contains("retryable"))
    }

    @Test
    fun `cancellation propagates and does not backfill an error result`() = runTest {
        val cancelling = object : SearchProvider {
            override val id = "fake"
            override suspend fun search(query: String, limit: Int): WebSearchOutcome =
                throw CancellationException("stopped")
        }
        val sender = Script(listOf(call("btc")), emptyList())
        val local = mutableListOf(ChatRequestMessage("user", "btc?"))

        val thrown = runCatching { loop(cancelling).run(local, sender) }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        // 取消不得被伪装成错误结果
        assertTrue(local.none { it.role == "tool" })
    }

    @Test
    fun `result text is truncated to configured budget`() = runTest {
        val long = "x".repeat(5000)
        val search = object : SearchProvider {
            override val id = "fake"
            override suspend fun search(query: String, limit: Int) =
                WebSearchOutcome.Ok(listOf(WebSearchResult("a1b2c3", 1, "T", "https://e", long)))
        }
        val sender = Script(listOf(call("btc")), emptyList())
        val result = ToolLoop(
            ToolLoopConfig(maxRounds = 3, maxCharsPerResult = 2000), search,
            onToolStarted = {}, onToolFinished = { },
        ).run(history, sender)

        assertTrue(result is ToolLoopResult.Done)
        val toolMsg = sender.histories[1].last { it.role == "tool" }
        assertTrue("应截断到 2000 字符预算", toolMsg.text.length < 2500)
    }
}
