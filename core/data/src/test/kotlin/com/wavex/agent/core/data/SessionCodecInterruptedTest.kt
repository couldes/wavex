package com.wavex.agent.core.data

import com.wavex.agent.core.domain.model.AgentConversationData
import com.wavex.agent.core.domain.model.StoredMessage
import com.wavex.agent.core.domain.model.TREE_ROOT
import com.wavex.agent.core.domain.model.TreeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「回答未说完」标记的落盘。
 *
 * 半截流（连接在生成中途断开）保留下来的部分正文必须带着 interrupted 一起落盘：
 * 进程随时可能被系统回收，重启后用户仍要能看出这条回答没说完，而不是拿到一条
 * 看起来完整、实际少了半篇的正文。
 *
 * 与 model/thinkingSignature 同款契约：false 不写字段（旧文件形状不变、普通消息不增加行宽），
 * 缺字段的旧文件解析补 false。
 */
class SessionCodecInterruptedTest {

    private fun header() = SessionCodec.buildSessionHeader("conv-1", TS)

    private fun parseOne(line: String): StoredMessage {
        val parsed = SessionCodec.parse(header() + "\n" + line)!!
        return parsed.tree.nodes.values.first()
    }

    private fun lineOf(m: StoredMessage) = SessionCodec.buildMessageEntry("e1", null, TS, m)

    @Test
    fun `interrupted round trips`() {
        val m = parseOne(lineOf(StoredMessage(text = "写到一半", fromUser = false, interrupted = true)))
        assertTrue(m.interrupted)
        assertEquals("写到一半", m.text)
    }

    @Test
    fun `complete answer does not carry the field`() {
        val line = lineOf(StoredMessage(text = "完整答案", fromUser = false))
        assertFalse("正常回答不该背 interrupted 字段", line.contains("\"interrupted\""))
        assertFalse(parseOne(line).interrupted)
    }

    /** 旧会话文件里没有这个字段：解析要补 false，不能报错也不能丢正文 */
    @Test
    fun `legacy line without interrupted still parses`() {
        val legacy = """{"id":"e1","type":"message","version":3,"parentId":null,""" +
            """"timestamp":"2026-01-01T00:00:00Z","message":{"text":"旧答案",""" +
            """"fromUser":false,"isError":false}}"""
        val m = parseOne(legacy)
        assertEquals("旧答案", m.text)
        assertFalse(m.interrupted)
    }

    /**
     * 标记必须参与内容指纹：断流的收尾只翻 interrupted、正文不再变，
     * 指纹不含它就得不到变更 → 标记永不落盘，重启后「未说完」的线索彻底消失。
     */
    @Test
    fun `fingerprint changes when interrupted changes`() {
        fun conv(m: StoredMessage) = AgentConversationData(
            id = "c1", title = "t",
            nodes = mapOf("m1" to m),
            children = mapOf(TREE_ROOT to listOf("m1")),
            activeChild = mapOf(TREE_ROOT to "m1"),
        )
        val whole = StoredMessage(text = "写到一半", fromUser = false)
        val flushed = SessionDiff.flushedOf(
            title = "t", userDefined = false, userCount = 0,
            tree = TreeData(conv(whole).nodes, conv(whole).children, conv(whole).activeChild),
        )
        val lines = SessionDiff.diff(
            flushed, conv(whole.copy(interrupted = true)), suppress = null, now = { TS }
        ).lines
        assertTrue("interrupted 变化必须落盘", lines.any { it.contains("\"message\"") })

        val unchanged = SessionDiff.diff(flushed, conv(whole), suppress = null, now = { TS }).lines
        assertTrue("无变化不应落盘", unchanged.none { it.contains("\"message\"") })
    }

    private companion object {
        const val TS = "2026-10-18T00:00:00.000Z"
    }
}
