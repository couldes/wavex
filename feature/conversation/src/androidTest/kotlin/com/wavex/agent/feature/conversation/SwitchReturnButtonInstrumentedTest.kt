package com.wavex.agent.feature.conversation.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasScrollAction
import com.wavex.agent.feature.conversation.model.AgentConversation
import com.wavex.agent.core.domain.model.ChatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 判别性复现：会话未贴底 → 切换会话 →「回到底部」按钮一直存在（用户报告）。
 *
 * H1（播种污染）：切换后首个测量帧若报 atBottom=false，onLayout 会播种
 * shouldFollowBottom=false + distance=MAX → 按钮常亮且贴底纠偏永不再触发。
 * H2（状态残留）：remember(conversation.id) 重置失效，按钮状态从 A 带入 B。
 *
 * 两阶段区分：
 *  - 阶段 1：首次组合「长内容 + 全新 listState」→ 断言贴底且无按钮（直接检验 H1a：
 *    全新长会话是否落在顶部且按钮卡死）。
 *  - 阶段 2：A 滚离底部 → 切到 B（已贴底访问过）→ 断言 B 无按钮（检验携带 H2）。
 *  - 阶段 3：A 滚离底部 → 切到 C（长内容、本进程从未展示）→ 观察落点与按钮。
 */
class SwitchReturnButtonInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun longMessages(prefix: String) = listOf(
        ChatMessage(text = "$prefix question", fromUser = true),
        ChatMessage(text = "$prefix answer\n" + "Synthetic filler line.\n".repeat(120), fromUser = false)
    )

    @Test(timeout = 120_000)
    fun returnButtonMustNotSurviveConversationSwitch() {
        // 阶段 1：全新长会话首次组合 —— 必须贴底、无按钮。
        val host = launchChatHost(
            compose = compose,
            conversationId = "switch-a",
            title = "A",
            messages = longMessages("A")
        )
        val convA = host.conversation
        val listStateA = host.listState
        compose.awaitSettled(listStateA)
        compose.runOnIdle {
            assertTrue(
                "Fresh long conversation must open pinned to bottom, " +
                    "got index=${listStateA.firstVisibleItemIndex} offset=${listStateA.firstVisibleItemScrollOffset}",
                listStateA.layoutInfo.isAtBottom(2f)
            )
        }
        compose.onNodeWithText("回到底部").assertDoesNotExist()

        // 把 A 滚进历史（手指下划 = 翻历史），再往回划一小段（>1 屏外、朝回底部方向）→ 按钮出现。
        compose.onNode(hasScrollAction()).performTouchInput {
            down(androidx.compose.ui.geometry.Offset(centerX, height * 0.7f))
            repeat(40) { moveBy(androidx.compose.ui.geometry.Offset(0f, height / 25f), delayMillis = 300) }
            up()
        }
        compose.awaitSettled(listStateA)
        compose.onNode(hasScrollAction()).performTouchInput {
            down(androidx.compose.ui.geometry.Offset(centerX, height * 0.3f))
            repeat(6) { moveBy(androidx.compose.ui.geometry.Offset(0f, -height / 60f), delayMillis = 300) }
            up()
        }
        compose.awaitSettled(listStateA)
        compose.runOnIdle { assertFalse(listStateA.layoutInfo.isAtBottom(2f)) }
        compose.onNodeWithText("回到底部").assertExists()

        // 阶段 2：切到 B（短内容、必贴底）→ 按钮必须消失（携带检验）。
        val convB = AgentConversation("switch-b", "B")
        convB.appendMessage(ChatMessage(text = "B short answer", fromUser = false))
        host.showConversation(convB)
        compose.waitForIdle()
        compose.onNodeWithText("回到底部").assertDoesNotExist()

        // 阶段 3：切到 C（长内容、本进程首次展示）→ 落点必须贴底、按钮必须不存在。
        val convC = AgentConversation("switch-c", "C")
        longMessages("C").forEach { convC.appendMessage(it) }
        host.showConversation(convC)
        compose.waitForIdle()
        val listStateC = host.state.listStateFor(convC.id)
        compose.awaitSettled(listStateC)
        compose.runOnIdle {
            assertTrue(
                "Fresh long conversation C must open pinned to bottom, " +
                    "got index=${listStateC.firstVisibleItemIndex} offset=${listStateC.firstVisibleItemScrollOffset}",
                listStateC.layoutInfo.isAtBottom(2f)
            )
        }
        compose.onNodeWithText("回到底部").assertDoesNotExist()

        // 阶段 4：切回 A（历史中）→ 按钮按设计出现；点击后贴底并消失（回归既有语义）。
        host.showConversation(convA)
        compose.waitForIdle()
        compose.onNodeWithText("回到底部").assertExists()
        compose.onNodeWithText("回到底部").performClick()
        compose.awaitSettled(listStateA)
        compose.runOnIdle { assertTrue(listStateA.layoutInfo.isAtBottom(2f)) }
        compose.onNodeWithText("回到底部").assertDoesNotExist()
        host.finish()
    }
}
