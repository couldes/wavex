package com.wavex.agent.network

import com.wavex.agent.data.Provider
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

    // ---------- anthropicFallbackModels ----------

    private fun providerAt(baseUrl: String) =
        Provider(name = "t", baseUrl = baseUrl, apiKey = "", model = "")

    @Test
    fun `deepseek anthropic fallback lists current official models`() {
        // 2026-02 官方定价页：现行模型只有 deepseek-flash 与 deepseek-v4-pro
        assertEquals(
            listOf("deepseek-flash", "deepseek-v4-pro"),
            ApiClient.anthropicFallbackModels(providerAt("https://api.deepseek.com/anthropic"), anthropic = true)
        )
    }

    @Test
    fun `non anthropic base url gets no fallback`() {
        assertNull(ApiClient.anthropicFallbackModels(providerAt("https://api.deepseek.com/v1"), anthropic = false))
    }

    @Test
    fun `non deepseek anthropic gateway falls back to claude lineup`() {
        val models = ApiClient.anthropicFallbackModels(providerAt("https://relay.example.com/anthropic"), anthropic = true)
        assertTrue(models.orEmpty().all { it.startsWith("claude-") })
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

    // ---------- redactSecrets：错误文本里的密钥打码 ----------

    @Test
    fun `secret occurrences replaced everywhere`() {
        assertEquals(
            "key [REDACTED] middle [REDACTED] end",
            redactSecrets("key sk-secret middle sk-secret end", "sk-secret")
        )
    }

    @Test
    fun `multiple secrets all masked`() {
        assertEquals(
            "[REDACTED] and [REDACTED]",
            redactSecrets("aaa and bbb", "aaa", "bbb")
        )
    }

    @Test
    fun `blank secrets leave text untouched`() {
        assertEquals("keep me", redactSecrets("keep me", "", "  "))
    }

    @Test
    fun `formatted api error with echoed key gets masked`() {
        val body = """{"error":{"message":"invalid key sk-secret-123","code":"authentication_error"}}"""
        assertEquals(
            "HTTP 401：[authentication_error] invalid key [REDACTED]",
            redactSecrets(formatApiError(401, body), "sk-secret-123")
        )
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

    @Test
    fun `gemini models use the same openai web_search tool (googleSearch rejected by relay)`() {
        // 钉住被否决的假设：曾认为 gemini 要发原生 googleSearch，实测 pop 网关反而
        // 不搜（用户实测 web_search 能真搜到天气）。网关不支持时只能重试/换渠道。
        val gemini = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "hi")), null, true
        )
        assertEquals("web_search", gemini.getJSONArray("tools").getJSONObject(0).getString("type"))
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

    // ---------- usage 埋点（Task 5: OpenAI 流式） ----------

    @Test
    fun `openai 流式 payload 注入 include_usage`() {
        val p = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "hi")), null, false,
            streamOptions = true
        )
        assertEquals(true, p.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    @Test
    fun `openai 非流式不注入`() {
        val p = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "hi")), null, false,
            streamOptions = false
        )
        assertFalse(p.has("stream_options"))
    }

    @Test
    fun `openai usage 末尾 chunk 被捕获`() {
        val usage = StreamUsage()
        val done = ApiClient.parseOpenAiData(
            """{"choices":[],"usage":{"prompt_tokens":123,"completion_tokens":45}}""",
            { _, _ -> }, usage
        )
        assertFalse(done)
        assertEquals(123L, usage.inputTokens)
        assertEquals(45L, usage.outputTokens)
    }

    @Test
    fun `openai 畸形 usage 记 0 不崩`() {
        val usage = StreamUsage()
        // 字符串数字 / null 字段：optLong 容错记 0
        ApiClient.parseOpenAiData(
            """{"choices":[],"usage":{"prompt_tokens":"abc","completion_tokens":null}}""",
            { _, _ -> }, usage
        )
        assertEquals(0L, usage.inputTokens)
        assertEquals(0L, usage.outputTokens)
    }

    @Test
    fun `错误体含 stream_options 判定降级`() {
        assertTrue(ApiClient.shouldDropStreamOptions("""{"error":{"code":"unsupported_parameter","message":"stream_options is not supported"}}"""))
        assertFalse(ApiClient.shouldDropStreamOptions("""{"error":{"message":"Incorrect API key"}}"""))
    }

    // ---------- usage 埋点（Task 6: Anthropic 流式） ----------

    @Test
    fun `anthropic 两个事件的 usage 合并捕获`() {
        val usage = StreamUsage()
        assertNull(ApiClient.parseAnthropicData(
            """{"type":"message_start","message":{"usage":{"input_tokens":100}}}""",
            { _, _ -> }, usage
        ))
        assertNull(ApiClient.parseAnthropicData(
            """{"type":"message_delta","usage":{"output_tokens":37}}""",
            { _, _ -> }, usage
        ))
        assertEquals(100L, usage.inputTokens)
        assertEquals(37L, usage.outputTokens)
    }

    @Test
    fun `anthropic 畸形 usage 记 0 不崩`() {
        val usage = StreamUsage()
        ApiClient.parseAnthropicData(
            """{"type":"message_start","message":{"usage":{"input_tokens":"x"}}}""",
            { _, _ -> }, usage
        )
        assertEquals(0L, usage.inputTokens)
        assertEquals(0L, usage.outputTokens)
    }

    @Test
    fun `anthropic 多次 message_delta 取最大值`() {
        val usage = StreamUsage()
        ApiClient.parseAnthropicData(
            """{"type":"message_delta","usage":{"output_tokens":20}}""", { _, _ -> }, usage
        )
        ApiClient.parseAnthropicData(
            """{"type":"message_delta","usage":{"output_tokens":37}}""", { _, _ -> }, usage
        )
        assertEquals(37L, usage.outputTokens)
    }

    // ---------- usage 埋点（Task 7: 非流式 usage 提取） ----------

    @Test
    fun `非流式 usage 两种协议提取`() {
        val openai = ApiClient.parseNonStreamingUsage(
            JSONObject("""{"usage":{"prompt_tokens":10,"completion_tokens":5}}"""), ApiProtocol.OPENAI
        )
        assertEquals(10L, openai.inputTokens); assertEquals(5L, openai.outputTokens)

        val anthro = ApiClient.parseNonStreamingUsage(
            JSONObject("""{"usage":{"input_tokens":7,"output_tokens":3}}"""), ApiProtocol.ANTHROPIC
        )
        assertEquals(7L, anthro.inputTokens); assertEquals(3L, anthro.outputTokens)
    }

    @Test
    fun `非流式 畸形 usage 记 0`() {
        val u = ApiClient.parseNonStreamingUsage(JSONObject("""{"usage":null}"""), ApiProtocol.OPENAI)
        assertEquals(0L, u.inputTokens); assertEquals(0L, u.outputTokens)
    }
}
