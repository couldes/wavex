package com.wavex.agent.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 协议判定的钉子（原在 core:network 的 ApiClientTest 里测 detectProtocol）。
 *
 * 判定下沉到 domain 的原因：feature 层解释「思考等级对当前模型意味着什么」时，
 * 必须先知道走的是 Anthropic 的预算制还是 OpenAI 的档位制。这份测试守住
 * 「下沉是纯搬家」——六种输入形态的结论与搬家前逐条一致。
 */
class ApiProtocolsTest {

    @Test
    fun `anthropic official host is anthropic`() {
        assertEquals(ApiProtocol.ANTHROPIC, ApiProtocols.detect("https://api.anthropic.com/v1"))
    }

    @Test
    fun `www prefix does not defeat the host check`() {
        assertEquals(ApiProtocol.ANTHROPIC, ApiProtocols.detect("https://www.anthropic.com/v1/messages"))
    }

    @Test
    fun `anthropic compat subpath on another host is anthropic`() {
        // DeepSeek 官方 Anthropic 网关就是这个形状
        assertEquals(ApiProtocol.ANTHROPIC, ApiProtocols.detect("https://api.deepseek.com/anthropic"))
        assertEquals(ApiProtocol.ANTHROPIC, ApiProtocols.detect("https://gateway.example.com/anthropic/v1"))
    }

    @Test
    fun `host check is case insensitive`() {
        assertEquals(ApiProtocol.ANTHROPIC, ApiProtocols.detect("https://API.DeepSeek.com/Anthropic"))
    }

    @Test
    fun `missing scheme defaults to https and still detects`() {
        assertEquals(ApiProtocol.OPENAI, ApiProtocols.detect("api.example.com/v1"))
        assertEquals(ApiProtocol.ANTHROPIC, ApiProtocols.detect("api.deepseek.com/anthropic"))
    }

    @Test
    fun `openai compatible is the default`() {
        assertEquals(ApiProtocol.OPENAI, ApiProtocols.detect("https://api.openai.com/v1"))
        assertEquals(ApiProtocol.OPENAI, ApiProtocols.detect(""))
    }

    /** 「anthropic」只作为完整路径段才算：作为子串出现在别的段里不该误判 */
    @Test
    fun `anthropic as a path segment only`() {
        assertEquals(ApiProtocol.OPENAI, ApiProtocols.detect("https://example.com/v1/anthropicproxy"))
    }
}
