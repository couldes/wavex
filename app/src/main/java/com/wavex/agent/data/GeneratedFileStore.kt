package com.wavex.agent.data

import com.wavex.agent.engine.ResponseImageExtractor

/**
 * 生成文件落盘（模型返回的 base64 附件）：
 * - 磁盘名 = UUID.ext，与 ChatAttachment.name（显示名）彻底分离——永不冲突、
 *   孤儿清理不误删，与用户附件「uri 与显示名分离」模式一致；
 * - 超限先按 base64 长度估算拒收（不解码，防 OOM）；解码/IO 失败返回 Failed 不抛；
 * - 目录 filesDir/generated/；启动时孤儿清理（见 sweepOrphans）。
 */
internal object GeneratedFileStore {

    /** 单文件解码后大小上限（沿用音频上传上限量级，见 AttachmentLoader.MAX_AUDIO_BYTES） */
    const val MAX_FILE_BYTES = 15 * 1024 * 1024

    /**
     * 解码 base64 并写 dir/UUID.ext。
     * 返回 Saved(uri=file://...) / TooLarge（估算超限，未写盘）/ Failed（坏 base64 或 IO 失败）。
     */
    fun write(dir: java.io.File, mime: String, base64: String): ResponseImageExtractor.WriteOutcome {
        // 先估尺寸再解码：base64 每字符 3/4 字节，超限直接拒（实测 60MB 图直接解码会 OOM）
        if (base64.length * 3 / 4 > MAX_FILE_BYTES) return ResponseImageExtractor.WriteOutcome.TooLarge
        return try {
            val bytes = java.util.Base64.getDecoder().decode(base64)
            dir.mkdirs()
            val file = java.io.File(dir, "${java.util.UUID.randomUUID()}.${extFor(mime)}")
            file.writeBytes(bytes)
            ResponseImageExtractor.WriteOutcome.Saved("file://${file.absolutePath}")
        } catch (_: Exception) {
            ResponseImageExtractor.WriteOutcome.Failed
        }
    }

    /**
     * 启动孤儿清理：删除 dir 下未被任何对话引用的生成文件。
     * referenced = 全树（含所有分支节点）attachment uri 的集合，file:// 前缀匹配本目录。
     * 目录缺失/列取失败静默返回（清理是 best-effort，不值得为它打断启动）。
     */
    fun sweepOrphans(dir: java.io.File, referenced: Set<String>) {
        val files = try { dir.listFiles() } catch (_: Exception) { null } ?: return
        val prefix = "file://${dir.absolutePath}${java.io.File.separator}"
        files.forEach { f ->
            if (f.isFile && "file://${f.absolutePath}" !in referenced &&
                "$prefix${f.name}" !in referenced
            ) {
                try { f.delete() } catch (_: Exception) { /* 单文件失败不影响其余 */ }
            }
        }
    }

    /**
     * MIME → 扩展名（磁盘名用，ACTION_VIEW 主要认 MIME，扩展名仅外观）。
     * 精确表与 engine.ResponseImageExtractor 的私有 extFor 保持同表（两处互指，勿单边改动）。
     */
    fun extFor(mime: String): String {
        TABLE[mime.lowercase()]?.let { return it }
        val sub = mime.substringAfter('/', "").substringBefore('+').lowercase()
        return if (sub.isBlank() || sub.startsWith("vnd.") || sub.contains('.')) "bin" else sub
    }

    private val TABLE = mapOf(
        "image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp",
        "image/gif" to "gif", "image/svg+xml" to "svg",
        "application/pdf" to "pdf", "text/plain" to "txt", "text/csv" to "csv",
        "application/json" to "json", "audio/mpeg" to "mp3", "audio/wav" to "wav",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/zip" to "zip"
    )
}
