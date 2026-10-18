package com.wavex.agent.core.domain.model

/**
 * 单条搜索结果。`id` 是模型写引用标记时用的短标识（`[cite:id]`），
 * 由搜索实现生成，同一批结果内唯一。
 */
data class WebSearchResult(
    val id: String,
    val index: Int,
    val title: String,
    val url: String,
    val text: String,
)

/**
 * 搜索结果的三种归宿——区分它们是因为三者在回路里的处置完全不同：
 * - [Ok]：搜到了（可能是空列表，「搜了没结果」不等于「搜索坏了」）；
 * - [Permanent]：未配置 / 凭据无效，重试永远不会成功，必须停止回路并告知用户；
 * - [Transient]：网络抖动 / 上游 5xx / 限流，可重试，交给模型自行决定是否重搜。
 */
sealed class WebSearchOutcome {
    data class Ok(val results: List<WebSearchResult>) : WebSearchOutcome()
    data class Permanent(val reason: String, val userAction: String) : WebSearchOutcome()
    data class Transient(val reason: String) : WebSearchOutcome()
}
