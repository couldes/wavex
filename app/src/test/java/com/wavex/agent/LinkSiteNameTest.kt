package com.wavex.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 行为钉子：链接标签如果只是裸域名，换成可读站点名；其余情况一律原样透传。
 * 背景是联网搜索的回答会带 `([github.com](https://github.com/owner/repo))` 这种引用链接，
 * 裸域名会被读成"这个链接指向 GitHub 主站"。
 */
class LinkSiteNameTest {

    // ---------- 裸域名标签：换成站点名 ----------

    @Test
    fun `bare host label becomes site name`() {
        assertEquals("GitHub", linkSiteName("github.com", "https://github.com/mlc-ai/web-llm"))
    }

    @Test
    fun `host label with www prefix recognized`() {
        assertEquals("GitHub", linkSiteName("www.github.com", "https://github.com/mlc-ai/web-llm"))
    }

    @Test
    fun `host label with scheme and trailing slash recognized`() {
        assertEquals("GitHub", linkSiteName("HTTPS://GitHub.com/", "https://github.com/mlc-ai/web-llm"))
    }

    @Test
    fun `host label wrapped in bold markers recognized`() {
        assertEquals("GitHub", linkSiteName("**github.com**", "https://github.com/x/y"))
    }

    @Test
    fun `subdomain label falls back to registrable domain entry`() {
        assertEquals("Wikipedia", linkSiteName("en.wikipedia.org", "https://en.wikipedia.org/wiki/Foo"))
        assertEquals("OpenAI", linkSiteName("platform.openai.com", "https://platform.openai.com/docs"))
        assertEquals("Hacker News", linkSiteName("news.ycombinator.com", "https://news.ycombinator.com/item?id=1"))
    }

    @Test
    fun `short domain form hits its own entry`() {
        assertEquals("YouTube", linkSiteName("youtu.be", "https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("Reddit", linkSiteName("reddit.com", "https://www.reddit.com/r/x"))
    }

    @Test
    fun `port stripped before host compare`() {
        assertEquals("GitHub", linkSiteName("github.com", "https://github.com:443/mlc-ai/web-llm"))
    }

    @Test
    fun `url without scheme still resolves host`() {
        assertEquals("GitHub", linkSiteName("github.com", "github.com/mlc-ai/web-llm"))
    }

    @Test
    fun `live search citation form rewritten`() {
        // 实测线上返回的引用链接原样：sudorelay gpt-6-luna 开联网后的尾巴
        assertEquals(
            "GitHub",
            linkSiteName("github.com", "https://github.com/mlc-ai/web-llm?utm_source=openai")
        )
    }

    // ---------- 不该动的情况 ----------

    @Test
    fun `descriptive label untouched`() {
        assertEquals("mlc-ai/web-llm", linkSiteName("mlc-ai/web-llm", "https://github.com/mlc-ai/web-llm"))
    }

    @Test
    fun `chinese label untouched`() {
        assertEquals("GitHub 仓库", linkSiteName("GitHub 仓库", "https://github.com/a/b"))
    }

    @Test
    fun `host missing from table keeps original label`() {
        assertEquals("some-blog.io", linkSiteName("some-blog.io", "https://some-blog.io/post"))
    }

    @Test
    fun `label naming another site than the target is not rewritten`() {
        // 标签写 github.com 但目标是别家：不能替它撒谎说这是 GitHub
        assertEquals("github.com", linkSiteName("github.com", "https://gitlab.com/a/b"))
    }

    @Test
    fun `blank label returned as is`() {
        assertEquals("", linkSiteName("", "https://github.com/a/b"))
    }

    @Test
    fun `malformed url leaves label alone`() {
        assertEquals("github.com", linkSiteName("github.com", ""))
        assertEquals("github.com", linkSiteName("github.com", "mailto:hi@example.com"))
    }

    @Test
    fun `returned name keeps the table capitalization`() {
        assertEquals("Hugging Face", linkSiteName("huggingface.co", "https://huggingface.co/datasets"))
        assertEquals("Stack Overflow", linkSiteName("stackoverflow.com", "https://stackoverflow.com/q/1"))
        assertEquals("arXiv", linkSiteName("arxiv.org", "https://arxiv.org/abs/1"))
        assertEquals("知乎", linkSiteName("zhihu.com", "https://www.zhihu.com/question/1"))
    }
}
