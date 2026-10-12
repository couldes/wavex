package com.wavex.agent.feature.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 预设 chip 的契约：只负责填名称与官方 Base URL，模型一律交给添加后的自动拉取。 */
class ProviderPresetTest {

    @Test
    fun `预设不再内置模型名单`() {
        val fields = ProviderPreset::class.java.declaredFields.map { it.name }
        assertTrue(
            "模型名单应交给自动拉取，而不是硬编码在预设里：$fields",
            fields.none { it == "defaultModel" || it == "fallbackModels" }
        )
    }

    @Test
    fun `除自定义外每个预设都带官方 https 地址`() {
        val named = PROVIDER_PRESETS.filter { it.label != "自定义" }
        assertTrue(named.isNotEmpty())
        named.forEach { preset ->
            assertTrue("${preset.label} 缺少官方 Base URL", preset.baseUrl.startsWith("https://"))
        }
    }

    @Test
    fun `自定义预设不预填名称与地址`() {
        val custom = PROVIDER_PRESETS.first { it.label == "自定义" }
        assertEquals("", custom.name)
        assertEquals("", custom.baseUrl)
    }

    @Test
    fun `chip 标签不重复`() {
        assertEquals(PROVIDER_PRESETS.size, PROVIDER_PRESETS.map { it.label }.distinct().size)
    }
}
