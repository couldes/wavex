package com.wavex.agent.ui.chat

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.longClick
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.test.hasScrollAction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.wavex.agent.data.ProviderStore
import com.wavex.agent.model.AgentConversation
import com.wavex.agent.model.ChatMessage
import com.wavex.agent.state.WavexViewModel
import com.wavex.agent.ui.theme.AgentTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Uses synthetic typewriter updates and the real ChatScreen, without touching user data/API keys. */
class BottomFollowInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test(timeout = 60_000)
    fun growthFollowsPreciselyButUserDragPausesUntilReturn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.targetContext.startActivity(
            Intent(instrumentation.targetContext, ComponentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        // Some ROMs block background launches from instrumentation. A shell-launched empty
        // host can satisfy the same test without granting permissions or loading user history.
        // The inline start works on stock Android; on MIUI it is silently aborted
        // ("Abort background activity starts"), so also fire the same start through
        // UiAutomation, which executes as the shell uid and is exempt from that block.
        instrumentation.uiAutomation.executeShellCommand(
            "am start -n ${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity"
        ).close()
        var host: ComponentActivity? = null
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.runOnUiThread {
                host = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<ComponentActivity>()
                    .firstOrNull()
            }
            host != null
        }
        lateinit var state: WavexViewModel
        lateinit var conversation: AgentConversation
        lateinit var streamScope: CoroutineScope
        var displayedConversation by mutableStateOf<AgentConversation?>(null)
        compose.runOnUiThread {
            // The test APK context has separate preferences; no persisted conversations are loaded.
            state = WavexViewModel(
                ProviderStore(InstrumentationRegistry.getInstrumentation().context),
                conversationStore = null
            )
            conversation = AgentConversation("bottom-follow-test", "Scroll test")
            conversation.appendMessage(ChatMessage(text = "Synthetic question", fromUser = true))
            conversation.appendMessage(ChatMessage(text = "Short answer", fromUser = false))
            displayedConversation = conversation
        }
        compose.runOnUiThread {
            host!!.setContent {
                AgentTheme(darkTheme = false, dynamicColor = false) {
                    streamScope = rememberCoroutineScope()
                    ChatScreen(modifier = Modifier, state = state, conversation = displayedConversation!!)
                }
            }
        }
        compose.waitForIdle()
        val listState = state.listStateFor(conversation.id)
        compose.runOnIdle {
            conversation.updateMessageAt(1, conversation.messages[1].copy(text = "Synthetic line.\n".repeat(70)))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.last()
            assertTrue("Large growth must keep the last item visible", last.index == 1)
            assertTrue("Large growth must align the bottom edge", last.offset + last.size <= info.viewportEndOffset - info.afterContentPadding + 2)
        }

        // A single extra line is smaller than the user-away tolerance, but still must be followed.
        compose.runOnIdle {
            conversation.updateMessageAt(1, conversation.messages[1].copy(text = conversation.messages[1].text + "Another line.\n"))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.last()
            assertTrue("Small growth must not accumulate before a visible jump", last.offset + last.size <= info.viewportEndOffset - info.afterContentPadding + 2)
        }

        // Exercise the real layout observer with typewriter-sized updates and large bursts.
        var streamingDone by mutableStateOf(false)
        compose.runOnIdle {
            streamScope.launch {
                repeat(30) { chunk ->
                    val suffix = if (chunk % 5 == 0) "Burst line.\n".repeat(12) else "Stream line.\n"
                    conversation.updateMessageAt(1, conversation.messages[1].copy(text = conversation.messages[1].text + suffix))
                    delay(16)
                }
                streamingDone = true
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { streamingDone }
        compose.waitForIdle()
        compose.runOnIdle {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.last()
            assertTrue("Rapid chunks must keep the last item visible", last.index == 1)
            assertTrue("Rapid chunks must not lose follow intent", last.offset + last.size <= info.viewportEndOffset - info.afterContentPadding + 2)
        }
        compose.onNodeWithText("回到底部").assertDoesNotExist()

        // A slow quarter-viewport drag into history stays below the one-viewport
        // threshold: no button. The old 96dp gate flashed it mid-drag (the complaint).
        compose.onNode(hasScrollAction()).performTouchInput {
            down(androidx.compose.ui.geometry.Offset(centerX, height / 2f))
            repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(0f, height / 40f), delayMillis = 250) }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("回到底部").assertDoesNotExist()

        // Scrolling deep into history must not summon the button at all: the reader
        // is deliberately browsing upward (direction gate).
        repeat(4) { compose.onNode(hasScrollAction()).performTouchInput { swipeDown() } }
        compose.waitForIdle()
        compose.onNodeWithText("回到底部").assertDoesNotExist()

        // Heading back toward the bottom (slow finger-up drag, no fling) shows the
        // button while the reader is still more than one viewport away.
        compose.onNode(hasScrollAction()).performTouchInput {
            down(androidx.compose.ui.geometry.Offset(centerX, height / 3f))
            repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(0f, -height / 80f), delayMillis = 300) }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("回到底部").assertExists()

        // One flick back into history hides it at once (direction gate).
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithText("回到底部").assertDoesNotExist()

        // Re-establish a clearly-far, heading-back position for the sections below.
        compose.onNode(hasScrollAction()).performTouchInput {
            down(androidx.compose.ui.geometry.Offset(centerX, height / 3f))
            repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(0f, -height / 80f), delayMillis = 300) }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("回到底部").assertExists()

        var indexBefore = 0
        var offsetBefore = 0
        compose.runOnIdle {
            indexBefore = listState.firstVisibleItemIndex
            offsetBefore = listState.firstVisibleItemScrollOffset
            assertFalse(listState.layoutInfo.isAtBottom(2f))
            conversation.updateMessageAt(1, conversation.messages[1].copy(text = conversation.messages[1].text + "While reading history.\n".repeat(20)))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Growth must preserve the reader's anchor", listState.firstVisibleItemIndex == indexBefore)
            assertTrue("Growth must preserve the reader's offset", listState.firstVisibleItemScrollOffset == offsetBefore)
        }
        // Switching away and back must preserve the per-conversation history position.
        compose.runOnIdle {
            val other = AgentConversation("other-conversation", "Other")
            other.appendMessage(ChatMessage(text = "Other answer", fromUser = false))
            displayedConversation = other
        }
        compose.waitForIdle()
        compose.runOnIdle { displayedConversation = conversation }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(listState.firstVisibleItemIndex == indexBefore)
            assertTrue(listState.firstVisibleItemScrollOffset == offsetBefore)
        }
        compose.onNodeWithText("回到底部").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.last()
            assertTrue("Explicit return must show the last item", last.index == 1)
            assertTrue("Explicit return must align the actual last-item edge", last.offset + last.size <= info.viewportEndOffset - info.afterContentPadding + 2)
        }
        compose.onNodeWithText("回到底部").assertDoesNotExist()

        // Long-press an actually visible text line. Content growth must yield to selection.
        compose.onNode(hasScrollAction()).performTouchInput {
            longClick(androidx.compose.ui.geometry.Offset(width * 0.3f, height * 0.5f))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            indexBefore = listState.firstVisibleItemIndex
            offsetBefore = listState.firstVisibleItemScrollOffset
            conversation.updateMessageAt(1, conversation.messages[1].copy(text = conversation.messages[1].text + "During selection.\n".repeat(12)))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Selection must keep the list still", listState.firstVisibleItemIndex == indexBefore)
            assertTrue("Selection must keep the selected text still", listState.firstVisibleItemScrollOffset == offsetBefore)
        }
        compose.runOnUiThread { host!!.finish() }
    }
}
