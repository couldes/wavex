package com.wavex.agent.ui

import com.wavex.agent.core.domain.model.ChatAttachment
import com.wavex.agent.core.domain.model.ChatMessage
import com.wavex.agent.feature.conversation.model.AgentConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分享导出：纯文本转写与 Markdown 文件内容的构造规则、导出文件名清洗 */
class ConversationExportTest {

    private fun conversation(vararg messages: ChatMessage, title: String = "测试对话"): AgentConversation =
        AgentConversation("conv-id", title).apply { messages.forEach(::appendMessage) }

    @Test
    fun `markdown export uses title heading and role subheadings`() {
        val md = buildMarkdownExport(
            conversation(
                ChatMessage(text = "你好", fromUser = true),
                ChatMessage(text = "你好！有什么可以帮你？", fromUser = false)
            )
        )
        assertEquals(
            "# 测试对话\n" +
                "\n" +
                "## 我\n" +
                "\n" +
                "你好\n" +
                "\n" +
                "## 助手\n" +
                "\n" +
                "你好！有什么可以帮你？\n" +
                "\n",
            md
        )
    }

    @Test
    fun `markdown export skips error and blank messages`() {
        val md = buildMarkdownExport(
            conversation(
                ChatMessage(text = "被取消的占位", fromUser = false, isError = true),
                ChatMessage(text = "   ", fromUser = true),
                ChatMessage(text = "正文", fromUser = true)
            )
        )
        assertEquals("# 测试对话\n\n## 我\n\n正文\n\n", md)
    }

    @Test
    fun `markdown export keeps blank-text attachment message and lists names`() {
        val md = buildMarkdownExport(
            conversation(
                ChatMessage(
                    text = "",
                    fromUser = true,
                    attachments = listOf(ChatAttachment(uri = "u", name = "截图.png"))
                )
            )
        )
        assertEquals("# 测试对话\n\n## 我\n\n（附件：截图.png）\n\n", md)
    }

    @Test
    fun `markdown export keeps message text verbatim including code fence`() {
        val md = buildMarkdownExport(
            conversation(ChatMessage(text = "```kotlin\nval a = 1\n```", fromUser = false))
        )
        assertTrue(md.contains("```kotlin\nval a = 1\n```"))
    }

    @Test
    fun `markdown export of empty conversation is title only`() {
        assertEquals("# 空对话\n\n", buildMarkdownExport(conversation(title = "空对话")))
    }

    @Test
    fun `export file name sanitizes illegal characters`() {
        assertEquals("草稿 初版.md", markdownExportFileName("草稿/初版"))
        assertEquals("a b.md", markdownExportFileName("a\u0000b"))
    }

    @Test
    fun `export file name trims trailing dots and caps length`() {
        assertEquals("标题.md", markdownExportFileName("标题."))
        assertEquals(53, markdownExportFileName("字".repeat(60)).length)
    }

    @Test
    fun `export file name falls back for blank title`() {
        assertEquals("对话导出.md", markdownExportFileName("   "))
        assertEquals("对话导出.md", markdownExportFileName("..."))
    }

    @Test
    fun `transcript keeps role prefix and lists attachments inline`() {
        val text = buildTranscript(
            conversation(
                ChatMessage(
                    text = "看这张图",
                    fromUser = true,
                    attachments = listOf(ChatAttachment(uri = "u", name = "图.png"))
                ),
                ChatMessage(text = "好的", fromUser = false)
            )
        )
        assertEquals("# 测试对话\n\n我：看这张图（附件：图.png）\n\n助手：好的\n\n", text)
    }
}
