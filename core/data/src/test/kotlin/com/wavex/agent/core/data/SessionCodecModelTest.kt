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
 * 消息级模型名落盘。
 *
 * 顶栏/抽屉展示的是「当前选中服务商的模型」，用户中途换模型后整条历史都会跟着变，
 * 「这条回答是谁写的」就无从分辨。模型名在发请求时快照进占位气泡、随消息落盘，
 * 气泡头部按消息级展示。
 *
 * 与 thinkingSignature 同款契约：空串不写字段（旧文件形状不变，也不给用户消息增加行宽），
 * 缺字段的旧文件解析要补空串。
 */
class SessionCodecModelTest {

    private fun header() = SessionCodec.buildSessionHeader("conv-1", TS)

    private fun parseOne(line: String): StoredMessage {
        val parsed = SessionCodec.parse(header() + "\n" + line)!!
        return parsed.tree.nodes.values.first()
    }

    private fun lineOf(m: StoredMessage) = SessionCodec.buildMessageEntry("e1", null, TS, m)

    @Test
    fun `model round trips`() {
        val m = parseOne(lineOf(StoredMessage(text = "答案", fromUser = false, model = "gpt-5.2")))
        assertEquals("gpt-5.2", m.model)
        assertEquals("答案", m.text)
    }

    @Test
    fun `blank model is not written`() {
        val userLine = lineOf(StoredMessage(text = "问题", fromUser = true))
        assertFalse("用户消息不该背模型字段", userLine.contains("\"model\""))
        assertEquals("", parseOne(userLine).model)
    }

    /** 旧会话文件里没有这个字段：解析要补空串，不能报错也不能丢正文 */
    @Test
    fun `legacy line without model still parses`() {
        val legacy = """{"id":"e1","type":"message","version":3,"parentId":null,""" +
            """"timestamp":"2026-01-01T00:00:00Z","message":{"text":"旧答案",""" +
            """"fromUser":false,"isError":false}}"""
        val m = parseOne(legacy)
        assertEquals("旧答案", m.text)
        assertEquals("", m.model)
    }

    /** 模型名参与指纹：收尾只回填模型名的行必须被识别为变更（否则重启即丢） */
    @Test
    fun `fingerprint changes when model changes`() {
        fun conv(m: StoredMessage) = AgentConversationData(
            id = "c1", title = "t",
            nodes = mapOf("m1" to m),
            children = mapOf(TREE_ROOT to listOf("m1")),
            activeChild = mapOf(TREE_ROOT to "m1"),
        )
        val blank = StoredMessage(text = "答案", fromUser = false)
        val flushed = SessionDiff.flushedOf(
            title = "t", userDefined = false, userCount = 0,
            tree = TreeData(conv(blank).nodes, conv(blank).children, conv(blank).activeChild),
        )
        val lines = SessionDiff.diff(
            flushed, conv(blank.copy(model = "gpt-5.2")), suppress = null, now = { TS }
        ).lines
        assertTrue("模型名变化必须落盘", lines.any { it.contains("\"message\"") })

        val unchanged = SessionDiff.diff(
            flushed, conv(blank), suppress = null, now = { TS }
        ).lines
        assertTrue("无变化不应落盘", unchanged.none { it.contains("\"message\"") })
    }

    private companion object {
        const val TS = "2026-10-17T00:00:00.000Z"
    }
}
