package com.wavex.agent.feature.conversation.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wavex.agent.core.domain.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * 思考区手动开合的滚动行为回归（fix(chat): let reasoning expand push content down）。
 *
 * 手动开合是阅读意图：不得触发贴底纠偏（snapToBottom）。回归前的真实表现：
 * 贴底状态下点开思考区，展开高度逐帧被 snapToBottom 吃掉 —— 观感是「向上展开」；
 * 展开非末条消息时下方内容先被顶下去再弹回（「界面先动再回位」）。
 * 展开方向锚定由 AnimatedVisibility 的 expandFrom/shrinkTowards=Top 保证，
 * 这里锚定可断言的几何事实：列表锚点与摘要行位置不动、列表不被重新钉回贴底。
 */
class ReasoningExpandInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** 3000 字思考：展开高度远超视口，任何贴底回拉都躲不过锚点断言。 */
    private val reasoning = "思考".repeat(1500)

    /** 摘要行（「已思考 N 字」）在窗口坐标系里的纵向位置。 */
    private fun summaryNodeTop(): Float =
        compose.onNodeWithText("已思考", substring = true)
            .fetchSemanticsNode().boundsInRoot.top

    private fun assertPinnedToBottomPrecondition(listState: androidx.compose.foundation.lazy.LazyListState) {
        compose.runOnIdle {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.last()
            assertTrue(
                "前置：列表应贴底（last.offset=${last.offset}, last.size=${last.size}）",
                last.offset + last.size <= info.viewportEndOffset - info.afterContentPadding + 2
            )
        }
    }

    @Test(timeout = 60_000)
    fun expandLastMessageReasoningAtBottomKeepsListStill() {
        val host = launchChatHost(
            compose = compose,
            conversationId = "reasoning-expand-last",
            title = "Reasoning expand last",
            messages = listOf(
                ChatMessage(text = "合成问题", fromUser = true),
                ChatMessage(text = "回答正文", fromUser = false, reasoning = reasoning)
            )
        )
        val listState = host.listState
        compose.waitForIdle()
        assertPinnedToBottomPrecondition(listState)
        compose.onNodeWithText("已思考", substring = true).assertExists()
        val anchorBefore = compose.anchor(listState)
        val topBefore = summaryNodeTop()

        compose.onNodeWithText("已思考", substring = true).performClick()
        val anchorAfter = compose.awaitSettled(listState)

        assertEquals("展开思考区不得滚动列表（贴底纠偏必须让位于阅读意图）", anchorBefore, anchorAfter)
        assertTrue(
            "摘要行必须原地不动（topBefore=$topBefore, topAfter=${summaryNodeTop()}）",
            abs(summaryNodeTop() - topBefore) <= 2f
        )
        compose.runOnIdle {
            assertFalse(
                "展开后的内容长到视口下方，列表不得被重新钉回贴底",
                listState.layoutInfo.isAtBottom(2f)
            )
        }
        // 展开不产生滚动距离，「回到底部」键不得闪现
        compose.onNodeWithText("回到底部").assertDoesNotExist()
        compose.onNodeWithText(reasoning.take(12), substring = true).assertExists()
        host.finish()
    }

    @Test(timeout = 60_000)
    fun expandNonLastReasoningAtBottomDoesNotYankView() {
        val host = launchChatHost(
            compose = compose,
            conversationId = "reasoning-expand-mid",
            title = "Reasoning expand mid",
            messages = listOf(
                ChatMessage(text = "第一条回答", fromUser = false, reasoning = reasoning),
                ChatMessage(text = "追问", fromUser = true),
                ChatMessage(text = "最终回答", fromUser = false)
            )
        )
        val listState = host.listState
        compose.waitForIdle()
        assertPinnedToBottomPrecondition(listState)
        compose.onNodeWithText("已思考", substring = true).assertExists()
        val anchorBefore = compose.anchor(listState)
        val topBefore = summaryNodeTop()

        compose.onNodeWithText("已思考", substring = true).performClick()
        val anchorAfter = compose.awaitSettled(listState)

        assertEquals("展开非末条的思考区不得把视图拽回底部", anchorBefore, anchorAfter)
        assertTrue(
            "被展开气泡的摘要行必须原地不动（topBefore=$topBefore, topAfter=${summaryNodeTop()}）",
            abs(summaryNodeTop() - topBefore) <= 2f
        )
        compose.runOnIdle {
            assertFalse(
                "下方消息被推开后列表不得回弹贴底",
                listState.layoutInfo.isAtBottom(2f)
            )
        }
        host.finish()
    }
}
