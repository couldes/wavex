package com.wavex.agent.state

import com.wavex.agent.model.AgentConversation
import com.wavex.agent.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 空对话复用与清理的规则钉子：
 * 「空」= 未发消息且未改名（title 仍是「新对话」）——改过名的空对话视为有意保留。
 */
class ConversationListOpsTest {

    private fun conv(id: String, title: String = "新对话", vararg texts: String): AgentConversation {
        val c = AgentConversation(id, title)
        texts.forEach { c.appendMessage(ChatMessage(text = it, fromUser = true)) }
        return c
    }

    // ---- findReusableEmptyConversation：新建时复用，不再堆空对话 ----

    @Test
    fun `reuses the first empty untitled conversation`() {
        val a = conv("a", "新对话", "问")
        val b = conv("b")
        val c = conv("c")
        val reused = findReusableEmptyConversation(listOf(a, b, c))
        assertSame(b, reused)
    }

    @Test
    fun `skips renamed empty conversations`() {
        val a = conv("a", title = "工作") // 空但改过名：视为有意保留
        assertNull(findReusableEmptyConversation(listOf(a)))
    }

    @Test
    fun `returns null when all conversations have messages`() {
        val a = conv("a", "新对话", "问", "答")
        assertNull(findReusableEmptyConversation(listOf(a)))
    }

    @Test
    fun `returns null for empty list`() {
        assertNull(findReusableEmptyConversation(emptyList()))
    }

    // ---- dropExtraEmptyConversations：启动/导入后清理积累的空对话 ----

    @Test
    fun `drops scratch empties when real conversations exist`() {
        val a = conv("a", "新对话", "问")
        val b = conv("b")
        val c = conv("c")
        val kept = dropExtraEmptyConversations(listOf(b, a, c))
        assertEquals(listOf(a), kept)
    }

    @Test
    fun `keeps renamed empty conversation on cleanup`() {
        val a = conv("a", title = "工作")
        val b = conv("b")
        val kept = dropExtraEmptyConversations(listOf(b, a))
        assertEquals(listOf(a), kept)
    }

    @Test
    fun `all-scratch list keeps exactly one as current`() {
        val b = conv("b")
        val c = conv("c")
        val kept = dropExtraEmptyConversations(listOf(b, c))
        assertEquals(1, kept.size)
        assertSame(b, kept[0])
        assertNotSame(c, kept[0])
    }

    @Test
    fun `cleanup preserves order of kept conversations`() {
        val a = conv("a", "新对话", "1")
        val b = conv("b", "新对话", "2")
        val scratch = conv("s")
        val kept = dropExtraEmptyConversations(listOf(a, scratch, b))
        assertEquals(listOf(a, b), kept)
    }

    @Test
    fun `cleanup of single conversation keeps it`() {
        val a = conv("a", "新对话", "1")
        assertEquals(listOf(a), dropExtraEmptyConversations(listOf(a)))
    }
}
