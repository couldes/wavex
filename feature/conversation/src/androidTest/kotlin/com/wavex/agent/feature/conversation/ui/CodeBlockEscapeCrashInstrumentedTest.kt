package com.wavex.agent.feature.conversation.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.wavex.agent.core.domain.model.ChatMessage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 代码块着色的线上闪退回归（真机 Android 13，v0.15.4）。
 *
 * 崩溃栈：
 *   java.lang.StringIndexOutOfBoundsException: begin 582, end 592, length 591
 *     at MarkdownCodeKt.highlightCode(MarkdownCode.kt:158)
 * 根因：未闭合字符串扫描中，反斜杠是最后一个字符时 `if (content[j] == '\\') j++`
 * 把 j 推到 length，随后无条件 `j++` 得 length + 1，作为 span 的 end 传给
 * `content.substring(start, end)`。这里在真实 ChatScreen 组合里渲染同一形态的
 * 代码块，确认整条渲染路径不再抛异常。
 */
class CodeBlockEscapeCrashInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** 模型常见形态：讲解转义时给出以反斜杠结尾的未闭合字符串示例 */
    private val crashingCode = "echo \"path is C:\\\\"

    @Test(timeout = 60_000)
    fun unclosedStringEndingWithBackslashRendersWithoutCrash() {
        val text = "看这段代码：\n\n```bash\n$crashingCode\n```\n\n它缺少收尾引号。"
        launchChatHost(
            compose = compose,
            conversationId = "code-escape-crash",
            title = "Code escape",
            messages = listOf(
                ChatMessage(text = "转义怎么写？", fromUser = true),
                ChatMessage(text = text, fromUser = false)
            )
        )
        // 组合完成即说明着色没有越界；再确认代码块确实渲染出来了
        compose.waitForIdle()
        assertTrue(
            "含未闭合字符串的代码块应渲染出内容",
            compose.onAllNodesWithText(crashingCode, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }
}
