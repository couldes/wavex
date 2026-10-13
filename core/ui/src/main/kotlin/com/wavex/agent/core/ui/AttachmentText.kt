package com.wavex.agent.core.ui

import android.util.LruCache
import com.wavex.agent.core.domain.model.ChatAttachment

/** 图片附件后缀判断（预编译正则：此前每次组合都重新编译一遍）；svg 可被 coil-svg 渲染为缩略图 */
internal val IMAGE_NAME_REGEX = Regex(".*\\.(png|jpg|jpeg|webp|gif|bmp|svg)$")

/** 纯字符串判断（零 I/O）：文件名带图片扩展名 */
internal fun isImageName(name: String): Boolean = IMAGE_NAME_REGEX.matches(name.lowercase())

/**
 * contentResolver.getType 结果缓存：它是 binder IPC + provider 查询，在滚动
 * 组合路径上逐次调用会造成可感知掉帧（上行首次经过附件气泡时明显，首查还可能
 * 撞上 provider 冷启动）。key = uri；用哨兵缓存「查过但为 null」，避免对无
 * MIME 的 uri 反复查询。
 */
private val mimeTypeCache = LruCache<String, String>(128)
private const val MIME_NULL = "\u0000"

private fun cachedMimeType(context: android.content.Context, uri: String): String? {
    val hit = mimeTypeCache.get(uri)
    if (hit != null) return if (hit == MIME_NULL) null else hit
    val fresh = runCatching { context.contentResolver.getType(android.net.Uri.parse(uri)) }.getOrNull()
    mimeTypeCache.put(uri, fresh ?: MIME_NULL)
    return fresh
}

/** 音频格式后缀集合（展示「音频 · ext」标签用） */
val AUDIO_EXT_SET = setOf("MP3", "WAV", "M4A", "AAC", "OGG", "FLAC", "MPEG", "X-WAV")

/** 附件格式标签：优先文件扩展名；扩展名缺失/过长时回退到 MIME 子类型（如 pdf） */
fun attachmentExtLabel(context: android.content.Context, attachment: ChatAttachment): String {
    val ext = attachment.name.substringAfterLast('.', "").uppercase()
    if (ext.isNotBlank() && ext.length <= 5) return ext
    return cachedMimeType(context, attachment.uri)
        ?.substringAfterLast('/')?.uppercase()
        ?.takeIf { it.isNotBlank() && it != "PLAIN" && it != "OCTET-STREAM" } ?: "文件"
}

/** 展示名：camera 时间戳命名转成友好文案，其余截短到 18 字 */
fun attachmentDisplayName(name: String): String {
    if (name.startsWith("camera_") && name.endsWith(".jpg")) {
        val stamp = name.removePrefix("camera_").removeSuffix(".jpg").toLongOrNull()
        if (stamp != null) {
            return "拍照 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(stamp))
        }
        return "拍照"
    }
    return name.takeLast(18)
}

fun isImageAttachment(context: android.content.Context, attachment: ChatAttachment): Boolean {
    // 文件名先判（纯字符串、零 I/O）：绝大多数图片附件带图片扩展名，
    // 不能让 contentResolver.getType 的 binder IPC 挡在组合主线程上
    //（上行滚动首次组合附件气泡时的掉帧源，实测）。语义与原先等价：
    // 名称匹配或 MIME 为 image/* 任一成立即为图片。
    if (isImageName(attachment.name)) return true
    return cachedMimeType(context, attachment.uri)?.startsWith("image/") == true
}
