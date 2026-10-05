package com.wavex.agent.ui.chat

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.wavex.agent.model.ChatMessage
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression: a second tap INSIDE the already-open inline message editor must not tear down and
 * re-establish its text-input session. That teardown + re-open IS the reported defect
 * (“每点击一次编辑界面，键盘就关闭又唤醒”): one session close + one session start == one keyboard
 * hide + one keyboard show.
 *
 * Root cause (gesture dispatch order): the root Column's `dismissKeyboardOnTap` observer runs on
 * PointerEventPass.Initial while children consume on Main. For a parent/child pair the order is
 *   parent.Initial -> child.Initial -> child.Main -> parent.Main
 * (HitPathTracker.dispatchMainEventPass), so the observer can never see the text field's claim for
 * the very gesture it is still processing — its own comment concedes this (“消费结果这一刻还看不到,
 * 只能靠报备推断”). It therefore falls through to `else -> hideKeyboard(localView)`, which calls
 * hideSoftInputFromWindow() AND AndroidComposeView.clearFocus(); the field's `tapToFocus` then
 * re-requests focus and the IME shows again — one close+reopen per tap.
 *
 * Every other tappable subtree inside that Column already registers the exemption
 * (Modifier.keyboardSuppressReport on the input pill, the 联网/附件 chips and the branch switcher);
 * the inline editor was the one left out.
 *
 * Why this observation and not a UI one:
 *  - “still focused after the tap” is VACUOUS: tapToFocus() restores focus within the same gesture,
 *    so the final focus state is identical with and without the bug.
 *  - real-IME visibility is not device-independent (this device runs Sogou; `mIsInputViewShown` in
 *    dumpsys was demonstrably unreliable for this window).
 *  - the interceptor counts and swallows requests in-process, so no IME on the device can affect
 *    the number.
 *
 * Attribution: only the editor field ever gains focus in this flow (the bottom chat input is never
 * tapped), so every counted session belongs to the editor.
 */
class EditorKeyboardFlickerInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @OptIn(ExperimentalComposeUiApi::class)
    @Test(timeout = 120_000)
    fun retappingInsideTheEditorDoesNotRestartItsTextInputSession() {
        dismissPrivacyDialog(instrumentation)

        val marker = "ZZKBDFLICKER"
        val sessions = AtomicInteger(0)

        val host = launchChatHost(
            compose = compose,
            conversationId = "kbd-flicker-test",
            title = "Keyboard flicker test",
            messages = listOf(
                ChatMessage(text = marker, fromUser = true),
                ChatMessage(text = "Answer.\n\n".repeat(4), fromUser = false)
            ),
            contentWrapper = { content ->
                InterceptPlatformTextInput(
                    interceptor = { _, _ ->
                        // Count and block. Nothing is forwarded, so no real IME session is
                        // started and the count is a purely in-process signal. The API
                        // guarantees one call at a time per text input modifier, releasing the
                        // previous call on cancellation — same lifetime as production.
                        sessions.incrementAndGet()
                        awaitCancellation()
                    },
                    content = content
                )
            }
        )

        // Open the inline editor by tapping the user bubble.
        compose.onNode(hasText(marker)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasSetTextAction() and hasText(marker)).fetchSemanticsNodes()
                .isNotEmpty()
        }
        val editor = compose.onAllNodes(hasSetTextAction() and hasText(marker))

        // Tap #1 inside the field: focus is acquired, so a session must start. Without this the
        // equality assertions below would pass vacuously on a field that never opens a session.
        editor.onFirst().performClick()
        compose.waitForIdle()
        val afterFirst = sessions.get()
        assertTrue(
            "Setup: tapping into the editor must start a text-input session (got $afterFirst)",
            afterFirst >= 1
        )

        // Tap #2 inside the ALREADY focused field. With the bug, the root observer's clearFocus
        // cancels this session and tapToFocus immediately starts a new one: afterSecond > first.
        editor.onFirst().performClick()
        compose.waitForIdle()
        val afterSecond = sessions.get()

        // Tap #3 — same, and it also proves the field is still interactive (the fix must not
        // disable the editor's own input).
        editor.onFirst().performClick()
        compose.waitForIdle()
        val afterThird = sessions.get()

        assertTrue(
            "Re-tapping inside the inline editor must not close and re-open its text-input " +
                "session — that churn is the keyboard hiding and re-showing on every tap. " +
                "sessions: $afterFirst -> $afterSecond -> $afterThird",
            afterSecond == afterFirst && afterThird == afterFirst
        )

        compose.runOnUiThread { host.activity.finish() }
    }
}
