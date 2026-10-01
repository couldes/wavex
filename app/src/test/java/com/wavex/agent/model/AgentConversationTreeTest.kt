package com.wavex.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分支树节点同步的不变量钉子（联网空流「空气泡」bug 的根因记录）：
 *
 * 流式收尾/报错占位更新必须通过 `copy()` 保留原 id 走 updateMessageAt——
 * updateMessageAt 只把「id 相同」的消息同步进树（children[parentId]），
 * 若换成新建 ChatMessage（新 id），仅活跃路径（messages）更新、树里仍是旧节点，
 * 切分支（rebuildFrom 按树重建路径）或重启加载后就变回空气泡。
 */
class AgentConversationTreeTest {

    private fun newConv(): AgentConversation = AgentConversation("c1", "测试")

    @Test
    fun `updateMessageAt with same id updates both path and tree`() {
        val c = newConv()
        c.appendMessage(ChatMessage(text = "问", fromUser = true))
        c.appendMessage(ChatMessage(text = "", fromUser = false))
        val placeholder = c.messages[1]
        val assistantParent = c.messages[0].id  // 助手节点挂在用户消息之下

        // 与引擎收尾一致：copy 保留 id，写入报错文案
        c.updateMessageAt(1, placeholder.copy(text = "（模型没有返回内容）", isError = true))

        // 路径上可见
        assertEquals("（模型没有返回内容）", c.messages[1].text)
        assertTrue(c.messages[1].isError)
        // 树节点同步（切分支/rebuildFrom 后依然可见）
        assertEquals("（模型没有返回内容）", c.children[assistantParent]!!.last().text)
    }

    @Test
    fun `rebuilding the path after in-place update keeps the error text`() {
        val c = newConv()
        c.appendMessage(ChatMessage(text = "问", fromUser = true))
        c.appendMessage(ChatMessage(text = "", fromUser = false))
        val placeholder = c.messages[1]
        c.updateMessageAt(1, placeholder.copy(text = "（模型没有返回内容）", isError = true))

        // 切分支＝按树重建路径（switchVersionAt 到同一版本，模拟用户点 ‹ › 后回来）
        c.switchVersionAt(0, c.messages[0])

        assertEquals("（模型没有返回内容）", c.messages[1].text)
        assertTrue(c.messages[1].isError)
    }

    @Test
    fun `replacing with a new id leaves the tree node stale (documents the old bug)`() {
        val c = newConv()
        c.appendMessage(ChatMessage(text = "问", fromUser = true))
        c.appendMessage(ChatMessage(text = "", fromUser = false))

        // 旧实现的写法：新建 ChatMessage（新 id）——路径变了，树没变
        c.updateMessageAt(1, ChatMessage(text = "（模型没有返回内容）", fromUser = false, isError = true))

        assertEquals("（模型没有返回内容）", c.messages[1].text)
        // 树里仍是空占位 → 切分支即空气泡。此测试钉住该行为差异，防止误改 updateMessageAt 语义。
        assertEquals("", c.children[c.messages[0].id]!!.last().text)
    }
}
