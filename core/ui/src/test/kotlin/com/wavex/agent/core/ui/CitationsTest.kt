package com.wavex.agent.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CitationsTest {

    private fun resolver(vararg ids: String): (String) -> CitationTarget? {
        val map = ids.withIndex().associate { (i, id) -> id to CitationTarget(i + 1, "https://e/$id") }
        return { id -> map[id] }
    }

    @Test
    fun `single citation becomes a numbered link`() {
        assertEquals(
            "Bitcoin is 108432 USD [[1](https://e/a1b2c3)]",
            replaceCitations("Bitcoin is 108432 USD [cite:a1b2c3]", resolver("a1b2c3")),
        )
    }

    @Test
    fun `multiple citations map to their own numbers`() {
        assertEquals(
            "A [[1](https://e/aaa)] and B [[2](https://e/bbb)]",
            replaceCitations("A [cite:aaa] and B [cite:bbb]", resolver("aaa", "bbb")),
        )
    }

    @Test
    fun `unknown id is preserved verbatim`() {
        // 模型引用了一个不存在的源：保留原标记，用户该看到这一点
        assertEquals(
            "x [cite:deadbeef]",
            replaceCitations("x [cite:deadbeef]", resolver("a1b2c3")),
        )
    }

    @Test
    fun `mixed known and unknown ids`() {
        assertEquals(
            "a [[1](https://e/a1b2c3)] b [cite:nope]",
            replaceCitations("a [cite:a1b2c3] b [cite:nope]", resolver("a1b2c3")),
        )
    }

    @Test
    fun `numbering follows first appearance across batches`() {
        // 两次搜索各自的第 1 条都带 index=1；直接用会出现两个 [1] 指向不同页面
        val resolve: (String) -> CitationTarget? = { id ->
            when (id) {
                "aaa" -> CitationTarget(1, "https://e/aaa")
                "bbb" -> CitationTarget(1, "https://e/bbb")
                else -> null
            }
        }
        assertEquals(
            "x [[1](https://e/aaa)] y [[2](https://e/bbb)]",
            replaceCitations("x [cite:aaa] y [cite:bbb]", resolve),
        )
        // 同一个 id 重复出现时复用同一个编号
        assertEquals(
            "a [[1](https://e/aaa)] b [[1](https://e/aaa)]",
            replaceCitations("a [cite:aaa] b [cite:aaa]", resolve),
        )
    }

    @Test
    fun `url with parentheses or spaces is percent encoded`() {
        val resolve: (String) -> CitationTarget? = { CitationTarget(1, "https://e/a(b) c") }
        assertEquals(
            "x [[1](https://e/a%28b%29%20c)]",
            replaceCitations("x [cite:aaa]", resolve),
        )
    }

    @Test
    fun `no markers is a no-op`() {
        assertEquals("plain text", replaceCitations("plain text", resolver("a1b2c3")))
    }

    @Test
    fun `findCitations reports positions`() {
        val text = "a [cite:xyz] b"
        val m = findCitations(text).single()
        assertEquals("xyz", m.id)
        assertEquals("[cite:xyz]", text.substring(m.start, m.end))
    }

    @Test
    fun `markdown link is not treated as a citation`() {
        assertEquals(emptyList<CitationMarker>(), findCitations("[label](https://example.com)"))
    }
}
