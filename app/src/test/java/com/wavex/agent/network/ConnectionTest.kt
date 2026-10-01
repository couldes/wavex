package com.wavex.agent.network

import com.wavex.agent.model.ApiProtocol
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端点归一化与鉴权方言的行为钉子。
 * 用户粘贴的 Base URL 形态不可控，这里拼错一个斜杠就是「连不上」，
 * 而且线上只能看到一个 404，没有测试就定位不到是哪一段拼错的。
 */
class ConnectionTest {

    // ---------- canonicalBase：剥掉用户粘贴的完整接口地址 ----------

    @Test
    fun `openai full endpoint stripped back to prefix`() {
        assertEquals(
            "https://api.openai.com/v1",
            Connection.canonicalBase("https://api.openai.com/v1/chat/completions")
        )
    }

    @Test
    fun `anthropic full messages url stripped`() {
        assertEquals(
            "https://api.anthropic.com/v1",
            Connection.canonicalBase("https://api.anthropic.com/v1/messages/")
        )
    }

    @Test
    fun `deepseek anthropic gateway full url stripped`() {
        assertEquals(
            "https://api.deepseek.com/anthropic/v1",
            Connection.canonicalBase("https://api.deepseek.com/anthropic/v1/messages")
        )
    }

    @Test
    fun `missing scheme defaults to https`() {
        assertEquals("https://api.openai.com", Connection.canonicalBase("api.openai.com"))
    }

    @Test
    fun `query and fragment dropped`() {
        assertEquals("https://x.com/v1", Connection.canonicalBase("https://x.com/v1?debug=1"))
        assertEquals("https://x.com/v1", Connection.canonicalBase("https://x.com/v1#docs"))
    }

    @Test
    fun `blank stays blank`() {
        assertEquals("", Connection.canonicalBase("   "))
    }

    // ---------- chatEndpoint：版本段只补一次 ----------

    @Test
    fun `openai prefix with version keeps it`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            Connection.chatEndpoint("https://api.openai.com/v1", ApiProtocol.OPENAI)
        )
    }

    @Test
    fun `pasted full endpoint does not duplicate leaf`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            Connection.chatEndpoint("https://api.openai.com/v1/chat/completions", ApiProtocol.OPENAI)
        )
    }

    @Test
    fun `pasted anthropic messages url does not duplicate version`() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            Connection.chatEndpoint("https://api.anthropic.com/v1/messages", ApiProtocol.ANTHROPIC)
        )
    }

    @Test
    fun `bare host gets version segment`() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            Connection.chatEndpoint("https://api.anthropic.com", ApiProtocol.ANTHROPIC)
        )
    }

    @Test
    fun `non v version path preserved`() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            Connection.chatEndpoint("https://open.bigmodel.cn/api/paas/v4", ApiProtocol.OPENAI)
        )
    }

    @Test
    fun `compatible mode prefix preserved`() {
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            Connection.chatEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1", ApiProtocol.OPENAI)
        )
    }

    @Test
    fun `gateway anthropic subpath gets version`() {
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            Connection.chatEndpoint("https://api.deepseek.com/anthropic", ApiProtocol.ANTHROPIC)
        )
    }

    @Test
    fun `v1beta suffix not double-appended`() {
        assertEquals(
            "https://example.com/v1beta/chat/completions",
            Connection.chatEndpoint("https://example.com/v1beta", ApiProtocol.OPENAI)
        )
    }

    @Test
    fun `empty base yields empty endpoint`() {
        assertEquals("", Connection.chatEndpoint("", ApiProtocol.OPENAI))
    }

    // ---------- modelsEndpoint ----------

    @Test
    fun `models endpoint on bare host`() {
        assertEquals(
            "https://api.anthropic.com/v1/models",
            Connection.modelsEndpoint("https://api.anthropic.com", ApiProtocol.ANTHROPIC)
        )
    }

    @Test
    fun `pasted models url does not duplicate`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            Connection.modelsEndpoint("https://api.openai.com/v1/models", ApiProtocol.OPENAI)
        )
    }

    // ---------- modelsCandidates：兼容子路径剥离兜底（cc-switch 同款） ----------

    @Test
    fun `deepseek anthropic base falls back to root openai endpoints`() {
        assertEquals(
            listOf(
                Connection.ModelsCandidate("https://api.deepseek.com/anthropic/v1/models", ApiProtocol.ANTHROPIC),
                Connection.ModelsCandidate("https://api.deepseek.com/v1/models", ApiProtocol.OPENAI),
                Connection.ModelsCandidate("https://api.deepseek.com/models", ApiProtocol.OPENAI)
            ),
            Connection.modelsCandidates("https://api.deepseek.com/anthropic", ApiProtocol.ANTHROPIC)
        )
    }

    @Test
    fun `base without compat suffix has single candidate`() {
        assertEquals(
            listOf(Connection.ModelsCandidate("https://api.openai.com/v1/models", ApiProtocol.OPENAI)),
            Connection.modelsCandidates("https://api.openai.com/v1", ApiProtocol.OPENAI)
        )
    }

    @Test
    fun `official anthropic host has no root fallback`() {
        assertEquals(
            listOf(Connection.ModelsCandidate("https://api.anthropic.com/v1/models", ApiProtocol.ANTHROPIC)),
            Connection.modelsCandidates("https://api.anthropic.com", ApiProtocol.ANTHROPIC)
        )
    }

    @Test
    fun `longest compat suffix wins`() {
        // /api/anthropic 必须先于 /anthropic 命中，根才是站点根而不是 …/api
        assertEquals(
            listOf(
                Connection.ModelsCandidate("https://x.com/api/anthropic/v1/models", ApiProtocol.ANTHROPIC),
                Connection.ModelsCandidate("https://x.com/v1/models", ApiProtocol.OPENAI),
                Connection.ModelsCandidate("https://x.com/models", ApiProtocol.OPENAI)
            ),
            Connection.modelsCandidates("https://x.com/api/anthropic", ApiProtocol.ANTHROPIC)
        )
    }

    // ---------- 鉴权方言 ----------

    @Test
    fun `default dialect per protocol`() {
        assertEquals(AuthDialect.X_API_KEY, Connection.defaultDialect(ApiProtocol.ANTHROPIC))
        assertEquals(AuthDialect.BEARER, Connection.defaultDialect(ApiProtocol.OPENAI))
    }

    @Test
    fun `other dialect toggles both ways`() {
        assertEquals(AuthDialect.BEARER, Connection.otherDialect(AuthDialect.X_API_KEY))
        assertEquals(AuthDialect.X_API_KEY, Connection.otherDialect(AuthDialect.BEARER))
    }

    /** 换成 Bearer 也必须保留 anthropic-version——官方网关缺它会报 400，与鉴权方式无关。 */
    @Test
    fun `bearer fallback still carries anthropic version`() {
        val request = Connection.applyAuth(
            Request.Builder().url("https://api.anthropic.com/v1/messages"),
            "sk-test", ApiProtocol.ANTHROPIC, AuthDialect.BEARER
        ).build()
        assertEquals("Bearer sk-test", request.header("Authorization"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertFalse(request.headers.names().contains("x-api-key"))
    }

    @Test
    fun `x api key dialect carries key and version`() {
        val request = Connection.applyAuth(
            Request.Builder().url("https://api.anthropic.com/v1/messages"),
            "sk-test", ApiProtocol.ANTHROPIC, AuthDialect.X_API_KEY
        ).build()
        assertEquals("sk-test", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertFalse(request.headers.names().contains("Authorization"))
    }

    @Test
    fun `openai dialect adds no anthropic version`() {
        val request = Connection.applyAuth(
            Request.Builder().url("https://api.openai.com/v1/chat/completions"),
            "sk-test", ApiProtocol.OPENAI, AuthDialect.BEARER
        ).build()
        assertEquals("Bearer sk-test", request.header("Authorization"))
        assertFalse(request.headers.names().contains("anthropic-version"))
    }

    // ---------- 体检结果展示 ----------

    @Test
    fun `report lines show inference details`() {
        val report = ConnectionReport(
            ok = true, protocol = ApiProtocol.ANTHROPIC,
            endpoint = "https://api.deepseek.com/anthropic/v1/messages",
            dialect = AuthDialect.BEARER, modelCount = 12, latencyMs = 480, error = null
        )
        val lines = report.summaryLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("连接成功"))
        assertTrue(lines[1].contains("Anthropic"))
        assertTrue(lines[1].contains("Bearer"))
        assertTrue(lines[1].contains("480ms"))
        assertTrue(lines[1].contains("api.deepseek.com"))
    }

    @Test
    fun `report without models says so`() {
        val report = ConnectionReport(
            ok = true, protocol = ApiProtocol.OPENAI, endpoint = "https://x/v1/chat/completions",
            dialect = AuthDialect.BEARER, modelCount = 0, latencyMs = 10, error = null
        )
        assertTrue(report.summaryLines()[0].contains("未开放模型列表"))
    }

    @Test
    fun `report failure keeps reason in first line`() {
        val report = ConnectionReport(
            ok = false, protocol = ApiProtocol.OPENAI, endpoint = "https://x/v1/chat/completions",
            dialect = AuthDialect.X_API_KEY, modelCount = null, latencyMs = 10, error = "HTTP 401：bad key"
        )
        assertTrue(report.summaryLines()[0].startsWith("连接失败"))
        assertTrue(report.summaryLines()[0].contains("HTTP 401"))
    }
}
