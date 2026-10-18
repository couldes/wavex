package com.wavex.agent.core.domain.model

/**
 * 模型请求的一次工具调用。
 *
 * [arguments] 保留原始 JSON 串：回填给上游时必须逐字一致，
 * 否则 Anthropic 的 `tool_use` 块校验会失败。
 * [query] 是 network 层解析时顺手提取的搜索关键词，使 domain 的
 * 回路驱动器不必依赖 JSON 解析器；非 web_search 工具或参数畸形时为 null。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val query: String? = null,
)
