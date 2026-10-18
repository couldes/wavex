package com.wavex.agent.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTest {

    @Test
    fun `ok outcome carries results in order`() {
        val r = listOf(
            WebSearchResult("a1b2c3", 1, "T1", "https://a.example", "s1"),
            WebSearchResult("d4e5f6", 2, "T2", "https://b.example", "s2"),
        )
        val outcome = WebSearchOutcome.Ok(r)
        assertEquals(2, outcome.results.size)
        assertEquals(1, outcome.results[0].index)
        assertEquals("d4e5f6", outcome.results[1].id)
    }

    @Test
    fun `empty ok is distinct from transient failure`() {
        val empty = WebSearchOutcome.Ok(emptyList())
        val failed = WebSearchOutcome.Transient("timeout")
        assertTrue(empty.results.isEmpty())
        assertEquals("timeout", (failed as WebSearchOutcome.Transient).reason)
    }

    @Test
    fun `permanent carries a user action`() {
        val p = WebSearchOutcome.Permanent("no provider", "请在设置中配置搜索源")
        assertEquals("请在设置中配置搜索源", p.userAction)
    }
}
