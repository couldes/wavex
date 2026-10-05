package com.wavex.agent.ui.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.wavex.agent.model.ChatMessage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 回归测试：聊天输入框超长文本不得占满整屏。
 *
 * 根因：输入区是根 Column 的非 weight 子项（先按全屏约束测量），BasicTextField
 * 按内容自适应高度。heightIn 只有 min 没有 max 时，超长输入把 weight(1f) 的
 * 消息列表挤没、输入框占满整屏。
 *
 * 断言一：输入 80 行文本后，输入框高度仍远小于整屏（上限约 7 行 + 内边距）。
 * 断言二：消息列表未被挤没——已有消息仍显示在屏幕上。
 */
class ChatInputHeightInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test(timeout = 60_000)
    fun longInputCapsInputHeightAndKeepsMessageListVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        launchChatHost(
            compose = compose,
            conversationId = "input-height-test",
            title = "Input height test",
            messages = listOf(
                ChatMessage(text = "Synthetic question", fromUser = true),
                ChatMessage(text = "Short answer", fromUser = false)
            )
        )

        val input = compose.onNode(hasSetTextAction())
        input.performClick()
        // 80 行 ≈ 1760sp 文本高度，远超任何合理上限；无 max 约束时必然占满整屏
        input.performTextInput("Line of text.\n".repeat(80))
        compose.waitForIdle()

        // 超过阈值后右上角出现「放大编辑」入口，点开全屏编辑器再取消：
        // 实时同步语义下取消不丢字（测试环境无服务商，发送路径另行覆盖）
        compose.onNodeWithContentDescription("放大编辑").assertIsDisplayed()
        compose.onNodeWithContentDescription("放大编辑").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("发送").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()

        val dm = instrumentation.targetContext.resources.displayMetrics
        val inputNode = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
        val inputHeightPx = inputNode.boundsInRoot.height
        assertTrue(
            "Input field must stay well under one screen height " +
                "(was ${inputHeightPx}px of ${dm.heightPixels}px); " +
                "missing heightIn(max=…) lets long input fill the screen",
            inputHeightPx < dm.heightPixels / 2
        )
        // 输入框占满整屏时消息列表被挤到 0 高，该节点不再可见
        compose.onNodeWithText("Synthetic question").assertIsDisplayed()
        // 输入文本仍在（未因布局约束被截断丢失）
        val editable = inputNode.config
            .getOrElse(SemanticsProperties.EditableText) { androidx.compose.ui.text.AnnotatedString("") }
        assertTrue(
            "Input text must be preserved",
            editable.text.count { it == '\n' } >= 80
        )
    }
}
