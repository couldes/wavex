package com.wavex.agent.network

import com.wavex.agent.data.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UsageTracker 测试：纯 JVM。
 * 钉住：快照条目构建、sink 异常吞掉、sink 缺失静默（spec §6：统计不伤主路径）。
 */
class UsageTrackerTest {

    private val provider = Provider(id = "p1", name = "DeepSeek", baseUrl = "https://x", apiKey = "k", model = "deepseek-chat")

    @Test
    fun `record 构建快照条目并送达 sink`() {
        val collected = mutableListOf<com.wavex.agent.data.UsageLogEntry>()
        val usage = StreamUsage().apply { inputTokens = 100; outputTokens = 50 }

        UsageTracker.sink = UsageSink { collected.add(it) }
        try {
            UsageTracker.record("chat", provider, usage, 200, 1234L)
        } finally {
            UsageTracker.sink = null
        }

        assertEquals(1, collected.size)
        with(collected[0]) {
            assertEquals("chat", kind)
            assertEquals("p1", providerId)
            assertEquals("DeepSeek", providerName)   // 快照
            assertEquals("deepseek-chat", model)     // 快照
            assertEquals(100L, inputTokens)
            assertEquals(50L, outputTokens)
            assertEquals(200, statusCode)
            assertEquals(1234L, latencyMs)
            assertTrue(createdAt > 0)
            assertTrue(id.isNotBlank())
        }
    }

    @Test
    fun `record 打码 error 里的 api key`() {
        val collected = mutableListOf<com.wavex.agent.data.UsageLogEntry>()
        val leakyProvider = provider.copy(apiKey = "sk-live-key-999")

        UsageTracker.sink = UsageSink { collected.add(it) }
        try {
            UsageTracker.record("chat", leakyProvider, null, 502, 5L, "HTTP 502：bad key sk-live-key-999 here")
        } finally {
            UsageTracker.sink = null
        }

        assertEquals("HTTP 502：bad key [REDACTED] here", collected[0].error)
    }

    @Test
    fun `sink 抛异常被吞掉`() {
        UsageTracker.sink = UsageSink { throw RuntimeException("boom") }
        try {
            // 不应抛出
            UsageTracker.record("chat", provider, null, 500, 10L, "err")
        } finally {
            UsageTracker.sink = null
        }
    }

    @Test
    fun `sink 为 null 时静默`() {
        UsageTracker.sink = null
        // 不应抛出
        UsageTracker.record("probe", provider, null, 200, 5L)
    }

    @Test
    fun `error 摘要截断 300 字符`() {
        val collected = mutableListOf<com.wavex.agent.data.UsageLogEntry>()
        UsageTracker.sink = UsageSink { collected.add(it) }
        try {
            UsageTracker.record("chat", provider, null, 500, 1L, "x".repeat(500))
        } finally {
            UsageTracker.sink = null
        }
        assertEquals(300, collected[0].error.length)
    }
}
