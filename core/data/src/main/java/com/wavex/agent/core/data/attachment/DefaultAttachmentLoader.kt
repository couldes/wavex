package com.wavex.agent.core.data.attachment

import android.content.Context
import com.wavex.agent.core.domain.chat.ContentLoader
import com.wavex.agent.core.domain.model.ChatAttachment

/**
 * ContentLoader 端口的数据层实现：委托 AttachmentLoader
 * （图片→缩放 JPEG data URL、文本→内联、音频→input_audio、PDF→原生 base64；其余不支持）。
 * AppContainer.contentLoader 端口的生产缺省值。
 */
class DefaultAttachmentLoader(private val context: Context) : ContentLoader {
    override suspend fun load(attachment: ChatAttachment): Pair<String, String>? =
        AttachmentLoader.loadContent(context, attachment)
}
