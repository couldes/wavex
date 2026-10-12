package com.wavex.agent.core.data.attachment

import android.content.Context
import android.webkit.MimeTypeMap
import com.wavex.agent.core.domain.port.AttachmentSnapshotter

/**
 * AttachmentSnapshotter 端口的数据层实现：入口（挑选/分享）时把外部 uri 字节复制进
 * IntakeAttachmentStore（filesDir/attachments/UUID.ext），返回 file:// uri。
 * 此刻授权尚新鲜、读取必然可用，是快照的唯一可靠时机；失败返回 null 由调用方回退。
 */
class IntakeAttachmentSnapshotter(
    private val context: Context,
    private val store: IntakeAttachmentStore,
) : AttachmentSnapshotter {
    override fun snapshot(uri: String, name: String): String? = try {
        val u = android.net.Uri.parse(uri)
        // 磁盘扩展名优先取显示名（AttachmentLoader 分类有显示名 ext 兜底），缺省按 MIME 推
        val rawExt = name.substringAfterLast('.', "")
        val ext = rawExt.ifBlank {
            val mime = try { context.contentResolver.getType(u) } catch (_: Exception) { null }
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime ?: "") ?: ""
        }
        context.contentResolver.openInputStream(u)?.use { store.copy(it, ext) }
    } catch (_: Exception) {
        null
    }
}
