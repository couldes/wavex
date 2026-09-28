package com.wavex.agent.engine

import com.wavex.agent.data.Provider
import com.wavex.agent.model.ChatAttachment
import com.wavex.agent.model.ChatRequestMessage

/**
 * 流式对话接口：生成引擎只依赖此抽象，测试用假实现驱动降级链，
 * 生产环境由 network.ApiClient 提供（签名原本就匹配）。
 */
internal fun interface ChatApi {
    suspend fun streamChat(
        provider: Provider,
        history: List<ChatRequestMessage>,
        reasoningEffort: String?,
        webSearch: Boolean,
        onDelta: (content: String, reasoning: String) -> Unit
    )
}

/**
 * 附件内容加载接口：历史构建只依赖此抽象，测试用假实现返回固定内容。
 * 生产环境由 data.AttachmentLoader 提供（见 HistoryBuilder.SystemContentLoader）。
 */
internal fun interface ContentLoader {
    suspend fun load(attachment: ChatAttachment): Pair<String, String>?
}
