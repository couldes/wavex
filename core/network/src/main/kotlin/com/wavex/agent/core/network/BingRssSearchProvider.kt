package com.wavex.agent.core.network

import com.wavex.agent.core.domain.model.WebSearchOutcome
import com.wavex.agent.core.domain.model.WebSearchResult
import com.wavex.agent.core.domain.port.SearchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 内置零配置搜索源：Bing 的 RSS 端点。
 *
 * 选 RSS 而非抓 HTML：返回 `text/xml`，结构是标准的 `<item><title>/<link>/<description>`，
 * 不需要 HTML 解析器（不引 Jsoup），也不依赖 `li.b_algo` 这类随页面改版就坏的选择器。
 * 端点无版本承诺，失败按 [WebSearchOutcome.Transient] 上报。
 */
class BingRssSearchProvider(
    private val client: OkHttpClient,
    private val endpoint: String = "https://cn.bing.com/search",
) : SearchProvider {

    override val id: String get() = "bing"

    override suspend fun search(query: String, limit: Int): WebSearchOutcome {
        // 空查询不发请求：模型偶尔会吐空 query，白跑一趟还浪费一次限流配额
        if (query.isBlank()) return WebSearchOutcome.Ok(emptyList())
        val url = buildString {
            append(endpoint)
            append(if (endpoint.contains('?')) '&' else '?')
            append("q=").append(URLEncoder.encode(query.trim(), "UTF-8"))
            append("&format=rss")
        }
        val request = Request.Builder()
            .url(url)
            // Bing 对无 UA 的请求会重定向或拒绝
            .header("User-Agent", USER_AGENT)
            .build()
        val call = client.newCall(request)
        // 用户点「停止」时必须立刻停手：execute() 是阻塞调用，没有挂起点，
        // 光靠协程取消只能等 socket 自己返回（最长一个读超时）。
        // 与 HttpApiSession 同法：把 call 挂在取消回调上，取消即掐断连接。
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                try {
                    cont.resume(runCall(call, limit))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 取消不是「搜索失败」：向上传播，别伪装成 Transient
                    cont.cancel(e)
                } catch (e: Exception) {
                    cont.resume(WebSearchOutcome.Transient(e.message ?: "网络异常"))
                }
            }
        }
    }

    private fun runCall(call: okhttp3.Call, limit: Int): WebSearchOutcome =
        call.execute().use { response ->
            if (!response.isSuccessful) {
                return@use WebSearchOutcome.Transient("HTTP ${response.code}")
            }
            // 上限保护：端点不可配置时 Bing 不会回大包，但抓包代理/门户劫持可能
            // 回一个巨大的 HTML，直接 string() 会把内存吃满。
            // peekBody(n) 截断到 n 字节且不要求读完整个响应（readByteArray(n) 会
            // 因为「不足 n 字节」抛 EOF，正是空 channel 用例踩到的坑）
            val declared = response.body?.contentLength() ?: -1L
            if (declared > MAX_BODY_BYTES) {
                return@use WebSearchOutcome.Transient("搜索源返回内容过大")
            }
            val body = response.peekBody(MAX_BODY_BYTES.toLong()).string()
            BingRssParser.parse(body, limit)
                ?.let { WebSearchOutcome.Ok(it) }
                ?: WebSearchOutcome.Transient("搜索源返回了无法解析的内容")
        }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        /** 单次搜索响应体上限：RSS 结果通常几十 KB，4 MB 足够宽容 */
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }
}

/** RSS 解析（纯函数，测试直接调，不碰网络）。 */
internal object BingRssParser {

    /** @return 解析出的结果；XML 畸形或根本不是 RSS 时返回 null（调用方按 Transient 处理） */
    fun parse(xml: String, limit: Int): List<WebSearchResult>? {
        val doc = try {
            newBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) {
            return null
        }
        // 根元素必须是 rss（或 atom 的 feed）：网关/代理回 HTML 错误页时 XML 也能解析成功，
        // 那种情况必须报 Transient，不能伪装成「搜了没结果」
        val root = doc.documentElement?.tagName?.lowercase()
        if (root != "rss" && root != "feed") return null
        val items = doc.getElementsByTagName("item")
        val out = ArrayList<WebSearchResult>(minOf(items.length, limit))
        for (i in 0 until items.length) {
            if (out.size >= limit) break
            val el = items.item(i) as? Element ?: continue
            val title = el.childText("title")
            val link = el.childText("link")
            val desc = el.childText("description")
            // 三者全空的是坏条目（Bing 偶尔回空 item），跳过而不是产出一条空结果
            if (title.isBlank() && link.isBlank() && desc.isBlank()) continue
            val index = out.size + 1
            out += WebSearchResult(
                id = uniqueId(link, title, out),
                index = index,
                title = title,
                url = link,
                text = desc,
            )
        }
        return out
    }

    /**
     * 6 位十六进制短 id，由 URL 派生。
     *
     * 不能只按批次内序号生成：一轮对话可能搜多次（模型会换关键词重搜），
     * 每次的第一条都会拿到同一个 id，而引用解析按 id 查找 → 点了跳错页面。
     * 用 URL 派生后，同一页面在任何批次里都是同一个 id，不同页面几乎不会撞。
     *
     * 注意：跨批次撞 id 是**预期行为**——同一个 URL 两次搜到就该是同一个 id，
     * 引用指向的仍是正确页面。这里只保证同批次内唯一（否则模型无法区分条目）。
     */
    private fun uniqueId(url: String, title: String, taken: List<WebSearchResult>): String {
        val seed = url.ifBlank { title }
        var id = Integer.toHexString(seed.hashCode() * 0x9E3779B1.toInt())
            .takeLast(6).padStart(6, '0')
        var n = 1
        while (taken.any { it.id == id }) {
            id = id.take(4) + n.toString().padStart(2, '0')
            n++
        }
        return id
    }

    private fun Element.childText(tag: String): String =
        getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()

    /**
     * 关闭外部实体（XXE）：RSS 来自网络，不允许它触发 DTD 拉取本地文件。
     *
     * 逐个 feature 单独 try：Android 的 DocumentBuilderFactory 实现并不支持
     * 全部 JVM feature（`disallow-doctype-decl` 在部分 ROM 上直接抛
     * ParserConfigurationException），整段 apply 一起写会让每个真实响应都
     * 变成「无法解析」——JVM 单测全绿、真机全挂的那种错。
     * 拿不到的 feature 退回到等价设置（禁 DTD / 禁外部实体），不放弃防护。
     */
    private fun newBuilder() = DocumentBuilderFactory.newInstance().apply {
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            .onFailure {
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        isExpandEntityReferences = false
        isNamespaceAware = false
    }.newDocumentBuilder()
}
