package com.wavex.agent.network

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
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
/** 默认生产门面：纯 payload/解析入口保留，请求执行委托独立会话。 */
internal object ApiClient : ModelService {
    private val session: HttpApiSession by lazy {
        HttpApiSession(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        )
    }

    override suspend fun streamChat(
        provider: Provider,
        history: List<ChatRequestMessage>,
        reasoningEffort: String?,
        webSearch: Boolean,
        onDelta: (String, String) -> Unit,
        onImage: (String) -> Unit
    ) = session.streamChat(provider, history, reasoningEffort, webSearch, onDelta, onImage)

    override suspend fun generateTitle(provider: Provider, transcript: String): String? =
        session.generateTitle(provider, transcript)

    override suspend fun fetchModels(provider: Provider): List<String>? = session.fetchModels(provider)

    override suspend fun probe(provider: Provider): ConnectionReport = session.probe(provider)

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
