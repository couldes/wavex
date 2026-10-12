package com.wavex.agent.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 列宽缓存键（tableColumnWidthsKey）：列宽测量的全局缓存依赖 key 精确覆盖
 * 所有影响宽度的输入。同一输入必须同 key（保证命中），任一输入变化必须
 * 换 key（保证不串样式/不串内容）。
 */
class TableColumnWidthsKeyTest {

    private val density = Density(2f)
    private val codeBg = Color(0x1F111111)
    private val link = Color(0xFF0066CC)
    private val base = Color(0xFF111111)

    private val table = Block.Table(
        header = listOf("指标", "阈值"),
        rows = listOf(listOf("p95", "18ms"), listOf("max", "36ms")),
        alignments = listOf(TextAlign.Left, TextAlign.Right),
    )

    @Test
    fun `same inputs produce identical key`() {
        val a = tableColumnWidthsKey(table, codeBg, link, base, density)
        val b = tableColumnWidthsKey(table, codeBg, link, base, density)
        assertEquals(a, b)
    }

    @Test
    fun `cell text changes key`() {
        val other = table.copy(header = listOf("指标", "上限"))
        assertNotEquals(tableColumnWidthsKey(table, codeBg, link, base, density), tableColumnWidthsKey(other, codeBg, link, base, density))
    }

    @Test
    fun `alignment changes key`() {
        val other = table.copy(alignments = listOf(TextAlign.Left, TextAlign.Center))
        assertNotEquals(tableColumnWidthsKey(table, codeBg, link, base, density), tableColumnWidthsKey(other, codeBg, link, base, density))
    }

    @Test
    fun `row count changes key`() {
        val other = table.copy(rows = table.rows + listOf(listOf("avg", "9ms")))
        assertNotEquals(tableColumnWidthsKey(table, codeBg, link, base, density), tableColumnWidthsKey(other, codeBg, link, base, density))
    }

    @Test
    fun `color changes key`() {
        val otherLink = Color(0xFF0077DD)
        assertNotEquals(
            tableColumnWidthsKey(table, codeBg, link, base, density),
            tableColumnWidthsKey(table, codeBg, otherLink, base, density),
        )
    }

    @Test
    fun `density changes key`() {
        val other = Density(2.625f)
        assertNotEquals(tableColumnWidthsKey(table, codeBg, link, base, density), tableColumnWidthsKey(table, codeBg, link, base, other))
    }

    @Test
    fun `cell boundary separators are unambiguous`() {
        // (["a|b"], ["a","b"]) 不得生成同一 key：单元格分隔符不能与文本内的 | 混淆
        val pipe = Block.Table(header = listOf("a|b"), rows = emptyList(), alignments = listOf(TextAlign.Left))
        val split = Block.Table(header = listOf("a", "b"), rows = emptyList(), alignments = listOf(TextAlign.Left))
        assertNotEquals(
            tableColumnWidthsKey(pipe, codeBg, link, base, density),
            tableColumnWidthsKey(split, codeBg, link, base, density),
        )
    }
}
