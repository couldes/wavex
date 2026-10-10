package com.wavex.agent.core.data.attachment

import com.wavex.agent.core.domain.port.AttachmentSelectionPolicy

/**
 * 附件可选性策略端口的数据层实现：委托 AttachmentLoader.unsupportedReason
 * （Context/MIME/扩展名判定留在 data 层），Domain 端口收 (uri: String, name: String)。
 */
class DefaultAttachmentSelectionPolicy(private val context: android.content.Context) : AttachmentSelectionPolicy {
    override fun unsupportedReason(uri: String, name: String): String? =
        AttachmentLoader.unsupportedReason(context, android.net.Uri.parse(uri), name)
}
