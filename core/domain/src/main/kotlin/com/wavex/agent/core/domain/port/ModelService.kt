package com.wavex.agent.core.domain.port

import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.ConnectionReport
import com.wavex.agent.core.domain.model.Provider

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
        onImage: (String) -> Unit = {}
    )

    suspend fun generateTitle(provider: Provider, transcript: String): String?
    suspend fun fetchModels(provider: Provider): List<String>?
    suspend fun probe(provider: Provider): ConnectionReport
}
