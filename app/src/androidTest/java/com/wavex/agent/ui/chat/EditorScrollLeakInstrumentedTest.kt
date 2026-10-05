package com.wavex.agent.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.wavex.agent.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Dragging inside the inline message editor must not drag the message list along.
 *
 * The editor's OutlinedTextField owns an internal vertical `scrollable` (foundation
 * BasicTextField.kt:439-458). Whatever that field's own range cannot absorb is handed UP the
 * parent nested-scroll chain by `dispatchPostScroll` (Scrollable.kt:798-805), and the leftover
 * fling velocity is handed up too (Scrollable.kt:831-846: a fling that hits its bounds cancels so
 * that "any nested scroll node above" picks up the remaining velocity). The nearest ancestor in
 * that chain is the chat LazyColumn — so dragging to the bottom of the编辑区 keeps going and
 * scrolls the whole conversation.
 *
 * The content is deliberately taller than maxLines = 8 so the field owns a REAL scroll range:
 * that is the reported scenario ("在编辑区域划到底"). With a one-line message the field's range is
 * 0, it never claims the drag at all and LazyColumn consumes the gesture directly — a different
 * mechanism that a nested-scroll swallow cannot affect (verified on device: the list moved by the
 * full injected distance in that case).
 *
 * Non-vacuity is proven two ways: the same amplitude ON THE LIST must move it, and the editor must
 * still be open afterwards (the drag was not swallowed as a tap).
 *
 * Companion observation (measured, pinned here so nobody re-derives it): with a ONE-LINE message
 * the field's own range is 0, it never claims the drag, and LazyColumn consumes the gesture in the
 * Main pass — the list moved by the FULL injected distance while the swallow node above was never
 * consulted (it logged nothing, whereas the measurement code in the same run did). That is a
 * different mechanism from the reported bug ("划到底" requires content that can actually be
 * scrolled to an end) and no nested-scroll swallow can affect it.
 */
class EditorScrollLeakInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun launchHost(
        question: String,
        answerParagraphs: Int
    ): ChatHostHandle = launchChatHost(
        compose = compose,
        conversationId = "scroll-leak-test",
        title = "Scroll leak test",
        // Long answer so the list genuinely CAN scroll: a leak must be observable.
        messages = listOf(
            ChatMessage(text = question, fromUser = true),
            ChatMessage(text = "Answer line.\n\n".repeat(answerParagraphs), fromUser = false)
        )
    )

    @Test(timeout = 120_000)
    fun draggingInsideScrollableInlineEditorDoesNotScrollMessageList() {
        dismissPrivacyDialog(instrumentation)
        val density = instrumentation.targetContext.resources.displayMetrics.density

        // Marker keeps the node findable by substring while the text stays long enough to scroll.
        // Slightly over maxLines = 8: the field owns a SMALL but real range (≈4 lines), so a drag
        // well past it must leak the leftover upward — the user's "在编辑区域划到底" case.
        val marker = "ZZEDITMARKER"
        val longText = marker + "\n" + "Edit line.\n".repeat(12)
        // 400dp ≈ 600px of travel vs a ~150px field range: most of it is leftover.
        val dragPx = 400f * density
        val step = -dragPx / 10f

        val listState = launchHost(question = longText, answerParagraphs = 60).listState

        // Tap the bubble itself (a substring match survives Markdown paragraph splitting).
        compose.onNode(hasText(marker, substring = true)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("取消").fetchSemanticsNodes().isNotEmpty()
        }
        // Exactly one editable node carries the text = the inline editor (the bottom chat input
        // holds different text, so it cannot match).
        val editor = compose.onAllNodes(hasSetTextAction() and hasText(marker, substring = true))
        editor.assertCountEquals(1)
        // The editor must keep its OWN internal scrolling: a lazy "fix" that disables the field's
        // scrollability would also stop the leak and would wrongly pass the assertion below.
        // (In this 12-line case the field absorbs its range first — measured: frames with
        // available=0 precede the saturating available=-51 frames that leaked upward.)
        compose.onAllNodes(
            hasSetTextAction() and hasText(marker, substring = true) and hasScrollAction()
        ).assertCountEquals(1)

        // Edit jump + IME resize settle before anything is measured.
        val listBefore = compose.awaitSettled(listState)

        editor.onFirst().performTouchInput {
            down(Offset(centerX, center.y))
            repeat(10) { moveBy(Offset(0f, step), delayMillis = 40) }
            up()
        }
        compose.waitForIdle()
        val listAfter = compose.awaitSettled(listState)

        assertTrue(
            "Dragging inside the inline editor must not scroll the message list: " +
                "$listBefore -> $listAfter",
            listAfter == listBefore
        )
        // The drag must not have been swallowed as a tap (editor still open).
        compose.onNodeWithText("取消").assertIsDisplayed()

        // Secondary guard: repeated drags must not accumulate into list movement either.
        // (The button-state consequence of the reported `consumed` is covered by
        // editorDragsDoNotRewriteTheReturnToBottomStateWhileTheListStandsStill below — checking
        // it HERE would be vacuous, because at the bottom the button is hidden by the
        // shouldFollowBottom gate no matter what the observer books.)
        var dragIndex = 0
        repeat(3) {
            dragIndex = it + 1
            editor.onFirst().performTouchInput {
                down(Offset(centerX, center.y))
                repeat(10) { moveBy(Offset(0f, step), delayMillis = 40) }
                up()
            }
            compose.waitForIdle()
            assertEquals(
                "Editor drag #$dragIndex must not scroll the message list",
                listBefore,
                compose.awaitSettled(listState)
            )
        }

        // Control — the same amplitude ON THE LIST must move it, so the assertion above cannot
        // pass vacuously (a list that cannot scroll, or a drag too small to be observable).
        val beforeControl = compose.anchor(listState)
        compose.onNode(hasScrollToIndexAction()).performTouchInput {
            down(Offset(centerX, center.y))
            repeat(10) { moveBy(Offset(0f, step), delayMillis = 40) }
            up()
        }
        compose.waitForIdle()
        val afterControl = compose.awaitSettled(listState)
        assertTrue(
            "Setup: a drag on the message list must move it, $beforeControl -> $afterControl",
            afterControl != beforeControl
        )
    }

    /**
     * The swallowed leftover is reported UP as `consumed` (NestedScrollNode.onPostScroll hands
     * the parent parentConsumed + selfConsumed), so the chat list's own scroll observer used to
     * book it as real travel: with the list standing at exactly (0, 0) the「回到底部」button
     * flickered 1 → 0 → 1 across drags made only inside the editor (measured on HA20629S).
     * The list must be parked away from the bottom with the button VISIBLE first, otherwise the
     * assertion is vacuous — from the bottom the direction gate keeps it hidden either way.
     */
    @Test(timeout = 120_000)
    fun editorDragsDoNotRewriteTheReturnToBottomStateWhileTheListStandsStill() {
        dismissPrivacyDialog(instrumentation)
        val density = instrumentation.targetContext.resources.displayMetrics.density

        val marker = "ZZSTATEMARKER"
        val longText = marker + "\n" + "Edit line.\n".repeat(12)
        val step = 400f * density / 10f

        val listState = launchHost(question = longText, answerParagraphs = 60).listState

        // Open the inline editor on the FIRST message: the edit jump scrolls to index 0, which is
        // far from the bottom of a 60-paragraph answer, so the button is legitimately showing.
        compose.onNode(hasText(marker, substring = true)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("取消").fetchSemanticsNodes().isNotEmpty()
        }
        val editor = compose.onAllNodes(hasSetTextAction() and hasText(marker, substring = true))
        editor.assertCountEquals(1)
        compose.awaitSettled(listState)
        compose.onNodeWithText("回到底部").assertExists()

        val anchorBefore = compose.anchor(listState)
        fun buttonCount() = compose.onAllNodesWithText("回到底部").fetchSemanticsNodes().size
        var observed = buttonCount()
        repeat(2) { round ->
            // One round into history (positive), one round back toward the bottom (negative).
            val direction = if (round == 0) step else -step
            editor.onFirst().performTouchInput {
                down(Offset(centerX, center.y))
                repeat(10) { moveBy(Offset(0f, direction), delayMillis = 40) }
                up()
            }
            compose.waitForIdle()
            compose.awaitSettled(listState)
            assertEquals(
                "Editor drag #$round must not move the message list", anchorBefore, compose.anchor(listState)
            )
            assertEquals(
                "Editor drag #$round must not rewrite the 回到底部 state while the list is still " +
                    "at $anchorBefore",
                observed,
                buttonCount()
            )
            observed = buttonCount()
        }
    }
}
