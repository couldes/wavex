package com.wavex.agent.feature.conversation.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.wavex.agent.core.domain.model.ChatMessage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 回归测试：放大编辑（全屏编辑器）唤起键盘后，编辑区文字不得被键盘遮住。
 *
 * 根因：全屏编辑器是独立窗口的 Compose Dialog，默认 decorFitsSystemWindows=true
 * 且内容没有 imePadding —— 键盘弹出时编辑区整体不动，BasicTextField 的光标
 * bring-into-view 只在自身（被键盘盖住的）可视区内滚动，文字"透过键盘"可见。
 *
 * 修复：Dialog decorFitsSystemWindows=false + Column.imePadding()。
 *
 * 断言：真实点击编辑区唤起系统键盘（uiAutomation 注入真实触摸，绕过测试环境
 * 不自动弹键盘的限制），待 IME inset 稳定后，编辑框底部必须高于键盘顶边。
 */
class ExpandedEditorImeClearanceInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test(timeout = 120_000)
    fun raisedKeyboardDoesNotCoverExpandedEditorText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = launchChatHost(
            compose = compose,
            conversationId = "expanded-ime-test",
            title = "Expanded IME test",
            messages = listOf(
                ChatMessage(text = "Synthetic question", fromUser = true),
                ChatMessage(text = "Short answer", fromUser = false)
            )
        )

        // 预置超长正文：既触发「放大编辑」入口，也让编辑区有真实的多行文字
        val input = compose.onNode(hasSetTextAction())
        input.performClick()
        input.performTextInput("Line of text.\n".repeat(80))
        compose.waitForIdle()

        compose.onNodeWithContentDescription("放大编辑").assertIsDisplayed()
        compose.onNodeWithContentDescription("放大编辑").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("发送").assertIsDisplayed()

        // 弹窗打开后就地编辑器与全屏编辑器同时存在（都有 SetText）；
        // 全屏编辑器 weight(1f) 占据弹窗主体，boundsInWindow.top 远小于底部输入栏
        val dialogNode = compose.onAllNodes(hasSetTextAction())
            .fetchSemanticsNodes()
            .minByOrNull { it.boundsInWindow.top }
            ?: error("expanded editor text field not found")
        val bounds = dialogNode.boundsInWindow
        // uiAutomation 注入真实屏幕触摸：让系统输入法真正弹出（performClick 在
        // 测试环境不保证唤起 IME）。真机 IME 弹起受输入法状态影响（残留键盘、
        // 首次点击仅迁移焦点），未弹出则收起重试，最多 3 次。
        fun imeVisible(): Boolean {
            val pfd = instrumentation.uiAutomation.executeShellCommand("dumpsys input_method")
            val raw = java.io.BufferedReader(
                java.io.InputStreamReader(
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd),
                    Charsets.UTF_8
                )
            ).use { it.readText() }
            return "mInputShown=true" in raw.replace(" ", "")
        }
        var raised = false
        repeat(3) {
            // 先收起可能残留的 IME，避免 tap 落在键盘上
            instrumentation.uiAutomation.executeShellCommand("input keyevent 111")
            Thread.sleep(500)
            instrumentation.uiAutomation.executeShellCommand(
                "input tap ${bounds.center.x.toInt()} ${bounds.center.y.toInt()}"
            )
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (imeVisible()) {
                    raised = true
                    break
                }
                Thread.sleep(300)
            }
            if (raised) return@repeat
        }
        check(raised) { "system IME did not rise after tapping the expanded editor" }
        compose.waitForIdle()

        val imeBottom = WindowInsetsCompat.toWindowInsetsCompat(
            host.activity.window.decorView.rootWindowInsets
        ).getInsets(WindowInsetsCompat.Type.ime()).bottom
        val fieldBounds = dialogNode.boundsInWindow
        val imeTop = host.activity.window.decorView.height - imeBottom
        assertTrue(
            "Expanded editor text field bottom (${fieldBounds.bottom}px) must be above " +
                "IME top ($imeTop}px, ime=$imeBottom px)",
            fieldBounds.bottom <= imeTop + 4
        )
    }
}
