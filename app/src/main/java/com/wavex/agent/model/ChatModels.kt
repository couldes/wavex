package com.wavex.agent.model

import java.util.UUID

/** 聊天附件（uri + 显示名 + 唯一 id）。同一文件可添加多次，id 保证逐个定位不串位。 */
internal data class ChatAttachment(
    val uri: String,
    val name: String,
    val id: String = UUID.randomUUID().toString()
)

/** 发给 API 的消息：文本 + 可选图片（data URL，走视觉模型）。 */
internal data class ChatRequestMessage(
    val role: String,
    val text: String,
    val imageDataUrls: List<String> = emptyList()
)

/**
 * API 协议：绝大多数服务商走 OpenAI 兼容协议；Base URL 指向 Anthropic（官方或
 * DeepSeek 的 /anthropic 网关等）时自动切换为 Anthropic 协议（/v1/messages + x-api-key）。
 */
internal enum class ApiProtocol { OPENAI, ANTHROPIC }

internal data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val fromUser: Boolean,
    val attachments: List<ChatAttachment> = emptyList(),
    val isError: Boolean = false,
    // 模型思考过程（reasoning_content 流式增量）：气泡里渲染成可折叠的「已思考 N 字」区块
    val reasoning: String = ""
)
