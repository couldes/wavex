package com.wavex.agent.feature.conversation.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.wavex.agent.core.domain.model.ChatMessage
import com.wavex.agent.feature.conversation.model.AgentConversation
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 回归：点用户气泡下的 ‹ › 切换版本后，列表必须停在用户正在看的那条气泡上，
 * 不能跳到会话末尾。
 *
 * 旧行为（已修）：switchVersionAt → rebuildFrom 重建整条尾链，末条长度/条数随之突变；
 * BottomFollowPolicy 仍处于贴底跟随（用户此前就在底部），于是 contentChanged 触发
 * snapToBottom，把新分支的末条钉到视口底边 —— 用户点的那条气泡被顶出屏幕，
 * 视觉上就是「点切换图标直接跳到底部」。
 *
 * 判别性证据（修复前实测，见提交说明）：底部场景切换后 firstVisibleItemIndex 从 0
 * 变成 5（只剩长答案可见）；修复后保持 0。
 */
class BranchSwitchScrollInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        dismissPrivacyDialog(instrumentation)
    }

    private fun longAnswer(prefix: String) =
        ChatMessage(text = "$prefix answer.\n\n".repeat(80), fromUser = false)

    private fun launch(conversation: AgentConversation): ChatHostHandle {
        val host = launchChatHost(
            compose = compose,
            conversationId = conversation.id,
            title = conversation.title,
            messages = emptyList()
        )
        compose.runOnIdle { host.showConversation(conversation) }
        compose.waitForIdle()
        compose.awaitSettled(host.listState)
        return host
    }

    /**
     * 底部场景（用户报告的原始现象）：会话短到必然贴底，最后一条用户气泡可见；
     * 它的答案有第二个版本且极长。点切换后用户气泡必须仍在屏幕上。
     */
    @Test(timeout = 180_000)
    fun switchingToALongerAnswerKeepsTheUserBubbleOnScreen() {
        val conv = AgentConversation("branch-bottom", "Bottom switch")
        conv.appendMessage(ChatMessage(text = "问题一", fromUser = true))
        conv.appendMessage(ChatMessage(text = "答一", fromUser = false))
        conv.appendMessage(ChatMessage(text = "问题二", fromUser = true))
        conv.appendMessage(ChatMessage(text = "答二", fromUser = false))
        conv.appendMessage(ChatMessage(text = "最后一个问题", fromUser = true))
        conv.appendMessage(ChatMessage(text = "短答案", fromUser = false))
        val shortAnswer = conv.messages[5]
        // 第二版答案：200 段，远高于一屏；插入后立刻切回短答案作为当前版本
        conv.insertVersionAt(5, ChatMessage(text = "长答案段落。\n\n".repeat(200), fromUser = false))
        conv.switchVersionAt(5, shortAnswer)

        val host = launch(conv)
        val listState = host.listState
        assertTrue("前置：起始必须贴底", listState.layoutInfo.isAtBottom(2f))
        compose.onNodeWithText("最后一个问题").assertIsDisplayed()

        compose.onAllNodesWithContentDescription("下一个版本")[0].performClick()
        compose.waitForIdle()
        compose.awaitSettled(listState)

        assertTrue(
            "切换版本后用户气泡被顶出屏幕（first=${listState.firstVisibleItemIndex}/" +
                "${listState.firstVisibleItemScrollOffset}，" +
                "items=${listState.layoutInfo.totalItemsCount}）",
            listState.firstVisibleItemIndex == 0
        )
        compose.onNodeWithText("最后一个问题").assertIsDisplayed()
        host.finish()
    }

    /**
     * 历史中段场景：用户把一条多版本的用户气泡滚到视口顶部后点切换。
     * 尾链被换成另一分支（条数可能变），视口仍必须停在这条气泡上。
     */
    @Test(timeout = 180_000)
    fun switchingQuestionVersionMidHistoryKeepsTheBubbleInPlace() {
        val conv = AgentConversation("branch-mid", "Mid branch switch")
        conv.appendMessage(ChatMessage(text = "问题一", fromUser = true))
        conv.appendMessage(longAnswer("A1"))
        val q2v1 = ChatMessage(text = "问题二·第一版", fromUser = true)
        conv.appendMessage(q2v1)
        conv.appendMessage(longAnswer("A2v1"))
        // 第二版问题 + 它自己的长答案，然后把活跃路径切回第一版
        conv.insertVersionAt(2, ChatMessage(text = "问题二·第二版", fromUser = true))
        conv.appendMessage(longAnswer("A2v2"))
        conv.switchVersionAt(2, q2v1)
        conv.appendMessage(ChatMessage(text = "问题三", fromUser = true))
        conv.appendMessage(longAnswer("A3"))

        val host = launch(conv)
        val listState = host.listState
        // 用真手势把 index=2 滚到视口顶部：程序化 scrollToItem 不派发 DragInteraction，
        // 会被贴底纠偏立刻拽回去
        compose.dragToItem(listState, index = 2)
        compose.awaitSettled(listState)
        val before = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        compose.onNodeWithText("问题二·第一版").assertIsDisplayed()

        compose.onAllNodesWithContentDescription("下一个版本")[0].performClick()
        compose.waitForIdle()
        compose.awaitSettled(listState)

        val after = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        assertTrue(
            "中段切换版本后视口跳走了：before=$before after=$after " +
                "（items=${listState.layoutInfo.totalItemsCount}）",
            after.first <= 2
        )
        compose.onNodeWithText("问题二·第二版").assertIsDisplayed()
        host.finish()
    }
}
