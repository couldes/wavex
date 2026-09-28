package com.wavex.agent.engine

import android.content.Context
import com.wavex.agent.model.ChatAttachment
import com.wavex.agent.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行为钉子：历史构建（多轮发图不丢图、附件类型分流、不可读附件上报、错误/空消息跳过）
 * 与降级链决策（音频→图片→PDF→思考等级→联网搜索的顺序，关键词匹配逐字保留）。
 */
class HistoryBuilderTest {

    /** 假加载器：按附件名返回固定内容；名字含 "dead" 返回 null（不可读） */
    private fun fakeLoader() = ContentLoader { attachment ->
        when {
            attachment.name.startsWith("dead") -> null
            attachment.name.endsWith(".png") -> "image" to "data:image/png;base64,QUJD"
            attachment.name.endsWith(".mp3") -> "audio" to "mp3|QUJD"
            attachment.name.endsWith(".pdf") -> "pdf" to "QUJD"
            else -> "text" to "文件内容"
        }
    }

    private suspend fun build(messages: List<ChatMessage>) =
        HistoryBuilder.build(messages, fakeLoader())

    @Test
    fun `plain text history keeps roles`() = kotlinx.coroutines.test.runTest {
        val r = build(listOf(
            ChatMessage(text = "问", fromUser = true),
            ChatMessage(text = "答", fromUser = false)
        ))
        assertEquals(listOf("user", "assistant"), r.history.map { it.role })
        assertEquals("问", r.history[0].text)
        assertEquals("答", r.history[1].text)
        assertTrue(r.history.all { it.imageDataUrls.isEmpty() })
        assertNull(r.unreadableName)
        assertFalse(r.historyHasAudio); assertFalse(r.historyHasImage); assertFalse(r.historyHasPdf)
    }

    @Test
    fun `multi-turn images all preserved`() = kotlinx.coroutines.test.runTest {
        val r = build(listOf(
            ChatMessage(text = "第一张", fromUser = true, attachments = listOf(att("a.png"))),
            ChatMessage(text = "第二张", fromUser = true, attachments = listOf(att("b.png")))
        ))
        assertEquals(listOf("data:image/png;base64,QUJD"), r.history[0].imageDataUrls)
        assertEquals(listOf("data:image/png;base64,QUJD"), r.history[1].imageDataUrls)
        assertTrue(r.historyHasImage)
    }

    @Test
    fun `audio attachment becomes x-audio prefixed entry`() = kotlinx.coroutines.test.runTest {
        val r = build(listOf(ChatMessage(text = "听", fromUser = true, attachments = listOf(att("a.mp3")))))
        assertEquals(listOf("x-audio:mp3|QUJD"), r.history[0].imageDataUrls)
        assertTrue(r.historyHasAudio)
        assertFalse(r.historyHasImage)
    }

    @Test
    fun `audio only message gets listening instruction`() = kotlinx.coroutines.test.runTest {
        val r = build(listOf(ChatMessage(text = "", fromUser = true, attachments = listOf(att("a.mp3")))))
        assertEquals("（这段对话附带了一段音频，请先听取它的内容再回答）", r.history[0].text)
    }

    @Test
    fun `pdf becomes x-pdf prefixed entry`() = kotlinx.coroutines.test.runTest {
        val r = build(listOf(ChatMessage(text = "读", fromUser = true, attachments = listOf(att("a.pdf")))))
        assertEquals(listOf("x-pdf:QUJD"), r.history[0].imageDataUrls)
        assertTrue(r.historyHasPdf)
        assertFalse(r.historyHasImage)
    }

    @Test
    fun `unreadable attachment reported by name`() = kotlinx.coroutines.test.runTest {
        val r = build(listOf(ChatMessage(text = "看", fromUser = true, attachments = listOf(att("dead1.png")))))
        assertEquals("dead1.png", r.unreadableName)
        // 不可读不进历史
        assertTrue(r.history[0].imageDataUrls.isEmpty())
    }

    @Test
    fun `first unreadable name wins`() = kotlinx.coroutines.test.runTest {
        val r = build(listOf(ChatMessage(
            text = "看",
            fromUser = true,
            attachments = listOf(att("dead1.png"), att("dead2.png"))
        )))
        assertEquals("dead1.png", r.unreadableName)
    }

    @Test
    fun `text file content inlined into message`() = kotlinx.coroutines.test.runTest {
        val r = build(listOf(ChatMessage(text = "内容如下", fromUser = true, attachments = listOf(att("a.txt")))))
        assertEquals("内容如下\n文件内容", r.history[0].text)
    }

    @Test
    fun `error messages and blank messages skipped`() = kotlinx.coroutines.test.runTest {
        val r = build(listOf(
            ChatMessage(text = "错误", fromUser = false, isError = true),
            ChatMessage(text = "", fromUser = true),
            ChatMessage(text = "有效", fromUser = true)
        ))
        assertEquals(1, r.history.size)
        assertEquals("有效", r.history[0].text)
    }

    @Test
    fun `assistant text message without attachments plain`() = kotlinx.coroutines.test.runTest {
        val r = build(listOf(ChatMessage(text = "回复", fromUser = false, attachments = listOf(att("a.png")))))
        // 助手消息带附件不加载（只处理用户消息附件）
        assertEquals("回复", r.history[0].text)
        assertTrue(r.history[0].imageDataUrls.isEmpty())
    }

    private fun att(name: String) = ChatAttachment(uri = "content://test/" + name, name = name)
}

class FallbackPolicyTest {

    private fun flags(
        audio: Boolean = false, image: Boolean = false, pdf: Boolean = false,
        audioDropped: Boolean = false, imageDropped: Boolean = false, pdfDropped: Boolean = false,
        effort: String? = null, web: Boolean = false
    ) = FallbackFlags(audio, image, pdf, audioDropped, imageDropped, pdfDropped, effort, web)

    @Test
    fun `audio rejection drops audio first`() {
        val a = FallbackPolicy.decide("HTTP 400: input_audio format not supported", flags(audio = true))
        assertEquals(FallbackAction.DropAudio, a)
    }

    @Test
    fun `audio already dropped falls through to image`() {
        val a = FallbackPolicy.decide(
            "HTTP 400: image_url not supported",
            flags(audio = true, image = true, audioDropped = true)
        )
        assertEquals(FallbackAction.DropImage, a)
    }

    @Test
    fun `image rejection drops image`() {
        val a = FallbackPolicy.decide("HTTP 400: 模型不支持图片输入", flags(image = true))
        assertEquals(FallbackAction.DropImage, a)
    }

    @Test
    fun `image rejected and dropped falls to pdf`() {
        val a = FallbackPolicy.decide(
            "HTTP 400: document type not supported",
            flags(image = true, pdf = true, imageDropped = true)
        )
        assertEquals(FallbackAction.DropPdf, a)
    }

    @Test
    fun `pdf rejection drops pdf`() {
        val a = FallbackPolicy.decide("HTTP 400: pdf not supported", flags(pdf = true))
        assertEquals(FallbackAction.DropPdf, a)
    }

    @Test
    fun `param error drops effort before web`() {
        val a = FallbackPolicy.decide(
            "HTTP 400 unsupported parameter reasoning_effort",
            flags(effort = "high", web = true)
        )
        assertEquals(FallbackAction.DropEffort, a)
    }

    @Test
    fun `param error with null effort drops web keeping effort decision separate`() {
        val a = FallbackPolicy.decide(
            "HTTP 400 unsupported parameter web_search",
            flags(effort = null, web = true)
        )
        assertEquals(FallbackAction.DropWeb, a)
    }

    @Test
    fun `plain server error is none`() {
        val a = FallbackPolicy.decide("HTTP 500 internal server error", flags(audio = true, image = true, effort = "high", web = true))
        assertEquals(FallbackAction.None, a)
    }

    @Test
    fun `nothing left to drop is none`() {
        val a = FallbackPolicy.decide(
            "HTTP 400: audio not supported",
            flags(audioDropped = true, imageDropped = true, pdfDropped = true, effort = null, web = false)
        )
        assertEquals(FallbackAction.None, a)
    }

    @Test
    fun `audio keyword does not trigger image drop`() {
        val a = FallbackPolicy.decide("HTTP 400: audio not supported", flags(image = true))
        // 音频不在历史里（historyHasAudio=false），图片关键词不匹配 audio 报文 → 不降图
        assertEquals(FallbackAction.None, a)
    }
}
