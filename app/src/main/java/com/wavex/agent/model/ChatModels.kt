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
 * DeepSeek 的 /anthropic 网关等）时自动切换为 Anthropic 协议（/v1/messages）。
 * 协议只决定端点叶子与默认鉴权头，实际鉴权头被拒时会自动换一种重发（见 network.Connection）。
 */
internal enum class ApiProtocol {
    /**
     * 按 Base URL 自动识别协议：域名以 anthropic.com 结尾或路径含 /anthropic 段时走
     * Anthropic（/v1/messages），否则 OpenAI 兼容（/chat/completions）。协议只决定端点
     * 叶子与默认鉴权头，实际鉴权被拒时会在 ApiClient.executeWithAuthFallback 中换一种重试。
     */ OPENAI, ANTHROPIC }

internal data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val fromUser: Boolean,
    val attachments: List<ChatAttachment> = emptyList(),
    val isError: Boolean = false,
    // 模型思考过程（reasoning_content 流式增量）：气泡里渲染成可折叠的「已思考 N 字」区块
    val reasoning: String = ""
)
