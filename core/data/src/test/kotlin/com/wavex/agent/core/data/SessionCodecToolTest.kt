package com.wavex.agent.core.data

import com.wavex.agent.core.domain.model.AgentConversationData
import com.wavex.agent.core.domain.model.StoredMessage
import com.wavex.agent.core.domain.model.TREE_ROOT
import com.wavex.agent.core.domain.model.TreeData
import com.wavex.agent.core.domain.model.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用的落盘格式：两个字段都是可选的（旧文件没有 → 解析为默认值），
 * 且普通消息不写它们（省行宽，也让旧版本读新文件时字段更少）。
 */
class SessionCodecToolTest {

    private fun header() = SessionCodec.buildSessionHeader("conv-1", "2026-10-17T00:00:00.000Z")

    private fun parseOne(line: String): StoredMessage {
        val parsed = SessionCodec.parse(header() + "\n" + line)!!
        return parsed.tree.nodes.values.first()
    }

    @Test
    fun `tool calls round trip`() {
        val call = ToolCall("call_1", "web_search", """{"query":"btc"}""", "btc")
        val line = SessionCodec.buildMessageEntry(
            entryId = "m1", parentId = null, timestamp = "2026-10-17T00:00:01.000Z",
            m = StoredMessage(text = "", fromUser = false, toolCalls = listOf(call)),
        )
        val m = parseOne(line)
        assertEquals(1, m.toolCalls.size)
        assertEquals("call_1", m.toolCalls[0].id)
        assertEquals("web_search", m.toolCalls[0].name)
        assertEquals("""{"query":"btc"}""", m.toolCalls[0].arguments)
        assertEquals("btc", m.toolCalls[0].query)
    }

    @Test
    fun `tool result id round trips`() {
        val line = SessionCodec.buildMessageEntry(
            entryId = "m1", parentId = null, timestamp = "2026-10-17T00:00:01.000Z",
            m = StoredMessage(text = """{"items":[]}""", fromUser = false, toolCallId = "call_1"),
        )
        assertEquals("call_1", parseOne(line).toolCallId)
    }

    @Test
    fun `old message without tool fields parses to empty`() {
        val line = SessionCodec.buildMessageEntry(
            entryId = "m1", parentId = null, timestamp = "2026-10-17T00:00:01.000Z",
            m = StoredMessage(text = "hi", fromUser = true),
        )
        // 普通消息不写这两个字段（省行宽）
        assertFalse(line.contains("\"toolCalls\""))
        assertFalse(line.contains("\"toolCallId\""))
        val m = parseOne(line)
        assertTrue(m.toolCalls.isEmpty())
        assertNull(m.toolCallId)
    }

    @Test
    fun `hand written line with tool fields parses`() {
        val line = """{"type":"message","id":"m1","parentId":null,""" +
            """"timestamp":"2026-10-17T00:00:01.000Z","message":{""" +
            """"text":"","fromUser":false,"isError":false,""" +
            """"toolCalls":[{"id":"call_1","name":"web_search","arguments":"{\"query\":\"eth\"}"}],""" +
            """"toolCallId":null}}"""
        val m = parseOne(line)
        assertEquals(1, m.toolCalls.size)
        assertEquals("eth", m.toolCalls[0].query)
        assertNull(m.toolCallId)
    }

    @Test
    fun `fingerprint changes when tool calls change`() {
        // 指纹漏字段会让工具调用变化不触发落盘（重启即丢）。
        // fingerprint 是私有的，走公开的 diff 路径断言行为：只改 toolCalls（正文不变）
        // 也必须产出 message 行；完全不变时不应产出。
        fun conv(m: StoredMessage) = AgentConversationData(
            id = "c1",
            title = "t",
            nodes = mapOf("m1" to m),
            children = mapOf(TREE_ROOT to listOf("m1")),
            activeChild = mapOf(TREE_ROOT to "m1"),
        )

        val withCall = StoredMessage(
            text = "", fromUser = false,
            toolCalls = listOf(ToolCall("call_1", "web_search", """{"query":"btc"}""", "btc")),
        )
        val before = conv(withCall)
        val flushed = SessionDiff.flushedOf(
            title = "t", userDefined = false, userCount = 0, tree = TreeData(before.nodes, before.children, before.activeChild),
        )

        val changedCalls = conv(
            withCall.copy(
                toolCalls = listOf(ToolCall("call_1", "web_search", """{"query":"eth"}""", "eth"))
            )
        )
        val lines = SessionDiff.diff(flushed, changedCalls, suppress = null, now = { TS }).lines
        assertTrue("toolCalls 变化必须落盘", lines.any { it.contains("\"message\"") })

        val unchanged = SessionDiff.diff(flushed, before, suppress = null, now = { TS }).lines
        assertTrue("无变化不应落盘", unchanged.none { it.contains("\"message\"") })
    }

    @Test
    fun `fingerprint changes when tool result id changes`() {
        fun conv(m: StoredMessage) = AgentConversationData(
            id = "c1",
            title = "t",
            nodes = mapOf("m1" to m),
            children = mapOf(TREE_ROOT to listOf("m1")),
            activeChild = mapOf(TREE_ROOT to "m1"),
        )
        val plain = StoredMessage(text = "{}", fromUser = false)
        val flushed = SessionDiff.flushedOf(
            title = "t", userDefined = false, userCount = 0, tree = TreeData(conv(plain).nodes, conv(plain).children, conv(plain).activeChild),
        )
        val withId = conv(plain.copy(toolCallId = "call_1"))
        val lines = SessionDiff.diff(flushed, withId, suppress = null, now = { TS }).lines
        assertTrue("toolCallId 变化必须落盘", lines.any { it.contains("\"message\"") })
    }

    private companion object {
        const val TS = "2026-10-17T00:00:02.000Z"
    }
}
