package com.wavex.agent.core.domain.model

/**
 * 引用映射的一条：搜索结果 id → 展示序号与跳转地址。
 *
 * 为什么不直接落盘 [ToolResultView]：它带着整段搜索正文，落盘会显著撑大会话文件，
 * 而渲染引用只需要「第几条 + 跳哪儿」。这层投影让重启后 `[cite:id]` 仍能解析成
 * 可点角标，而不是把原始标记漏在气泡里。
 */
data class CitationRef(
    val id: String,
    val index: Int,
    val url: String,
)
