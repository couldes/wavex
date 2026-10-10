package com.wavex.agent.core.domain.port

/**
 * 附件保存结果：Saved 带面向用户的文件名；Failed 带可 Toast 的原因
 * （与 data.AttachmentSaver.SaveOutcome 现行文案契约一致）。
 */
sealed interface AttachmentSaveOutcome {
    data class Saved(val display: String) : AttachmentSaveOutcome
    data class Failed(val reason: String) : AttachmentSaveOutcome
}

/**
 * 附件保存端口（图片 → 相册、其余 → 下载目录）：纯 Kotlin 声明，
 * Context/MediaStore 实现由后续任务在 data 层接入。目录常量沿用现行值
 * （Pictures/Wavex 与 Download/Wavex）。
 */
interface AttachmentSaver {
    /** 相册相对目录（如 "Pictures/Wavex"） */
    val galleryDirectory: String

    /** 下载相对目录（如 "Download/Wavex"） */
    val downloadDirectory: String

    /** 图片 → 相册。uri 为附件源地址（http(s)/file:/data:/content:），displayName 为显示名。 */
    suspend fun saveToGallery(uri: String, displayName: String): AttachmentSaveOutcome

    /** 非图片 → 下载目录。参数语义同 saveToGallery。 */
    suspend fun saveToDownloads(uri: String, displayName: String): AttachmentSaveOutcome
}
