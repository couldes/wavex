package com.wavex.agent.core.data.attachment

import java.io.File
import java.io.InputStream

/**
 * 用户附件入口快照落盘（挑选/分享时把外部 content:// 字节复制进应用私有目录 filesDir/attachments/）：
 * - 磁盘名 = UUID.ext，与 ChatAttachment.name（显示名）彻底分离——永不冲突、孤儿清理不误删，
 *   与 GeneratedFileStore「uri 与显示名分离」模式一致；
 * - 背景：附件曾只存外部 uri + 尽力取持久授权。分享入口几乎必然拿不到持久授权（来源 App 不给
 *   FLAG_GRANT_PERSISTABLE_URI_PERMISSION），进程被杀/重启后历史重建全部读失败，每条消息都报
 *   「无法读取…文件可能已被移动或删除」。入口快照后，加载不再依赖外部授权的持续有效；
 * - IO 失败返回 null 不抛（调用方回退旧行为：保留原 uri + 尽力取持久授权）；
 * - 启动时孤儿清理（见 sweepOrphans），由 DefaultConversationRepository.loadAll 驱动。
 */
class IntakeAttachmentStore(private val dir: File) {

    /** 绑定的快照目录（DefaultConversationRepository 的孤儿清扫按它对账） */
    val directory: File get() = dir

    /** 把流内容写 dir/UUID.ext，返回 file:// uri；失败返回 null 并清掉残盘文件。 */
    fun copy(stream: InputStream, ext: String): String? {
        var file: File? = null
        return try {
            dir.mkdirs()
            file = File(dir, "${java.util.UUID.randomUUID()}.${extFor(ext)}")
            file.outputStream().use { out -> stream.use { it.copyTo(out) } }
            "file://${file.absolutePath}"
        } catch (_: Exception) {
            file?.let { f -> try { f.delete() } catch (_: Exception) { /* 残盘清理尽力而为 */ } }
            null
        }
    }

    /**
     * 启动孤儿清理：删除 dir 下未被任何对话引用的快照文件。
     * referenced = 全树（含所有分支节点）attachment uri 的集合（file:// 形态，与 copy 返回值同构）。
     * 目录缺失/列取失败静默返回（清理是 best-effort，不值得为它打断启动）。
     */
    fun sweepOrphans(referenced: Set<String>) {
        val files = try { dir.listFiles() } catch (_: Exception) { null } ?: return
        files.forEach { f ->
            if (f.isFile && "file://${f.absolutePath}" !in referenced) {
                try { f.delete() } catch (_: Exception) { /* 单文件失败不影响其余 */ }
            }
        }
    }

    companion object {
        /** 扩展名规范化：小写、只留字母数字、截断到 8 位；空回落 bin（仅磁盘名外观，分类按显示名/MIME） */
        fun extFor(raw: String): String {
            val cleaned = raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(8)
            return cleaned.ifBlank { "bin" }
        }
    }
}
