package com.wavex.agent.core.domain.model

import java.util.UUID

/** 聊天附件（uri + 显示名 + 唯一 id）。同一文件可添加多次，id 保证逐个定位不串位。 */
data class ChatAttachment(
    val uri: String,
    val name: String,
    val id: String = UUID.randomUUID().toString()
)

/** 发给 API 的消息：文本 + 可选图片（data URL，走视觉模型）。 */
data class ChatRequestMessage(
    val role: String,
    val text: String,
    val imageDataUrls: List<String> = emptyList(),
    /** 仅 assistant 消息携带：本轮模型请求的工具调用 */
    val toolCalls: List<ToolCall> = emptyList(),
    /** 仅 role="tool" 携带：对应哪一次工具调用 */
    val toolCallId: String? = null,
    /**
     * 仅 assistant 消息携带：模型本轮的思考原文。
     *
     * Anthropic 协议下只要历史里出现了 tool_use，上游就要求把同一条 assistant 消息的
     * thinking 块原样传回，否则 400。所以工具回路必须带上它，不能只给 UI 看完就丢。
     */
    val thinking: String? = null,
    /** [thinking] 的签名（Anthropic signature_delta）；上游按字段存在性校验，缺了要补空串 */
    val thinkingSignature: String? = null,
)

/**
 * API 协议：绝大多数服务商走 OpenAI 兼容协议；Base URL 指向 Anthropic（官方或
 * DeepSeek 的 /anthropic 网关等）时自动切换为 Anthropic 协议（/v1/messages）。
 * 协议只决定端点叶子与默认鉴权头，实际鉴权头被拒时会自动换一种重发（见 network.Connection）。
 */
enum class ApiProtocol {
    /**
     * 按 Base URL 自动识别协议：域名以 anthropic.com 结尾或路径含 /anthropic 段时走
     * Anthropic（/v1/messages），否则 OpenAI 兼容（/chat/completions）。协议只决定端点
     * 叶子与默认鉴权头，实际鉴权被拒时会在 network 层的鉴权回退中换一种重试。
     */ OPENAI, ANTHROPIC }

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val fromUser: Boolean,
    val attachments: List<ChatAttachment> = emptyList(),
    val isError: Boolean = false,
    // 模型思考过程（reasoning_content 流式增量）：气泡里渲染成可折叠的「已思考 N 字」区块
    val reasoning: String = "",
    // 思考块签名（Anthropic）：工具轮主要在同一次对话内回传，落盘是为了追问时还能配对
    val thinkingSignature: String = "",
    /** 本消息是 assistant 时：模型请求的工具调用 */
    val toolCalls: List<ToolCall> = emptyList(),
    /** 本消息是工具结果时：对应哪一次调用 */
    val toolCallId: String? = null,
    /** 本轮工具执行结果（工具卡片渲染用；持久化不落，重启后卡片折叠为历史形态） */
    val toolResults: List<ToolResultView> = emptyList(),
    /**
     * 引用映射（id → 序号 + URL）。与 [toolResults] 分开是因为它要落盘：
     * 不落的话重启后 `[cite:id]` 无处解析，原始标记会漏在气泡里。
     * 只存三个短字段，不背搜索正文。
     */
    val citationRefs: List<CitationRef> = emptyList(),
)
