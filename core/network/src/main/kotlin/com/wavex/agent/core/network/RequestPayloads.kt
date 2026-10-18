package com.wavex.agent.core.network

import com.wavex.agent.core.domain.chat.ReasoningSupport
import com.wavex.agent.core.domain.chat.reasoningSupportOf
import com.wavex.agent.core.domain.model.ApiProtocol
import com.wavex.agent.core.domain.model.ApiProtocols
import com.wavex.agent.core.domain.model.ChatRequestMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 按 Base URL 自动识别协议——用户不需要选，填地址即可：
 * - 域名以 anthropic.com 结尾，或路径中出现 /anthropic 段（如 DeepSeek 官方
 *   Anthropic 网关 https://api.deepseek.com/anthropic）→ Anthropic 协议；
 * - 其余一律按 OpenAI 兼容协议。
 * 协议只决定路径叶子与默认鉴权方言；实际鉴权方言在拿到 401/403 时会自动换一种重发。
 *
 * 判定规则本体在 domain 的 [ApiProtocols.detect]（feature 层解释思考支持形态时要用同一套）；
 * 这里只保留 internal 名字给 network 内部与既有测试。
 */
internal fun detectProtocol(baseUrl: String): ApiProtocol = ApiProtocols.detect(baseUrl)

/**
 * 请求体构建（payload builders）：OpenAI 兼容与 Anthropic 双协议、Images API、
 * 图片模型路由。实现从 ApiClient 拆出；ApiClient 保留同名门面方法委托到这里
 * （兼容既有测试与调用点）。
 */
internal object RequestPayloads {

    /**
     * 客户端 web_search 工具的说明文案（spec §4.3 第一段）。
     * 这段文字决定模型到底搜不搜，比后端实现重要：四家参考实现（RikkaHub / Kelivo /
     * Cherry Studio / Open WebUI）都用同构的长描述。
     */
    const val WEB_SEARCH_TOOL_DESCRIPTION: String =
        "Search the web for current information, news, and real-time data.\n\n" +
            "Use this when:\n" +
            "- The user asks about recent events, current prices, live data, or anything that may have " +
            "changed after your training cutoff\n" +
            "- You need to verify a fact you are uncertain about\n\n" +
            "Don't use for:\n" +
            "- Math, code reasoning, or things you can answer from your training data\n" +
            "- Well-known stable facts\n\n" +
            "Write focused keyword queries, not full sentences. You may call this tool multiple times " +
            "with refined queries. If the topic likely has better sources in another language, repeat " +
            "the search in that language.\n\n" +
            "Response format: {\"items\":[{\"id\",\"index\",\"title\",\"url\",\"text\"}], \"error\"?}"

    /**
     * 引用指令（spec §4.3 第二段），作为 system 注入而不是塞进工具描述——
     * 工具描述容易被模型当参数说明读过去，system 里的格式约束才真的会被遵守。
     */
    const val CITATION_HINT: String =
        "<citations>\n" +
            "When a statement is based on a web_search result, append [cite:id] immediately after " +
            "that statement, using the exact id field. Multiple markers may chain.\n" +
            "Never invent or reuse ids. Do not write a References/Sources section — the app renders " +
            "citations from the inline markers.\n" +
            "</citations>"


    /** OpenAI function 形状的工具声明 */
    fun webSearchToolDeclaration(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "web_search")
                .put("description", WEB_SEARCH_TOOL_DESCRIPTION)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "query",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "The search query to look up online")
                            )
                        )
                        .put("required", JSONArray().put("query"))
                )
        )

    /** Anthropic 形状的工具声明（input_schema 而非 function.parameters） */
    fun anthropicWebSearchToolDeclaration(): JSONObject = JSONObject()
        .put("name", "web_search")
        .put("description", WEB_SEARCH_TOOL_DESCRIPTION)
        .put(
            "input_schema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "query",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "The search query to look up online")
                    )
                )
                .put("required", JSONArray().put("query"))
        )

    /** OpenAI 兼容请求体：messages + 可选 reasoning_effort / web_search 工具。 */
    fun openAiPayload(
        history: List<ChatRequestMessage>,
        reasoningEffort: String?,
        webSearch: Boolean,
        streamOptions: Boolean = true,   // 流式默认注入 include_usage（cc-switch 同款，见 streamChat）
        model: String = "",             // 模型名：开/关式思考家族按前缀识别（glm/qwen/kimi/deepseek）
        citationHint: Boolean = false   // 联网时注入引用指令（spec §4.3）
    ): JSONObject {
        val messagesJson = JSONArray()
        // 引用指令注入：历史里已有 system 时不重复插（部分中转站对双 system 直接报错）
        if (citationHint && history.none { it.role == "system" }) {
            messagesJson.put(JSONObject().put("role", "system").put("content", CITATION_HINT))
        }
        history.forEach { m ->
            // 工具结果消息：纯字符串内容 + tool_call_id（不能走视觉 parts 分支）
            if (m.role == "tool") {
                messagesJson.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", m.toolCallId ?: "")
                        .put("content", m.text)
                )
                return@forEach
            }
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
            val msg = JSONObject().put("role", m.role)
            if (m.toolCalls.isNotEmpty()) {
                // 带工具调用的 assistant 消息 content 可以为 null，也可以带正文；
                // 有正文就发正文——同一轮里模型可能先说「我来搜一下」再调用工具，
                // 把它抹成 null 会让模型看不到自己上一轮说过什么（Anthropic 侧保留正文，
                // 两边不一致还会造成行为差异）
                msg.put("content", if (m.text.isBlank()) JSONObject.NULL else m.text)
                val calls = JSONArray()
                m.toolCalls.forEach { c ->
                    calls.put(
                        JSONObject()
                            .put("id", c.id)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject().put("name", c.name).put("arguments", c.arguments)
                            )
                    )
                }
                msg.put("tool_calls", calls)
            } else {
                msg.put("content", content)
            }
            messagesJson.put(msg)
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
            // 开/关式思考家族：reasoning_effort 发不得（被拒或被静默忽略，
            // 静默忽略最糟——用户以为在控制思考实际没生效）。选任何档位都等于开思考。
            // DeepSeek 例外：官方 API 无思考开关参数，思考由模型名决定（deepseek-reasoner），
            // 发什么都被拒，干脆什么都不发。
            //
            // 分支依据是 reasoningSupportOf 的同一张表：UI 状态条按它解释「实际生效的
            // 是什么」，两处必须同源，否则界面会和真实请求体打架。
            when (reasoningSupportOf(model, ApiProtocol.OPENAI)) {
                ReasoningSupport.MODEL_DECIDED -> {}
                ReasoningSupport.TOGGLE -> {
                    when {
                        model.startsWith("glm", ignoreCase = true) ->
                            payload.put("thinking", JSONObject().put("type", "enabled"))
                        // qwen / kimi：开/关族里走 enable_thinking 的那两家
                        else -> payload.put("enable_thinking", true)
                    }
                }
                // effort 式模型（gpt-5 系等）：原样透传
                ReasoningSupport.TIERED -> payload.put("reasoning_effort", reasoningEffort)
            }
        }
        if (webSearch) {
            // 客户端 function 工具（协议核心规范），不是服务器端 web_search 扩展。
            // 2026-10-17 实测：7 家中转站里 6 家的服务端 web_search 失效（400 / 静默不搜 /
            // 503），而客户端 tools 7/7 透传——所以联网改由 App 自己执行搜索。
            payload.put("tools", JSONArray().put(webSearchToolDeclaration()))
            payload.put("tool_choice", "auto")
        }
        return payload
    }

    /**
     * Anthropic 协议请求体：/v1/messages。差异点全部在这里吸收，调用方无感：
     * - max_tokens 必填（默认 8192）；
     * - 思考等级映射为 thinking.budget_tokens（低/中/高/极致 → 2k/4k/8k/16k，逐档翻倍）；
     * - 联网搜索声明为客户端 function 工具（tool_use/tool_result 块往返）；
     * - 音频输入不支持，自动替换为文字占位（OpenAI 协议照旧传 input_audio）。
     */
    fun anthropicPayload(
        model: String,
        history: List<ChatRequestMessage>,
        reasoningEffort: String?,
        webSearch: Boolean,
        citationHint: Boolean = false
    ): JSONObject {
        val messagesJson = JSONArray()
        // 并行工具调用的结果要合进同一条 user 消息：模型在一条 assistant 里请求多个
        // web_search 时（实测 DeepSeek 会中英文各搜一次），Anthropic 要求这些 tool_use
        // 的 tool_result 全部出现在紧随其后的那一条 user 消息里；一条结果一个 user
        // 消息会被判成孤儿配对，直接 400。
        // 合并窗口只覆盖「连续的 tool 消息」——中间夹了别的消息就另起一条，
        // 否则会把不同轮的结果串到一起，反而制造错配。
        var openWindow: JSONArray? = null
        history.forEach { m ->
            // 工具结果：Anthropic 要求 tool_result 放在 user 角色的 content 块里
            if (m.role == "tool") {
                val arr = openWindow ?: JSONArray().also {
                    openWindow = it
                    messagesJson.put(JSONObject().put("role", "user").put("content", it))
                }
                arr.put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", m.toolCallId ?: "")
                        .put("content", m.text)
                )
                return@forEach
            }
            openWindow = null
            val parts = JSONArray()
            var hadAudio = false
            // thinking 块必须是第一个（Anthropic 定的块序：thinking → text → tool_use），
            // 且只在确实带思考时插入，普通历史不该凭空多出空 thinking 块
            if (!m.thinking.isNullOrBlank()) {
                parts.put(
                    JSONObject()
                        .put("type", "thinking")
                        .put("thinking", m.thinking)
                        // signature 字段必须存在（中转站按存在性校验），没签名就补空串
                        .put("signature", m.thinkingSignature.orEmpty())
                )
            }
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
            // 工具调用块：紧跟在正文块之后
            m.toolCalls.forEach { c ->
                val input = try {
                    JSONObject(c.arguments)
                } catch (_: Exception) {
                    // 参数串畸形时退化为最小可用形状，避免整条历史因一块坏数据全废
                    JSONObject().put("query", c.query ?: "")
                }
                parts.put(
                    JSONObject()
                        .put("type", "tool_use")
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("input", input)
                )
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
            payload.put("tools", JSONArray().put(anthropicWebSearchToolDeclaration()))
        }
        if (citationHint) {
            // Anthropic 的 system 是顶层字段（不是 messages 里的一条）
            payload.put("system", CITATION_HINT)
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
