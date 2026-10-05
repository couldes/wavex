package com.wavex.agent.ui.chat

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.longClick
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.test.hasScrollAction
import com.wavex.agent.model.AgentConversation
import com.wavex.agent.model.ChatMessage
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
        lateinit var streamScope: CoroutineScope
        val host = launchChatHost(
            compose = compose,
            conversationId = "bottom-follow-test",
            title = "Scroll test",
            messages = listOf(
                ChatMessage(text = "Synthetic question", fromUser = true),
                ChatMessage(text = "Short answer", fromUser = false)
            ),
            onScope = { streamScope = it }
        )
        val conversation = host.conversation
        val listState = host.listState
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
            host.showConversation(other)
        }
        compose.waitForIdle()
        compose.runOnIdle { host.showConversation(conversation) }
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
        host.finish()
    }
}
