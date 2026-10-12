package com.wavex.agent.feature.conversation.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.wavex.agent.core.ui.AgentTheme
import com.wavex.agent.core.ui.MathBlock
import org.junit.Rule
import org.junit.Test

/**
 * MathBlock 异步渲染回归：块级公式首帧以等宽 LaTeX 原文占位（主线程零渲染成本，
 * 复杂公式解析实测可达 300ms+，曾整帧冻结 LazyList 滚动），后台串行渲染完成后
 * 换入位图。若渲染调度或缓存键断裂，位图永远不出现——本测试钉住「占位 → 位图」
 * 链路端到端可用。不断言时序（50ms 流式防抖、后台线程调度因设备而异），
 * 只断言占位先在、位图终到。
 */
class MathBlockAsyncRenderTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 60_000)
    fun blockMathShowsPlaceholderThenBitmap() {
        dismissPrivacyDialog(instrumentation)
        val host = awaitResumedHost(compose)
        // 避开其它测试用过的公式串：缓存键含 latex，撞上同 key 会直接命中、跳过占位
        val latex = "\\sqrt[3]{8+x-y}"
        compose.runOnUiThread {
            host.setContent {
                AgentTheme(darkTheme = false, dynamicColor = false) {
                    MathBlock(latex, Color(0xFF111111))
                }
            }
        }
        // 首帧：等宽 LaTeX 原文占位（不是空白，也不是主线程同步渲染出的位图）
        compose.onNodeWithText(latex).assertExists()
        // 终态：后台串行渲染完成，位图（contentDescription=数学公式）换入、占位符退场
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("数学公式").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(latex).assertDoesNotExist()
        compose.runOnUiThread { host.finish() }
    }
}
