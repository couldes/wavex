package com.wavex.agent.core.domain.port

/**
 * 用户附件入口快照端口（挑选/分享时把外部 uri 字节复制进应用私有存储，磁盘名 UUID.ext）。
 * 实现由 data 层提供（IntakeAttachmentSnapshotter，包装 IntakeAttachmentStore）；
 * 快照失败返回 null（调用方回退：保留原 uri + 尽力取持久授权），不抛。
 */
fun interface AttachmentSnapshotter {
    fun snapshot(uri: String, name: String): String?
}
