package com.wavex.agent.core.network

import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.domain.model.ToolCall
import com.wavex.agent.core.domain.model.UsageLogEntry
import com.wavex.agent.core.domain.port.UsageSink
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

/**
 * 网络回归测试（JDK 内置 HttpServer，不引 mockwebserver）：
 * - Anthropic 网关鉴权方言回退：x-api-key 被拒后自动换 Bearer 重发（服务器侧断言请求头）；
 * - 网关长报错：用量账目保留真实原因、且密钥被 [REDACTED] 打码。
 * 走公开工厂 createModelService，与 app 实际接线同路径。
 */
class NetworkServiceFactoryTest {

    private var server: HttpServer? = null
    private val executor = Executors.newSingleThreadExecutor()

    /** 服务器实际看到的请求头快照（小写键），按到达顺序累积 */
    private val seenRequests = mutableListOf<Map<String, String>>()

    @After
    fun tearDown() {
        server?.stop(0)
        executor.shutdownNow()
    }

    /** 起一个绑定随机端口的本地服务器，返回 base url；handler 每请求调用一次 */
    private fun startServer(handler: (HttpExchange) -> Unit): String {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server = s
        s.executor = executor
        s.createContext("/") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        s.start()
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun snapshotHeaders(exchange: HttpExchange): Map<String, String> =
        exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to (v.firstOrNull() ?: "") }

    /** 有界超时的客户端：外部服务器无响应时测试快速失败，不挂起 */
    private fun testClient() = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ---------- 鉴权方言回退（Anthropic 网关） ----------

    @Test
    fun `anthropicAuthFallbackUsesAlternateDialect`() = runTest {
        val root = startServer { exchange ->
            val headers = snapshotHeaders(exchange)
            synchronized(seenRequests) { seenRequests.add(headers) }
            if (headers.containsKey("authorization")) {
                // Bearer 方言：网关认这种头，回真实模型列表
                respond(exchange, 200, """{"data":[{"id":"m1"},{"id":"m2"}]}""")
            } else {
                // x-api-key 方言：该网关一律拒绝
                respond(exchange, 401, """{"error":{"message":"invalid x-api-key"}}""")
            }
        }
        val collected = mutableListOf<UsageLogEntry>()
        val service = createModelService(testClient(), UsageSink { collected.add(it) })
        val provider = Provider(
            name = "t",
            baseUrl = "$root/anthropic",   // /anthropic 路径段 → Anthropic 协议 → 默认 x-api-key
            apiKey = "sk-test-key",
            model = "claude-x"
        )

        val models = service.fetchModels(provider)

        // 重发成功：拿到列表
        assertEquals(listOf("m1", "m2"), models)

        val seen = synchronized(seenRequests) { seenRequests.toList() }
        // 首次请求带 x-api-key（Anthropic 默认方言）且不带 Authorization
        assertEquals(2, seen.size)
        assertEquals("sk-test-key", seen[0]["x-api-key"])
        assertFalse(seen[0].containsKey("authorization"))
        assertEquals("2023-06-01", seen[0]["anthropic-version"])
        // 重试请求换成了 Authorization: Bearer
        assertEquals("Bearer sk-test-key", seen[1]["authorization"])
        assertFalse(seen[1].containsKey("x-api-key"))
    }

    // ---------- 网关长报错：原因保留 + 密钥打码 ----------

    @Test
    fun `longGatewayErrorKeepsReasonAndRedactsKey`() = runTest {
        val apiKey = "sk-live-0123456789abcdef"
        val reason = "upstream provider timeout"
        // 任务型网关真实形状：error 对象排在元数据之后，message 超长（>200 触发截断）；
        // key 与 gateway reason 都落在 message 前 200 字符内
        val body = buildString {
            append("""{"pad0":""")
            append('"'); append("x".repeat(600)); append('"')
            append(""","endpoint":"chat","error":{"code":"task_failed","message":""")
            append('"')
            append("网关任务执行失败：key=$apiKey reason=$reason")
            append("。明细".repeat(120))
            append('"')
            append("}}")
        }
        assertTrue("原始报文应包含密钥", body.contains(apiKey))
        val root = startServer { exchange ->
            exchange.requestBody.use { it.readBytes() }   // 排干请求体再回
            respond(exchange, 400, body)
        }
        val collected = mutableListOf<UsageLogEntry>()
        val service = createModelService(testClient(), UsageSink { collected.add(it) })
        val provider = Provider(name = "网关", baseUrl = "$root/v1", apiKey = apiKey, model = "gpt-4o")

        val thrown = runCatching {
            service.streamChat(
                provider,
                listOf(ChatRequestMessage(role = "user", text = "hi")),
                reasoningEffort = null,
                webSearch = false,
                onDelta = { _, _ -> },
                onImage = {}
            )
        }.exceptionOrNull()

        // 抛出的异常同样不打码漏 key
        assertTrue("应抛 ApiException", thrown is ApiException)
        assertTrue((thrown as ApiException).message!!.contains(reason))
        assertFalse(thrown.message!!.contains(apiKey))

        // 落账条目：状态码保留，gateway reason 保留，密钥不出现
        assertEquals(1, collected.size)
        val entry = collected[0]
        assertEquals("chat", entry.kind)
        assertEquals(400, entry.statusCode)
        assertTrue("账目应保留网关原因", entry.error.contains(reason))
        assertFalse("账目不得包含密钥", entry.error.contains(apiKey))
        assertTrue(entry.error.contains("[REDACTED]"))
    }

    // ---------- 客户端工具调用：流结束交出完整 ToolCall ----------

    @Test
    fun `streamChat surfaces accumulated tool calls`() = runTest {
        // 真实 OpenAI 流形状：首个分片给 id/name，参数逐段到达，finish_reason=tool_calls
        val chunks = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"web_search","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"btc price\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )
        val root = startServer { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = buildString {
                chunks.forEach { append("data: ").append(it).append("\n\n") }
                append("data: [DONE]\n\n")
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val service = createModelService(testClient(), UsageSink { })
        val provider = Provider(name = "t", baseUrl = "$root/v1", apiKey = "sk-t", model = "gpt-4o")
        val deltas = mutableListOf<String>()
        val calls = mutableListOf<List<ToolCall>>()

        service.streamChat(
            provider,
            listOf(ChatRequestMessage(role = "user", text = "btc price?")),
            reasoningEffort = null,
            webSearch = true,
            onDelta = { c, _ -> if (c.isNotEmpty()) deltas.add(c) },
            onImage = {},
            onToolCalls = { calls.add(it) },
        )

        assertEquals(emptyList<String>(), deltas)
        assertEquals(1, calls.size)
        val call = calls[0].single()
        assertEquals("call_abc", call.id)
        assertEquals("web_search", call.name)
        assertEquals("""{"query":"btc price"}""", call.arguments)
        assertEquals("btc price", call.query)
    }

    @Test
    fun `tool call only stream is not reported as empty content`() = runTest {
        // 只有工具调用、正文全空的流是本轮的正常形态，旧实现会抛 EmptyContent/ToolCall
        val chunk =
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"web_search","arguments":"{\"query\":\"x\"}"}}]}}]}"""
        val root = startServer { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = "data: $chunk\n\ndata: [DONE]\n\n"
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val service = createModelService(testClient(), UsageSink { })
        val provider = Provider(name = "t", baseUrl = "$root/v1", apiKey = "sk-t", model = "gpt-4o")
        val calls = mutableListOf<List<ToolCall>>()

        service.streamChat(
            provider,
            listOf(ChatRequestMessage(role = "user", text = "x")),
            reasoningEffort = null,
            webSearch = true,
            onDelta = { _, _ -> },
            onImage = {},
            onToolCalls = { calls.add(it) },
        )

        assertEquals("x", calls.single().single().query)
    }
}
