package com.wavex.agent.core.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCallParsingTest {

    private fun acc(): ToolCallAccumulator = ToolCallAccumulator()

    @Test
    fun `openai accumulates arguments across chunks`() {
        val a = acc()
        a.onOpenAiDelta(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_x","type":"function",
                   "function":{"name":"web_search","arguments":""}}]}}]}"""
            )
        )
        a.onOpenAiDelta(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":"}}]}}]}"""
            )
        )
        a.onOpenAiDelta(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"btc\"}"}}]}}]}"""
            )
        )
        val calls = a.snapshot()
        assertEquals(1, calls.size)
        assertEquals("call_x", calls[0].id)
        assertEquals("web_search", calls[0].name)
        assertEquals("""{"query":"btc"}""", calls[0].arguments)
        assertEquals("btc", calls[0].query)
    }

    @Test
    fun `openai parallel calls keep separate ids and indices`() {
        val a = acc()
        a.onOpenAiDelta(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[
                   {"index":0,"id":"call_a","function":{"name":"web_search","arguments":""}},
                   {"index":1,"id":"call_b","function":{"name":"web_search","arguments":""}}]}}]}"""
            )
        )
        a.onOpenAiDelta(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[
                   {"index":0,"function":{"arguments":"{\"query\":\"btc\"}"}},
                   {"index":1,"function":{"arguments":"{\"query\":\"eth\"}"}}]}}]}"""
            )
        )
        val calls = a.snapshot()
        assertEquals(2, calls.size)
        assertEquals("call_a", calls[0].id)
        assertEquals("btc", calls[0].query)
        assertEquals("call_b", calls[1].id)
        assertEquals("eth", calls[1].query)
    }

    @Test
    fun `anthropic accumulates input_json_delta`() {
        val a = acc()
        a.onAnthropicStart("toolu_1", "web_search")
        a.onAnthropicDelta("""{"query":""")
        a.onAnthropicDelta(""""btc"}""")
        a.onAnthropicStop()
        val calls = a.snapshot()
        assertEquals(1, calls.size)
        assertEquals("toolu_1", calls[0].id)
        assertEquals("""{"query":"btc"}""", calls[0].arguments)
        assertEquals("btc", calls[0].query)
    }

    @Test
    fun `malformed arguments yields null query but keeps raw`() {
        val a = acc()
        a.onOpenAiDelta(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_x",
                   "function":{"name":"web_search","arguments":"{broken"}}]}}]}"""
            )
        )
        val calls = a.snapshot()
        assertEquals(1, calls.size)
        assertEquals("{broken", calls[0].arguments)
        assertNull(calls[0].query)
    }

    @Test
    fun `non web_search tool keeps raw arguments and null query`() {
        val a = acc()
        a.onOpenAiDelta(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_x",
                   "function":{"name":"create_file","arguments":"{\"path\":\"a.txt\"}"}}]}}]}"""
            )
        )
        val calls = a.snapshot()
        assertEquals("create_file", calls[0].name)
        assertNull(calls[0].query)
    }

    @Test
    fun `parallel calls without ids get distinct synthesized ids`() {
        // 有的中转站不发 id：两个并行同名调用若都退化成 call_web_search，
        // 历史配对按 id 去重后会漏掉一个 tool_use（上游 400）
        val a = acc()
        a.onOpenAiDelta(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[
                   {"index":0,"function":{"name":"web_search","arguments":"{\"query\":\"a\"}"}},
                   {"index":1,"function":{"name":"web_search","arguments":"{\"query\":\"b\"}"}}]}}]}"""
            )
        )
        val ids = a.snapshot().map { it.id }
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `snapshot is non destructive`() {
        val a = acc()
        a.onOpenAiDelta(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_x",
                   "function":{"name":"web_search","arguments":"{\"query\":\"a\"}"}}]}}]}"""
            )
        )
        assertEquals(1, a.snapshot().size)
        assertEquals(1, a.snapshot().size)
    }
}
