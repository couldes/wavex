package com.wavex.agent.network

import com.wavex.agent.data.Provider
import com.wavex.agent.model.ChatRequestMessage

/** 现有请求的可替换边界；默认生产实现为 ApiClient。 */
internal interface ModelService {
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

/** 保持生产默认读超时；独立 HTTP 会话可以使用有界的测试配置。 */
internal data class ApiReadTimeouts(
    val firstDataMs: Long = 5_000L,
    val streamMs: Long = 120_000L
)
