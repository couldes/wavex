package com.wavex.agent.core.data.attachment

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.wavex.agent.core.data.GeneratedFileStore
import com.wavex.agent.core.domain.port.AttachmentSaveOutcome
import com.wavex.agent.core.domain.port.AttachmentSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型返回附件的「保存到本机」（AttachmentSaver 端口的数据层实现）：
 * - 图片 → 相册（MediaStore.Images，Pictures/Wavex/）
 * - 非图片 → 下载目录（MediaStore.Downloads / Download/Wavex/）
 *
 * 纯函数（文件名清理、MIME 推导）供 JVM 测试钉行为；MediaStore 与网络读取依赖
 * Context/ContentResolver，走真机验证。存储权限（WRITE_EXTERNAL_STORAGE）由调用方
 * 在 API ≤28 时负责运行时请求（29+ scoped storage 不需要）。
 */
class MediaAttachmentSaver(private val context: Context) : AttachmentSaver {

    override val galleryDirectory: String = GALLERY_DIR
    override val downloadDirectory: String = DOWNLOAD_DIR

    /** 图片 → 相册 Pictures/Wavex/。调用方需保证已获存储权限（API ≤28）。 */
    override suspend fun saveToGallery(uri: String, displayName: String): AttachmentSaveOutcome =
        save(uri, displayName, toGallery = true)

    /** 非图片 → 下载目录 Download/Wavex/。调用方需保证已获存储权限（API ≤28）。 */
    override suspend fun saveToDownloads(uri: String, displayName: String): AttachmentSaveOutcome =
        save(uri, displayName, toGallery = false)

    private suspend fun save(uri: String, displayName: String, toGallery: Boolean): AttachmentSaveOutcome =
        withContext(Dispatchers.IO) {
            try {
                // MIME 线索 + 内容复制共用同一条流：此前探测后重开一条，http(s) 源会
                // 两次建连、整图下载两遍。mark/reset 读完 12 字节头部后回到起点继续拷贝
                java.io.BufferedInputStream(openSource(context, uri), 8192).use { src ->
                    src.mark(16)
                    val head = ByteArray(12)
                    var read = 0
                    while (read < head.size) {
                        val n = src.read(head, read, head.size - read)
                        if (n < 0) break
                        read += n
                    }
                    src.reset()
                    val mime = mimeFromUri(uri) ?: sniffMime(head) ?: mimeFromNameFallback(displayName)
                    val savedName = sanitizeFileName(displayName, mime)
                    val target = insertTarget(context, savedName, mime, toGallery)
                        ?: return@withContext AttachmentSaveOutcome.Failed("创建目标文件失败")
                    val out = target.out ?: return@withContext AttachmentSaveOutcome.Failed("无法写入目标文件")
                    out.use { output -> src.copyTo(output) }
                    target.finalize()
                    AttachmentSaveOutcome.Saved(savedName)
                }
            } catch (e: Exception) {
                AttachmentSaveOutcome.Failed(e.message ?: "保存失败")
            }
        }

    /** 显示名扩展名 → MIME（file/content 附件无 URL MIME 时的兜底；无扩展名按二进制流）。 */
    private fun mimeFromNameFallback(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return EXT_TO_MIME[ext] ?: "application/octet-stream"
    }

    /** 打开源流：http(s) 走网络 GET，file: 直接读，data: 解 base64，content: 走 ContentResolver。 */
    private fun openSource(context: Context, uri: String): InputStream {
        val lower = uri.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> {
                val conn = URL(uri).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                conn.inputStream
            }
            lower.startsWith("file://") -> File(java.net.URI(uri)).inputStream()
            lower.startsWith("data:") -> {
                val b64 = uri.substringAfter("base64,", "")
                java.util.Base64.getDecoder().decode(b64).inputStream()
            }
            else -> context.contentResolver.openInputStream(android.net.Uri.parse(uri))
                ?: throw IllegalStateException("无法读取源文件")
        }
    }

    /** 写入目标：Q+ 走 MediaStore（写完清 IS_PENDING）；≤28 直接落公共目录文件（同名顺延）。 */
    private fun insertTarget(
        context: Context, name: String, mime: String, toGallery: Boolean
    ): Target? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = if (toGallery) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                val rel = if (toGallery) GALLERY_DIR else DOWNLOAD_DIR
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$rel/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val outUri = resolver.insert(collection, values) ?: return null
            return Target(resolver.openOutputStream(outUri)) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                runCatching { resolver.update(outUri, done, null, null) }
            }
        }
        val base = if (toGallery) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(base, "Wavex").apply { mkdirs() }
        val file = uniqueFile(dir, name)
        return Target(file.outputStream()) { }
    }

    /** ≤28 直接落文件：同名顺延 (1)、(2)，不覆盖已有文件。 */
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val stem = name.substringBeforeLast('.', "")
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = if (ext.isEmpty()) File(dir, "$stem($i)") else File(dir, "$stem($i).$ext")
            i++
        }
        return f
    }

    /** 写入目标：输出流 + finalize（Q+ 清 IS_PENDING 落库；旧版本无操作）。 */
    private class Target(val out: java.io.OutputStream?, val finalize: () -> Unit)

    companion object {
        const val GALLERY_DIR = "Pictures/Wavex"
        const val DOWNLOAD_DIR = "Download/Wavex"

        /** ext → MIME（GeneratedFileStore.TABLE 的反向表；两处互指，勿单边改动）。 */
        private val EXT_TO_MIME = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "webp" to "image/webp", "gif" to "image/gif", "svg" to "image/svg+xml",
            "pdf" to "application/pdf", "txt" to "text/plain", "csv" to "text/csv",
            "json" to "application/json", "mp3" to "audio/mpeg", "wav" to "audio/wav",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "zip" to "application/zip"
        )

        /** 显示名 → 保存文件名：剥路径段、文件系统非法字符（\/:*?"<>|）替换为 _、
         *  首尾空白裁掉、缺扩展名按 MIME 补全、主名为空兜底「附件」。 */
        fun sanitizeFileName(name: String, mime: String): String {
            val illegal = Regex("[\\\\/:*?\"<>|]")
            var base = name.substringAfterLast('/').trim()
            base = illegal.replace(base, "_")
            val ext = base.substringAfterLast('.', "")
            val stem = if (ext.isEmpty()) base else base.removeSuffix(".$ext")
            val finalExt = when {
                ext.isNotEmpty() -> ext
                mime == "application/octet-stream" -> ""
                else -> GeneratedFileStore.extFor(mime)
            }
            val finalStem = if (stem.isEmpty()) "附件" else stem
            return if (finalExt.isEmpty()) finalStem else "$finalStem.$finalExt"
        }

        /** 保存时源 MIME 推导：http(s) URL 按扩展名反查、data: URL 直接取；其余（file:/content:）返回 null。 */
        fun mimeFromUri(uri: String): String? {
            val lower = uri.lowercase()
            if (lower.startsWith("data:")) {
                val mime = uri.substringAfter("data:", "").substringBefore(';')
                return mime.ifBlank { null }
            }
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
            val path = uri.substringBefore('?').substringBefore('#')
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty() || ext.length > 5 || ext.any { !it.isLetterOrDigit() }) return null
            return EXT_TO_MIME[ext]
        }

        /** 文件头魔数呦探（前 12 字节）：无 MIME 线索的附件修正真实类型，
         *  尤其旧版落盘的无扩展名生成文件。识别不出返回 null。 */
        fun sniffMime(head: ByteArray): String? {
            if (head.size < 4) return null
            fun startsWith(vararg prefix: Byte) = head.size >= prefix.size &&
                prefix.indices.all { head[it] == prefix[it] }
            fun at(offset: Int, vararg prefix: Byte) = head.size >= offset + prefix.size &&
                prefix.indices.all { head[offset + it] == prefix[it] }
            return when {
                startsWith(0x89.toByte(), 0x50, 0x4E, 0x47) -> "image/png"                          // ‰PNG
                startsWith(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) -> "image/jpeg"             // FFD8FF
                startsWith(0x47, 0x49, 0x46) -> "image/gif"                                         // GIF8
                startsWith(0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "image/webp" // RIFF…WEBP
                startsWith(0x25, 0x50, 0x44, 0x46) -> "application/pdf"                             // %PDF
                startsWith(0x50, 0x4B) -> "application/zip"                                         // PK（xlsx/docx 容器）
                else -> null
            }
        }
    }
}
