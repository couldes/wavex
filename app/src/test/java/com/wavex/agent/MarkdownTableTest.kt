package com.wavex.agent

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格块切分回归：GFM 分隔行每个单元格允许单个短横线（|-|-|），
 * 旧正则要求 -{2,}，导致 |A|B| + |-|-| 整段不被识别成表格。
 */
class MarkdownTableTest {

    @Test
    fun `单横线分隔行识别为表格`() {
        val blocks = splitBlocks("|A|B|\n|-|-|\n|1|2|")
        val table = blocks.last()
        assertTrue("最后一个块应为 Table，实际: $blocks", table is Block.Table)
        table as Block.Table
        assertEquals(listOf("A", "B"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun `带空格的单横线分隔行识别为表格`() {
        val blocks = splitBlocks("| A | B |\n| - | - |\n| 1 | 2 |")
        assertTrue(blocks.last() is Block.Table)
    }

    @Test
    fun `双横线及以上分隔行回归不受影响`() {
        val blocks = splitBlocks("|A|B|\n|---|---|\n|1|2|")
        assertTrue(blocks.last() is Block.Table)
    }

    @Test
    fun `完整测试消息中表格仍被识别`() {
        val text = "`print(1)`\n\n\$\$E=mc^2\$\$\n\n|A|B|\n|-|-|\n|1|2|"
        val blocks = splitBlocks(text)
        assertTrue("应包含 Table 块，实际: ${blocks.map { it::class.simpleName }}", blocks.any { it is Block.Table })
    }

    @Test
    fun `孤立短横线不误判为分隔行`() {
        // 前一行不是表格行时，单横线只是普通文本/不构成表格
        val blocks = splitBlocks("A\n|\n-")
        assertTrue(blocks.none { it is Block.Table })
    }

    @Test
    fun `行内公式占位宽度换算成测量区间`() {
        // 回归：列宽测量用纯文本时，公式只算一个空格宽 → 列窄 → 渲染时公式被截断。
        // inlinePlaceholderRanges 必须把内联内容注解换成带真实尺寸的 Placeholder 区间
        val builder = AnnotatedString.Builder()
        builder.append("平方误差（")
        builder.appendInlineContent("math-1", " ")
        builder.append("）")
        val s = builder.toAnnotatedString()
        val ph = Placeholder(40.sp, 16.sp, PlaceholderVerticalAlign.TextCenter)
        val ranges = s.inlinePlaceholderRanges(mapOf("math-1" to InlineTextContent(ph) {}))
        assertEquals(1, ranges.size)
        assertEquals(5, ranges[0].start)
        assertEquals(6, ranges[0].end)
        assertEquals(40.sp, ranges[0].item.width)
    }

    @Test
    fun `无内联内容时占位区间为空`() {
        val s = AnnotatedString("普通文本")
        assertEquals(0, s.inlinePlaceholderRanges(emptyMap()).size)
    }
}
