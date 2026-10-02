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
 * 已知密钥打码（cc-switch redact_known_secrets_strict 同款）：错误文本要展示到
 * 聊天气泡/体检报告或落盘用量记录，网关报错里回显的 API Key 一律替换为 [REDACTED]。
 * 空白串跳过；返回新字符串，不改原文。
 */
internal fun redactSecrets(text: String, vararg secrets: String): String {
    var out = text
    for (secret in secrets) {
        if (secret.isNotBlank()) out = out.replace(secret, "[REDACTED]")
    }
    return out
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
/**
 * 流式对话客户端。历史上实现过 engine.ChatApi 接口，为让 streamChat 携带带默认值的
 * onImage 参数（fun interface 抽象方法不允许默认参），改为独立 object；该接口已随
 * 冗余清理移除（engine/ChatApi.kt 现仅存 ContentLoader）。
 */
internal object ApiClient {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** 首 token 单读超时（毫秒）：首个 data 行到达前每读限时；keep-alive 心跳内不误杀 */
    private const val FIRST_TOKEN_READ_TIMEOUT_MS = 5_000L

    /** 流式读超时（毫秒）：与 client.readTimeout 一致，首个 data 行后恢复 */
    private const val STREAM_READ_TIMEOUT_MS = 120_000L

    /** Base URL 规范化与端点拼接见 Connection（纯函数、可单测）。 */

    /**
     * 网关实际接受的鉴权方言，按「服务商+地址」记在进程内。
     * 落盘没必要：协议判断本身是地址字符串的纯函数，重算是纳秒级；这里记的只是
     * 探测产生的一次额外 401 往返，重启后最多再付一次。
     */
    private val dialectByProvider = ConcurrentHashMap<String, AuthDialect>()

    /** 记住不支持 stream_options 的 Provider：请求失败且错误体含该字样时永久摘除注入（Task 5 降级保险） */
    private val streamOptionsUnsupported: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 错误体提及 stream_options → 视为上游不认该参数，摘除后重试 */
    internal fun shouldDropStreamOptions(errorBody: String): Boolean =
        errorBody.contains("stream_options")

    /** 非流式响应体 usage 提取（title/probe 用）：OpenAI 与 Anthropic 字段名不同，畸形记 0 */
    internal fun parseNonStreamingUsage(json: JSONObject, protocol: ApiProtocol): StreamUsage {
        val usage = StreamUsage()
        val uo = json.optJSONObject("usage") ?: return usage
        when (protocol) {
            ApiProtocol.OPENAI -> {
                usage.inputTokens = uo.optLong("prompt_tokens", 0L)
                usage.outputTokens = uo.optLong("completion_tokens", 0L)
            }
            ApiProtocol.ANTHROPIC -> {
                usage.inputTokens = uo.optLong("input_tokens", 0L)
                usage.outputTokens = uo.optLong("output_tokens", 0L)
            }
        }
        return usage
    }

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
        body: String?,
        // 剥高兼容子路径后的根域名候选走 OpenAI 方言；不传则按 baseUrl 推断。
        //（此前参数被同名局部变量遮蔽，listModels 传入的 candidate.protocol 被忽略——
        // Anthropic 网关的根域名 /models 候选先用 x-api-key 白付一次 401 才回退 Bearer）
        protocol: ApiProtocol = detectProtocol(provider.baseUrl)
    ): Pair<okhttp3.Response, AuthDialect> {
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
     * 自动命名（ChatGPT/Gemini 式）：用当前模型给对话生成简短标题。
     * transcript 由调用方按 TitlePolicy 构建（首题=首轮问答，演化=最近几轮），
     * 非流式请求；任何失败都返回 null（调用方首题回退首条消息截断，演化保留旧题）。
     * Anthropic 协议服务商用 /v1/messages（非流式）实现同等效果。
     */
    suspend fun generateTitle(provider: Provider, transcript: String): String? = withContext(Dispatchers.IO) {
        if (provider.baseUrl.isBlank() || provider.apiKey.isBlank()) return@withContext null
        if (transcript.isBlank()) return@withContext null
        try {
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
                        JSONArray().put(JSONObject().put("role", "user").put("content", transcript))
                    )
                    .toString()
            } else {
                JSONObject()
                    .put("model", provider.model)
                    .put(
                        "messages",
                        JSONArray()
                            .put(JSONObject().put("role", "system").put("content", titlePrompt))
                            .put(JSONObject().put("role", "user").put("content", transcript))
                    )
                    .put("stream", false)
                    .toString()
            }
            val t0 = System.nanoTime()
            val (response, _) = executeWithAuthFallback(
                provider, Connection.chatEndpoint(provider.baseUrl, protocol), payload
            )
            response.use {
                if (!it.isSuccessful) {
                    UsageTracker.record("title", provider, null, it.code, elapsedMs(t0),
                        formatApiError(it.code, it.body?.string()?.take(300) ?: ""))
                    return@withContext null
                }
                val body = it.body?.string() ?: run {
                    UsageTracker.record("title", provider, null, it.code, elapsedMs(t0), "空响应")
                    return@withContext null
                }
                val json = JSONObject(body)
                // title 也真实计费：usage 直接在响应体里（非流式两种协议字段名不同）
                UsageTracker.record("title", provider,
                    parseNonStreamingUsage(json, protocol), it.code, elapsedMs(t0))
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
        } catch (e: Exception) {
            // 网络层失败没有响应码：记 0（spec：流级/无响应 = 0）
            UsageTracker.record("title", provider, null, 0, 0, e.message ?: "")
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
     * 按候选端点依次尝试：Anthropic 兼容层普遍不挂 /models（404/405），
     * 此时剥掉兼容子路径后在根域名上试 OpenAI 格式端点（同 key 可用，cc-switch 同款）。
     */
    private suspend fun listModels(provider: Provider): ModelsOutcome {
        val t0 = System.nanoTime()
        for (candidate in Connection.modelsCandidates(provider.baseUrl, detectProtocol(provider.baseUrl))) {
            try {
                val (response, dialect) = executeWithAuthFallback(
                    provider, candidate.url, null, candidate.protocol
                )
                response.use {
                    when {
                        // 该端点不提供 /models：换下一个候选
                        it.code == 404 || it.code == 405 -> null
                        !it.isSuccessful -> ModelsOutcome(null, dialect, it.code, elapsedMs(t0))
                        else -> ModelsOutcome(parseModelIds(it), dialect, it.code, elapsedMs(t0))
                    }
                }?.let { return it }
            } catch (_: Exception) {
                // 网络层失败对同主机的所有候选一样：直接放弃（同 cc-switch）
                return ModelsOutcome(null, null, 0, elapsedMs(t0))
            }
        }
        return ModelsOutcome(null, null, 0, elapsedMs(t0))
    }

    /** /models 响应体 → 模型 id 列表（OpenAI 与 Anthropic 的 /models 都是 data[].id 形状）。 */
    private fun parseModelIds(response: okhttp3.Response): List<String> {
        val ids = mutableListOf<String>()
        JSONObject(response.body?.string() ?: "{}").optJSONArray("data")?.let { data ->
            for (i in 0 until data.length()) {
                val id = data.getJSONObject(i).optString("id")
                if (id.isNotBlank()) ids.add(id)
            }
        }
        return ids.sorted()
    }

    private fun elapsedMs(nanos: Long): Long = (System.nanoTime() - nanos) / 1_000_000

    /** 拉取模型列表；所有候选都拿不到时回退到预设名单。Anthropic 协议先试 /anthropic/v1/models，
     *  404 后自动换根域名上的 OpenAI 格式端点（见 Connection.modelsCandidates）。 */
    suspend fun fetchModels(provider: Provider): List<String>? = withContext(Dispatchers.IO) {
        if (provider.baseUrl.isBlank()) return@withContext null
        listModels(provider).ids?.takeIf { it.isNotEmpty() }
            ?: anthropicFallbackModels(provider, detectProtocol(provider.baseUrl) == ApiProtocol.ANTHROPIC)
    }

    /** 兜底名单：所有候选端点都不可用（无 key/全部 404/网络不通）时的已知模型，避免模型页空白。 */
    internal fun anthropicFallbackModels(provider: Provider, anthropic: Boolean): List<String>? {
        if (!anthropic) return null
        val host = provider.baseUrl.trim().lowercase().substringAfter("://").substringBefore('/')
        return when {
            // 2026-02 官方文档：现行模型为 deepseek-flash（V4.1-Flash）与 deepseek-v4-pro，
            // 旧名 deepseek-chat/deepseek-reasoner 已从定价页下线
            host.startsWith("api.deepseek") -> listOf("deepseek-flash", "deepseek-v4-pro")
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
        webSearch: Boolean,
        streamOptions: Boolean = true,   // 流式默认注入 include_usage（cc-switch 同款，见 streamChat）
        model: String = ""              // 模型名：开/关式思考家族按前缀识别（glm/qwen/kimi/deepseek）
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
        if (streamOptions) {
            // OpenAI 兼容上游流式默认不回传 usage，必须显式声明才会末尾吐 usage chunk
            //（cc-switch inject_openai_stream_include_usage 同款；仅此流式 payload 函数注入）
            payload.put("stream_options", JSONObject().put("include_usage", true))
        }
        if (!reasoningEffort.isNullOrBlank()) {
            when {
                // 开/关式思考家族：reasoning_effort 发不得（被拒或被静默忽略，
                // 静默忽略最糟——用户以为在控制思考实际没生效）。选任何档位都等于开思考。
                // DeepSeek 例外：官方 API 无思考开关参数，思考由模型名决定（deepseek-reasoner），
                // 发什么都被拒，干脆什么都不发。
                model.startsWith("deepseek", ignoreCase = true) -> {}
                model.startsWith("glm", ignoreCase = true) ->
                    payload.put("thinking", JSONObject().put("type", "enabled"))
                model.startsWith("qwen", ignoreCase = true) || model.startsWith("kimi", ignoreCase = true) ->
                    payload.put("enable_thinking", true)
                // effort 式模型（gpt-5 系等）：原样透传
                else -> payload.put("reasoning_effort", reasoningEffort)
            }
        }
        if (webSearch) {
            // 中转站实测支持的联网搜索工具声明（OpenAI 风格）。
            // 曾试过按模型家族改发 Gemini 原生 googleSearch：实测 pop 网关反而不搜索；
            // web_search 对 gemini 同样有效（同一会话两次请求，一次真搜一次没搜——
            // 网关多渠道轮询，部分渠道不支持联网，属网关侧限制，重试/换渠道可解）。
            payload.put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
        }
        return payload
    }

    /**
     * Anthropic 协议请求体：/v1/messages。差异点全部在这里吸收，调用方无感：
     * - max_tokens 必填（默认 8192）；
     * - 思考等级映射为 thinking.budget_tokens（低/中/高/极致 → 2k/4k/8k/16k，逐档翻倍）；
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
            "low" -> 2048L
            "medium" -> 4096L
            "high" -> 8192L
            "xhigh" -> 16384L
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

    /**
     * 解析 OpenAI 兼容流式片段；返回 true 表示收到 [DONE]（流结束）。usage 非空时捕获末尾 usage chunk。
     * 流内报错 chunk（中转站/上游在 SSE 事件里直接回 error 对象，OpenAI 风格）抛 ApiException——
     * 与 parseAnthropicData 的 error 事件同语义；静默吞掉会让流「正常」结束后
     * 上层只能显示误导性的「模型没有返回内容」。
     */
    internal fun parseOpenAiData(
        data: String,
        onDelta: (content: String, reasoning: String) -> Unit,
        usage: StreamUsage? = null,
        onImage: (String) -> Unit = {},
        onToolCall: (name: String) -> Unit = {}
    ): Boolean {
        if (data == "[DONE]") return true
        if (data.isEmpty()) return false
        try {
            val chunk = JSONObject(data)
            // 流内报错：顶层 error 对象（可能在带 choices 的 chunk 里，也可能单独出现）。
            // 必须在此主动区分并重抛，否则落入下方宽松 catch 被当「无法解析的片段」吞掉
            chunk.optJSONObject("error")?.let { err ->
                val msg = err.optString("message", "").takeIf { it.isNotBlank() && it != "null" }
                    ?: "服务返回错误"
                throw ApiException(msg)
            }
            // usage chunk（include_usage 声明后末尾追加，choices 为空）只含 token 数，不产生正文增量
            usage?.let { u ->
                val uo = chunk.optJSONObject("usage")
                if (uo != null) {
                    // optLong 对 JSON null/缺失/非数字统一返回 0，畸形 usage 不致崩
                    u.inputTokens += uo.optLong("prompt_tokens", 0L)
                    u.outputTokens += uo.optLong("completion_tokens", 0L)
                }
            }
            val delta = chunk.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
            // optString 对 JSON null / 缺失字段统一返回 ""，不会把字面量 "null" 当正文；
            // 有些中转站会把 reasoning/content 字段序列化成 null，宽松的 has()/getString()
            // 链路会把 "null" 字符串追加进正文，表现为消息里混着成串 null
            val content = delta?.let { it.optString("content", "") } ?: ""
            // 有些中转站会把字段序列化成字面量 "null" 字符串，统一当空处理，
            // 避免「有 reasoning 但 content 为 null」的 chunk 被提前丢弃
            val contentClean = if (content == "null") "" else content
            // 思考增量：不同服务商字段名不同（reasoning_content / reasoning），同样丢弃 "null"
            val reasoning = delta?.let {
                it.optString("reasoning_content", "").ifEmpty { it.optString("reasoning", "") }
            } ?: ""
            val reasoningClean = if (reasoning == "null") "" else reasoning
            if (contentClean.isNotEmpty() || reasoningClean.isNotEmpty()) onDelta(contentClean, reasoningClean)
            // 结构化图片输出（OpenRouter / Gemini 兼容层事实标准）：delta.images[] 元素三种形状
            // （chunkShape/imagesShape 的形状表）：image_url.url 包装 / 直接挂 url 键 / URL 字符串本身，
            // 后两种是部分中转站的变体，旧实现只认第一种 → 附件静默丢弃（表现为正文里
            // 模型声称的文件链接只剩空加粗，无附件卡无文件名）。统一取出 URL 逐条回调
            // （要求单 SSE 事件内完整，跨事件分片不支持）；空串与字面量 "null" 丢弃。
            // trim：部分网关 URL 带首尾空白，不 trim 会在 structuredToAttachment 的 matchEntire 被拒、
            // 图片静默丢失（表现为消息仅剩「模型没有返回内容」）
            delta?.optJSONArray("images")?.let { imgs ->
                for (k in 0 until imgs.length()) {
                    val el = imgs.opt(k)
                    val url = (when {
                        el is String -> el
                        el is JSONObject ->
                            el.optJSONObject("image_url")?.optString("url", "")
                                ?: el.optString("url", "")
                        else -> ""
                    }).trim()
                    if (url.isNotEmpty() && url != "null") onImage(url)
                }
            }
            // 工具调用分片（函数调用）：网关按标准 function-calling 语义把工具交回客户端执
            // 行时，delta.tool_calls 是唯一产出，旧实现完全不识别 → 整条流被判「零可识别
            // 产出」（gemini 系发附件实测命中）。只取函数名（协议元数据，非用户内容）；
            // arguments 可能回显用户输入，不采集。续传分片只带 arguments，无 name，自然跳过
            delta?.optJSONArray("tool_calls")?.let { calls ->
                for (k in 0 until calls.length()) {
                    val name = calls.optJSONObject(k)?.optJSONObject("function")?.optString("name", "") ?: ""
                    if (name.isNotBlank() && name != "null") onToolCall(name)
                }
            }
        } catch (e: ApiException) {
            // 流内报错不是「无法解析的片段」：向上传递（先于宽松 catch）
            throw e
        } catch (_: Exception) {
            // 忽略无法解析的片段（如注释行/keep-alive）
        }
        return false
    }

    /**
     * 数据分片形状指纹：只描述 JSON 结构（顶层键、字段名、part 类型名），
     * 绝不包含任何内容/数据值（隐私约束）。用于「流有数据分片但零可识别产出」的
     * 诊断文案——网关实际回了什么形状，气泡/用量日志里一眼可见。
     */
    internal fun chunkShape(data: String): String {
        val o = try {
            JSONObject(data)
        } catch (_: Exception) {
            return "非JSON(len=${data.length})"
        }
        return when {
            // Gemini 原生流形状（网关未翻译成 OpenAI 兼容层直接透传）
            o.has("candidates") -> {
                val parts = o.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val types = parts?.let { p ->
                    (0 until minOf(p.length(), 3)).mapNotNull { i ->
                        p.optJSONObject(i)?.keys()?.asSequence()?.firstOrNull()
                    }
                }?.joinToString(",")
                if (types.isNullOrBlank()) "candidates" else "candidates/[$types]"
            }
            o.optJSONObject("error") != null -> "error"
            o.has("choices") -> {
                val arr = o.optJSONArray("choices")
                val c0 = arr?.optJSONObject(0)
                when {
                    c0 == null -> "choices为空"
                    c0.has("message") -> {
                        // 中转站无视 stream:true，一次性回完整 message（非 delta）聚合形状
                        val msg = c0.optJSONObject("message")
                        "message" + when {
                            msg?.has("images") == true -> imagesShape(msg.optJSONArray("images"))
                            msg?.has("content") != true -> "+无content"
                            else -> ""
                        }
                    }
                    c0.has("delta") -> {
                        val d = c0.optJSONObject("delta")!!
                        val extra = when {
                            d.length() == 0 -> "空"
                            d.has("images") -> imagesShape(d.optJSONArray("images"))
                            d.has("content") -> contentShape(d)
                            else -> "+${d.keys().asSequence().take(3).sorted().joinToString(",")}"
                        }
                        val n = if ((arr?.length() ?: 1) > 1) "×${arr?.length()}" else ""
                        "delta$extra$n"
                    }
                    else -> "choices/[${c0.keys().asSequence().take(4).sorted().joinToString(",")}]"
                }
            }
            else -> "keys=[${o.keys().asSequence().take(4).sorted().joinToString(",")}]"
        }
    }

    /** images 字段子形状：数组元素是字符串 / image_url 包装 / 直挂 url / 其他键 */
    private fun imagesShape(arr: JSONArray?): String {
        if (arr == null) return "+images=非数组"
        if (arr.length() == 0) return "+images=空数组"
        return when (val e0 = arr.opt(0)) {
            is String -> "+images[字符串元素]"
            is JSONObject -> when {
                e0.has("image_url") ->
                    if (e0.optJSONObject("image_url")?.has("url") == true) "+images[image_url.url]" else "+images[image_url无url]"
                e0.has("url") -> "+images[url]"
                else -> "+images[${e0.keys().asSequence().take(3).sorted().joinToString(",")}]"
            }
            else -> "+images[${e0?.javaClass?.simpleName ?: "null"}]"
        }
    }

    /** delta.content 子形状：存在但没产出增量时才有意义（空串/null/数组/其他类型） */
    private fun contentShape(d: JSONObject): String = when {
        d.isNull("content") -> "+content=null"
        else -> when (val v = d.opt("content")) {
            is String -> if (v.isEmpty()) "+content=空串" else "+content=字符串"
            is JSONArray -> "+content=数组"
            else -> "+content=${v?.javaClass?.simpleName ?: "null"}"
        }
    }

    /**
     * 解析 Anthropic 流式事件（content_block_delta：text_delta / thinking_delta）。
     * 返回非空表示服务端报错（error 事件），由调用方抛出。
     * usage 非空时捕获：message_start → 输入 token；message_delta → 输出 token（累计值取 max）。
     */
    internal fun parseAnthropicData(
        data: String,
        onDelta: (content: String, reasoning: String) -> Unit,
        usage: StreamUsage? = null,
        onToolCall: (name: String) -> Unit = {}
    ): String? {
        if (data.isEmpty()) return null
        try {
            val chunk = JSONObject(data)
            usage?.let { u ->
                when (chunk.optString("type")) {
                    "message_start" -> {
                        val uo = chunk.optJSONObject("message")?.optJSONObject("usage")
                        if (uo != null) u.inputTokens = maxOf(u.inputTokens, uo.optLong("input_tokens", 0L))
                    }
                    "message_delta" -> {
                        val uo = chunk.optJSONObject("usage")
                        if (uo != null) u.outputTokens = maxOf(u.outputTokens, uo.optLong("output_tokens", 0L))
                    }
                }
            }
            when (chunk.optString("type")) {
                // 工具调用块（函数调用）：与 OpenAI 路径同因同修，只取函数名
                "content_block_start" -> {
                    val block = chunk.optJSONObject("content_block") ?: return null
                    if (block.optString("type") == "tool_use") {
                        val name = block.optString("name", "")
                        if (name.isNotBlank() && name != "null") onToolCall(name)
                    }
                }
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
    suspend fun streamChat(
        provider: Provider,
        history: List<ChatRequestMessage>,
        reasoningEffort: String?,
        webSearch: Boolean,
        onDelta: (content: String, reasoning: String) -> Unit,
        onImage: (String) -> Unit = {}
    ): Unit = withContext(Dispatchers.IO) {
        val protocol = detectProtocol(provider.baseUrl)
        val url = Connection.chatEndpoint(provider.baseUrl, protocol)

        // 瞬时错误（429/5xx）自动重试：中转站上游抖动很常见（实测 503 会突然出现），
        // 只对响应阶段的状态码重试（此时尚未输出任何流式内容，无重复风险）；
        // 一旦开始吐流就不再重试。退避 0.8s/2s，429 多等一会。
        var dialect = dialectFor(provider, protocol)
        var authTried = false
        var attempt = 0
        // stream_options 降级只限一次：异常网关反复报错时不无限重试（plan Task 5）
        var downgraded = false
        // 是否已有任何增量送达调用方：决定连接层异常时保留部分内容还是向上报错
        var receivedAny = false
        val trackedOnDelta: (String, String) -> Unit = { c, r ->
            if (c.isNotEmpty() || r.isNotEmpty()) receivedAny = true
            onDelta(c, r)
        }
        // onImage 同样计入「已有可识别输出」：纯图片流（无正文）不能被误判为空流
        val trackedOnImage: (String) -> Unit = { url ->
            if (url.isNotEmpty()) receivedAny = true
            onImage(url)
        }
        // 工具调用函数名收集：只记名字（协议元数据，非用户内容），去重上限 3 个
        val toolCallNames = mutableListOf<String>()
        val trackedOnToolCall: (String) -> Unit = { name ->
            if (name.isNotBlank() && name != "null" && name !in toolCallNames && toolCallNames.size < 3) {
                toolCallNames.add(name)
            }
        }
        // 埋点：一次逻辑请求一条记录（重试中的 429/5xx 不记，终态才记——plan Task 7）
        val usage = StreamUsage()
        val t0 = System.nanoTime()
        var recorded = false
        fun recordOnce(code: Int, err: String) {
            if (recorded) return
            recorded = true
            UsageTracker.record("chat", provider, usage, code, elapsedMs(t0), err)
        }
        try {
            while (true) {
                attempt++
                // payload 在循环内构建：降级重试必须重建（旧 payload 里的 stream_options 要被摘除）
                val inject = protocol == ApiProtocol.OPENAI && provider.id !in streamOptionsUnsupported
                val payload = when (protocol) {
                    ApiProtocol.OPENAI -> openAiPayload(history, reasoningEffort, webSearch, inject, provider.model)
                    ApiProtocol.ANTHROPIC -> anthropicPayload(provider.model, history, reasoningEffort, webSearch)
                }.put("model", provider.model) // 统一填模型名
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
                            // 中转站不认 stream_options：摘参重试一次并记住该 Provider（Task 5 降级保险）；
                            // 此时未吐流，重发无重复计费风险。已降级过/非 OpenAI/非注入请求不进此支
                            if (inject && !downgraded && shouldDropStreamOptions(err)) {
                                streamOptionsUnsupported.add(provider.id)
                                downgraded = true
                                throw RetrySignal(response.code)
                            }
                            // 统一格式化：JSON 报文提取 error.message，HTML 错误页给人话提示；
                            // 保留到 300 字符：降级重试的关键词匹配需要看到报文尾部的
                            // "code":"unsupported_parameter" 等字段（截到 120 会把它截掉）
                            // 终态 HTTP 失败：真实状态码落账（重试中的不记）
                        recordOnce(response.code, formatApiError(response.code, err))
                        throw ApiException(redactSecrets(formatApiError(response.code, err), provider.apiKey))
                        }
                        val source = response.body?.source() ?: throw ApiException("空响应")
                        var done = false
                        var hasContent = false
                        // 非 blank 数据分片计数与形状指纹（仅本行零产出时记录）：
                        // 整条流零产出时用于诊断文案（见流尾 !receivedAny 检查）
                        var dataLines = 0
                        val unparsedShapes = mutableListOf<String>()

                        // 首 token 守卫（OkHttp per-read 超时实现）：旧墙钟检查只能两次读之间
                        // 执行——阻塞读期间检查不到，静默服务器实际仍要等满 120s socket 超时
                        //（守卫名不副实）；却会在「服务器活着、发过 keep-alive 注释行、首 token
                        // 慢于 5s」时被误杀（循环顶检查恰在注释行后触发）。改为按读限时：
                        // - 首个 data 行到达前，每次底层读限时 5s：静默服务器 5s 即报
                        //   FirstByteTimeout，不再干等 120s（占位气泡久挂的根源）；
                        // - 期间到达的注释/空行让下一读重获 5s：服务器有心跳就不误杀，
                        //   联网搜索/慢网关的首 token 再慢也等得到；
                        // - 首个 data 行到达后恢复 120s：正文/思考段的生成间隔本就可以远超 5s，
                        //   不能拦腰砍断。readUtf8Line 仍是阻塞读，协程取消靠 call.cancel() 掐断。
                        val readTimeout = source.timeout()
                        readTimeout.timeout(FIRST_TOKEN_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        var firstDataSeen = false

                        while (!done) {
                            coroutineContext.ensureActive()

                            try {
                                val line: String? = source.readUtf8Line()
                                when (line) {
                                    null -> break
                                    else -> {
                                        val isData = line.startsWith("data:")
                                        if (isData && !firstDataSeen) {
                                            // 首个 data 行到达：首 token 已来，恢复常规读超时
                                            firstDataSeen = true
                                            readTimeout.timeout(STREAM_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                        }
                                        if (!isData) continue
                                        val data = line.removePrefix("data:").trim()
                                        when (protocol) {
                                            ApiProtocol.OPENAI -> {
                                                if (parseOpenAiData(data, trackedOnDelta, usage, trackedOnImage, trackedOnToolCall)) {
                                                    done = true
                                                    hasContent = true
                                                } else if (data.isNotBlank()) {
                                                    hasContent = true
                                                    dataLines++
                                                    if (!receivedAny) {
                                                        val shape = chunkShape(data)
                                                        if (shape !in unparsedShapes && unparsedShapes.size < 3) unparsedShapes.add(shape)
                                                    }
                                                }
                                            }
                                            ApiProtocol.ANTHROPIC -> {
                                                parseAnthropicData(data, trackedOnDelta, usage, trackedOnToolCall)?.let { throw ApiException(it) }
                                                if (data.isNotBlank()) hasContent = true
                                            }
                                        }
                                    }
                                }
                            } catch (e: java.net.SocketTimeoutException) {
                                if (!hasContent) {
                                    // 首 data 行前的读超时 = 首 token 守卫触发（静默 5s）；
                                    // 之后的静默段超时（120s）按「服务器长时间没有响应」上报
                                    throw StreamErrorCode(
                                        if (firstDataSeen) StreamErrorKind.NoFirstToken else StreamErrorKind.FirstByteTimeout,
                                        ""
                                    )
                                }
                                break
                            } catch (e: java.io.IOException) {
                                // 连接中断（非读超时，如服务端 RST/代理断开）：
                                // 未收到任何内容按首 token 失败上报；已收到内容则视为流提前
                                // 结束，保留已生成的部分（与用户取消同语义）。
                                // 修复：旧实现 catch(Exception){continue}，连接断开后 readUtf8Line
                                // 每次都立即抛错 → continue → 死循环空转 IO 线程直到用户手动停止
                                if (!hasContent) {
                                    throw StreamErrorCode(StreamErrorKind.NoFirstToken, "")
                                }
                                break
                            }
                            // 其余异常不再吞掉重试：解析层自己已 catch，能到这里的只有
                            // 编程错误，交给外层统一处理（协程取消在此向上传播）
                        }
                    
                        // End-of-stream content check: empty stream is NOT success
                        if (!hasContent) {
                            throw StreamErrorCode(StreamErrorKind.EmptyContent, "")
                        }
                        // 整条流零产出但收到了工具调用分片：网关按标准 function-calling 语义
                        // 把工具交回客户端执行并结束本轮（finish_reason=tool_calls），App 无
                        // 客户端工具执行回路，这轮必然无正文——用指明根因的报错替代笼统的
                        // 「空流+形状指纹」文案（gemini 系发附件实测命中的场景）。
                        // 文案不含「HTTP 4」等字样，避免误触发 FallbackPolicy
                        if (!receivedAny && toolCallNames.isNotEmpty()) {
                            throw StreamErrorCode(StreamErrorKind.ToolCall, toolCallNames.joinToString(","))
                        }
                        // 有数据分片但整条流零产出（正文/思考/图片全无）：不再「正常」结束——
                        // 否则上层只能显示误导性的「模型没有返回内容」。带上分片形状指纹，
                        // 网关实际回了什么形状气泡里直接可见（gemini 发附件空流实测的定位手段）
                        if (!receivedAny) {
                            val shapes = if (unparsedShapes.isEmpty()) "仅结束标记" else unparsedShapes.joinToString(" | ")
                            throw StreamErrorCode(
                                StreamErrorKind.EmptyContent,
                                "网关返回了 $dataLines 个数据分片，但都不是可识别的内容格式（形状: $shapes）"
                            )
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
            // 循环正常退出（break）= 完整成功
            recordOnce(200, "")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 用户取消：记 499 并继续传播（吞掉取消会卡死会话——plan Review Focus #1）
            recordOnce(499, "")
            throw e
        } catch (e: ApiException) {
            recordOnce(0, e.message ?: "")
            // 流内报错（parseOpenAiData 抛出）的消息可能回显密钥，与 HTTP 路径同标准打码；
            // redactSecrets 幂等，对 HTTP 路径已打码的消息再跑一遍无副作用
            throw ApiException(redactSecrets(e.message ?: "", provider.apiKey))
        } catch (e: StreamErrorCode) {
            // 空流/超时：记 0 并继续抛出——首 token 守卫的契约就是让 ViewModel 的
            // catch (StreamErrorCode) 用联网感知的友好文案提示；吞掉会让上层误走
            // 「流正常结束但无内容」的兜底分支，提示变成干巴巴的「模型没有返回内容」
            recordOnce(0, e.underlying?.ifBlank { e.kind.name } ?: e.kind.name)
            throw e
        } catch (e: Exception) {
            recordOnce(0, e.message ?: "")
            // 未收到任何内容（连接被拒/DNS 失败等）：必须向上抛，
            // 否则上层误走「流正常结束」分支，提示变成误导性的「模型没有返回内容」，
            // 且降级链（FallbackPolicy）也失去介入机会。
            // 已有部分内容（流中途断开）：保留已生成的部分，静默结束（与取消同语义）——
            // 向上抛错会用错误文案覆盖掉已有的正文。
            if (!receivedAny) throw e
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
                val body = it.body?.string()
                // probe 的 /models 路径不计费不记录；只有这个小对话请求落一条明细日志
                //（仅用于「最近请求」展示，不计入统计口径 —— 见 UsageStore.UNCOUNTED_KINDS）
                if (it.isSuccessful) {
                    val usage = try { parseNonStreamingUsage(JSONObject(body ?: ""), protocol) } catch (_: Exception) { StreamUsage() }
                    UsageTracker.record("probe", provider, usage, it.code, elapsedMs(t0))
                } else {
                    UsageTracker.record("probe", provider, null, it.code, elapsedMs(t0),
                        formatApiError(it.code, body?.take(300) ?: ""))
                }
                ConnectionReport(
                    ok = it.isSuccessful,
                    protocol = protocol,
                    endpoint = url,
                    dialect = pingedDialect,
                    modelCount = if (it.isSuccessful) 0 else null,
                    latencyMs = elapsedMs(t0),
                    error = if (it.isSuccessful) null
                    else redactSecrets(formatApiError(it.code, body?.take(300) ?: ""), provider.apiKey)
                )
            }
        } catch (e: Exception) {
            UsageTracker.record("probe", provider, null, 0, elapsedMs(t0), e.message ?: "")
            ConnectionReport(
                ok = false, protocol = protocol, endpoint = url, dialect = dialect,
                modelCount = null, latencyMs = elapsedMs(t0),
                error = redactSecrets(e.message?.take(200) ?: "网络错误", provider.apiKey)
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

internal enum class StreamErrorKind {
    EmptyContent, FirstByteTimeout, NoFirstToken, Abort, ToolCall
}

internal class StreamErrorCode(
    val kind: StreamErrorKind,
    val underlying: String?
) : Exception(underlying ?: when (kind) {
        StreamErrorKind.EmptyContent -> "服务器没有返回任何消息内容"
        StreamErrorKind.FirstByteTimeout -> "服务器一直连接中，没给出首个字"
        StreamErrorKind.NoFirstToken -> "服务器长时间没有响应"
        StreamErrorKind.Abort -> "连接中断"
        StreamErrorKind.ToolCall -> "模型请求调用工具，App 暂不支持"
    })

/**
 * 空流/超时错误翻译成人话：必须避开「HTTP 4xx」格式以免误触发 FallbackPolicy。
 * web=true → 引导关闭联网；effort≠null → 说明思考等级已被拒。
 */
internal fun streamFailureText(e: StreamErrorCode, web: Boolean, effort: String?): String {
    val base = when (e.kind) {
        StreamErrorKind.EmptyContent ->
            if (web && effort != null)
                "当前模型未返回任何结果。已检测到您同时开启了两项（联网搜索 + 思考等级），建议先关闭联网重试，或换支持这两项的模型"
            else if (web)
                "当前模型不支持联网搜索并返回了空响应。请关闭联网选项后重试，或切换为原生支持该功能的模型（如 GPT-4o/claude）"
            else if (effort != null)
                "未收到模型回复。当前模型可能不支持所选思考等级，请降低或取消思考等级后重试"
            else
                "服务器没有返回任何消息内容"
        StreamErrorKind.FirstByteTimeout ->
            if (web)
                "服务器一直没响应，可能是模型不支持联网参数。请关闭联网后重试"
            else
                "服务器一直连接中，没给出首个字"
        StreamErrorKind.NoFirstToken ->
            if (web)
                "服务器长时间没有响应，可能是模型不支持联网参数。请关闭联网后重试"
            else
                "服务器长时间没有响应"
        StreamErrorKind.ToolCall -> {
            // 函数名已内联进正文，underlying 不再走末尾 detail 追加（那只对 EmptyContent 生效）
            val names = e.underlying?.takeIf { it.isNotBlank() }
            if (names != null)
                "模型请求调用工具（$names），App 暂不支持工具执行。请重试或更换模型/渠道"
            else
                "模型请求调用工具，App 暂不支持工具执行。请重试或更换模型/渠道"
        }
        StreamErrorKind.Abort -> e.underlying ?: "连接中断"
    }
    // 诊断细节只对 EmptyContent 追加（Abort 的 underlying 已是正文，不重复）：
    // 分片形状指纹直接告诉用户（和开发者）网关实际回了什么形状
    val detail = if (e.kind == StreamErrorKind.EmptyContent) e.underlying?.takeIf { it.isNotBlank() } else null
    return if (detail != null) "$base（$detail）" else base
}
