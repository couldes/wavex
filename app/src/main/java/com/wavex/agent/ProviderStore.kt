package com.wavex.agent

import android.content.Context
import android.content.SharedPreferences
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.wavex.agent.model.ApiProtocol
import com.wavex.agent.model.ChatAttachment
import com.wavex.agent.model.ChatRequestMessage
import com.wavex.agent.model.TREE_ROOT
import kotlin.coroutines.coroutineContext

/**
 * 服务商配置（参考 ccswitch 的设计：一个服务商 = 名称 + Base URL + API Key + 模型）。
 * 预设模板让用户尽量少填东西；中转站/自建服务直接选"自定义"。
 */
data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String
)

/** 内置预设：点一下就填好 Base URL 与默认模型，用户只需要粘贴 API Key。 */
data class ProviderPreset(
    val label: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val fallbackModels: List<String>
)

val PROVIDER_PRESETS = listOf(
    // OpenAI / Claude 放最前：国际主流服务商优先展示，国内服务商与自定义依次往后
    ProviderPreset("OpenAI", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini",
        listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini")),
    // Claude：Base URL 指向 anthropic.com 时自动切换 Anthropic 协议（/v1/messages + x-api-key）
    ProviderPreset("Claude", "Claude", "https://api.anthropic.com", "claude-sonnet-4-5",
        listOf("claude-sonnet-4-5", "claude-haiku-4-5", "claude-opus-4-1")),
    ProviderPreset("DeepSeek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat",
        listOf("deepseek-chat", "deepseek-reasoner")),
    ProviderPreset("Kimi", "Kimi", "https://api.moonshot.cn/v1", "kimi-k2-0711-preview",
        listOf("kimi-k2-0711-preview", "moonshot-v1-8k", "moonshot-v1-32k")),
    ProviderPreset("通义千问", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
        listOf("qwen-plus", "qwen-turbo", "qwen-max")),
    ProviderPreset("智谱 GLM", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-air",
        listOf("glm-4.5-air", "glm-4-plus", "glm-4-flash")),
    // 自定义：Base URL 完全留空（用户自己填），不预设默认模型——拉到列表后自动选第一个
    ProviderPreset("自定义", "", "", "",
        listOf())
)

/**
 * 服务商持久化：SharedPreferences + 手写 JSON，不引入额外依赖。
 * API Key 只保存在本机，不上传、不打日志。
 */
class ProviderStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("agent_providers", Context.MODE_PRIVATE)

    fun loadProviders(): MutableList<Provider> {
        val list = mutableListOf<Provider>()
        val raw = prefs.getString("providers", null) ?: return list
        return try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                list.add(
                    Provider(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", "未命名"),
                        baseUrl = o.optString("baseUrl", ""),
                        apiKey = o.optString("apiKey", ""),
                        model = o.optString("model", "deepseek-chat")
                    )
                )
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveProviders(providers: List<Provider>) {
        val array = JSONArray()
        providers.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("baseUrl", p.baseUrl)
            o.put("apiKey", p.apiKey)
            o.put("model", p.model)
            array.put(o)
        }
        prefs.edit().putString("providers", array.toString()).apply()
    }

    fun loadCurrentProviderId(): String? = prefs.getString("currentProviderId", null)

    // 思考等级 / 联网搜索是全局偏好（不绑定单个服务商）
    fun loadReasoningEffort(): String = prefs.getString("reasoningEffort", "") ?: ""

    fun saveReasoningEffort(v: String) {
        prefs.edit().putString("reasoningEffort", v).apply()
    }


    fun saveCurrentProviderId(id: String?) {
        prefs.edit().putString("currentProviderId", id).apply()
    }
}


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
    private fun openAiPayload(
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
    private fun anthropicPayload(
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
    private fun parseOpenAiData(data: String, onDelta: (content: String, reasoning: String) -> Unit): Boolean {
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
    private fun parseAnthropicData(data: String, onDelta: (content: String, reasoning: String) -> Unit): String? {
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
private class RetrySignal(val code: Int) : Exception()

/** 可重试的瞬时状态码（顶层常量，避免每次请求重新分配） */
private val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504)

class ApiException(message: String) : Exception(message)


/**
 * 对话持久化：JSON 文件存 filesDir/conversations.json（分叉树格式）。
 * 每个对话存：nodes（全部消息节点，含各分支）+ children（父 id -> 子 id 列表）+
 * activeChild（父 id -> 当前激活子 id）；旧版平铺 messages 格式自动迁移为线性树。
 * 附件 URI 之前已 takePersistableUriPermission，重启后仍可读。
 * 保存时机：消息列表变更时防抖写入，避免流式输出每帧写盘。
 */
class ConversationStore(context: Context) {
    private val file = java.io.File(context.filesDir, "conversations.json")

    private fun parseStoredMessage(m: JSONObject): StoredMessage {
        val attsJson = m.optJSONArray("attachments") ?: JSONArray()
        val atts = mutableListOf<Pair<String, String>>()
        for (k in 0 until attsJson.length()) {
            val a = attsJson.getJSONObject(k)
            atts.add(a.optString("uri") to a.optString("name"))
        }
        return StoredMessage(
            id = m.optString("id", ""),
            text = m.optString("text"),
            fromUser = m.optBoolean("fromUser"),
            isError = m.optBoolean("isError"),
            reasoning = m.optString("reasoning"),
            attachments = atts
        )
    }

    /** 旧格式（平铺消息列表）→ 线性树迁移 */
    private fun linearToTree(msgs: List<StoredMessage>): TreeData {
        val nodes = LinkedHashMap<String, StoredMessage>()
        val children = LinkedHashMap<String, MutableList<String>>()
        val activeChild = LinkedHashMap<String, String>()
        var parent = TREE_ROOT
        msgs.forEach { m ->
            nodes[m.id] = m
            children.getOrPut(parent) { mutableListOf() }.add(m.id)
            activeChild[parent] = m.id
            parent = m.id
        }
        return TreeData(nodes, children, activeChild)
    }

    /** 加载全部对话（含旧格式自动迁移） */
    fun load(): List<ConversationSnapshot> {
        val result = mutableListOf<ConversationSnapshot>()
        val raw = try { file.readText() } catch (_: Exception) { null } ?: return result
        return try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val conv = arr.getJSONObject(i)
                if (conv.has("nodes")) {
                    // 新格式：分叉树
                    val nodesJson = conv.getJSONArray("nodes")
                    val nodes = LinkedHashMap<String, StoredMessage>()
                    for (j in 0 until nodesJson.length()) {
                        var m = parseStoredMessage(nodesJson.getJSONObject(j))
                        if (m.id.isBlank()) m = m.copy(id = java.util.UUID.randomUUID().toString())
                        nodes[m.id] = m
                    }
                    val children = LinkedHashMap<String, List<String>>()
                    val childrenJson = conv.optJSONObject("children") ?: JSONObject()
                    val it = childrenJson.keys()
                    while (it.hasNext()) {
                        val parent = it.next()
                        val ids = mutableListOf<String>()
                        val arr2 = childrenJson.optJSONArray(parent) ?: JSONArray()
                        for (k in 0 until arr2.length()) ids.add(arr2.optString(k))
                        children[parent] = ids
                    }
                    val activeChild = LinkedHashMap<String, String>()
                    val activeJson = conv.optJSONObject("activeChild") ?: JSONObject()
                    val it2 = activeJson.keys()
                    while (it2.hasNext()) {
                        val parent = it2.next()
                        activeChild[parent] = activeJson.optString(parent)
                    }
                    result.add(ConversationSnapshot(conv.optString("id"), conv.optString("title", "新对话"), TreeData(nodes, children, activeChild)))
                } else {
                    // 旧格式：平铺消息 → 迁移成线性树
                    val msgsJson = conv.optJSONArray("messages") ?: JSONArray()
                    val msgs = mutableListOf<StoredMessage>()
                    for (j in 0 until msgsJson.length()) {
                        var m = parseStoredMessage(msgsJson.getJSONObject(j))
                        if (m.id.isBlank()) m = m.copy(id = java.util.UUID.randomUUID().toString())
                        msgs.add(m)
                    }
                    result.add(ConversationSnapshot(conv.optString("id"), conv.optString("title", "新对话"), linearToTree(msgs)))
                }
            }
            result
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(conversations: List<AgentConversationData>) {
        try {
            val arr = JSONArray()
            conversations.forEach { conv ->
                val nodesJson = JSONArray()
                conv.nodes.values.forEach { m ->
                    val mJson = JSONObject()
                        .put("id", m.id)
                        .put("text", m.text)
                        .put("fromUser", m.fromUser)
                        .put("isError", m.isError)
                    if (m.reasoning.isNotBlank()) mJson.put("reasoning", m.reasoning)
                    if (m.attachments.isNotEmpty()) {
                        val atts = JSONArray()
                        m.attachments.forEach { a ->
                            atts.put(JSONObject().put("uri", a.first).put("name", a.second))
                        }
                        mJson.put("attachments", atts)
                    }
                    nodesJson.put(mJson)
                }
                val childrenJson = JSONObject()
                conv.children.forEach { (parent, ids) -> childrenJson.put(parent, JSONArray(ids)) }
                val activeJson = JSONObject()
                conv.activeChild.forEach { (parent, id) -> activeJson.put(parent, id) }
                arr.put(
                    JSONObject()
                        .put("id", conv.id)
                        .put("title", conv.title)
                        .put("nodes", nodesJson)
                        .put("children", childrenJson)
                        .put("activeChild", activeJson)
                )
            }
            // 原子写入：先写临时文件再改名，进程在写盘中途被杀不会损坏 conversations.json
            // （旧实现直接 writeText，写一半被杀 = 全部历史丢失）
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                // 个别文件系统 rename 到已存在目标会失败：删旧文件后重试一次
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (_: Exception) {
            // 磁盘满等异常时静默失败，不打断聊天
        }
    }
}

/** 纯数据形态（与 UI 状态解耦） */
data class StoredMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val fromUser: Boolean,
    val isError: Boolean = false,
    val reasoning: String = "",  // 模型思考过程（可空，旧数据自动补 ""）
    val attachments: List<Pair<String, String>> = emptyList()  // (uri, name)
)

/** 分叉树纯数据：nodes=全部节点，children=父 id -> 子 id 列表，activeChild=父 id -> 激活子 id */
data class TreeData(
    val nodes: Map<String, StoredMessage>,
    val children: Map<String, List<String>>,
    val activeChild: Map<String, String>
)

data class AgentConversationData(
    val id: String,
    val title: String,
    val nodes: Map<String, StoredMessage>,
    val children: Map<String, List<String>>,
    val activeChild: Map<String, String>
)

/** 加载用快照 */
data class ConversationSnapshot(val id: String, val title: String, val tree: TreeData)

/** 附件内容加载：图片转 base64 data URL（视觉消息），文本文件内联内容，PDF 转逐页图片；其余不支持。 */
object AttachmentLoader {
    private const val MAX_TEXT_CHARS = 20000
    // 图片发送上限：长边像素与压缩质量。超大图直接 base64 会 OOM（实测 60MB 图闪退），
    // 先降采样到视觉模型识别够用的尺寸再压缩，兼顾清晰度与请求体大小。
    private const val MAX_IMAGE_DIMEN = 1568
    private const val JPEG_QUALITY = 85
    // 音频发送上限：base64 后约 20MB 请求体，超出直接拒绝（中转站一般也会拒）
    private const val MAX_AUDIO_BYTES = 15 * 1024 * 1024
    // PDF 发送上限：同量级防请求体爆炸；PDF 原生 base64 上传（OpenAI "file" 类型），
    // 模型直接读原始文档（保留排版/文本层），而非转图片
    // OpenAI 兼容接口支持的音频格式（input_audio）；其余音频格式暂不支持
    private val AUDIO_MIMES = mapOf(
        "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/wave" to "wav", "audio/vnd.wave" to "wav",
        "audio/mpeg" to "mp3", "audio/mp3" to "mp3"
    )
    // 已解析附件的内存缓存（key=uri），多轮对话不再重复解码/转 base64，减少 GC 卡顿
    private val cache = object : android.util.LruCache<String, Pair<String, String>>(6_000_000) {
        override fun sizeOf(key: String, value: Pair<String, String>): Int = value.second.length
    }
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "json", "xml", "yml", "yaml", "log",
        "kt", "java", "py", "js", "ts", "jsx", "tsx", "html", "htm", "css", "scss",
        "c", "h", "cpp", "hpp", "cc", "sh", "bat", "ps1", "sql", "toml", "ini", "conf", "cfg",
        "gradle", "properties", "env", "gitignore", "dockerfile", "cmake", "swift", "rb", "go",
        "rs", "php", "dart", "lua", "pl", "r", "m", "srt", "vtt", "lrc"
    )
    // 按扩展名识别的音频格式（MIME 缺失时的兑底）
    private val AUDIO_EXTENSIONS = mapOf(
        "mp3" to "mp3", "wav" to "wav", "wave" to "wav"
    )
    // 按扩展名识别的图片格式
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

    /**
     * 软件层面能否处理该附件：返回 null = 支持；非 null = 不支持原因（可直接展示给用户）。
     * 软件无法处理的格式（PDF/未知类型/非 MP3·WAV 音频）转不成 API 载荷，
     * 模型肯定收不到 —— 挑选时即拦截并报错，不浪费时间上传。
     * 软件支持的格式（图片/音频/文本）放行，模型层面是否接受由发送后的
     * 降级链判断（isModelError），两层错误分开、各自在最早时机提示。
     */
    fun unsupportedReason(context: android.content.Context, uri: android.net.Uri, name: String): String? {
        val mime = (try { context.contentResolver.getType(uri) } catch (_: Exception) { null } ?: "").lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        val effectiveMime = when {
            mime.isNotBlank() && mime != "application/octet-stream" -> mime
            ext in IMAGE_EXTENSIONS -> "image/x"
            ext in AUDIO_EXTENSIONS -> "audio/x"
            else -> mime
        }
        return when {
            effectiveMime == "application/pdf" || ext == "pdf" -> null
            effectiveMime.startsWith("image/") || ext in IMAGE_EXTENSIONS -> null
            effectiveMime.startsWith("text/") || ext in TEXT_EXTENSIONS -> null
            else -> {
                val format = AUDIO_MIMES[effectiveMime]
                    ?: AUDIO_EXTENSIONS[ext]
                    ?: if (effectiveMime.startsWith("audio/")) effectiveMime.substringAfter('/') else null
                when {
                    format == null -> "暂不支持该文件类型（目前支持图片、音频和文本类文件）"
                    format != "mp3" && format != "wav" -> "音频仅支持 MP3 / WAV 格式"
                    else -> null
                }
            }
        }
    }

    /**
     * 图片 → 缩放后的 JPEG data URL：
     * 1) 只读尺寸（inJustDecodeBounds），按 2 的幂降采样到长边 ≤ MAX_IMAGE_DIMEN；
     * 2) 再精确缩放到目标尺寸，JPEG 压缩后 base64。
     * 这样任何分辨率的图都能稳定控制在几百 KB 内，不会 OOM。
     */
    private fun loadScaledImage(resolver: android.content.ContentResolver, uri: android.net.Uri): String? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= MAX_IMAGE_DIMEN) sample *= 2
        val sampled = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, sampled)
        } ?: return null

        val longEdge = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longEdge > MAX_IMAGE_DIMEN) {
            val scale = MAX_IMAGE_DIMEN.toFloat() / longEdge
            android.graphics.Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else bitmap
        if (scaled !== bitmap) bitmap.recycle()

        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        if (scaled.width > 0) scaled.recycle()
        return "data:image/jpeg;base64,$b64"
    }

    /** 返回 (类型, 内容)：image=dataURL、text=内联文本；null=不支持该类型。结果会进 LruCache。 */
    suspend internal fun loadContent(context: android.content.Context, attachment: ChatAttachment): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val cacheKey = attachment.uri.toString()
            cache.get(cacheKey)?.let { return@withContext it }
            try {
                val uri = attachment.uri
                val resolver = context.contentResolver
                val mime = (resolver.getType(uri) ?: "").lowercase()
                val ext = attachment.name.substringAfterLast('.', "").lowercase()
                // MIME 缺失/笼统（application/octet-stream）时按扩展名兑底判断
                val effectiveMime = when {
                    mime.isNotBlank() && mime != "application/octet-stream" -> mime
                    ext in IMAGE_EXTENSIONS -> "image/x"
                    ext in AUDIO_EXTENSIONS -> "audio/x"
                    else -> mime
                }
                val loaded = when {
                    effectiveMime.startsWith("image/") || ext in IMAGE_EXTENSIONS -> {
                        val dataUrl = loadScaledImage(resolver, uri) ?: return@withContext null
                        "image" to dataUrl
                    }
                    effectiveMime.startsWith("text/") || ext in TEXT_EXTENSIONS -> {
                        val text = resolver.openInputStream(uri)?.use {
                            it.bufferedReader().readText()
                        } ?: return@withContext null
                        if (text.length > MAX_TEXT_CHARS) {
                            "text" to "文件 ${attachment.name}：\n${text.take(MAX_TEXT_CHARS)}\n…（内容过长已截断）"
                        } else {
                            "text" to "文件 ${attachment.name}：\n$text"
                        }
                    }
                    effectiveMime in AUDIO_MIMES || effectiveMime.startsWith("audio/") || ext in AUDIO_EXTENSIONS -> {
                        val format = AUDIO_MIMES[effectiveMime]
                            ?: AUDIO_EXTENSIONS[ext]
                            ?: if (effectiveMime.startsWith("audio/")) effectiveMime.substringAfter('/') else null
                        // OpenAI input_audio 只收 mp3/wav，其余音频格式不发送（否则整个请求被拒）
                        if (format == null || format !in setOf("mp3", "wav")) return@withContext null
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@withContext null
                        if (bytes.size > MAX_AUDIO_BYTES) return@withContext null
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        "audio" to "$format|$b64"
                    }
                    // PDF：API 协议原生支持（OpenAI file 类型 / Anthropic document 类型），
                    // 原样 base64 上传，模型直接读原始文档；模型不支持时由发送后降级链处理
                    effectiveMime == "application/pdf" || ext == "pdf" -> {
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@withContext null
                        if (bytes.size > MAX_AUDIO_BYTES) return@withContext null
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        "pdf" to b64
                    }
                    else -> null
                }
                loaded?.let { cache.put(cacheKey, it) }
                loaded
            } catch (_: Exception) {
                null
            }
        }
}
