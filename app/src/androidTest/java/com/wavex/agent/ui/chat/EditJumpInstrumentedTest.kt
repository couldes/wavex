package com.wavex.agent.ui.chat

import android.app.Instrumentation
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import com.wavex.agent.data.ProviderStore
import com.wavex.agent.model.AgentConversation
import com.wavex.agent.model.ChatMessage
import com.wavex.agent.state.WavexViewModel
import com.wavex.agent.ui.theme.AgentTheme
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

    private lateinit var instrumentation: Instrumentation
    private var testScope: CoroutineScope? = null

    /**
     * This ROM (ZUI) shows a system privacy dialog ("正在尝试读取应用列表") whenever the
     * instrumentation queries installed packages; every test reinstall resets the grant.
     * While it is focused, the compose test rule inspects the dialog's window instead of
     * the app, so interactions silently no-op. Dismiss it by tapping 允许 whenever a
     * foreign window holds focus.
     */
    private fun dismissPrivacyDialog() {
        val targetPackage = instrumentation.targetContext.packageName
        repeat(10) {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
            if (root.packageName == targetPackage) return
            val allow = root.findAccessibilityNodeInfosByText("允许")
                .firstOrNull { it.isVisibleToUser }
            if (allow != null) {
                val b = android.graphics.Rect()
                allow.getBoundsInScreen(b)
                instrumentation.uiAutomation.executeShellCommand(
                    "input tap ${b.centerX()} ${b.centerY()}"
                ).close()
            }
            Thread.sleep(250)
        }
    }

    private fun launchHost(
        withAnswerParagraphs: Int,
        question: String,
        tailUserParagraphs: Int = 0
    ): Pair<WavexViewModel, AgentConversation> {
        instrumentation.targetContext.startActivity(
            Intent(instrumentation.targetContext, ComponentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        instrumentation.uiAutomation.executeShellCommand(
            "am start -n ${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity"
        ).close()
        var host: ComponentActivity? = null
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.runOnUiThread {
                host = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>()
                    .firstOrNull()
            }
            host != null
        }
        lateinit var state: WavexViewModel
        lateinit var conversation: AgentConversation
        var displayedConversation by mutableStateOf<AgentConversation?>(null)
        compose.runOnUiThread {
            state = WavexViewModel(
                ProviderStore(instrumentation.context),
                conversationStore = null
            )
            conversation = AgentConversation("edit-jump-test", "Edit jump test")
            conversation.appendMessage(ChatMessage(text = question, fromUser = true))
            // Paragraph breaks keep each line its own block, so the answer's rendered
            // height scales with the repeat count (single \n would soft-wrap into one
            // paragraph of nearly the same visual height on narrow text).
            conversation.appendMessage(
                ChatMessage(text = "Answer line.\n\n".repeat(withAnswerParagraphs), fromUser = false)
            )
            // 可选的末尾超长用户消息（可点开就地编辑）：构造「编辑最后一条且消息比一屏高」
            if (tailUserParagraphs > 0) {
                conversation.appendMessage(ChatMessage(text = "Tail line.\n\n".repeat(tailUserParagraphs), fromUser = true))
            }
            displayedConversation = conversation
        }
        compose.runOnUiThread {
            host!!.setContent {
                AgentTheme(darkTheme = false, dynamicColor = false) {
                    val scope = rememberCoroutineScope()
                    ChatScreen(modifier = Modifier, state = state, conversation = displayedConversation!!)
                    testScope = scope
                }
            }
        }
        compose.waitForIdle()
        return state to conversation
    }

    @Test(timeout = 120_000)
    fun editJumpKeepsReturnButtonCoherentWithSettledGeometry() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        dismissPrivacyDialog()

        // Scenario A — shallow conversation: content (4-paragraph answer) stays well
        // under the viewport on any phone (≈400dp vs ≥600dp chat viewport), so editing
        // the top question settles inside the bottom tolerance BY CONSTRUCTION —
        // device-independent (a fixed paragraph count tuned to "≈ one viewport" breaks
        // on smaller screens, where the same content legitimately jumps away).
        // The button must never appear, not even transiently (the old code flashed it:
        // show at tap, hide at settle).
        val (stateA, conversationA) = launchHost(withAnswerParagraphs = 4, question = "Near question")
        val listStateA = stateA.listStateFor(conversationA.id)
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
        val (stateB, conversationB) = launchHost(withAnswerParagraphs = 200, question = "Far question")
        val listStateB = stateB.listStateFor(conversationB.id)
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
        val (stateC, conversationC) = launchHost(
            withAnswerParagraphs = 2,
            question = "Mid question",
            tailUserParagraphs = 200
        )
        val listStateC = stateC.listStateFor(conversationC.id)
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
