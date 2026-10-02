package com.wavex.agent.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 思考档位的行为钉子：档位表均匀递进（默认/低/中/高/极高），
 * 旧值 minimal（已移除的「极低」）读取时归一化为 low，未知值回退默认。
 */
class ReasoningLevelsTest {

    @Test
    fun `stored minimal normalizes to low`() {
        assertEquals("low", ReasoningLevels.normalize("minimal"))
    }

    @Test
    fun `known levels pass through`() {
        assertEquals("", ReasoningLevels.normalize(""))
        assertEquals("low", ReasoningLevels.normalize("low"))
        assertEquals("medium", ReasoningLevels.normalize("medium"))
        assertEquals("high", ReasoningLevels.normalize("high"))
        assertEquals("xhigh", ReasoningLevels.normalize("xhigh"))
    }

    @Test
    fun `unknown value falls back to default`() {
        assertEquals("", ReasoningLevels.normalize("bogus"))
    }

    @Test
    fun `display levels are default and four even rungs`() {
        assertEquals(listOf("", "low", "medium", "high", "xhigh"), ReasoningLevels.displayLevels.map { it.first })
    }
}
