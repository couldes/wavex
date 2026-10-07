package com.wavex.agent.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.wavex.agent.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Regression for the reported bug: tapping a user bubble to edit it, then raising the
 * keyboard, used to drag the WHOLE page up by the full keyboard height — the
 * bottom-follow logic kept pinning the LAST message to the keyboard edge while the
 * inline editor was open, throwing the editor and every bubble above it off screen
 * (measured on device: −589px, the entire IME height).
 *
 * Desired behavior: while an inline editor is open, the EDITOR is the anchor of
 * attention. A viewport shrink (what the IME does to the LazyColumn viewport via
 * AgentApp's bottomInputClearance Spacer in production) may scroll only the minimum
 * needed to keep the editor fully visible above the viewport bottom — not the full
 * delta, and never a snap-to-bottom correction.
 */
class EditorKeyboardFollowInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 120_000)
    fun imeShrinkWhileEditingPinsTheEditorNotTheWholePage() {
        dismissPrivacyDialog(instrumentation)

        // Simulated IME clearance below the chat column — production grows a Spacer of
        // exactly this kind (bottomInputClearance), and the viewport-shrink follower
        // only ever sees the resulting LazyListState viewport height change.
        var extraBottomPx by mutableStateOf(0)
        var testScope: CoroutineScope? = null

        // Content must span several viewports so the list has real scroll range: the
        // full-delta yank and the minimal editor-anchored scroll must differ measurably.
        val pairCount = 16
        val messages = buildList {
            repeat(pairCount) { i ->
                add(
                    ChatMessage(
                        text = if (i == pairCount - 1) "edit target" else "Question $i",
                        fromUser = true
                    )
                )
                add(ChatMessage(text = "Answer $i.\n\n".repeat(5), fromUser = false))
            }
        }
        val host = launchChatHost(
            compose = compose,
            conversationId = "editor-keyboard-follow-test",
            title = "Editor keyboard follow test",
            messages = messages,
            contentWrapper = { inner ->
                Box(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    Box(
                        Modifier.fillMaxSize()
                            .padding(bottom = with(density) { extraBottomPx.toDp() })
                    ) { inner() }
                }
            },
            onScope = { testScope = it }
        )
        val listState = host.listState
        val editorItemIndex = pairCount * 2 - 2

        // Start pinned to the true bottom, like a user who has read to the end.
        compose.runOnIdle {
            testScope!!.launch {
                listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, 100_000)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.last()
            assertTrue(
                "Precondition: the list starts pinned to the bottom",
                last.index == info.totalItemsCount - 1 &&
                    last.offset + last.size <= info.viewportEndOffset - info.afterContentPadding + 2
            )
        }

        // Tap the last user bubble: the inline editor replaces it in place.
        compose.onNodeWithText("edit target").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("取消").fetchSemanticsNodes().isNotEmpty()
        }
        compose.awaitSettled(listState)

        // Geometry before the keyboard rises.
        var viewportBottomBefore = 0
        var viewportHeightBefore = 0
        var editorBottomBefore = 0
        var editorOffsetBefore = 0
        compose.runOnIdle {
            val info = listState.layoutInfo
            val editor = info.visibleItemsInfo.first { it.index == editorItemIndex }
            viewportHeightBefore = info.viewportEndOffset - info.viewportStartOffset
            viewportBottomBefore = info.viewportEndOffset - info.afterContentPadding
            editorBottomBefore = editor.offset + editor.size
            editorOffsetBefore = editor.offset
            assertTrue(
                "Precondition: the editor opens fully visible above the viewport bottom " +
                    "(editorBottom=$editorBottomBefore, viewportBottom=$viewportBottomBefore)",
                editorBottomBefore <= viewportBottomBefore
            )
        }

        // "Raise the keyboard": shrink the viewport in one step, the way the IME inset
        // does in production. Half a viewport — large enough that the old full-delta
        // yank and the minimal editor-anchored scroll differ by far more than noise.
        val shrinkPx = viewportHeightBefore / 2
        compose.runOnIdle { extraBottomPx = shrinkPx }
        compose.awaitSettled(listState)

        var viewportBottomAfter = 0
        var editorBottomAfter = 0
        var editorOffsetAfter = 0
        compose.runOnIdle {
            val info = listState.layoutInfo
            val editor = info.visibleItemsInfo.first { it.index == editorItemIndex }
            viewportBottomAfter = info.viewportEndOffset - info.afterContentPadding
            editorBottomAfter = editor.offset + editor.size
            editorOffsetAfter = editor.offset
        }

        val viewportShrink = viewportBottomBefore - viewportBottomAfter
        val actualScroll = editorOffsetBefore - editorOffsetAfter
        // The editor had `gap` px of content below it before the shrink (the reply after
        // the edited bubble). The minimal scroll that keeps the editor fully visible is
        // exactly viewportShrink - gap; the old code scrolled the full viewportShrink.
        val gapBefore = viewportBottomBefore - editorBottomBefore
        val desiredScroll = (viewportShrink - gapBefore).coerceAtLeast(0)

        assertTrue(
            "Precondition: the yank and the minimal scroll must be distinguishable " +
                "(viewportShrink=$viewportShrink, gapBefore=$gapBefore)",
            viewportShrink - gapBefore > 20
        )
        assertTrue(
            "The editor must remain fully visible above the viewport bottom after the " +
                "keyboard rises (editorBottom=$editorBottomAfter, " +
                "viewportBottom=$viewportBottomAfter)",
            editorBottomAfter <= viewportBottomAfter + 2
        )
        assertEquals(
            "The keyboard must scroll only the minimum needed to keep the editor " +
                "visible, not drag the whole page by the keyboard height " +
                "(actualScroll=$actualScroll, desiredScroll=$desiredScroll, " +
                "viewportShrink=$viewportShrink)",
            desiredScroll.toDouble(),
            actualScroll.toDouble(),
            2.0
        )
        assertTrue(
            "Editing near the bottom must not summon the return-to-bottom button",
            compose.onAllNodesWithText("回到底部").fetchSemanticsNodes().isEmpty()
        )
        host.finish()
    }
}
