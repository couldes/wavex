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

    // ---------- 思考档位：开/关式家族映射（去极低、补极高） ----------

    @Test
    fun `openai effort style model sends xhigh as reasoning_effort`() {
        val p = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "hi")), "xhigh", false, model = "gpt-5.2"
        )
        assertEquals("xhigh", p.getString("reasoning_effort"))
        assertFalse(p.has("thinking"))
        assertFalse(p.has("enable_thinking"))
    }

    @Test
    fun `glm model with level sends thinking enabled instead of effort`() {
        val p = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "hi")), "high", false, model = "glm-4.6"
        )
        assertEquals("enabled", p.getJSONObject("thinking").getString("type"))
        assertFalse(p.has("reasoning_effort"))
    }

    @Test
    fun `qwen model with level sends enable_thinking`() {
        val p = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "hi")), "low", false, model = "qwen3-max"
        )
        assertEquals(true, p.getBoolean("enable_thinking"))
        assertFalse(p.has("reasoning_effort"))
    }

    @Test
    fun `kimi model with level sends enable_thinking`() {
        val p = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "hi")), "medium", false, model = "kimi-k2-0905-preview"
        )
        assertEquals(true, p.getBoolean("enable_thinking"))
        assertFalse(p.has("reasoning_effort"))
    }

    @Test
    fun `on off family model id match is case insensitive`() {
        val p = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "hi")), "high", false, model = "GLM-5"
        )
        assertEquals("enabled", p.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun `on off family with default level sends nothing`() {
        val p = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "hi")), null, false, model = "qwen3-max"
        )
        assertFalse(p.has("enable_thinking"))
        assertFalse(p.has("thinking"))
        assertFalse(p.has("reasoning_effort"))
    }

    @Test
    fun `deepseek never gets effort or thinking params`() {
        // DeepSeek 官方 API 无思考开关参数：思考由模型名决定（deepseek-reasoner），
        // 发档位/开关参数会被拒或被静默忽略，干脆不发。
        val p = ApiClient.openAiPayload(
            listOf(ChatRequestMessage(role = "user", text = "hi")), "high", false, model = "deepseek-chat"
        )
        assertFalse(p.has("reasoning_effort"))
        assertFalse(p.has("enable_thinking"))
        assertFalse(p.has("thinking"))
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
    fun `anthropic xhigh maps to 16384 budget`() {
        val p = anthro(history = listOf(ChatRequestMessage(role = "user", text = "hi")), effort = "xhigh", web = false)
        assertTrue(p.has("thinking"))
        assertEquals(16384L, p.getJSONObject("thinking").getLong("budget_tokens"))
        assertEquals(16384L + 4096L, p.getLong("max_tokens"))
    }

    @Test
    fun `anthropic minimal no longer maps to thinking`() {
        // minimal（极低）已从档位表移除：仅初代 gpt-5 支持，gpt-5.1 起全部不支持。
        // 旧存值在读取层归一化为 low，payload 层遇未知值一律不发 thinking。
        val p = anthro(history = listOf(ChatRequestMessage(role = "user", text = "hi")), effort = "minimal", web = false)
        assertFalse(p.has("thinking"))
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

    // ---------- delta.images 结构化图片（模型返回附件） ----------

    @Test
    fun `openai delta images forwarded to onImage`() {
        val urls = mutableListOf<String>()
        val data = """{"choices":[{"delta":{"images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AAA"}}]}}]}"""
        ApiClient.parseOpenAiData(data, { _, _ -> }, onImage = { urls.add(it) })
        assertEquals(listOf("data:image/png;base64,AAA"), urls)
    }

    @Test
    fun `multiple delta images forwarded in order`() {
        val urls = mutableListOf<String>()
        val data = """{"choices":[{"delta":{"images":[
            {"type":"image_url","image_url":{"url":"data:image/png;base64,A1"}},
            {"type":"image_url","image_url":{"url":"https://x/y.png"}}]}}]}"""
        ApiClient.parseOpenAiData(data, { _, _ -> }, onImage = { urls.add(it) })
        assertEquals(listOf("data:image/png;base64,A1", "https://x/y.png"), urls)
    }

    @Test
    fun `missing or empty or null image url not forwarded`() {
        val urls = mutableListOf<String>()
        ApiClient.parseOpenAiData("""{"choices":[{"delta":{"content":"hi"}}]}""", { _, _ -> }, onImage = { urls.add(it) })
        ApiClient.parseOpenAiData("""{"choices":[{"delta":{"images":[{"type":"image_url","image_url":{"url":""}}]}}]}""", { _, _ -> }, onImage = { urls.add(it) })
        ApiClient.parseOpenAiData("""{"choices":[{"delta":{"images":[{"type":"image_url","image_url":{"url":null}}]}}]}""", { _, _ -> }, onImage = { urls.add(it) })
        ApiClient.parseOpenAiData("""{"choices":[{"delta":{"images":[]}}]}""", { _, _ -> }, onImage = { urls.add(it) })
        assertEquals(0, urls.size)
    }

    @Test
    fun `openai delta images url-keyed element forwarded`() {
        // 中转站变体形状：元素直接挂 url 键（chunkShape 已识别此形状，提取层之前静默丢弃）
        val urls = mutableListOf<String>()
        val data = """{"choices":[{"delta":{"images":[{"url":"data:image/png;base64,AAA"}]}}]}"""
        ApiClient.parseOpenAiData(data, { _, _ -> }, onImage = { urls.add(it) })
        assertEquals(listOf("data:image/png;base64,AAA"), urls)
    }

    @Test
    fun `openai delta images plain string element forwarded`() {
        // 中转站变体形状：元素就是 URL 字符串本身
        val urls = mutableListOf<String>()
        val data = """{"choices":[{"delta":{"images":["data:image/png;base64,AAA","https://x/y.png"]}}]}"""
        ApiClient.parseOpenAiData(data, { _, _ -> }, onImage = { urls.add(it) })
        assertEquals(listOf("data:image/png;base64,AAA", "https://x/y.png"), urls)
    }

    @Test
    fun `openai delta images mixed shapes forwarded and junk dropped`() {
        // 三种形状混排按序转发；无 url 的对象/非字符串非对象元素/JSON null 丢弃
        val urls = mutableListOf<String>()
        val data = """{"choices":[{"delta":{"images":[
            {"type":"image_url","image_url":{"url":"data:image/png;base64,A1"}},
            {"url":"https://x/2.png"},
            "data:image/png;base64,A3",
            {"type":"image_url"},
            42,
            null]}}]}"""
        ApiClient.parseOpenAiData(data, { _, _ -> }, onImage = { urls.add(it) })
        assertEquals(listOf("data:image/png;base64,A1", "https://x/2.png", "data:image/png;base64,A3"), urls)
    }

    @Test
    fun `anthropic stream tolerates images field without error`() {
        // 协议隔离：Anthropic 分支不接 onImage（streamChat 布线保证），额外 JSON 字段不影响解析
        val err = ApiClient.parseAnthropicData("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"a","images":[{"image_url":{"url":"https://x"}}]}}""", { _, _ -> })
        assertNull(err)
    }

    // ---------- 流内报错 chunk 上抛（gemini 发附件返回空内容的根因修复） ----------

    @Test
    fun `openai in-stream error chunk surfaces message`() {
        // 中转站/上游在 SSE 里直接回 error 对象：旧实现静默吞掉，
        // 流「正常」结束后上层只能显示误导性的「模型没有返回内容」
        val e = runCatching {
            ApiClient.parseOpenAiData(
                """{"error":{"message":"Image generation is not enabled for this channel","type":"server_error"}}""",
                { _, _ -> })
        }.exceptionOrNull()
        assertTrue("应抛 ApiException", e is ApiException)
        assertEquals("Image generation is not enabled for this channel", e?.message)
    }

    @Test
    fun `openai error chunk alongside choices also surfaces`() {
        val e = runCatching {
            ApiClient.parseOpenAiData(
                """{"choices":[{"delta":{}}],"error":{"message":"upstream failed"}}""",
                { _, _ -> })
        }.exceptionOrNull()
        assertEquals("upstream failed", (e as? ApiException)?.message)
    }

    @Test
    fun `openai error chunk without message gets fallback text`() {
        // message 缺失/JSON null：与 parseAnthropicData 同款兑底，不吞错也不抛原始报文
        val e = runCatching {
            ApiClient.parseOpenAiData("""{"error":{"type":"server_error"}}""", { _, _ -> })
        }.exceptionOrNull()
        assertEquals("服务返回错误", (e as? ApiException)?.message)
    }

    @Test
    fun `openai delta images url is trimmed`() {
        // 部分网关 URL 带首尾空白：旧实现原样回调，structuredToAttachment matchEntire 拒收、图片静默丢失
        val urls = mutableListOf<String>()
        ApiClient.parseOpenAiData(
            """{"choices":[{"delta":{"images":[{"type":"image_url","image_url":{"url":" data:image/png;base64,AAAAAAAA "}}]}}]}""",
            { _, _ -> }, onImage = { urls.add(it) })
        assertEquals(listOf("data:image/png;base64,AAAAAAAA"), urls)
    }

    // ---------- chunkShape 分片形状指纹（空流诊断，无隐私内容） ----------

    @Test
    fun `chunk shape message aggregate with images`() {
        // 中转站无视 stream:true，一次性回完整 message（非 delta）
        assertEquals(
            "message+images[image_url.url]",
            ApiClient.chunkShape("""{"choices":[{"message":{"role":"assistant","content":"","images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AA"}}]}}]}""")
        )
    }

    @Test
    fun `chunk shape native gemini candidates`() {
        // 网关未翻译直接透传 Gemini 原生流形状
        assertEquals(
            "candidates/[inlineData]",
            ApiClient.chunkShape("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"AA"}}]}}],"usageMetadata":{}}""")
        )
    }

    @Test
    fun `chunk shape delta with url-keyed images`() {
        assertEquals(
            "delta+images[url]",
            ApiClient.chunkShape("""{"choices":[{"delta":{"images":[{"url":"x"}]}}]}""")
        )
    }

    @Test
    fun `chunk shape images as plain string array`() {
        assertEquals(
            "delta+images[字符串元素]",
            ApiClient.chunkShape("""{"choices":[{"delta":{"images":["data:image/png;base64,AA"]}}]}""")
        )
    }

    @Test
    fun `chunk shape delta with array content`() {
        assertEquals(
            "delta+content=数组",
            ApiClient.chunkShape("""{"choices":[{"delta":{"content":[{"type":"text","text":"hi"}]}}]}""")
        )
    }

    @Test
    fun `chunk shape non json line`() {
        assertEquals("非JSON(len=5)", ApiClient.chunkShape("hello"))
    }

    // ---------- streamFailureText 诊断细节追加 ----------

    @Test
    fun `stream failure text appends empty-content detail`() {
        val text = streamFailureText(
            StreamErrorCode(StreamErrorKind.EmptyContent, "网关返回了 3 个数据分片"),
            web = false, effort = null
        )
        assertEquals("服务器没有返回任何消息内容（网关返回了 3 个数据分片）", text)
    }

    @Test
    fun `stream failure text unchanged when empty-content detail blank`() {
        assertEquals(
            "服务器没有返回任何消息内容",
            streamFailureText(StreamErrorCode(StreamErrorKind.EmptyContent, ""), web = false, effort = null)
        )
    }

    // ---------- 工具调用分片识别（网关把 function-calling 交回客户端的场景） ----------

    @Test
    fun `openai tool_calls delta surfaces function name`() {
        val names = mutableListOf<String>()
        val (c, r) = collect { api, cb ->
            assertEquals(
                false,
                api.parseOpenAiData(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"create_file","arguments":""}}]}}]}""",
                    cb,
                    onToolCall = { names.add(it) }
                )
            )
        }
        assertEquals("", c.toString()); assertEquals("", r.toString())
        assertEquals(listOf("create_file"), names)
    }

    @Test
    fun `openai tool_calls arguments-only fragment has no name`() {
        val names = mutableListOf<String>()
        collect { api, cb ->
            api.parseOpenAiData(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]}}]}""",
                cb,
                onToolCall = { names.add(it) }
            )
        }
        assertEquals(emptyList<String>(), names)
    }

    @Test
    fun `anthropic tool_use block surfaces function name`() {
        val names = mutableListOf<String>()
        ApiClient.parseAnthropicData(
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"web_search"}}""",
            { _, _ -> },
            onToolCall = { names.add(it) }
        )
        assertEquals(listOf("web_search"), names)
    }

    @Test
    fun `stream failure text for tool call names the tool`() {
        val text = streamFailureText(
            StreamErrorCode(StreamErrorKind.ToolCall, "create_file"),
            web = false, effort = null
        )
        assertTrue(text.contains("create_file"))
        assertTrue(text.contains("工具"))
    }

}