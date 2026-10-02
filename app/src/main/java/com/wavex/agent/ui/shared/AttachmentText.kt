package com.wavex.agent.ui.shared

import com.wavex.agent.model.ChatAttachment

/** 图片附件后缀判断（预编译正则：此前每次组合都重新编译一遍）；svg 可被 coil-svg 渲染为缩略图 */
internal val IMAGE_NAME_REGEX = Regex(".*\\.(png|jpg|jpeg|webp|gif|bmp|svg)$")

/** 音频格式后缀集合（展示「音频 · ext」标签用） */
internal val AUDIO_EXT_SET = setOf("MP3", "WAV", "M4A", "AAC", "OGG", "FLAC", "MPEG", "X-WAV")

/** 附件格式标签：优先文件扩展名；扩展名缺失/过长时回退到 MIME 子类型（如 pdf） */
internal fun attachmentExtLabel(context: android.content.Context, attachment: ChatAttachment): String {
    val ext = attachment.name.substringAfterLast('.', "").uppercase()
    if (ext.isNotBlank() && ext.length <= 5) return ext
    return runCatching {
        context.contentResolver.getType(android.net.Uri.parse(attachment.uri))
            ?.substringAfterLast('/')?.uppercase()
    }.getOrNull().takeIf { !it.isNullOrBlank() && it != "PLAIN" && it != "OCTET-STREAM" } ?: "文件"
}

/** 展示名：camera 时间戳命名转成友好文案，其余截短到 18 字 */
internal fun attachmentDisplayName(name: String): String {
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

internal fun isImageAttachment(context: android.content.Context, attachment: ChatAttachment): Boolean {
    val byMime = runCatching {
        context.contentResolver.getType(android.net.Uri.parse(attachment.uri))?.startsWith("image/") == true
    }.getOrDefault(false)
    return byMime || IMAGE_NAME_REGEX.matches(attachment.name.lowercase())
}
