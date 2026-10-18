package com.wavex.agent.feature.conversation.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.wavex.agent.core.domain.model.ChatMessage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * 用户消息气泡与模型气泡同宽策略（用户要求：用户消息气泡要做的和模型的气泡一样宽）。
 *
 * 旧实现给用户气泡硬封顶 `widthIn(max = 300.dp)`，模型气泡则按内容自适应：
 * 短内容收缩、长内容铺满整行（屏宽 − 列表左右各 16dp 内边距）。
 * 两者宽度不同源，长消息时用户气泡明显更窄。
 *
 * 断言（都取气泡可点击卡片的外接宽度，不含列表内边距）：
 * 1. 长用户消息气泡宽度 = 长模型消息气泡宽度（同一行宽），这是本次要求的核心；
 * 2. 短用户消息仍随内容收缩，不被"改成铺满"而矫枉过正（与短模型气泡同为收缩语义）。
 */
class BubbleWidthInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val shortUserText = "短问题"
    private val shortModelText = "短回答"
    private val longUserText = "用户的长消息需要换行显示".repeat(12)
    private val longModelText = "模型的长消息同样需要换行显示".repeat(12)

    /** 气泡卡片（带点击语义的合并节点）在窗口坐标系里的宽度，单位 px。 */
    private fun bubbleWidth(text: String): Float =
        compose.onNode(hasText(text) and hasClickAction())
            .fetchSemanticsNode().boundsInRoot.width

    @Test(timeout = 60_000)
    fun userBubbleFollowsModelBubbleWidthPolicy() {
        launchChatHost(
            compose = compose,
            conversationId = "bubble-width-test",
            title = "Bubble width",
            messages = listOf(
                ChatMessage(text = shortUserText, fromUser = true),
                ChatMessage(text = shortModelText, fromUser = false),
                ChatMessage(text = longUserText, fromUser = true),
                ChatMessage(text = longModelText, fromUser = false)
            )
        )

        val shortUser = bubbleWidth(shortUserText)
        val longUser = bubbleWidth(longUserText)
        val longModel = bubbleWidth(longModelText)

        assertTrue(
            "长用户气泡应铺满整行（$longUser px），与长模型气泡（$longModel px）同宽；" +
                "旧实现的 widthIn(max = 300.dp) 会把它压在 300dp 以内",
            abs(longUser - longModel) <= 1f
        )
        assertTrue(
            "短用户气泡应随内容收缩（短 $shortUser px < 长 $longUser px），" +
                "不能为了同宽改成无条件 fillMaxWidth",
            shortUser < longUser - 1f
        )
    }
}
