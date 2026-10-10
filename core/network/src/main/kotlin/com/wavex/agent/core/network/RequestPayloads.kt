package com.wavex.agent.core.network

import com.wavex.agent.core.domain.model.ApiProtocol
import com.wavex.agent.core.domain.model.ChatRequestMessage
import org.json.JSONArray
import org.json.JSONObject

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
 * 请求体构建（payload builders）：OpenAI 兼容与 Anthropic 双协议、Images API、
 * 图片模型路由。实现从 ApiClient 拆出；ApiClient 保留同名门面方法委托到这里
 * （兼容既有测试与调用点）。
 */
internal object RequestPayloads {

    /** OpenAI 兼容请求体：messages + 可选 reasoning_effort / web_search 工具。 */
    fun openAiPayload(
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
    fun anthropicPayload(
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
     * 模型是否走 OpenAI Images API（POST /v1/images/generations）。这类模型在中转站
     * （new-api 系）按图片计费矩阵定价，普通 /chat/completions 请求会被以
     * [model_price_error] 「not an image request」拒绝；OpenAI 官方 gpt-image 系也
     * 只通过 Images/Responses API 提供。名单刻意保守：只收已知同步生成的
     * gpt-image- / dall-e 两族；flux/wan2.x-t2i 等在各网关常挂异步任务流
     * （响应 task_id 需轮询），误导向只会走进死路——维持 chat 行为不变。
     * gemini-*-image 等聊天式出图模型不含此前缀，照走 chat（正文 data URL 管线）。
     */
    fun isImagesApiModel(model: String): Boolean {
        val lower = model.trim().lowercase()
        return lower.startsWith("gpt-image-") || lower.startsWith("dall-e")
    }

    /** Images API 请求体：model + prompt + n=1。不带 stream（一次 JSON 拿结果），
     *  size/quality 不填，吃网关默认（gpt-image 默认 auto 尺寸/质量）。 */
    fun imageGenerationPayload(model: String, prompt: String): JSONObject =
        JSONObject().put("model", model).put("prompt", prompt).put("n", 1)
}
