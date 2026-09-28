package com.wavex.agent.network

import com.wavex.agent.model.ApiProtocol
import com.wavex.agent.model.ChatRequestMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行为钉子：协议识别、错误格式化、双协议 payload 组装、SSE 增量解析。
 * 这些是重构安全网——测试失败即重构破坏了既有行为。
 */
class ApiClientTest {

    // ---------- detectProtocol ----------

    @Test
    fun `deepseek anthropic gateway detected`() {
        assertEquals(ApiProtocol.ANTHROPIC, detectProtocol("https://api.deepseek.com/anthropic"))
    }

    @Test
    fun `anthropic official host detected`() {
        assertEquals(ApiProtocol.ANTHROPIC, detectProtocol("https://api.anthropic.com/v1"))
    }

    @Test
    fun `www prefix stripped before host match`() {
        assertEquals(ApiProtocol.ANTHROPIC, detectProtocol("https://www.anthropic.com/v1/messages"))
    }

    @Test
    fun `path segment anthropic detected`() {
        assertEquals(ApiProtocol.ANTHROPIC, detectProtocol("https://gateway.example.com/anthropic/v1"))
    }

    @Test
    fun `no scheme falls back to openai`() {
        assertEquals(ApiProtocol.OPENAI, detectProtocol("api.example.com/v1"))
    }

    @Test
    fun `mixed case still detected`() {
        assertEquals(ApiProtocol.ANTHROPIC, detectProtocol("https://API.DeepSeek.com/Anthropic"))
    }

    @Test
    fun `ordinary openai base url`() {
        assertEquals(ApiProtocol.OPENAI, detectProtocol("https://api.openai.com/v1"))
    }

    // ---------- formatApiError ----------

    @Test
    fun `json error message extracted`() {
        val body = """{"error":{"message":"Rate limit reached"}}"""
        assertEquals("HTTP 429：Rate limit reached", formatApiError(429, body))
    }

    @Test
    fun `error code preserved as prefix`() {
        val body = """{"error":{"message":"bad param","code":"unsupported_parameter"}}"""
        assertEquals("HTTP 400：[unsupported_parameter] bad param", formatApiError(400, body))
    }

    @Test
    fun `html error page reported plainly`() {
        val body = "<html><body>502 Bad Gateway</body></html>"
        val msg = formatApiError(502, body)
        assertTrue(msg.contains("服务返回了网页"))
        assertTrue(msg.startsWith("HTTP 502"))
    }

    @Test
    fun `blank body gives bare code`() {
        assertEquals("HTTP 500", formatApiError(500, "   "))
    }

    @Test
    fun `plain text truncated to 200 chars`() {
        val long = "x".repeat(500)
        val msg = formatApiError(500, long)
        assertEquals("HTTP 500：${"x".repeat(200)}", msg)
    }

    // ---------- openAiPayload ----------

    @Test
    fun `openai text only message keeps plain content`() {
        val p = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "你好")), null, false
        )
        val msg = p.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", msg.getString("role"))
        assertEquals("你好", msg.getString("content"))
        assertEquals(true, p.getBoolean("stream"))
        assertFalse(p.has("reasoning_effort"))
        assertFalse(p.has("tools"))
    }

    @Test
    fun `openai image message uses image_url part`() {
        val p = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "看图", imageDataUrls = listOf("data:image/jpeg;base64,QUJD"))),
            null, false
        )
        val parts = p.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,QUJD", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun `openai audio becomes input_audio`() {
        val p = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "听", imageDataUrls = listOf("x-audio:mp3|QUJD"))),
            null, false
        )
        val part = p.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1)
        assertEquals("input_audio", part.getString("type"))
        assertEquals("QUJD", part.getJSONObject("input_audio").getString("data"))
        assertEquals("mp3", part.getJSONObject("input_audio").getString("format"))
    }

    @Test
    fun `openai pdf becomes file part`() {
        val p = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "读", imageDataUrls = listOf("x-pdf:QUJD"))),
            null, false
        )
        val part = p.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1)
        assertEquals("file", part.getString("type"))
        assertEquals("data:application/pdf;base64,QUJD", part.getJSONObject("file").getString("file_data"))
    }

    @Test
    fun `openai reasoning effort only when provided`() {
        val with = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "hi")), "high", false
        )
        assertEquals("high", with.getString("reasoning_effort"))
        val without = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "hi")), null, false
        )
        assertFalse(without.has("reasoning_effort"))
    }

    @Test
    fun `openai web search tool declared only when enabled`() {
        val on = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "hi")), null, true
        )
        assertEquals("web_search", on.getJSONArray("tools").getJSONObject(0).getString("type"))
        val off = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "hi")), null, false
        )
        assertFalse(off.has("tools"))
    }

    // ---------- anthropicPayload ----------

    private fun anthro(model: String = "claude-x", history: List<ChatRequestMessage>, effort: String?, web: Boolean) =
        ApiClient.anthropicPayload(model, history, effort, web)

    @Test
    fun `anthropic model and max_tokens present`() {
        val p = anthro(history = listOf(ChatRequestMessage(role = "user", text = "你好")), effort = null, web = false)
        assertEquals("claude-x", p.getString("model"))
        assertEquals(8192L, p.getLong("max_tokens"))
        assertFalse(p.has("thinking"))
    }

    @Test
    fun `anthropic image becomes base64 source with media type`() {
        val p = anthro(history = listOf(
            ChatRequestMessage(role = "user", text = "看", imageDataUrls = listOf("data:image/png;base64,QUJD"))
        ), effort = null, web = false)
        val part = p.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1)
        assertEquals("image", part.getString("type"))
        val src = part.getJSONObject("source")
        assertEquals("base64", src.getString("type"))
        assertEquals("image/png", src.getString("media_type"))
        assertEquals("QUJD", src.getString("data"))
    }

    @Test
    fun `anthropic audio replaced by placeholder text`() {
        val p = anthro(history = listOf(
            ChatRequestMessage(role = "user", text = "", imageDataUrls = listOf("x-audio:mp3|QUJD"))
        ), effort = null, web = false)
        val parts = p.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(1, parts.length())
        assertEquals("（语音附件）", parts.getJSONObject(0).getString("text"))
    }

    @Test
    fun `anthropic pdf becomes document source`() {
        val p = anthro(history = listOf(
            ChatRequestMessage(role = "user", text = "读", imageDataUrls = listOf("x-pdf:QUJD"))
        ), effort = null, web = false)
        val part = p.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1)
        assertEquals("document", part.getString("type"))
        assertEquals("application/pdf", part.getJSONObject("source").getString("media_type"))
        assertEquals("QUJD", part.getJSONObject("source").getString("data"))
    }

    @Test
    fun `anthropic thinking budget mapped and max_tokens raised`() {
        val p = anthro(history = listOf(ChatRequestMessage(role = "user", text = "hi")), effort = "low", web = false)
        val thinking = p.getJSONObject("thinking")
        assertEquals("enabled", thinking.getString("type"))
        assertEquals(2048L, thinking.getLong("budget_tokens"))
        assertEquals(2048L + 4096L, p.getLong("max_tokens"))
    }

    @Test
    fun `anthropic web search tool with max uses`() {
        val p = anthro(history = listOf(ChatRequestMessage(role = "user", text = "hi")), effort = null, web = true)
        val tool = p.getJSONArray("tools").getJSONObject(0)
        assertEquals("web_search_20250305", tool.getString("type"))
        assertEquals(5, tool.getInt("max_uses"))
    }

    // ---------- parseOpenAiData ----------

    private fun collect(block: (ApiClient, (String, String) -> Unit) -> Any): Pair<StringBuilder, StringBuilder> {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        block(ApiClient) { c, r ->
            content.append(c); reasoning.append(r)
        }
        return content to reasoning
    }

    @Test
    fun `openai done sentinel ends stream`() {
        val (c, r) = collect { api, cb -> assertEquals(true, api.parseOpenAiData("[DONE]", cb)) }
        assertEquals("", c.toString()); assertEquals("", r.toString())
    }

    @Test
    fun `openai chinese delta delivered`() {
        val (c, r) = collect { api, cb ->
            assertEquals(false, api.parseOpenAiData("""{"choices":[{"delta":{"content":"你好，世界"}}]}""", cb))
        }
        assertEquals("你好，世界", c.toString())
        assertEquals("", r.toString())
    }

    @Test
    fun `openai reasoning_content routed to reasoning channel`() {
        val (c, r) = collect { api, cb ->
            api.parseOpenAiData("""{"choices":[{"delta":{"reasoning_content":"思考中"}}]}""", cb)
        }
        assertEquals("", c.toString())
        assertEquals("思考中", r.toString())
    }

    @Test
    fun `openai legacy reasoning field as fallback`() {
        val (c, r) = collect { api, cb ->
            api.parseOpenAiData("""{"choices":[{"delta":{"reasoning":"想"}}]}""", cb)
        }
        assertEquals("想", r.toString())
    }

    @Test
    fun `openai literal null content ignored`() {
        val (c, r) = collect { api, cb ->
            assertEquals(false, api.parseOpenAiData("""{"choices":[{"delta":{"content":null}}]}""", cb))
        }
        assertEquals("", c.toString()); assertEquals("", r.toString())
    }

    @Test
    fun `openai keepalive garbage ignored`() {
        val (c, r) = collect { api, cb ->
            assertEquals(false, api.parseOpenAiData(": keep-alive", cb))
        }
        assertEquals("", c.toString()); assertEquals("", r.toString())
    }

    @Test
    fun `openai empty line ignored`() {
        val (c, r) = collect { api, cb -> assertEquals(false, api.parseOpenAiData("", cb)) }
        assertEquals("", c.toString()); assertEquals("", r.toString())
    }

    // ---------- parseAnthropicData ----------

    @Test
    fun `anthropic text_delta routed to content`() {
        val (c, r) = collect { api, cb ->
            assertNull(api.parseAnthropicData(
                """{"type":"content_block_delta","delta":{"type":"text_delta","text":"早上好"}}""", cb))
        }
        assertEquals("早上好", c.toString())
        assertEquals("", r.toString())
    }

    @Test
    fun `anthropic thinking_delta routed to reasoning`() {
        val (c, r) = collect { api, cb ->
            assertNull(api.parseAnthropicData(
                """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"推理"}}""", cb))
        }
        assertEquals("", c.toString())
        assertEquals("推理", r.toString())
    }

    @Test
    fun `anthropic error event returns message`() {
        val (c, r) = collect { api, cb ->
            val err = api.parseAnthropicData(
                """{"type":"error","error":{"message":"overloaded"}}""", cb)
            assertEquals("overloaded", err)
        }
        assertEquals("", c.toString()); assertEquals("", r.toString())
    }

    @Test
    fun `anthropic other events ignored`() {
        val (c, r) = collect { api, cb ->
            assertNull(api.parseAnthropicData("""{"type":"message_stop"}""", cb))
        assertNull(api.parseAnthropicData("", cb))
        }
        assertEquals("", c.toString()); assertEquals("", r.toString())
    }
}
