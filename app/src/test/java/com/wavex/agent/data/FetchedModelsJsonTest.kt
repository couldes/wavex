package com.wavex.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行为钉子：模型页拉取结果缓存的 JSON 编解码 round-trip。
 * 持久化本体在 ProviderStore（依赖 Context 无法进 JVM 测试），编解码抽成纯函数覆盖：
 * 正常往返、空白模型名剔除、空列表丢弃、损坏数据静默降级为空表。
 */
class FetchedModelsJsonTest {

    @Test
    fun `round trip 保留各服务商的模型列表与顺序`() {
        val map = mapOf(
            "provider-a" to listOf("ds-flash", "ds-v4 pro", "deepseek-chat"),
            "provider-b" to listOf("kimi-k2-0711-preview")
        )
        val decoded = fetchedModelsFromJson(fetchedModelsToJson(map))
        assertEquals(map, decoded)
    }

    @Test
    fun `空白模型名被剔除 空列表的服务商不写入`() {
        val json = fetchedModelsToJson(
            mapOf(
                "p1" to listOf("ds-flash", "  ", ""),
                "p2" to emptyList()
            )
        )
        val decoded = fetchedModelsFromJson(json)
        assertEquals(mapOf("p1" to listOf("ds-flash")), decoded)
    }

    @Test
    fun `损坏的 JSON 静默降级为空表`() {
        assertTrue(fetchedModelsFromJson("{not json").isEmpty())
        assertTrue(fetchedModelsFromJson("[]").isEmpty())
        assertTrue(fetchedModelsFromJson("").isEmpty())
    }

    @Test
    fun `非字符串条目被跳过而不抛异常`() {
        val decoded = fetchedModelsFromJson("""{"p1":["ds-flash",123,null]}""")
        assertEquals(mapOf("p1" to listOf("ds-flash")), decoded)
    }
}
