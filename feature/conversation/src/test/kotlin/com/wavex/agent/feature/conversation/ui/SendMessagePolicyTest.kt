package com.wavex.agent.feature.conversation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sendMessage 未配置服务商分支的回归钉子：
 * 用户输入不能被丢弃成 text="" 的空气泡（历史上曾持久化为空胶囊）。
 */
class SendMessagePolicyTest {

    // ---- isProviderConfigured ----

    @Test
    fun `configured provider with base url and key passes`() {
        assertTrue(isProviderConfigured("https://example.com", "sk-test", hasProvider = true))
    }

    @Test
    fun `missing provider is unconfigured`() {
        assertFalse(isProviderConfigured("https://example.com", "sk-test", hasProvider = false))
    }

    @Test
    fun `blank base url or key is unconfigured`() {
        assertFalse(isProviderConfigured("", "sk-test", hasProvider = true))
        assertFalse(isProviderConfigured("https://example.com", "", hasProvider = true))
        assertFalse(isProviderConfigured(null, "sk-test", hasProvider = true))
        assertFalse(isProviderConfigured("https://example.com", null, hasProvider = true))
    }

    // ---- unconfiguredUserMessageText：用户输入保留原文，不产生空气泡 ----

    @Test
    fun `plain text is kept trimmed`() {
        assertEquals("帮我写个方案", unconfiguredUserMessageText("  帮我写个方案  "))
    }

    @Test
    fun `plain text without padding is unchanged`() {
        assertEquals("看这张图", unconfiguredUserMessageText("看这张图"))
    }

    @Test
    fun `blank input trims to empty string`() {
        // 纯空白的输入不会渲染出带文字的空气泡；仅附件发送时消息仍会带上附件本身
        assertEquals("", unconfiguredUserMessageText(""))
        assertEquals("", unconfiguredUserMessageText("   "))
    }
}
