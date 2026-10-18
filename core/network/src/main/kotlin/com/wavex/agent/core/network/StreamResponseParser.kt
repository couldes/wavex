package com.wavex.agent.core.network

import com.wavex.agent.core.domain.model.ApiProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * 响应/SSE 解析（response & chunk parsing）：OpenAI 兼容与 Anthropic 流式事件、
 * 流内 usage 捕获、分片形状指纹、非流式响应与 Images API 响应解析。
 * 实现从 ApiClient 拆出；ApiClient 保留同名门面方法委托到这里（兼容既有测试与调用点）。
 */
internal object StreamResponseParser {

    /** 单图下载字节上限：与 app 的 GeneratedFileStore.MAX_FILE_BYTES 同值
     *  （数据层搬迁后统一常量，见计划 Task 4）。 */
    private const val MAX_IMAGE_DOWNLOAD_BYTES = 15 * 1024 * 1024

    /**
     * 解析 OpenAI 兼容流式片段；返回 true 表示收到 [DONE]（流结束）。usage 非空时捕获末尾 usage chunk。
     * 流内报错 chunk（中转站/上游在 SSE 事件里直接回 error 对象，OpenAI 风格）抛 ApiException——
     * 与 parseAnthropicData 的 error 事件同语义；静默吞掉会让流「正常」结束后
     * 上层只能显示误导性的「模型没有返回内容」。
     */
    fun parseOpenAiData(
        data: String,
        onDelta: (content: String, reasoning: String) -> Unit,
        usage: StreamUsage? = null,
        onImage: (String) -> Unit = {},
        onToolCall: (name: String) -> Unit = {},
        accumulator: ToolCallAccumulator? = null
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
                // 完整累积（id + 分片 arguments）：旧实现只留函数名、丢 arguments，
                // 导致客户端 function calling 无法执行工具。增量由累加器统一归并
                accumulator?.onOpenAiDelta(chunk)
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
    fun chunkShape(data: String): String {
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
    fun parseAnthropicData(
        data: String,
        onDelta: (content: String, reasoning: String) -> Unit,
        usage: StreamUsage? = null,
        onToolCall: (name: String) -> Unit = {},
        accumulator: ToolCallAccumulator? = null
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
                        // id/name 在此块给出，参数在随后的 input_json_delta 里分片到达
                        accumulator?.onAnthropicStart(
                            id = block.optString("id", ""),
                            name = name,
                            index = chunk.optInt("index", 0),
                        )
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
                        "input_json_delta" -> {
                            val partial = delta.optString("partial_json", "")
                            if (partial.isNotEmpty()) accumulator?.onAnthropicDelta(partial)
                        }
                    }
                }
                "content_block_stop" -> accumulator?.onAnthropicStop()
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

    /** 非流式响应体 usage 提取（title/probe 用）：OpenAI 与 Anthropic 字段名不同，畸形记 0 */
    fun parseNonStreamingUsage(json: JSONObject, protocol: ApiProtocol): StreamUsage {
        val usage = StreamUsage()
        val uo = json.optJSONObject("usage") ?: return usage
        when (protocol) {
            ApiProtocol.OPENAI -> {
                // Images API（gpt-image 系）的 usage 字段名是 input/output_tokens，与 chat 不同
                usage.inputTokens = uo.optLong("prompt_tokens", uo.optLong("input_tokens", 0L))
                usage.outputTokens = uo.optLong("completion_tokens", uo.optLong("output_tokens", 0L))
            }
            ApiProtocol.ANTHROPIC -> {
                usage.inputTokens = uo.optLong("input_tokens", 0L)
                usage.outputTokens = uo.optLong("output_tokens", 0L)
            }
        }
        return usage
    }

    /** org.json 的 optString 对 JSON null 返回 "null" 字符串：与空串一起按缺省处理 */
    private fun optStr(json: JSONObject, key: String, fallback: String): String {
        val v = json.optString(key, fallback)
        return if (v.isEmpty() || v == "null") fallback else v
    }

    /**
     * Images API 响应 → 图片列表：data[].b64_json 组装 data URL（MIME 取 output_format，
     * item 优先于顶层，缺省 png）；data[].url 远程链接原样保留。两种形态最终都由
     * ResponseImageExtractor.structuredToAttachment 落盘/引用，与 delta.images 同管线。
     * 非对象项与非法 URL 跳过；全部无效返回空列表，由调用方报「没有返回图片」。
     */
    fun parseImagesResponse(json: JSONObject): List<String> {
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<String>()
        val topFormat = optStr(json, "output_format", "png").lowercase()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val b64 = optStr(item, "b64_json", "")
            if (b64.isNotEmpty()) {
                val fmt = optStr(item, "output_format", topFormat).lowercase()
                val mime = if (fmt.startsWith("image/")) fmt else "image/$fmt"
                out.add("data:$mime;base64,$b64")
            } else {
                val url = optStr(item, "url", "")
                if (url.startsWith("http://") || url.startsWith("https://")) out.add(url)
            }
        }
        return out
    }

    /**
     * 响应字节 → data URL：Content-Type 非 image 开头（网关返回错误页/HTML）或超限
     * （与落盘上限同量级）返回 null，由调用方回退保留原 URL。
     */
    fun asImageDataUrl(contentType: String?, bytes: ByteArray): String? {
        val mime = (contentType ?: "").substringBefore(';').trim().ifBlank { "image/png" }
        if (!mime.startsWith("image/")) return null
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_DOWNLOAD_BYTES) return null
        return "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
    }
}
