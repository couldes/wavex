package com.wavex.agent.core.network

import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.domain.model.UsageLogEntry
import com.wavex.agent.core.domain.port.UsageSink
import java.util.UUID

/**
 * 流式 usage 累积器：parse 函数（Task 5/6）填充，请求出口（Task 7）读取。
 * 同一协程内写入与读取，无需并发防护。
 */
class StreamUsage {
    var inputTokens: Long = 0L
    var outputTokens: Long = 0L
}

/**
 * 埋点入口：构建快照条目并送达调用方显式传入的 sink（无全局状态）。
 * 全程 runCatching——统计永远不能伤害聊天主路径（spec §6）。
 */
object UsageTracker {
    fun recordWithSink(
        sink: UsageSink?,
        kind: String,                       // "chat" | "title" | "probe"（title/probe 仅落明细日志，不计入统计口径）
        provider: Provider,
        usage: StreamUsage?,
        statusCode: Int,                    // 2xx 成功；0 流级失败；499 用户取消
        latencyMs: Long,
        error: String = ""
    ) {
        runCatching {
            sink?.record(
                UsageLogEntry(
                    id = UUID.randomUUID().toString(),
                    kind = kind,
                    providerId = provider.id,
                    providerName = provider.name,   // 快照：Provider 删除后历史统计仍显示名字
                    model = provider.model,          // 快照
                    inputTokens = usage?.inputTokens ?: 0L,
                    outputTokens = usage?.outputTokens ?: 0L,
                    statusCode = statusCode,
                    latencyMs = latencyMs,
                    createdAt = System.currentTimeMillis(),
                    // 密钥打码后再截断：先 take 会把长 key 截半截留在账里
                    error = redactSecrets(error, provider.apiKey).take(300)
                )
            )
        }
    }
}
