package com.wavex.agent.core.domain.port

import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.ConnectionReport
import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.domain.model.ToolCall

/**
 * 现有请求的可替换边界；生产实现由 core:network 的 createModelService 装配
 * （HTTP 执行与 JSON/SSE 解析留在 network 模块，端口声明归 domain）。
 */
interface ModelService {
    suspend fun streamChat(
        provider: Provider,
        history: List<ChatRequestMessage>,
        reasoningEffort: String?,
        webSearch: Boolean,
        onDelta: (String, String) -> Unit,
        onImage: (String) -> Unit = {},
        /**
         * 本轮模型请求的工具调用（流结束时给出完整参数）。
         * 联网搜索由 App 本机执行：network 层只负责把协议增量还原成 [ToolCall]，
         * 是否执行、执行几轮由 domain 的 ToolLoop 决定。
         */
        onToolCalls: (List<ToolCall>) -> Unit = {},
        /**
         * 本轮 assistant 的思考原文与签名（流结束时一次性给出）。
         * Anthropic 协议在工具轮次要把它原样回传，所以 domain 需要拿到，
         * 不能只当 UI 的「已思考 N 字」看完就丢。
         */
        onThinking: (thinking: String, signature: String) -> Unit = { _, _ -> }
    )

    suspend fun generateTitle(provider: Provider, transcript: String): String?
    suspend fun fetchModels(provider: Provider): List<String>?
    suspend fun probe(provider: Provider): ConnectionReport
}
