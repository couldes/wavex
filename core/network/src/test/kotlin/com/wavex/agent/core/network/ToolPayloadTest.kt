package com.wavex.agent.core.network

import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPayloadTest {

    @Test
    fun `openai declares function tool when web search enabled`() {
        val p = RequestPayloads.openAiPayload(
            history = listOf(ChatRequestMessage("user", "hi")),
            reasoningEffort = null, webSearch = true, streamOptions = false, model = "gpt-4o"
        )
        val tools = p.getJSONArray("tools")
        assertEquals(1, tools.length())
        val fn = tools.getJSONObject(0).getJSONObject("function")
        assertEquals("web_search", fn.getString("name"))
        assertTrue(fn.getString("description").contains("Search the web"))
        val params = fn.getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        assertEquals("string", params.getJSONObject("properties").getJSONObject("query").getString("type"))
        assertEquals("query", params.getJSONArray("required").getString(0))
    }

    @Test
    fun `openai omits tools when web search disabled`() {
        val p = RequestPayloads.openAiPayload(
            history = listOf(ChatRequestMessage("user", "hi")),
            reasoningEffort = null, webSearch = false, streamOptions = false, model = "gpt-4o"
        )
        assertTrue(!p.has("tools"))
    }

    @Test
    fun `openai serializes assistant tool calls`() {
        val call = ToolCall("call_1", "web_search", """{"query":"btc"}""", "btc")
        val p = RequestPayloads.openAiPayload(
            history = listOf(
                ChatRequestMessage("user", "price?"),
                ChatRequestMessage("assistant", "", toolCalls = listOf(call)),
                ChatRequestMessage("tool", """{"items":[]}""", toolCallId = "call_1"),
            ),
            reasoningEffort = null, webSearch = true, streamOptions = false, model = "gpt-4o"
        )
        val msgs = p.getJSONArray("messages")
        val assistant = msgs.getJSONObject(1)
        assertEquals("assistant", assistant.getString("role"))
        val tc = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", tc.getString("id"))
        assertEquals("function", tc.getString("type"))
        assertEquals("web_search", tc.getJSONObject("function").getString("name"))
        assertEquals("""{"query":"btc"}""", tc.getJSONObject("function").getString("arguments"))
        val toolMsg = msgs.getJSONObject(2)
        assertEquals("tool", toolMsg.getString("role"))
        assertEquals("call_1", toolMsg.getString("tool_call_id"))
        assertEquals("""{"items":[]}""", toolMsg.getString("content"))
    }

    @Test
    fun `citation hint injected as system message when requested`() {
        val p = RequestPayloads.openAiPayload(
            history = listOf(ChatRequestMessage("user", "hi")),
            reasoningEffort = null, webSearch = true, streamOptions = false, model = "gpt-4o",
            citationHint = true
        )
        val first = p.getJSONArray("messages").getJSONObject(0)
        assertEquals("system", first.getString("role"))
        assertTrue(first.getString("content").contains("[cite:"))
    }

    @Test
    fun `citation hint not duplicated when history already has system message`() {
        val p = RequestPayloads.openAiPayload(
            history = listOf(
                ChatRequestMessage("system", "you are helpful"),
                ChatRequestMessage("user", "hi"),
            ),
            reasoningEffort = null, webSearch = true, streamOptions = false, model = "gpt-4o",
            citationHint = true
        )
        val msgs = p.getJSONArray("messages")
        var systems = 0
        for (i in 0 until msgs.length()) {
            if (msgs.getJSONObject(i).getString("role") == "system") systems++
        }
        assertEquals(1, systems)
    }

    @Test
    fun `anthropic declares input_schema tool`() {
        val p = RequestPayloads.anthropicPayload(
            model = "claude-x",
            history = listOf(ChatRequestMessage("user", "hi")),
            reasoningEffort = null, webSearch = true
        )
        val tool = p.getJSONArray("tools").getJSONObject(0)
        assertEquals("web_search", tool.getString("name"))
        assertTrue(tool.has("input_schema"))
        assertTrue(!tool.has("type"))
    }

    @Test
    fun `anthropic serializes tool_use and tool_result blocks`() {
        val call = ToolCall("toolu_1", "web_search", """{"query":"btc"}""", "btc")
        val p = RequestPayloads.anthropicPayload(
            model = "claude-x",
            history = listOf(
                ChatRequestMessage("user", "price?"),
                ChatRequestMessage("assistant", "let me search", toolCalls = listOf(call)),
                ChatRequestMessage("tool", """{"items":[]}""", toolCallId = "toolu_1"),
            ),
            reasoningEffort = null, webSearch = true
        )
        val msgs = p.getJSONArray("messages")
        val assistant = msgs.getJSONObject(1)
        assertEquals("assistant", assistant.getString("role"))
        val blocks = assistant.getJSONArray("content")
        assertEquals(2, blocks.length())
        assertEquals("text", blocks.getJSONObject(0).getString("type"))
        val use = blocks.getJSONObject(1)
        assertEquals("tool_use", use.getString("type"))
        assertEquals("toolu_1", use.getString("id"))
        assertEquals("web_search", use.getString("name"))
        assertEquals("btc", use.getJSONObject("input").getString("query"))
        val resultMsg = msgs.getJSONObject(2)
        assertEquals("user", resultMsg.getString("role"))
        val resultBlock = resultMsg.getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", resultBlock.getString("type"))
        assertEquals("toolu_1", resultBlock.getString("tool_use_id"))
    }
}
