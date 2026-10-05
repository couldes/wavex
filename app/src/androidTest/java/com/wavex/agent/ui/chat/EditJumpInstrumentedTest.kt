package com.wavex.agent.ui.chat

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import com.wavex.agent.model.ChatMessage
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Regression: tapping a user message to edit must not flash the「回到底部」button
 * (it used to appear at tap and vanish once the edit scroll settled within the
 * 96dp bottom tolerance), and the edit scroll must not be yanked back to the bottom.
 *
 * Both scenarios tap the first (top) message with the list at scroll 0, so no scroll
 * plumbing is needed to reach the target.
 */
class EditJumpInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var testScope: CoroutineScope? = null

    private fun launchHost(
        withAnswerParagraphs: Int,
        question: String,
        tailUserParagraphs: Int = 0
    ): ChatHostHandle {
        // 可选的末尾超长用户消息（可点开就地编辑）：构造「编辑最后一条且消息比一屏高」
        val messages = mutableListOf(
            ChatMessage(text = question, fromUser = true),
            // Paragraph breaks keep each line its own block, so the answer's rendered
            // height scales with the repeat count (single \n would soft-wrap into one
            // paragraph of nearly the same visual height on narrow text).
            ChatMessage(text = "Answer line.\n\n".repeat(withAnswerParagraphs), fromUser = false)
        )
        if (tailUserParagraphs > 0) {
            messages += ChatMessage(text = "Tail line.\n\n".repeat(tailUserParagraphs), fromUser = true)
        }
        return launchChatHost(
            compose = compose,
            conversationId = "edit-jump-test",
            title = "Edit jump test",
            messages = messages,
            onScope = { testScope = it }
        )
    }

    @Test(timeout = 120_000)
    fun editJumpKeepsReturnButtonCoherentWithSettledGeometry() {
        dismissPrivacyDialog(instrumentation)

        // Scenario A — shallow conversation: content (4-paragraph answer) stays well
        // under the viewport on any phone (≈400dp vs ≥600dp chat viewport), so editing
        // the top question settles inside the bottom tolerance BY CONSTRUCTION —
        // device-independent (a fixed paragraph count tuned to "≈ one viewport" breaks
        // on smaller screens, where the same content legitimately jumps away).
        // The button must never appear, not even transiently (the old code flashed it:
        // show at tap, hide at settle).
        val listStateA = launchHost(withAnswerParagraphs = 4, question = "Near question").listState
        compose.waitForIdle()
        compose.onNodeWithText("Near question").performClick()
        var sawButton = false
        compose.waitUntil(timeoutMillis = 5_000) {
            sawButton = sawButton ||
                compose.onAllNodesWithText("回到底部").fetchSemanticsNodes().isNotEmpty()
            // Settle marker: the inline editor (with its cancel button) is composed.
            compose.onAllNodesWithText("取消").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        assertFalse("Editing a near-bottom message must not flash the return button", sawButton)
        compose.onNodeWithText("取消").assertExists()
        compose.runOnIdle {
            assertTrue(
                "Near-bottom edit must settle inside the bottom tolerance",
                listStateA.layoutInfo.isAtBottom(300f)
            )
        }
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Near question").assertExists()

        // Scenario B — deep conversation (content ≈ several viewports): editing the top
        // question is an explicit jump away from the bottom. The button appears and STAYS,
        // and the editor must not be yanked back to the bottom.
        val listStateB = launchHost(withAnswerParagraphs = 200, question = "Far question").listState
        compose.waitForIdle()
        compose.onNodeWithText("Far question").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("回到底部").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("取消").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithText("回到底部").assertExists()
        compose.onNodeWithText("取消").assertExists()
        compose.runOnIdle {
            assertTrue(
                "Far edit must scroll the edited message into view",
                listStateB.firstVisibleItemIndex == 0
            )
        }
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Far question").assertExists()

        // Scenario C — editing a LAST message whose bubble is much taller than one
        // viewport: opening the inline editor REPLACES the bubble (editor is max 8
        // lines, so the item collapses), and animateScrollToItem ends within the bottom
        // tolerance. The editor must stay visible, no yank, and — since the session was
        // at the bottom when tapped — no return button. Pins the boundary that the
        // settled-geometry classification must never treat as "jumped into history".
        val listStateC = launchHost(
            withAnswerParagraphs = 2,
            question = "Mid question",
            tailUserParagraphs = 200
        ).listState
        val tailText = "Tail line.\n\n".repeat(200)
        compose.waitForIdle()
        // Programmatically scroll to the true bottom (the offset clamps at the content
        // end), so the session starts in the follow-bottom state — same as a user who
        // has read to the bottom and then taps the last message.
        compose.runOnIdle {
            testScope!!.launch {
                listStateC.scrollToItem(listStateC.layoutInfo.totalItemsCount - 1, 100_000)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(tailText).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("取消").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        // The editor must stay visible — no bottom-correction yank, no return button.
        compose.onNodeWithText("取消").assertIsDisplayed()
        assertTrue(
            "Editing the last message from the bottom must not summon the return button",
            compose.onAllNodesWithText("回到底部").fetchSemanticsNodes().isEmpty()
        )
    }
}
