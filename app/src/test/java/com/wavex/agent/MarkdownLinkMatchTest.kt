package com.wavex.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 行为钉子：链接解析出的标签区间必须精确——渲染时直接拿它对原文递归解析行内符号，
 * 下标偏一位就会把星号/括号喂进文本流。
 */
class MarkdownLinkMatchTest {

    @Test
    fun `label range slices exactly the label text`() {
        val text = "[**a b**](https://x.y/z)"
        val m = mdLinkMatch(text, 0)!!
        assertEquals("**a b**", text.substring(m.labelFrom, m.labelTo))
        assertEquals("https://x.y/z", m.url)
        assertEquals(text.length, m.end)
    }

    @Test
    fun `offset start uses from as the bracket position`() {
        val text = "看 [mlc-ai/web-llm](https://github.com/mlc-ai/web-llm) 一个项目"
        val from = text.indexOf('[')
        val m = mdLinkMatch(text, from)!!
        assertEquals("mlc-ai/web-llm", text.substring(m.labelFrom, m.labelTo))
        assertEquals("https://github.com/mlc-ai/web-llm", m.url)
        assertEquals(text.indexOf(')', from) + 1, m.end)
    }

    @Test
    fun `scheme-less url gains https while label range stays aligned`() {
        val text = "[github.com](github.com/a/b)"
        val m = mdLinkMatch(text, 0)!!
        assertEquals("github.com", text.substring(m.labelFrom, m.labelTo))
        assertEquals("https://github.com/a/b", m.url)
    }

    @Test
    fun `cjk and emoji labels keep utf-16 range correct`() {
        val cjk = "[中文仓库](https://x.y/z)"
        val mc = mdLinkMatch(cjk, 0)!!
        assertEquals("中文仓库", cjk.substring(mc.labelFrom, mc.labelTo))

        val emoji = "[🚀 repo](https://x.y/z)" // 🚀 占两个 UTF-16 单元
        val m = mdLinkMatch(emoji, 0)!!
        assertEquals("🚀 repo", emoji.substring(m.labelFrom, m.labelTo))
    }

    @Test
    fun `unterminated link does not match`() {
        assertNull(mdLinkMatch("[a](https://x.y", 0))
        assertNull(mdLinkMatch("[a]", 0))
    }

    @Test
    fun `label may contain an open bracket so nested links cannot form`() {
        // 标签字符集排除 ']'，因此标签里装不下第二个完整链接：递归解析不会出现嵌套 withLink
        val text = "[a[b](https://x.y/z)"
        val m = mdLinkMatch(text, 0)!!
        assertEquals("a[b", text.substring(m.labelFrom, m.labelTo))
    }
}
