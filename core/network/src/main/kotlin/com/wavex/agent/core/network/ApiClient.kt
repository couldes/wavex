package com.wavex.agent.core.network

import com.wavex.agent.core.domain.model.ApiProtocol
import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.network.shouldDropStreamOptions as shouldDropStreamOptionsImpl
import org.json.JSONObject

/**
 * 兼容门面（compatibility facade）：纯 payload/解析入口保留，委托到拆分后的
 * RequestPayloads / StreamResponseParser / ApiErrors；请求执行已迁往 HttpApiSession
 * （由 createModelService 工厂装配），这里不再是全局服务实例、也不持有会话。
 */
internal object ApiClient {

    /** 错误体提及 stream_options → 视为上游不认该参数，摘除后重试 */
    internal fun shouldDropStreamOptions(errorBody: String): Boolean =
        shouldDropStreamOptionsImpl(errorBody)

    /** 非流式响应体 usage 提取（title/probe 用）：OpenAI 与 Anthropic 字段名不同，畸形记 0 */
    internal fun parseNonStreamingUsage(json: JSONObject, protocol: ApiProtocol) =
        StreamResponseParser.parseNonStreamingUsage(json, protocol)

    /**
     * 模型是否走 OpenAI Images API（POST /v1/images/generations）。这类模型在中转站
     * （new-api 系）按图片计费矩阵定价，普通 /chat/completions 请求会被以
     * [model_price_error] 「not an image request」拒绝；OpenAI 官方 gpt-image 系也
     * 只通过 Images/Responses API 提供。名单刻意保守：只收已知同步生成的
     * gpt-image- / dall-e 两族；flux/wan2.x-t2i 等在各网关常挂异步任务流
     * （响应 task_id 需轮询），误导向只会走进死路——维持 chat 行为不变。
     * gemini-*-image 等聊天式出图模型不含此前缀，照走 chat（正文 data URL 管线）。
     */
    internal fun isImagesApiModel(model: String): Boolean = RequestPayloads.isImagesApiModel(model)

    /** Images API 请求体：model + prompt + n=1。不带 stream（一次 JSON 拿结果），
     *  size/quality 不填，吃网关默认（gpt-image 默认 auto 尺寸/质量）。 */
    internal fun imageGenerationPayload(model: String, prompt: String): JSONObject =
        RequestPayloads.imageGenerationPayload(model, prompt)

    /**
     * Images API 响应 → 图片列表：data[].b64_json 组装 data URL（MIME 取 output_format，
     * item 优先于顶层，缺省 png）；data[].url 远程链接原样保留。两种形态最终都由
     * ResponseImageExtractor.structuredToAttachment 落盘/引用，与 delta.images 同管线。
     * 非对象项与非法 URL 跳过；全部无效返回空列表，由调用方报「没有返回图片」。
     */
    internal fun parseImagesResponse(json: JSONObject): List<String> =
        StreamResponseParser.parseImagesResponse(json)

    /**
     * 响应字节 → data URL：Content-Type 非 image 开头（网关返回错误页/HTML）或超限
     * （与落盘上限同量级）返回 null，由调用方回退保留原 URL。
     */
    internal fun asImageDataUrl(contentType: String?, bytes: ByteArray): String? =
        StreamResponseParser.asImageDataUrl(contentType, bytes)

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

    /** OpenAI 兼容请求体：messages + 可选 reasoning_effort / web_search 工具。 */
    internal fun openAiPayload(
        history: List<ChatRequestMessage>,
        reasoningEffort: String?,
        webSearch: Boolean,
        streamOptions: Boolean = true,   // 流式默认注入 include_usage（cc-switch 同款，见 streamChat）
        model: String = ""              // 模型名：开/关式思考家族按前缀识别（glm/qwen/kimi/deepseek）
    ): JSONObject = RequestPayloads.openAiPayload(history, reasoningEffort, webSearch, streamOptions, model)

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
    ): JSONObject = RequestPayloads.anthropicPayload(model, history, reasoningEffort, webSearch)

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
        onToolCall: (name: String) -> Unit = {},
        accumulator: ToolCallAccumulator? = null
    ): Boolean =
        StreamResponseParser.parseOpenAiData(data, onDelta, usage, onImage, onToolCall, accumulator)

    /**
     * 数据分片形状指纹：只描述 JSON 结构（顶层键、字段名、part 类型名），
     * 绝不包含任何内容/数据值（隐私约束）。用于「流有数据分片但零可识别产出」的
     * 诊断文案——网关实际回了什么形状，气泡/用量日志里一眼可见。
     */
    internal fun chunkShape(data: String): String = StreamResponseParser.chunkShape(data)

    /**
     * 解析 Anthropic 流式事件（content_block_delta：text_delta / thinking_delta）。
     * 返回非空表示服务端报错（error 事件），由调用方抛出。
     * usage 非空时捕获：message_start → 输入 token；message_delta → 输出 token（累计值取 max）。
     */
    internal fun parseAnthropicData(
        data: String,
        onDelta: (content: String, reasoning: String) -> Unit,
        usage: StreamUsage? = null,
        onToolCall: (name: String) -> Unit = {},
        accumulator: ToolCallAccumulator? = null
    ): String? =
        StreamResponseParser.parseAnthropicData(data, onDelta, usage, onToolCall, accumulator)
}
