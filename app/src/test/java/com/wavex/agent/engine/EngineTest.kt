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
    fun `image-drop fallback keeps audio and pdf entries`() = kotlinx.coroutines.test.runTest {
        val r = build(listOf(
            ChatMessage(
                text = "混合附件", fromUser = true,
                attachments = listOf(att("a.png"), att("a.mp3"), att("a.pdf"))
            )
        ))
        // 去图片降级：只摘图片，音频与 PDF 必须保留。
        //（旧实现 filterNot { !startsWith("x-audio:") } 是「只留音频」，
        // 模型拒收图片触发降级时 PDF 被连带静默丢掉）
        assertEquals(listOf("x-pdf:QUJD", "x-audio:mp3|QUJD"), r.noImageHistory[0].imageDataUrls)
        // 去音频降级：图片与 PDF 保留
        assertEquals(
            listOf("data:image/png;base64,QUJD", "x-pdf:QUJD"),
            r.noAudioHistory[0].imageDataUrls
        )
        // 去 PDF 降级：图片与音频保留
        assertEquals(
            listOf("data:image/png;base64,QUJD", "x-audio:mp3|QUJD"),
            r.noPdfHistory[0].imageDataUrls
        )
    }

    @Test
    fun `multiple audio attachments all preserved`() = kotlinx.coroutines.test.runTest {
        val r = build(listOf(
            ChatMessage(text = "", fromUser = true, attachments = listOf(att("a.mp3"), att("b.mp3")))
        ))
        // 旧写法只留第一条音频，其余静默丢失
        assertEquals(
            listOf("x-audio:mp3|QUJD", "x-audio:mp3|QUJD"),
            r.history[0].imageDataUrls
        )
        assertTrue(r.historyHasAudio)
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
    fun `assistant placeholder rewritten to self describing note in history`() = kotlinx.coroutines.test.runTest {
        // 提取占位符原样回传会教会模型“照抄 [图片] 发图”（实测）：必须改写为自描述说明
        val r = build(listOf(
            ChatMessage(text = "换一张", fromUser = true),
            ChatMessage(text = "好的\n[图片]\n（说明）", fromUser = false, attachments = listOf(att("pic.png"))),
            ChatMessage(text = "表格\n[附件 数据表.xlsx]", fromUser = false, attachments = listOf(att("数据表.xlsx")))
        ))
        assertEquals("好的\n（图片已作为附件显示给用户）\n（说明）", r.history[1].text)
        assertEquals("表格\n（附件 数据表.xlsx 已显示给用户）", r.history[2].text)
    }

    @Test
    fun `failure placeholders and user text untouched`() = kotlinx.coroutines.test.runTest {
        // 失败态占位本身自描述，不改写；用户消息正文永远原样
        val r = build(listOf(
            ChatMessage(text = "[图片：保存失败]", fromUser = false),
            ChatMessage(text = "[附件：过大未保存]", fromUser = false),
            ChatMessage(text = "看这个 [图片] 标记", fromUser = true)
        ))
        assertEquals("[图片：保存失败]", r.history[0].text)
        assertEquals("[附件：过大未保存]", r.history[1].text)
        assertEquals("看这个 [图片] 标记", r.history[2].text)
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

    @Test
    fun `assistant attachment-only message gets history note`() = kotlinx.coroutines.test.runTest {
        // 成功态占位已从正文删除：纯附件助手消息正文为空，历史不能发空 content
        // （部分提供方拒收），模型也需要知道自己生成过附件
        val r = build(listOf(ChatMessage(text = "", fromUser = false, attachments = listOf(att("生成图片-1.png")))))
        assertEquals("assistant", r.history[0].role)
        assertTrue(r.history[0].text.isNotBlank())
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
