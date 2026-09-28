package com.wavex.agent.engine

/** 降级链决策的输入快照：历史里有哪些内容、哪些已经降级过、当前参数。 */
internal data class FallbackFlags(
    val historyHasAudio: Boolean,
    val historyHasImage: Boolean,
    val historyHasPdf: Boolean,
    val audioDropped: Boolean,
    val imageDropped: Boolean,
    val pdfDropped: Boolean,
    val effort: String?,
    val web: Boolean
)

/** 降级链决策结果：每次只降一层，由调用方执行重发与 Toast。 */
internal sealed class FallbackAction {
    data object DropAudio : FallbackAction()
    data object DropImage : FallbackAction()
    data object DropPdf : FallbackAction()
    data object DropEffort : FallbackAction()
    data object DropWeb : FallbackAction()
    data object None : FallbackAction()
}

/**
 * 递归降级的决策层：音频 → 图片 → PDF → 思考等级 → 联网搜索，逐层剔除被模型拒绝的部分。
 * 错误分类：isModelError(msg) = 模型不收这类内容（格式本身没问题，软件能处理）；
 * 其余 HTTP 4xx = 参数不支持。Toast 提示留在调用方（UI 关切）。
 */
internal object FallbackPolicy {

    // 中转站的参数拒绝报文：英文关键词或中文（可能 GBK 乱码）
    private fun isParamError(msg: String) = msg.contains("HTTP 4") &&
        (msg.contains("reasoning") || msg.contains("effort") || msg.contains("tool") ||
            msg.contains("web_search") || msg.contains("thinking") || msg.contains("unsupported") ||
            msg.contains("not support") || msg.contains("不支持"))

    // 模型不收某类内容：识别为对应内容的关键词才算，避免误伤普通参数报错。
    // 消息里明确告知「模型不支持」，与「软件不支持」区分开
    private fun isModelError(msg: String, audio: Boolean = false, pdf: Boolean = false): Boolean = msg.contains("HTTP 4") && (
        when {
            audio -> msg.contains("input_audio") || msg.contains("audio") || msg.contains("音频")
            pdf -> msg.contains("pdf") || msg.contains("document") || msg.contains("文件")
            else -> msg.contains("image") || msg.contains("image_url") || msg.contains("vision") ||
                msg.contains("visual") || msg.contains("图片") || msg.contains("图像")
        }
        )

    fun decide(errorMessage: String, flags: FallbackFlags): FallbackAction = when {
        flags.historyHasAudio && !flags.audioDropped && isModelError(errorMessage, audio = true) ->
            FallbackAction.DropAudio
        flags.historyHasImage && !flags.imageDropped && isModelError(errorMessage) ->
            FallbackAction.DropImage
        flags.historyHasPdf && !flags.pdfDropped && isModelError(errorMessage, pdf = true) ->
            FallbackAction.DropPdf
        flags.effort != null && isParamError(errorMessage) ->
            FallbackAction.DropEffort
        flags.web && isParamError(errorMessage) ->
            FallbackAction.DropWeb
        else -> FallbackAction.None
    }
}
