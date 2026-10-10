package com.wavex.agent.core.domain.model

/**
 * 流错误分类与展示文案（Task 6 裁决：自 :core:network 迁入 —— 这是 ModelService 端口的
 * 错误契约面，feature 层消费它却不得依赖 network；纯 Kotlin，无 HTTP 代码）。
 * 网络实现抛出 domain 类型的 StreamErrorCode，VM 按此翻译人话提示。
 */

/** 流错误分类：StreamErrorCode 公开构造器暴露此类型，随之一并公开（同模块测试直接使用）。 */
enum class StreamErrorKind {
    EmptyContent, FirstByteTimeout, NoFirstToken, Abort, ToolCall
}

class StreamErrorCode(
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
fun streamFailureText(e: StreamErrorCode, web: Boolean, effort: String?): String {
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
