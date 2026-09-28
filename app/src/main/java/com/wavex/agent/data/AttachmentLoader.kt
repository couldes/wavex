package com.wavex.agent.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wavex.agent.model.ChatAttachment

/** 附件内容加载：图片转 base64 data URL（视觉消息），文本文件内联内容，PDF 转逐页图片；其余不支持。 */
object AttachmentLoader {
    private const val MAX_TEXT_CHARS = 20000
    // 图片发送上限：长边像素与压缩质量。超大图直接 base64 会 OOM（实测 60MB 图闪退），
    // 先降采样到视觉模型识别够用的尺寸再压缩，兼顾清晰度与请求体大小。
    private const val MAX_IMAGE_DIMEN = 1568
    private const val JPEG_QUALITY = 85
    // 音频发送上限：base64 后约 20MB 请求体，超出直接拒绝（中转站一般也会拒）
    private const val MAX_AUDIO_BYTES = 15 * 1024 * 1024
    // PDF 发送上限：同量级防请求体爆炸；PDF 原生 base64 上传（OpenAI "file" 类型），
    // 模型直接读原始文档（保留排版/文本层），而非转图片
    // OpenAI 兼容接口支持的音频格式（input_audio）；其余音频格式暂不支持
    private val AUDIO_MIMES = mapOf(
        "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/wave" to "wav", "audio/vnd.wave" to "wav",
        "audio/mpeg" to "mp3", "audio/mp3" to "mp3"
    )
    // 已解析附件的内存缓存（key=uri），多轮对话不再重复解码/转 base64，减少 GC 卡顿
    private val cache = object : android.util.LruCache<String, Pair<String, String>>(6_000_000) {
        override fun sizeOf(key: String, value: Pair<String, String>): Int = value.second.length
    }
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "json", "xml", "yml", "yaml", "log",
        "kt", "java", "py", "js", "ts", "jsx", "tsx", "html", "htm", "css", "scss",
        "c", "h", "cpp", "hpp", "cc", "sh", "bat", "ps1", "sql", "toml", "ini", "conf", "cfg",
        "gradle", "properties", "env", "gitignore", "dockerfile", "cmake", "swift", "rb", "go",
        "rs", "php", "dart", "lua", "pl", "r", "m", "srt", "vtt", "lrc"
    )
    // 按扩展名识别的音频格式（MIME 缺失时的兑底）
    private val AUDIO_EXTENSIONS = mapOf(
        "mp3" to "mp3", "wav" to "wav", "wave" to "wav"
    )
    // 按扩展名识别的图片格式
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

    /**
     * 软件层面能否处理该附件：返回 null = 支持；非 null = 不支持原因（可直接展示给用户）。
     * 软件无法处理的格式（PDF/未知类型/非 MP3·WAV 音频）转不成 API 载荷，
     * 模型肯定收不到 —— 挑选时即拦截并报错，不浪费时间上传。
     * 软件支持的格式（图片/音频/文本）放行，模型层面是否接受由发送后的
     * 降级链判断（isModelError），两层错误分开、各自在最早时机提示。
     */
    fun unsupportedReason(context: android.content.Context, uri: android.net.Uri, name: String): String? {
        val mime = (try { context.contentResolver.getType(uri) } catch (_: Exception) { null } ?: "").lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        val effectiveMime = when {
            mime.isNotBlank() && mime != "application/octet-stream" -> mime
            ext in IMAGE_EXTENSIONS -> "image/x"
            ext in AUDIO_EXTENSIONS -> "audio/x"
            else -> mime
        }
        return when {
            effectiveMime == "application/pdf" || ext == "pdf" -> null
            effectiveMime.startsWith("image/") || ext in IMAGE_EXTENSIONS -> null
            effectiveMime.startsWith("text/") || ext in TEXT_EXTENSIONS -> null
            else -> {
                val format = AUDIO_MIMES[effectiveMime]
                    ?: AUDIO_EXTENSIONS[ext]
                    ?: if (effectiveMime.startsWith("audio/")) effectiveMime.substringAfter('/') else null
                when {
                    format == null -> "暂不支持该文件类型（目前支持图片、音频和文本类文件）"
                    format != "mp3" && format != "wav" -> "音频仅支持 MP3 / WAV 格式"
                    else -> null
                }
            }
        }
    }

    /**
     * 图片 → 缩放后的 JPEG data URL：
     * 1) 只读尺寸（inJustDecodeBounds），按 2 的幂降采样到长边 ≤ MAX_IMAGE_DIMEN；
     * 2) 再精确缩放到目标尺寸，JPEG 压缩后 base64。
     * 这样任何分辨率的图都能稳定控制在几百 KB 内，不会 OOM。
     */
    private fun loadScaledImage(resolver: android.content.ContentResolver, uri: android.net.Uri): String? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= MAX_IMAGE_DIMEN) sample *= 2
        val sampled = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, sampled)
        } ?: return null

        val longEdge = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longEdge > MAX_IMAGE_DIMEN) {
            val scale = MAX_IMAGE_DIMEN.toFloat() / longEdge
            android.graphics.Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else bitmap
        if (scaled !== bitmap) bitmap.recycle()

        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        if (scaled.width > 0) scaled.recycle()
        return "data:image/jpeg;base64,$b64"
    }

    /** 返回 (类型, 内容)：image=dataURL、text=内联文本；null=不支持该类型。结果会进 LruCache。 */
    suspend internal fun loadContent(context: android.content.Context, attachment: ChatAttachment): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val cacheKey = attachment.uri.toString()
            cache.get(cacheKey)?.let { return@withContext it }
            try {
                val uri = attachment.uri
                val resolver = context.contentResolver
                val mime = (resolver.getType(uri) ?: "").lowercase()
                val ext = attachment.name.substringAfterLast('.', "").lowercase()
                // MIME 缺失/笼统（application/octet-stream）时按扩展名兑底判断
                val effectiveMime = when {
                    mime.isNotBlank() && mime != "application/octet-stream" -> mime
                    ext in IMAGE_EXTENSIONS -> "image/x"
                    ext in AUDIO_EXTENSIONS -> "audio/x"
                    else -> mime
                }
                val loaded = when {
                    effectiveMime.startsWith("image/") || ext in IMAGE_EXTENSIONS -> {
                        val dataUrl = loadScaledImage(resolver, uri) ?: return@withContext null
                        "image" to dataUrl
                    }
                    effectiveMime.startsWith("text/") || ext in TEXT_EXTENSIONS -> {
                        val text = resolver.openInputStream(uri)?.use {
                            it.bufferedReader().readText()
                        } ?: return@withContext null
                        if (text.length > MAX_TEXT_CHARS) {
                            "text" to "文件 ${attachment.name}：\n${text.take(MAX_TEXT_CHARS)}\n…（内容过长已截断）"
                        } else {
                            "text" to "文件 ${attachment.name}：\n$text"
                        }
                    }
                    effectiveMime in AUDIO_MIMES || effectiveMime.startsWith("audio/") || ext in AUDIO_EXTENSIONS -> {
                        val format = AUDIO_MIMES[effectiveMime]
                            ?: AUDIO_EXTENSIONS[ext]
                            ?: if (effectiveMime.startsWith("audio/")) effectiveMime.substringAfter('/') else null
                        // OpenAI input_audio 只收 mp3/wav，其余音频格式不发送（否则整个请求被拒）
                        if (format == null || format !in setOf("mp3", "wav")) return@withContext null
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@withContext null
                        if (bytes.size > MAX_AUDIO_BYTES) return@withContext null
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        "audio" to "$format|$b64"
                    }
                    // PDF：API 协议原生支持（OpenAI file 类型 / Anthropic document 类型），
                    // 原样 base64 上传，模型直接读原始文档；模型不支持时由发送后降级链处理
                    effectiveMime == "application/pdf" || ext == "pdf" -> {
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@withContext null
                        if (bytes.size > MAX_AUDIO_BYTES) return@withContext null
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        "pdf" to b64
                    }
                    else -> null
                }
                loaded?.let { cache.put(cacheKey, it) }
                loaded
            } catch (_: Exception) {
                null
            }
        }
}
