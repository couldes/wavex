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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import com.wavex.agent.data.Provider
import com.wavex.agent.data.ProviderStore
import com.wavex.agent.model.ApiProtocol
import com.wavex.agent.model.ChatRequestMessage

/**
 * 按 Base URL 自动识别协议——用户不需要选，填地址即可：
 * - 域名以 anthropic.com 结尾，或路径中出现 /anthropic 段（如 DeepSeek 官方
 *   Anthropic 网关 https://api.deepseek.com/anthropic）→ Anthropic 协议；
 * - 其余一律按 OpenAI 兼容协议（/chat/completions + Bearer）。
 */
internal fun detectProtocol(baseUrl: String): ApiProtocol {
    val lower = baseUrl.trim().lowercase()
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
object ApiClient {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** Base URL 规范化：去尾部斜杠；若没有任何路径（纯域名）则补 /v1。 */
    fun normalizeBaseUrl(raw: String): String {
        val base = raw.trim().trimEnd('/')
        if (base.isEmpty()) return base
        val pathStart = base.substringAfter("://", "").indexOf('/')
        return if (pathStart < 0) "$base/v1" else base
    }

    private fun endpoint(baseUrl: String, path: String): String =
        "${normalizeBaseUrl(baseUrl)}$path"

    /**
     * Anthropic 协议端点：/v1 由规范化补齐——api.anthropic.com → /v1/messages；
     * DeepSeek 的 …/anthropic 网关 → …/anthropic/v1/messages。path 传 "/messages" 或 "/models"。
     */
    private fun anthropicEndpoint(baseUrl: String, path: String): String {
        val base = normalizeBaseUrl(baseUrl)
        return if (base.endsWith("/v1")) "$base$path" else "$base/v1$path"
    }

    /** Anthropic 协议认证头：x-api-key + anthropic-version（不是 Bearer）。 */
    private fun anthropicRequest(provider: Provider, url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("x-api-key", provider.apiKey)
            .header("anthropic-version", "2023-06-01")

    private fun authRequest(provider: Provider, url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${provider.apiKey}")

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
            val request = if (detectProtocol(provider.baseUrl) == ApiProtocol.ANTHROPIC) {
                val payload = JSONObject()
                    .put("model", provider.model)
                    .put("max_tokens", 50)
                    .put(
                        "system",
                        "为下面的对话生成一个简短标题。要求：直接输出标题本身；不超过14个字；不加引号、不加任何前后缀；概括用户的主要意图。"
                    )
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", content))
                    )
                anthropicRequest(provider, anthropicEndpoint(provider.baseUrl, "/messages"))
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            } else {
                val sys = JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        "为下面的对话生成一个简短标题。要求：直接输出标题本身；不超过14个字；不加引号、不加任何前后缀；概括用户的主要意图。"
                    )
                val payload = JSONObject()
                    .put("model", provider.model)
                    .put(
                        "messages",
                        JSONArray()
                            .put(sys)
                            .put(JSONObject().put("role", "user").put("content", content))
                    )
                    .put("stream", false)
                authRequest(provider, endpoint(provider.baseUrl, "/chat/completions"))
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            }
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val text = if (detectProtocol(provider.baseUrl) == ApiProtocol.ANTHROPIC) {
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

    /** 拉取模型列表；失败返回 null（调用方回退到预设模型）。Anthropic 协议走 /v1/models。 */
    suspend fun fetchModels(provider: Provider): List<String>? = withContext(Dispatchers.IO) {
        if (provider.baseUrl.isBlank()) return@withContext null
        val anthropic = detectProtocol(provider.baseUrl) == ApiProtocol.ANTHROPIC
        try {
            val request = (
                if (anthropic) anthropicRequest(provider, anthropicEndpoint(provider.baseUrl, "/models"))
                else authRequest(provider, endpoint(provider.baseUrl, "/models"))
            ).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext anthropicFallbackModels(provider, anthropic)
                val body = response.body?.string() ?: return@withContext anthropicFallbackModels(provider, anthropic)
                val ids = mutableListOf<String>()
                val data = JSONObject(body).optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val id = data.getJSONObject(i).optString("id")
                        if (id.isNotBlank()) ids.add(id)
                    }
                }
                // Anthropic 兼容网关普遍不开放 /models（实测 PARAM 等中转返回 404），
                // 此时回退到按域名匹配的已知模型名单，模型页不至于空白
                ids.sorted().ifEmpty { anthropicFallbackModels(provider, anthropic) }
            }
        } catch (_: Exception) {
            anthropicFallbackModels(provider, anthropic)
        }
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
    suspend internal fun streamChat(
        provider: Provider,
        history: List<ChatRequestMessage>,
        reasoningEffort: String? = null,
        webSearch: Boolean = false,
        onDelta: (content: String, reasoning: String) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val protocol = detectProtocol(provider.baseUrl)
        val payload = when (protocol) {
            ApiProtocol.OPENAI -> openAiPayload(history, reasoningEffort, webSearch)
            ApiProtocol.ANTHROPIC -> anthropicPayload(provider.model, history, reasoningEffort, webSearch)
        }.put("model", provider.model) // 统一填模型名
        val url = when (protocol) {
            ApiProtocol.OPENAI -> endpoint(provider.baseUrl, "/chat/completions")
            ApiProtocol.ANTHROPIC -> anthropicEndpoint(provider.baseUrl, "/messages")
        }

        // 瞬时错误（429/5xx）自动重试：中转站上游抖动很常见（实测 503 会突然出现），
        // 只对响应阶段的状态码重试（此时尚未输出任何流式内容，无重复风险）；
        // 一旦开始吐流就不再重试。退避 0.8s/2s，429 多等一会。
        var attempt = 0
        while (true) {
            attempt++
            val request = (
                if (protocol == ApiProtocol.ANTHROPIC) anthropicRequest(provider, url)
                else authRequest(provider, url)
            ).post(payload.toString().toRequestBody("application/json".toMediaType()))
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
                break // 本轮完整成功，退出重试循环
            } catch (e: RetrySignal) {
                kotlinx.coroutines.delay(if (e.code == 429) 2000L else 800L * attempt)
            } finally {
                cancelHandle?.dispose()
                call.cancel()
            }
        }
    }
    /**
     * 连通性测试兑底：部分网关/协议不开放 /models（实测 DeepSeek 的 /anthropic 网关等），
     * 此时改发一个极小的非流式对话请求，用「能不能拿到 2xx」验证地址+密钥+模型。
     * 返回 null = 连通成功；返回字符串 = 失败原因（已格式化）。
     */
    suspend fun ping(provider: Provider): String? = withContext(Dispatchers.IO) {
        try {
            val anthropic = detectProtocol(provider.baseUrl) == ApiProtocol.ANTHROPIC
            val payload = if (anthropic) {
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
            val request = (
                if (anthropic) anthropicRequest(provider, anthropicEndpoint(provider.baseUrl, "/messages"))
                else authRequest(provider, endpoint(provider.baseUrl, "/chat/completions"))
            ).post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) null
                else formatApiError(response.code, response.body?.string()?.take(300) ?: "")
            }
        } catch (e: Exception) {
            e.message?.take(200) ?: "网络错误"
        }
    }
}

/** 流式请求重试的内部信号：带状态码跳出本轮，外层退避后重发 */
internal class RetrySignal(val code: Int) : Exception()

/** 可重试的瞬时状态码（顶层常量，避免每次请求重新分配） */
internal val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504)

class ApiException(message: String) : Exception(message)
