package com.wavex.agent.core.domain.port

import com.wavex.agent.core.domain.model.WebSearchOutcome

/**
 * 搜索源端口：实现放在 :core:network（HTTP 执行归 network），
 * domain 只声明契约。内置实现为 Bing RSS，后续可加 SearXNG / Tavily 等。
 */
interface SearchProvider {
    val id: String
    suspend fun search(query: String, limit: Int = 6): WebSearchOutcome
}
