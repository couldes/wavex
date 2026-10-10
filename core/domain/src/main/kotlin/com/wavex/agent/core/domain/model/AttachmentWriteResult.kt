package com.wavex.agent.core.domain.model

/**
 * 附件落盘结果（原 engine.ResponseImageExtractor.WriteOutcome）：由调用方注入的
 * writeFile 返回，决定正文占位形态。Saved 带落盘 uri（file://…）；TooLarge 估算超限
 * 未写盘；Failed 坏 base64 或 IO 失败。
 */
sealed interface AttachmentWriteResult {
    data class Saved(val uri: String) : AttachmentWriteResult
    data object TooLarge : AttachmentWriteResult
    data object Failed : AttachmentWriteResult
}
