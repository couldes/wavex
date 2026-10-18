package com.wavex.agent

import androidx.test.platform.app.InstrumentationRegistry
import com.wavex.agent.core.domain.model.WebSearchOutcome
import com.wavex.agent.core.network.BingRssSearchProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 真机联网冒烟（不是回归钉子）：用生产实现打真实 Bing 端点，
 * 确认「搜索由本机发起」这条路在设备网络下真的走得通。
 *
 * 放在 app 模块：feature 不允许直接依赖 core:network（架构约束），
 * 而这里要测的正是 network 的真实实现。
 *
 * 依赖外网，故断言宽松——只要求拿到 Ok 且至少一条结果；
 * 失败信息里带上 outcome，便于区分「网络问题」与「实现问题」。
 */
class LiveSearchSmokeTest {

    @Test(timeout = 90_000)
    fun bing_rss_returns_results_for_chinese_and_english_queries() = runBlocking {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val provider = BingRssSearchProvider(client)

        val zh = provider.search("比特币 价格", limit = 6)
        val en = provider.search("bitcoin price", limit = 6)

        val zhOk = (zh as? WebSearchOutcome.Ok)?.results.orEmpty()
        val enOk = (en as? WebSearchOutcome.Ok)?.results.orEmpty()
        assertTrue("中文查询应返回结果，实际: $zh", zhOk.isNotEmpty())
        assertTrue("英文查询应返回结果，实际: $en", enOk.isNotEmpty())
        // 结果必须带可点的 URL 与能写进 [cite:id] 的短 id
        assertTrue("URL 应可点: ${zhOk.map { it.url }}", zhOk.all { it.url.startsWith("http") })
        assertTrue("id 应为 6 位十六进制: ${zhOk.map { it.id }}",
            zhOk.all { it.id.matches(Regex("[0-9a-f]{6}")) })
        // 同一批次内 id 必须唯一（模型靠它区分条目）。
        // 跨批次重复是预期行为：同一个 URL 两次搜到就该是同一个 id，
        // 引用仍然指向正确页面——真机上中英文查询都会命中同一个行情页。
        assertTrue("中文批次 id 应唯一: ${zhOk.map { it.id }}",
            zhOk.map { it.id }.toSet().size == zhOk.size)
        assertTrue("英文批次 id 应唯一: ${enOk.map { it.id }}",
            enOk.map { it.id }.toSet().size == enOk.size)
    }

    /**
     * 契约测试：确认应用真的持有 INTERNET 权限。缺了它上面那条会以
     * 「网络异常」失败，报错却指向搜索实现。
     */
    @Test
    fun app_holds_internet_permission() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val granted = ctx.packageManager.checkPermission(
            android.Manifest.permission.INTERNET, ctx.packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        assertTrue("应用需要 INTERNET 权限才能本机搜索", granted)
    }
}
