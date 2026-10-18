package com.wavex.agent.core.network

import com.wavex.agent.core.domain.model.WebSearchOutcome
import com.wavex.agent.core.domain.model.WebSearchResult
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BingRssSearchProviderTest {

    private var server: HttpServer? = null
    private val executor = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        server?.stop(0)
        executor.shutdownNow()
    }

    private fun serve(status: Int, body: String, contentType: String = "text/xml"): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.executor = executor
        s.createContext("/") { ex ->
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", contentType)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun client() = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    private val okRss = """
        <?xml version="1.0" encoding="utf-8" ?><rss version="2.0"><channel>
        <item><title>Bitcoin price</title><link>https://coinmarketcap.com/currencies/bitcoin/</link>
        <description>The live Bitcoin price is 108432 USD.</description></item>
        <item><title>BTC 实时价格</title><link>https://www.coinglass.com/zh/currencies/BTC</link>
        <description>Bitcoin 当前价格为 108500 美元。</description></item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun `parses items into results with stable ids and 1-based index`() {
        val results = BingRssParser.parse(okRss, limit = 6)!!
        assertEquals(2, results.size)
        assertEquals(1, results[0].index)
        assertEquals("Bitcoin price", results[0].title)
        assertEquals("https://coinmarketcap.com/currencies/bitcoin/", results[0].url)
        assertEquals("The live Bitcoin price is 108432 USD.", results[0].text)
        assertEquals(2, results[1].index)
        assertTrue(results[0].id.matches(Regex("[0-9a-f]{6}")))
        assertTrue(results[0].id != results[1].id)
    }

    @Test
    fun `ids are url derived so separate searches do not collide`() {
        // 同一页面在不同批次里应拿到同一个 id；不同页面必须不同。
        // 只按序号生成会让每次搜索的第一条都是同一个 id，引用会跳到错的源
        val a = BingRssParser.parse(okRss, limit = 6)!!
        val b = BingRssParser.parse(okRss, limit = 6)!!
        assertEquals(a.map { it.id }, b.map { it.id })
        assertTrue(a[0].id != a[1].id)
        // 只取第二条时，id 不应退化成「第一条的 id」
        val onlySecond = BingRssParser.parse(
            okRss.replaceFirst(Regex("<item>.*?</item>", RegexOption.DOT_MATCHES_ALL), ""),
            limit = 6,
        )!!
        assertEquals(a[1].id, onlySecond[0].id)
    }

    @Test
    fun `limit truncates`() {
        assertEquals(1, BingRssParser.parse(okRss, limit = 1)!!.size)
    }

    @Test
    fun `empty channel yields empty list not null`() {
        val empty = """<?xml version="1.0"?><rss version="2.0"><channel></channel></rss>"""
        assertEquals(emptyList<Any>(), BingRssParser.parse(empty, limit = 6))
    }

    @Test
    fun `malformed xml yields null`() {
        assertNull(BingRssParser.parse("<rss><channel><item>", limit = 6))
    }

    @Test
    fun `http 200 with empty channel is Ok empty not Transient`() = runTest {
        val base = serve(200, """<?xml version="1.0"?><rss version="2.0"><channel></channel></rss>""")
        val provider = BingRssSearchProvider(client(), endpoint = "$base/search")
        val outcome = provider.search("no such thing", limit = 6)
        assertTrue(outcome is WebSearchOutcome.Ok)
        assertTrue((outcome as WebSearchOutcome.Ok).results.isEmpty())
    }

    @Test
    fun `http 500 is Transient`() = runTest {
        val base = serve(500, "boom", "text/plain")
        val provider = BingRssSearchProvider(client(), endpoint = "$base/search")
        assertTrue(provider.search("btc", limit = 6) is WebSearchOutcome.Transient)
    }

    @Test
    fun `malformed body is Transient`() = runTest {
        val base = serve(200, "<html>not rss</html>", "text/html")
        val provider = BingRssSearchProvider(client(), endpoint = "$base/search")
        assertTrue(provider.search("btc", limit = 6) is WebSearchOutcome.Transient)
    }

    @Test
    fun `blank query short-circuits to Ok empty without a request`() = runTest {
        val provider = BingRssSearchProvider(client(), endpoint = "http://127.0.0.1:1/search")
        val outcome = provider.search("   ", limit = 6)
        assertTrue(outcome is WebSearchOutcome.Ok)
        assertTrue((outcome as WebSearchOutcome.Ok).results.isEmpty())
    }
}
