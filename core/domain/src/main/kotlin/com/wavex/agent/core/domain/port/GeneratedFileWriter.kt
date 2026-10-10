package com.wavex.agent.core.domain.port

import com.wavex.agent.core.domain.model.AttachmentWriteResult

/**
 * 生成文件落盘端口（模型返回的 base64 附件 → 本机文件，磁盘名 UUID.ext）。
 * 实现由 data 层提供（GeneratedAttachmentWriter，包装 GeneratedFileStore）；
 * 超限返回 TooLarge（未写盘）、坏 base64/IO 失败返回 Failed，均不抛。
 */
fun interface GeneratedFileWriter {
    fun write(mime: String, base64: String, displayName: String): AttachmentWriteResult
}
