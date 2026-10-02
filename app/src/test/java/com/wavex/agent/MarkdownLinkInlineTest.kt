package com.wavex.agent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
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
    fun `markdown image renders as a link labelled with alt text`() {
        // 图片直链 ![alt](http…) 不是模型发的图，只是链接：按链接渲染，不出「!」前缀
        val out = render("![雪山湖泊](https://images.unsplash.com/photo-1?w=1200)")
        assertEquals("雪山湖泊", out.text)
        assertNotNull(
            "图片直链应渲染成链接注解",
            out.getLinkAnnotations(0, out.length).firstOrNull {
                (it.item as? LinkAnnotation.Url)?.url == "https://images.unsplash.com/photo-1?w=1200"
            }
        )
    }

    @Test
    fun `markdown image without alt shows the url itself`() {
        val out = render("前图 ![](https://x/y.png) 后图")
        assertEquals("前图 https://x/y.png 后图", out.text)
        assertNotNull(
            out.getLinkAnnotations(0, out.length).firstOrNull {
                (it.item as? LinkAnnotation.Url)?.url == "https://x/y.png"
            }
        )
    }

    /** 钉住：！分支匹配失败时 ！ 不被吞（未闭合图片语法保持字面） */
    @Test
    fun `unterminated image markup stays literal`() {
        assertEquals("![a](https://x.y", render("![a](https://x.y").text)
        assertEquals("![a]", render("![a]").text)
    }

    @Test
    fun `unterminated markup stays literal`() {
        assertEquals("[a](https://x.y", render("[a](https://x.y").text)
        assertEquals("[a]", render("[a]").text)
    }

    @Test
    fun `currency amounts with two dollar signs are not swallowed as inline math`() {
        // 旧实现只要开 $ 后非空白就找闭 $，「$5 再买 $10」会被当成公式，
        // 渲染失败回退后连 $ 符号一起丢掉
        assertEquals("升级到 $5 再买 $10 的套餐", render("升级到 $5 再买 $10 的套餐").text)
    }

    @Test
    fun `tight inline math still recognized`() {
        // 公式定界符紧贴内容（闭 $ 前非空格）仍走公式路径：
        // JVM 测试无 android.graphics，MathRenderer 失败回退为原始 LaTeX 文本
        val out = render("面积为 \$x^2\$ 平方米")
        assertEquals("面积为 x^2 平方米", out.text)
    }
}
