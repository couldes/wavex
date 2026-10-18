package com.wavex.agent.core.data

import com.wavex.agent.core.domain.model.AgentConversationData
import com.wavex.agent.core.domain.model.StoredMessage
import com.wavex.agent.core.domain.model.ToolCall
import com.wavex.agent.core.domain.model.TreeData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考签名落盘。
 *
 * 思考正文早有 `reasoning` 字段在持久化，但 Anthropic 的 thinking 块是带签名的：
 * 后续追问重建历史时只回传正文、签名丢了，回填轮照样会被上游拒。
 * 签名为空时不写字段（旧文件形状不变，也不给普通消息增加行宽）。
 */
class SessionCodecThinkingTest {

    private fun header() = SessionCodec.buildSessionHeader("conv-1", TS)

    private fun parseOne(line: String): StoredMessage {
        val parsed = SessionCodec.parse(header() + "\n" + line)!!
        return parsed.tree.nodes.values.first()
    }

    private val call = ToolCall("toolu_1", "web_search", """{"query":"btc"}""", "btc")

    private fun lineOf(m: StoredMessage) =
        SessionCodec.buildMessageEntry("e1", null, TS, m)

    @Test
    fun `signature round trips`() {
        val m = parseOne(
            lineOf(
                StoredMessage(
                    text = "答案", fromUser = false,
                    reasoning = "先搜索再回答", thinkingSignature = "sig_abc",
                    toolCalls = listOf(call),
                )
            )
        )
        assertEquals("先搜索再回答", m.reasoning)
        assertEquals("sig_abc", m.thinkingSignature)
    }

    @Test
    fun `blank signature is not written`() {
        val line = lineOf(
            StoredMessage(text = "a", fromUser = false, reasoning = "r", toolCalls = listOf(call))
        )
        assertFalse(line.contains("\"thinkingSignature\""))
        assertEquals("", parseOne(line).thinkingSignature)
    }

    /** 没思考的普通消息不该凭空多字段——旧文件形状必须原样 */
    @Test
    fun `plain message writes no thinking signature`() {
        val line = lineOf(StoredMessage(text = "hi", fromUser = true))
        assertFalse(line.contains("\"thinkingSignature\""))
        assertEquals("", parseOne(line).thinkingSignature)
    }

    /** 旧会话文件里没有这个字段：解析要补空串，不能报错也不能丢正文 */
    @Test
    fun `legacy line without signature still parses`() {
        val legacy = """{"id":"e1","type":"message","version":3,"parentId":null,""" +
            """"timestamp":"2026-01-01T00:00:00Z","message":{"text":"旧答案",""" +
            """"fromUser":false,"isError":false,"reasoning":"旧思考"}}"""
        val m = parseOne(legacy)
        assertEquals("旧思考", m.reasoning)
        assertEquals("", m.thinkingSignature)
    }

    /** 签名参与指纹：只改签名的行必须被识别为变更（否则同步会漏掉它） */
    @Test
    fun `fingerprint changes when signature changes`() {
        fun conv(m: StoredMessage) = AgentConversationData(
            id = "c1", title = "t",
            nodes = mapOf("m1" to m),
            children = mapOf(com.wavex.agent.core.domain.model.TREE_ROOT to listOf("m1")),
            activeChild = mapOf(com.wavex.agent.core.domain.model.TREE_ROOT to "m1"),
        )
        val withSig = StoredMessage(
            text = "", fromUser = false, reasoning = "r", thinkingSignature = "s1",
            toolCalls = listOf(call),
        )
        val flushed = SessionDiff.flushedOf(
            title = "t", userDefined = false, userCount = 0,
            tree = TreeData(conv(withSig).nodes, conv(withSig).children, conv(withSig).activeChild),
        )
        val changed = conv(withSig.copy(thinkingSignature = "s2"))
        val lines = SessionDiff.diff(flushed, changed, suppress = null, now = { TS }).lines
        assertTrue("签名变化必须落盘", lines.any { it.contains("\"message\"") })

        val unchanged = SessionDiff.diff(flushed, conv(withSig), suppress = null, now = { TS }).lines
        assertTrue("无变化不应落盘", unchanged.none { it.contains("\"message\"") })
    }

    private companion object {
        const val TS = "2026-10-17T00:00:00.000Z"
    }
}
