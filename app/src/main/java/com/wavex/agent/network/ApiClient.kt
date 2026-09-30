package com.wavex.agent.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import com.wavex.agent.data.Provider
import com.wavex.agent.model.ApiProtocol
import com.wavex.agent.model.ChatRequestMessage

/**
 * 按 Base URL 自动识别协议——用户不需要选，填地址即可：
 * - 域名以 anthropic.com 结尾，或路径中出现 /anthropic 段（如 DeepSeek 官方
 *   Anthropic 网关 https://api.deepseek.com/anthropic）→ Anthropic 协议；
 * - 其余一律按 OpenAI 兼容协议。
 * 协议只决定路径叶子与默认鉴权方言；实际鉴权方言在拿到 401/403 时会自动换一种重发。
 */
internal fun detectProtocol(baseUrl: String): ApiProtocol {
    // 先归一化：用户漏写协议头、或粘贴的是完整 …/anthropic/v1/messages 时也要认得出 Anthropic
    val lower = Connection.canonicalBase(baseUrl).ifBlank { baseUrl.trim() }.lowercase()
    if (!lower.startsWith("http")) return ApiProtocol.OPENAI
    val noScheme = lower.substringAfter("://")
    val host = noScheme.substringBefore('/').removePrefix("www.")
    val path = noScheme.substringAfter('/', "")
    val isAnthropic = host.endsWith("anthropic.com") || path.split('/').any { it == "anthropic" }
    return if (isAnthropic) ApiProtocol.ANTHROPIC else ApiProtocol.OPENAI
}

/**
 * 把 HTTP 错误响应整理成一句人话：优先取 JSON 里的 message 字段（OpenAI/Anthropic 都是
 * error.message 形状），HTML 错误页给明确提示，避免满屏报文刷屏。
 */
fun formatApiError(httpCode: Int, body: String): String {
    val trimmed = body.trim()
    if (trimmed.startsWith("{")) {
        try {
            val o = JSONObject(trimmed)
            val errObj = o.optJSONObject("error")
            val detail = errObj?.optString("message", "")?.takeIf { it.isNotBlank() }
                ?: o.optString("message", "").takeIf { it.isNotBlank() }
                ?: o.optString("detail", "").takeIf { it.isNotBlank() }
            if (detail != null) {
                // 保留错误码/类型：上层降级重试逻辑依赖其中的关键词（unsupported_parameter 等）
                val code = listOf(
                    errObj?.optString("code", ""),
                    errObj?.optString("type", ""),
                    o.optString("code", "")
                ).firstOrNull { !it.isNullOrBlank() && it != "null" }
                return "HTTP $httpCode：" + (if (code != null) "[$code] " else "") + detail.take(200)
            }
        } catch (_: Exception) {
        }
    }
    if (trimmed.startsWith("<")) {
        return "HTTP $httpCode：服务返回了网页而不是接口数据（Base URL 可能填错了）"
    }
    return if (trimmed.isBlank()) "HTTP $httpCode" else "HTTP $httpCode：${trimmed.take(200)}"
}

/**
 * OpenAI 兼容 API 客户端：/chat/completions（SSE 流式）与 /models；
 * Base URL 指向 Anthropic 时自动改走 /v1/messages（x-api-key 认证）。
 * 统一在这里处理 URL 规范化与协议适配，用户不需要理解 endpoint path。
 */
internal object ApiClient : com.wavex.agent.engine.ChatApi {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** Base URL 规范化与端点拼接见 Connection（纯函数、可单测）。 */

    /**
     * 网关实际接受的鉴权方言，按「服务商+地址」记在进程内。
     * 落盘没必要：协议判断本身是地址字符串的纯函数，重算是纳秒级；这里记的只是
     * 探测产生的一次额外 401 往返，重启后最多再付一次。
     */
    private val dialectByProvider = ConcurrentHashMap<String, AuthDialect>()

    private fun dialectKey(provider: Provider) = "${provider.id}|${provider.baseUrl.trim()}"

    private fun dialectFor(provider: Provider, protocol: ApiProtocol): AuthDialect =
        dialectByProvider[dialectKey(provider)] ?: Connection.defaultDialect(protocol)

    /**
     * 非流式请求的统一出口：首选方言拿到 401/403 时换另一种再试一次，
     * 只要不再是鉴权拒绝就记住生效的方言。返回的 Response 由调用方关闭。
     */
    private fun executeWithAuthFallback(
        provider: Provider,
        url: String,
        body: String?
    ): Pair<okhttp3.Response, AuthDialect> {
        val protocol = detectProtocol(provider.baseUrl)
        val first = dialectFor(provider, protocol)
        val attempt = send(provider, url, body, protocol, first)
        if (attempt.isSuccessful || !isAuthRejected(attempt.code)) return attempt to first
        attempt.close()

        val alt = Connection.otherDialect(first)
        val retry = send(provider, url, body, protocol, alt)
        if (retry.isSuccessful || !isAuthRejected(retry.code)) {
            dialectByProvider[dialectKey(provider)] = alt
        }
        return retry to alt
    }

    private fun isAuthRejected(code: Int) = code == 401 || code == 403

    private fun send(
        provider: Provider,
        url: String,
        body: String?,
        protocol: ApiProtocol,
        dialect: AuthDialect
    ): okhttp3.Response {
        val builder = Connection.applyAuth(Request.Builder().url(url), provider.apiKey, protocol, dialect)
        val request = if (body == null) builder.build()
        else builder.post(body.toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute()
    }

    /**
     * 自动命名（ChatGPT/Gemini 式）：用当前模型给首轮问答生成简短标题。
     * 非流式请求；任何失败都返回 null（调用方回退为首条用户消息截断）。
     * Anthropic 协议服务商用 /v1/messages（非流式）实现同等效果。
     */
    suspend fun generateTitle(provider: Provider, userText: String, assistantText: String): String? = withContext(Dispatchers.IO) {
        if (provider.baseUrl.isBlank() || provider.apiKey.isBlank()) return@withContext null
        try {
            val content = buildString {
                append("用户：").append(userText.take(600).ifBlank { "（图片/附件）" })
                if (assistantText.isNotBlank()) {
                    append("\n助手：").append(assistantText.take(600))
                }
            }
            val titlePrompt =
                "为下面的对话生成一个简短标题。要求：直接输出标题本身；不超过14个字；不加引号、不加任何前后缀；概括用户的主要意图。"
            val protocol = detectProtocol(provider.baseUrl)
            val payload = if (protocol == ApiProtocol.ANTHROPIC) {
                JSONObject()
                    .put("model", provider.model)
                    .put("max_tokens", 50)
                    .put("system", titlePrompt)
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", content))
                    )
                    .toString()
            } else {
                JSONObject()
                    .put("model", provider.model)
                    .put(
                        "messages",
                        JSONArray()
                            .put(JSONObject().put("role", "system").put("content", titlePrompt))
                            .put(JSONObject().put("role", "user").put("content", content))
                    )
                    .put("stream", false)
                    .toString()
            }
            val (response, _) = executeWithAuthFallback(
                provider, Connection.chatEndpoint(provider.baseUrl, protocol), payload
            )
            response.use {
                if (!it.isSuccessful) return@withContext null
                val body = it.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val text = if (protocol == ApiProtocol.ANTHROPIC) {
                    // Anthropic 非流式响应：content 是内容块数组，取第一个 text 块
                    json.optJSONArray("content")
                        ?.let { arr -> (0 until arr.length()).map { arr.optJSONObject(it) } }
                        ?.firstOrNull { it?.optString("type", "") == "text" }
                        ?.optString("text", "")
                } else {
                    json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")
                } ?: return@withContext null
                val cleaned = text.trim()
                    .trim('「', '」', '『', '』', '"', '\u201c', '\u201d', '\'', '`', '#', '*', ' ')
                    .replace("\n", " ")
                    .trim()
                    .take(20)
                if (cleaned.isBlank()) null else cleaned
            }
        } catch (_: Exception) {
            null
        }
    }

    /** /models 的结果：ids=null 表示没拿到列表（网关不开放 / 鉴权被拒 / 网络异常） */
    internal data class ModelsOutcome(
        val ids: List<String>?,
        val dialect: AuthDialect?,
        val httpCode: Int,
        val latencyMs: Long
    )

    /**
     * 拉模型列表。鉴权方言由 executeWithAuthFallback 决定，命中后记在进程内，
     * 后续对话请求直接走生效的那种，不再多付一次 401。
     */
    private suspend fun listModels(provider: Provider): ModelsOutcome {
        val protocol = detectProtocol(provider.baseUrl)
        val t0 = System.nanoTime()
        return try {
            val (response, dialect) = executeWithAuthFallback(
                provider, Connection.modelsEndpoint(provider.baseUrl, protocol), null
            )
            response.use {
                if (!it.isSuccessful) return@use ModelsOutcome(null, dialect, it.code, elapsedMs(t0))
                val ids = mutableListOf<String>()
                JSONObject(it.body?.string() ?: "{}").optJSONArray("data")?.let { data ->
                    for (i in 0 until data.length()) {
                        val id = data.getJSONObject(i).optString("id")
                        if (id.isNotBlank()) ids.add(id)
                    }
                }
                ModelsOutcome(ids.sorted(), dialect, it.code, elapsedMs(t0))
            }
        } catch (_: Exception) {
            ModelsOutcome(null, null, 0, elapsedMs(t0))
        }
    }

    private fun elapsedMs(nanos: Long): Long = (System.nanoTime() - nanos) / 1_000_000

    /** 拉取模型列表；失败返回 null（调用方回退到预设模型）。Anthropic 协议走 /v1/models。 */
    suspend fun fetchModels(provider: Provider): List<String>? = withContext(Dispatchers.IO) {
        if (provider.baseUrl.isBlank()) return@withContext null
        val anthropic = detectProtocol(provider.baseUrl) == ApiProtocol.ANTHROPIC
        // Anthropic 兼容网关普遍不开放 /models（实测 PARAM 等中转返回 404），
        // 此时回退到按域名匹配的已知模型名单，模型页不至于空白
        listModels(provider).ids?.takeIf { it.isNotEmpty() }
            ?: anthropicFallbackModels(provider, anthropic)
    }

    /** Anthropic 协议下 /models 不可用时的已知模型兑底（按网关域名区分）。 */
    private fun anthropicFallbackModels(provider: Provider, anthropic: Boolean): List<String>? {
        if (!anthropic) return null
        val host = provider.baseUrl.trim().lowercase().substringAfter("://").substringBefore('/')
        return when {
            host.startsWith("api.deepseek") -> listOf("deepseek-chat", "deepseek-reasoner")
            else -> listOf(
                "claude-sonnet-4-5", "claude-haiku-4-5", "claude-opus-4-1",
                "claude-sonnet-4-20250514", "claude-3-7-sonnet-20250219"
            )
        }
    }

    /**
     * 流式对话。每收到一段增量文本就回调 onDelta(content 增量, reasoning 增量)。
     * 思考型模型（qwen3/deepseek/gemini）会先流式输出 reasoning_content 再输出正文；
     * 两个增量分开上报，调用方自行决定如何展示思考过程。
     * 协程取消时立即掐断网络请求（invokeOnCompletion 关闭 socket，阻塞中的读取立即中断），
     * 已收到的部分由调用方保留。
     */
    /** OpenAI 兼容请求体：messages + 可选 reasoning_effort / web_search 工具。 */
    internal fun openAiPayload(
        history: List<ChatRequestMessage>,
        reasoningEffort: String?,
        webSearch: Boolean
    ): JSONObject {
        val messagesJson = JSONArray()
        history.forEach { m ->
            val content: Any = if (m.imageDataUrls.isEmpty()) {
                m.text
            } else {
                val parts = JSONArray()
                if (m.text.isNotBlank()) {
                    parts.put(JSONObject().put("type", "text").put("text", m.text))
                }
                m.imageDataUrls.forEach { url ->
                    when {
                        url.startsWith("x-audio:") -> {
                            // 音频："x-audio:mp3|<base64>" → OpenAI input_audio 格式
                            val body = url.removePrefix("x-audio:")
                            val format = body.substringBefore('|')
                            val data = body.substringAfter('|')
                            parts.put(
                                JSONObject()
                                    .put("type", "input_audio")
                                    .put("input_audio", JSONObject().put("data", data).put("format", format))
                            )
                        }
                        url.startsWith("x-pdf:") -> {
                            // PDF："x-pdf:<base64>" → OpenAI file 类型（gpt-4o/luna 等原生可读）
                            parts.put(
                                JSONObject()
                                    .put("type", "file")
                                    .put(
                                        "file",
                                        JSONObject()
                                            .put("filename", "document.pdf")
                                            .put("file_data", "data:application/pdf;base64," + url.removePrefix("x-pdf:"))
                                    )
                            )
                        }
                        else -> parts.put(
                            JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", url))
                        )
                    }
                }
                parts
            }
            messagesJson.put(JSONObject().put("role", m.role).put("content", content))
        }
        val payload = JSONObject()
            .put("messages", messagesJson)
            .put("stream", true)
        if (!reasoningEffort.isNullOrBlank()) {
            payload.put("reasoning_effort", reasoningEffort)
        }
        if (webSearch) {
            // 中转站实测支持的联网搜索工具声明（OpenAI 风格）
            payload.put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
        }
        return payload
    }

    /**
     * Anthropic 协议请求体：/v1/messages。差异点全部在这里吸收，调用方无感：
     * - max_tokens 必填（默认 8192）；
     * - 思考等级映射为 thinking.budget_tokens（低/中/高 → 2k/4k/8k）；
     * - 联网搜索映射为 web_search 服务器工具（不支持的网关报错后由上层自动降级）；
     * - 音频输入不支持，自动替换为文字占位（OpenAI 协议照旧传 input_audio）。
     */
    internal fun anthropicPayload(
        model: String,
        history: List<ChatRequestMessage>,
        reasoningEffort: String?,
        webSearch: Boolean
    ): JSONObject {
        val messagesJson = JSONArray()
        history.forEach { m ->
            val parts = JSONArray()
            var hadAudio = false
            if (m.text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", m.text))
            m.imageDataUrls.forEach { url ->
                when {
                    url.startsWith("x-audio:") -> hadAudio = true // Anthropic 协议无音频输入
                    url.startsWith("x-pdf:") -> {
                        // PDF："x-pdf:<base64>" → Anthropic document 类型（Claude 原生可读）
                        val data = url.removePrefix("x-pdf:")
                        if (data.isNotEmpty()) {
                            parts.put(
                                JSONObject()
                                    .put("type", "document")
                                    .put(
                                        "source",
                                        JSONObject()
                                            .put("type", "base64")
                                            .put("media_type", "application/pdf")
                                            .put("data", data)
                                    )
                            )
                        }
                    }
                    else -> {
                        // "data:image/jpeg;base64,xxx" → base64 source
                        val mediaType = url.substringBefore(';', "").removePrefix("data:").ifBlank { "image/jpeg" }
                        val data = url.substringAfter("base64,", "")
                        if (data.isNotEmpty()) {
                            parts.put(
                                JSONObject()
                                    .put("type", "image")
                                    .put(
                                        "source",
                                        JSONObject()
                                            .put("type", "base64")
                                            .put("media_type", mediaType)
                                            .put("data", data)
                                    )
                            )
                        }
                    }
                }
            }
            if (parts.length() == 0) {
                parts.put(
                    JSONObject().put("type", "text")
                        .put("text", if (hadAudio) "（语音附件）" else "（附件）")
                )
            }
            messagesJson.put(JSONObject().put("role", m.role).put("content", parts))
        }
        val thinkingBudget = when (reasoningEffort) {
            "minimal" -> 1024L
            "low" -> 2048L
            "medium" -> 4096L
            "high" -> 8192L
            else -> null
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messagesJson)
            // Anthropic 要求 max_tokens 必填；开思考时必须大于预算
            .put("max_tokens", if (thinkingBudget != null) thinkingBudget + 4096L else 8192L)
            .put("stream", true)
        if (thinkingBudget != null) {
            payload.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", thinkingBudget))
        }
        if (webSearch) {
            payload.put(
                "tools",
                JSONArray().put(
                    JSONObject().put("type", "web_search_20250305")
                        .put("name", "web_search")
                        .put("max_uses", 5)
                )
            )
        }
        return payload
    }

    /** 解析 OpenAI 兼容流式片段；返回 true 表示收到 [DONE]（流结束）。 */
    internal fun parseOpenAiData(data: String, onDelta: (content: String, reasoning: String) -> Unit): Boolean {
        if (data == "[DONE]") return true
        if (data.isEmpty()) return false
        try {
            val chunk = JSONObject(data)
            val delta = chunk.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
            // optString 对 JSON null / 缺失字段统一返回 ""，不会把字面量 "null" 当正文；
            // 有些中转站会把 reasoning/content 字段序列化成 null，宽松的 has()/getString()
            // 链路会把 "null" 字符串追加进正文，表现为消息里混着成串 null
            val content = delta?.let { it.optString("content", "") } ?: ""
            if (content == "null") return false
            // 思考增量：不同服务商字段名不同（reasoning_content / reasoning），同样丢弃 "null"
            val reasoning = delta?.let {
                it.optString("reasoning_content", "").ifEmpty { it.optString("reasoning", "") }
            } ?: ""
            val reasoningClean = if (reasoning == "null") "" else reasoning
            if (content.isNotEmpty() || reasoningClean.isNotEmpty()) onDelta(content, reasoningClean)
        } catch (_: Exception) {
            // 忽略无法解析的片段（如注释行/keep-alive）
        }
        return false
    }

    /**
     * 解析 Anthropic 流式事件（content_block_delta：text_delta / thinking_delta）。
     * 返回非空表示服务端报错（error 事件），由调用方抛出。
     */
    internal fun parseAnthropicData(data: String, onDelta: (content: String, reasoning: String) -> Unit): String? {
        if (data.isEmpty()) return null
        try {
            val chunk = JSONObject(data)
            when (chunk.optString("type")) {
                "content_block_delta" -> {
                    val delta = chunk.optJSONObject("delta") ?: return null
                    when (delta.optString("type")) {
                        "text_delta" -> {
                            val text = delta.optString("text", "")
                            if (text.isNotEmpty() && text != "null") onDelta(text, "")
                        }
                        "thinking_delta" -> {
                            val thinking = delta.optString("thinking", "")
                            if (thinking.isNotEmpty() && thinking != "null") onDelta("", thinking)
                        }
                    }
                }
                "error" -> {
                    val err = chunk.optJSONObject("error")
                    val msg = err?.optString("message", "")?.takeIf { it.isNotBlank() }
                        ?: "服务返回错误"
                    return msg
                }
            }
        } catch (_: Exception) {
            // 忽略无法解析的片段
        }
        return null
    }

    /**
     * 流式对话。每收到一段增量文本就回调 onDelta(content 增量, reasoning 增量)。
     * 协议按 Base URL 自动选择（OpenAI 兼容 / Anthropic），调用方不感知差异。
     * 思考型模型（qwen3/deepseek/gemini/claude）会先流式输出思考再输出正文；
     * 两个增量分开上报，调用方自行决定如何展示思考过程。
     * 协程取消时立即抠断网络请求（invokeOnCompletion 关闭 socket，阻塞中的读取立即中断），
     * 已收到的部分由调用方保留。
     */
    override suspend fun streamChat(
        provider: Provider,
        history: List<ChatRequestMessage>,
        reasoningEffort: String?,
        webSearch: Boolean,
        onDelta: (content: String, reasoning: String) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val protocol = detectProtocol(provider.baseUrl)
        val payload = when (protocol) {
            ApiProtocol.OPENAI -> openAiPayload(history, reasoningEffort, webSearch)
            ApiProtocol.ANTHROPIC -> anthropicPayload(provider.model, history, reasoningEffort, webSearch)
        }.put("model", provider.model) // 统一填模型名
        val url = Connection.chatEndpoint(provider.baseUrl, protocol)

        // 瞬时错误（429/5xx）自动重试：中转站上游抖动很常见（实测 503 会突然出现），
        // 只对响应阶段的状态码重试（此时尚未输出任何流式内容，无重复风险）；
        // 一旦开始吐流就不再重试。退避 0.8s/2s，429 多等一会。
        var dialect = dialectFor(provider, protocol)
        var authTried = false
        var attempt = 0
        while (true) {
            attempt++
            val request = Connection.applyAuth(Request.Builder().url(url), provider.apiKey, protocol, dialect)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val call: Call = client.newCall(request)
            // 取消即断：协程取消时从取消方立即关闭 socket，
            // 否则阻塞在 readUtf8Line 的读取线程要等到下一条数据（或 120s 读超时）才退出
            val job = coroutineContext[Job]
            val cancelHandle = job?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string()?.take(300) ?: ""
                        if (response.code in RETRYABLE_CODES && attempt < 3) {
                            // 跳出 use/try，由外层循环退避后重试
                            throw RetrySignal(response.code)
                        }
                        // 鉴权被拒且没换过方言：请求根本没被处理，换一种头重发不会重复计费
                        if (isAuthRejected(response.code) && !authTried) throw AuthFallbackSignal(response.code)
                        // 统一格式化：JSON 报文提取 error.message，HTML 错误页给人话提示；
                        // 保留到 300 字符：降级重试的关键词匹配需要看到报文尾部的
                        // "code":"unsupported_parameter" 等字段（截到 120 会把它截掉）
                        throw ApiException(formatApiError(response.code, err))
                    }
                    val source = response.body?.source() ?: throw ApiException("空响应")
                    var done = false
                    while (!done) {
                        coroutineContext.ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        when (protocol) {
                            ApiProtocol.OPENAI ->
                                if (parseOpenAiData(data, onDelta)) done = true
                            ApiProtocol.ANTHROPIC ->
                                parseAnthropicData(data, onDelta)?.let { throw ApiException(it) }
                        }
                    }
                }
                // 只有真正跑完整流才记住生效方言，避免两次都 401 时把错的方言留下来
                if (authTried) dialectByProvider[dialectKey(provider)] = dialect
                break // 本轮完整成功，退出重试循环
            } catch (e: RetrySignal) {
                kotlinx.coroutines.delay(if (e.code == 429) 2000L else 800L * attempt)
            } catch (e: AuthFallbackSignal) {
                authTried = true
                dialect = Connection.otherDialect(dialect)
            } finally {
                cancelHandle?.dispose()
                call.cancel()
            }
        }
    }
    /**
     * 连接体检：先试 /models；拿不到列表再发一个极小的非流式对话请求，
     * 用「能不能拿到 2xx」验证地址+密钥+模型——部分网关不开放 /models
     * （实测 DeepSeek 的 /anthropic 网关等），不能因此误报「连接失败」。
     * 返回结构化的推断结果，UI 据此告诉用户「App 替你选了什么」。
     */
    suspend fun probe(provider: Provider): ConnectionReport = withContext(Dispatchers.IO) {
        val protocol = detectProtocol(provider.baseUrl)
        val url = Connection.chatEndpoint(provider.baseUrl, protocol)
        if (provider.baseUrl.isBlank() || provider.apiKey.isBlank()) {
            return@withContext ConnectionReport(
                ok = false, protocol = protocol, endpoint = url,
                dialect = Connection.defaultDialect(protocol), modelCount = null,
                latencyMs = 0, error = if (provider.baseUrl.isBlank()) "缺少 Base URL" else "缺少 API Key"
            )
        }
        val models = listModels(provider)
        val dialect = models.dialect ?: dialectFor(provider, protocol)
        if (!models.ids.isNullOrEmpty()) {
            return@withContext ConnectionReport(
                ok = true, protocol = protocol, endpoint = url, dialect = dialect,
                modelCount = models.ids.size, latencyMs = models.latencyMs, error = null
            )
        }
        val payload = if (protocol == ApiProtocol.ANTHROPIC) {
            JSONObject()
                .put("model", provider.model)
                .put("max_tokens", 16)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
        } else {
            JSONObject()
                .put("model", provider.model)
                .put("stream", false)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
        }
        val t0 = System.nanoTime()
        try {
            val (response, pingedDialect) = executeWithAuthFallback(provider, url, payload.toString())
            response.use {
                ConnectionReport(
                    ok = it.isSuccessful,
                    protocol = protocol,
                    endpoint = url,
                    dialect = pingedDialect,
                    modelCount = if (it.isSuccessful) 0 else null,
                    latencyMs = elapsedMs(t0),
                    error = if (it.isSuccessful) null
                    else formatApiError(it.code, it.body?.string()?.take(300) ?: "")
                )
            }
        } catch (e: Exception) {
            ConnectionReport(
                ok = false, protocol = protocol, endpoint = url, dialect = dialect,
                modelCount = null, latencyMs = elapsedMs(t0),
                error = e.message?.take(200) ?: "网络错误"
            )
        }
    }
}

/** 流式请求重试的内部信号：带状态码跳出本轮，外层退避后重发 */
internal class RetrySignal(val code: Int) : Exception()

/** 鉴权被拒的内部信号：跳出本轮，换另一种鉴权方言立即重发（不占用瞬时重试额度） */
internal class AuthFallbackSignal(val code: Int) : Exception()

/** 可重试的瞬时状态码（顶层常量，避免每次请求重新分配） */
internal val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504)

class ApiException(message: String) : Exception(message)
