package com.wavex.agent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 端到端渲染验证：链接标签内部的行内标记必须真的转成 span。
 * 断言只看 out.text——星号/中括号/圆括号都不该出现在成品文本里，
 * 这条正是「[**名字**](网址) 显示成字面星号」那个缺陷的反证。
 */
class MarkdownLinkInlineTest {

    private fun render(input: String): AnnotatedString {
        var id = 0
        return AnnotatedString.Builder().apply {
            appendInlineSegment(
                input, 0, input.length, null,
                Color.Gray, Color.Blue, Color.Black,
                Density(1f), mutableMapOf(), { id++ }, 0
            )
        }.toAnnotatedString()
    }

    @Test
    fun `bold markers inside a link label render as bold instead of literal asterisks`() {
        val out = render("[**web-llm**](https://github.com/mlc-ai/web-llm)")
        assertEquals("web-llm", out.text)
        val bold = out.spanStyles.firstOrNull { it.item.fontWeight == FontWeight.Bold }
        assertNotNull("链接标签内的加粗没有生成 Bold span", bold)
        assertEquals(0, bold!!.start)
        assertEquals(7, bold.end)
    }

    @Test
    fun `inline code inside a link label renders monospace without backticks`() {
        val out = render("看看 [`uv`](https://astral.sh/uv) 这个项目")
        assertEquals("看看 uv 这个项目", out.text)
        assertNotNull(
            out.spanStyles.firstOrNull { it.item.fontFamily == FontFamily.Monospace }
        )
    }

    @Test
    fun `plain descriptive label is preserved verbatim`() {
        val out = render("[mlc-ai/web-llm](https://github.com/mlc-ai/web-llm)")
        assertEquals("mlc-ai/web-llm", out.text)
    }

    @Test
    fun `bare host label is replaced by the site name end to end`() {
        val out = render("([github.com](https://github.com/mlc-ai/web-llm?utm_source=openai))")
        assertEquals("(GitHub)", out.text)
    }

    @Test
    fun `open bracket inside a label does not become a nested link`() {
        val out = render("[a[b](https://x.y/z)")
        assertEquals("a[b", out.text)
    }

    @Test
    fun `unterminated markup stays literal`() {
        assertEquals("[a](https://x.y", render("[a](https://x.y").text)
        assertEquals("[a]", render("[a]").text)
    }
}
