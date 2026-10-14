package com.wavex.agent.core.ui

import androidx.compose.ui.text.AnnotatedString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * 整块行内渲染缓存（MarkdownText.InlineBlock 跨组合复用）的语义钉子：
 * - put/get 命中返回同实例；
 * - 流式生长（严格增长前缀 + 窗口期内连续尝试）不入缓存，且不冲刷已有条目；
 * - 无关新内容（批量装载历史气泡）窗口期内照常入缓存；
 * - 窗口期后的重访（流结束后的稳定内容）入缓存；
 * - 超容量按 LRU 淘汰。
 */
class InlineBlockCacheTest {

    private fun result(text: String) = InlineResult(AnnotatedString(text), emptyMap())

    @Before
    fun setUp() {
        InlineBlockCache.clear()
    }

    @After
    fun tearDown() {
        InlineBlockCache.clear()
        InlineBlockCache.nowMs = System::currentTimeMillis
    }

    @Test
    fun `put then get returns same instance`() {
        val v = result("a")
        InlineBlockCache.put("k1", v)
        assertSame(v, InlineBlockCache.get("k1"))
    }

    @Test
    fun `growing prefix inside window is skipped and does not evict`() {
        var now = 1_000L
        InlineBlockCache.nowMs = { now }
        InlineBlockCache.put("k1", result("a"))
        // 打字机下一帧：前缀增长 + 16ms 后 → 判定流式生长，不入缓存
        now += 16
        InlineBlockCache.put("k1\u0001ab", result("ab"))
        assertNull(InlineBlockCache.get("k1\u0001ab"))
        // 已有条目不被冲刷
        assertNotNull(InlineBlockCache.get("k1"))
    }

    @Test
    fun `distinct content inside window is cached (bulk load)`() {
        var now = 1_000L
        InlineBlockCache.nowMs = { now }
        InlineBlockCache.put("kA", result("A"))
        now += 16
        // 无关新 key（打开久置会话批量装载可见气泡）：不满足增长前缀 → 照常入缓存
        InlineBlockCache.put("kB", result("B"))
        assertNotNull(InlineBlockCache.get("kA"))
        assertNotNull(InlineBlockCache.get("kB"))
    }

    @Test
    fun `stable content revisited after window is cached`() {
        var now = 1_000L
        InlineBlockCache.nowMs = { now }
        InlineBlockCache.put("k1", result("a"))
        now += 16
        // 流式生长被跳过
        InlineBlockCache.put("k1\u0001ab", result("ab"))
        assertNull(InlineBlockCache.get("k1\u0001ab"))
        // 流结束 >500ms 后重访（滚出视口再滚回）：不再判定为生长 → 入缓存
        now += 600
        val v = result("ab")
        InlineBlockCache.put("k1\u0001ab", v)
        assertSame(v, InlineBlockCache.get("k1\u0001ab"))
    }

    @Test
    fun `same key put twice inside window is cached (not growth)`() {
        var now = 1_000L
        InlineBlockCache.nowMs = { now }
        InlineBlockCache.put("k1", result("a"))
        now += 16
        val v2 = result("a2")
        InlineBlockCache.put("k1", v2)
        assertSame(v2, InlineBlockCache.get("k1"))
    }

    @Test
    fun `evicts least recently used beyond capacity`() {
        var now = 1_000L
        InlineBlockCache.nowMs = { now }
        val cap = InlineBlockCache.maxEntriesForTest
        // 灌入 cap + 10 条无关 key（都绕过流式门）
        for (i in 0 until cap + 10) {
            now += 16
            InlineBlockCache.put("key-$i", result(i.toString()))
        }
        assertNull(InlineBlockCache.get("key-0"))
        assertNull(InlineBlockCache.get("key-9"))
        assertNotNull(InlineBlockCache.get("key-${cap + 9}"))
    }

    @Test
    fun `oversized content is not cached`() {
        var now = 1_000L
        InlineBlockCache.nowMs = { now }
        // key 含整段内容：超长消息的 key 超过上限 → 不入缓存（限制单条目内存上界）
        val bigKey = "k" + "x".repeat(InlineBlockCache.maxKeyCharsForTest)
        InlineBlockCache.put(bigKey, result("v"))
        assertNull(InlineBlockCache.get(bigKey))
    }

    @Test
    fun `get refreshes lru order`() {
        var now = 1_000L
        InlineBlockCache.nowMs = { now }
        val cap = InlineBlockCache.maxEntriesForTest
        InlineBlockCache.put("old", result("o"))
        for (i in 0 until cap - 1) {
            now += 16
            InlineBlockCache.put("k-$i", result(i.toString()))
        }
        // 访问最老条目 → 刷新 LRU 序
        InlineBlockCache.get("old")
        for (i in 0 until 2) {
            now += 16
            InlineBlockCache.put("new-$i", result(i.toString()))
        }
        assertNotNull(InlineBlockCache.get("old"))
        assertNull(InlineBlockCache.get("k-0"))
    }

    @Test
    fun `inlineBlockOf round trip returns same instance for same args`() {
        var now = 1_000L
        InlineBlockCache.nowMs = { now }
        val density = androidx.compose.ui.unit.Density(1.25f)
        val first = inlineBlockOf("hello **bold**", androidx.compose.ui.graphics.Color(0x11000000), androidx.compose.ui.graphics.Color(0xFF0000FF), androidx.compose.ui.graphics.Color(0xFF000000), density)
        now += 16
        val second = inlineBlockOf("hello **bold**", androidx.compose.ui.graphics.Color(0x11000000), androidx.compose.ui.graphics.Color(0xFF0000FF), androidx.compose.ui.graphics.Color(0xFF000000), density)
        assertSame(first, second)
    }
}
