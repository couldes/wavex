package com.wavex.agent.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 代码块着色回归：未闭合字符串的转义扫描越界。
 *
 * 线上崩溃（v0.15.4，真机 Android 13）：
 *   java.lang.StringIndexOutOfBoundsException: begin 582, end 592, length 591
 *     at com.wavex.agent.core.ui.MarkdownCodeKt.highlightCode(MarkdownCode.kt:158)
 * 两次崩溃的 end 都恰好等于 length + 1：转义扫描里 `if (content[j] == '\\') j++`
 * 只检查了 j，没检查 j + 1；反斜杠落在最后一个字符时 j 自增到 length，
 * 随后无条件 `j++` 得到 length + 1，作为 span 的 end 传给 substring。
 */
class MarkdownCodeHighlightTest {

    @Test
    fun `双引号字符串以反斜杠结尾不越界`() {
        val content = "echo \"abc\\"
        assertEquals(content, highlightPlain(content, "bash"))
    }

    @Test
    fun `单引号字符串以反斜杠结尾不越界`() {
        val content = "echo 'abc\\"
        assertEquals(content, highlightPlain(content, "bash"))
    }

    @Test
    fun `通用语言字符串以反斜杠结尾不越界`() {
        val content = "val s = \"abc\\"
        assertEquals(content, highlightPlain(content, "kotlin"))
    }

    @Test
    fun `反引号字符串以反斜杠结尾不越界`() {
        val content = "val s = `abc\\"
        assertEquals(content, highlightPlain(content, "kotlin"))
    }

    @Test
    fun `python 字符串以反斜杠结尾不越界`() {
        val content = "s = \"abc\\"
        assertEquals(content, highlightPlain(content, "python"))
    }

    @Test
    fun `未闭合字符串跨行不越界`() {
        val content = "echo \"abc\necho done"
        assertEquals(content, highlightPlain(content, "bash"))
    }

    /** 着色只加 span，不改变文本；同时不得抛异常 */
    private fun highlightPlain(content: String, language: String): String =
        highlightCode(content, language).text
}
