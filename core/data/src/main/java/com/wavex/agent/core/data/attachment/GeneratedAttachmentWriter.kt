package com.wavex.agent.core.data.attachment

import com.wavex.agent.core.data.GeneratedFileStore
import com.wavex.agent.core.domain.model.AttachmentWriteResult
import com.wavex.agent.core.domain.port.GeneratedFileWriter

/**
 * GeneratedFileWriter 端口的数据层实现：包装 GeneratedFileStore（绑定生成目录）。
 * 已由 AppContainer.generatedFileWriter 端口接线（组合根装配）。
 */
class GeneratedAttachmentWriter(
    private val generatedFileStore: GeneratedFileStore,
) : GeneratedFileWriter {
    override fun write(mime: String, base64: String, displayName: String): AttachmentWriteResult =
        generatedFileStore.write(mime, base64)
}
