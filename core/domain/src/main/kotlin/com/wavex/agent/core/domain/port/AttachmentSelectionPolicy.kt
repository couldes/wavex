package com.wavex.agent.core.domain.port

/**
 * 附件可选性策略端口：挑选附件前询问；返回 null 表示可选中，
 * 否则返回面向用户的不支持原因（如「文件过大」「格式不支持」）。
 * 纯 Kotlin 声明；实现由后续任务在 data 层接入。
 */
fun interface AttachmentSelectionPolicy {
    fun unsupportedReason(uri: String, name: String): String?
}
