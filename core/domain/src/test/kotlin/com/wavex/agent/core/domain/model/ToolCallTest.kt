package com.wavex.agent.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallTest {

    @Test
    fun `request message defaults have no tool data`() {
        val m = ChatRequestMessage("user", "hi")
        assertTrue(m.toolCalls.isEmpty())
        assertNull(m.toolCallId)
    }

    @Test
    fun `assistant message carries calls`() {
        val call = ToolCall("call_1", "web_search", """{"query":"btc"}""", "btc")
        val m = ChatRequestMessage("assistant", "", toolCalls = listOf(call))
        assertEquals(1, m.toolCalls.size)
        assertEquals("btc", m.toolCalls[0].query)
        assertEquals("""{"query":"btc"}""", m.toolCalls[0].arguments)
    }

    @Test
    fun `tool message carries result id`() {
        val m = ChatRequestMessage("tool", """{"items":[]}""", toolCallId = "call_1")
        assertEquals("call_1", m.toolCallId)
    }

    @Test
    fun `stored message carries calls for persistence`() {
        val call = ToolCall("call_1", "web_search", "{}", null)
        val s = StoredMessage(text = "", fromUser = false, toolCalls = listOf(call))
        assertEquals(1, s.toolCalls.size)
        assertNull(s.toolCalls[0].query)
    }
}
