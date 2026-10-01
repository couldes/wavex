package com.wavex.agent.network

import com.wavex.agent.data.Provider
import com.wavex.agent.data.UsageLogEntry
import java.util.UUID

/**
 * 用量埋点出口：ApiClient 在请求终态调用，实现方（AppContainer）落到 UsageStore。
 * 接口化解耦：ApiClient 不依赖 UsageStore，测试注入 fake 收集器即可断言。
 */
fun interface UsageSink {
    fun record(entry: UsageLogEntry)
}

/**
 * 流式 usage 累积器：parse 函数（Task 5/6）填充，请求出口（Task 7）读取。
 * 同一协程内写入与读取，无需并发防护。
 */
class StreamUsage {
    var inputTokens: Long = 0L
    var outputTokens: Long = 0L
}

/**
 * 埋点入口：构建快照条目并送达 sink。
 * 全程 runCatching——统计永远不能伤害聊天主路径（spec §6）。
 */
internal object UsageTracker {
    @Volatile var sink: UsageSink? = null   // AppContainer 启动时注入；测试注入 fake

    fun record(
        kind: String,                       // "chat" | "title" | "probe"
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
