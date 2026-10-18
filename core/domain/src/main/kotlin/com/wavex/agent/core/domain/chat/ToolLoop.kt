package com.wavex.agent.core.domain.chat

import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.ToolCall
import com.wavex.agent.core.domain.model.WebSearchOutcome
import com.wavex.agent.core.domain.model.WebSearchResult
import com.wavex.agent.core.domain.port.SearchProvider

/** 回路参数（spec §4.5）：轮次上限与结果预算，全部可配置 */
data class ToolLoopConfig(
    val maxRounds: Int = 5,
    val resultsPerSearch: Int = 6,
    val maxCharsPerResult: Int = 2000,
)

/**
 * 一轮请求的发送器：把历史发给模型，正文增量由调用方在 `streamChat` 回调里消费，
 * 这里只带回本轮模型请求的工具调用（空列表 = 模型收尾了）。
 */
fun interface RoundSender {
    suspend fun send(history: List<ChatRequestMessage>): List<ToolCall>
}

/** 回路终态 */
sealed class ToolLoopResult {
    /** 正常结束（模型不再调用工具） */
    data object Done : ToolLoopResult()

    /** 达到轮数上限 */
    data class RoundLimit(val rounds: Int) : ToolLoopResult()

    /** 搜索永久失败，已回填错误并让模型无搜索作答 */
    data class SearchUnavailable(val reason: String) : ToolLoopResult()
}

/**
 * 客户端工具回路：模型要搜索 → App 搜 → 结果回填 → 再问模型，直到模型给出正文。
 *
 * 放在 domain 是刻意的：轮次控制、截断、失败分类都是纯逻辑，用注入的
 * [RoundSender] 和 [SearchProvider] 驱动，JVM 单测能跑完整回路而不碰网络。
 *
 * 三类失败处置不同（spec §6）：
 * - [WebSearchOutcome.Transient]：回填 `retryable`，让模型自己决定是否重搜，回路继续；
 * - [WebSearchOutcome.Permanent]：回填 `terminal` 并明确「不要重试」，发一轮收尾后停止；
 * - 协程取消：原样向上抛，绝不伪装成业务错误（否则用户点了停止回路还在跑）。
 */
class ToolLoop(
    private val config: ToolLoopConfig = ToolLoopConfig(),
    private val search: SearchProvider?,
    private val onToolStarted: (ToolCall) -> Unit = {},
    private val onToolFinished: (ToolCall, WebSearchOutcome) -> Unit = { _, _ -> },
) {

    suspend fun run(
        history: MutableList<ChatRequestMessage>,
        sendRound: RoundSender,
    ): ToolLoopResult {
        var round = 0
        while (true) {
            round++
            val calls = sendRound.send(history.toList())
            if (calls.isEmpty()) return ToolLoopResult.Done

            // assistant 消息必须连 toolCalls 一起进历史：下一轮请求要靠它配对 tool 结果，
            // 只写正文会让上游看到孤儿 tool_result 而 400
            history += ChatRequestMessage(role = "assistant", text = "", toolCalls = calls)

            var permanent: WebSearchOutcome.Permanent? = null
            for (call in calls) {
                if (permanent != null) {
                    // 前一个并行调用已永久失败：剩下的也各回填一条，否则留下 tool_use 孤儿
                    history += ChatRequestMessage(
                        role = "tool",
                        text = encodeOutcome(
                            WebSearchOutcome.Permanent("已取消", "前一个搜索失败，本轮已中止")
                        ),
                        toolCallId = call.id,
                    )
                    continue
                }
                onToolStarted(call)
                val outcome = execute(call)
                onToolFinished(call, outcome)
                history += ChatRequestMessage(
                    role = "tool",
                    text = encodeOutcome(outcome),
                    toolCallId = call.id,
                )
                if (outcome is WebSearchOutcome.Permanent) permanent = outcome
            }

            if (permanent != null) {
                // 再发一轮（此时 tools 声明仍开着，但历史里已有 terminal 结果，
                // 模型被要求不要重试）——用户至少能拿到一句不含编造事实的解释
                sendRound.send(history.toList())
                return ToolLoopResult.SearchUnavailable(permanent.reason)
            }
            // 轮数上限：本轮的工具已执行完（结果都回填了，不留孤儿），到此收手
            if (round >= config.maxRounds) return ToolLoopResult.RoundLimit(round)
        }
    }

    private suspend fun execute(call: ToolCall): WebSearchOutcome {
        if (call.name != WEB_SEARCH) {
            return WebSearchOutcome.Permanent("不支持的工具：${call.name}", "请关闭联网搜索后重试")
        }
        val query = call.query?.trim().orEmpty()
        if (query.isEmpty()) {
            return WebSearchOutcome.Permanent("参数缺失：搜索关键词为空", "请换个问法让模型明确搜索内容")
        }
        val provider = search
            ?: return WebSearchOutcome.Permanent("未配置搜索源", "请在设置中启用联网搜索")
        return provider.search(query, config.resultsPerSearch)
    }

    /** 手工拼 JSON：domain 是纯 Kotlin/JVM，不引 org.json */
    private fun encodeOutcome(outcome: WebSearchOutcome): String = when (outcome) {
        is WebSearchOutcome.Ok -> encodeResults(outcome.results)
        is WebSearchOutcome.Transient ->
            """{"error":${escapeJson(outcome.reason)},"retryable":true}"""
        is WebSearchOutcome.Permanent ->
            """{"error":${escapeJson(outcome.reason)},"terminal":true,""" +
                """"userMessage":${escapeJson(outcome.userAction)}}"""
    }

    private fun encodeResults(results: List<WebSearchResult>): String {
        val items = results.joinToString(",") { r ->
            val text = if (r.text.length > config.maxCharsPerResult) {
                r.text.take(config.maxCharsPerResult) + "…"
            } else {
                r.text
            }
            """{"id":${escapeJson(r.id)},"index":${r.index},"title":${escapeJson(r.title)},""" +
                """"url":${escapeJson(r.url)},"text":${escapeJson(text)}}"""
        }
        return """{"items":[$items]}"""
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private companion object {
        const val WEB_SEARCH = "web_search"
    }
}
