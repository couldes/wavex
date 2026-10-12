package com.wavex.agent.feature.conversation.ui

import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.wavex.agent.core.ui.AgentTheme
import com.wavex.agent.core.ui.blockDescendantFling
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 回归钉：**框内惯性滑动未停时点按，光标必须落在点按所见行**（blockDescendantFling 修复）。
 *
 * 根因（设备实测 TB331FC / Android 15，foundation 1.10.4）：Compose 文本字段的内部惯性
 * 滑动不在点按 down 时冻结（View 时代 EditText 会在 ACTION_DOWN 强停 scroller），而
 * 点按→光标映射发生在抬起瞬间 → 甩向顶部后快速点按，惯性在 down→up 间把内容滑到顶，
 * 光标落到第 0 行 = 「点编辑框光标有时直接跳到最上面」。
 *
 * 修复前实测：静止对照 Z=15/5 行（映射折叠滚动正确），按住 250ms 滑动中点按 Y=0/0 行
 * （就地编辑框与主输入框旧路径一致）。修复后预期：惯性被容器吞掉，抬起时内容仍停在
 * 甩动拖拽段末尾 → Y = 该处所见行（A ≈ 11-12，B ≈ 3）。
 *
 * 协议：基线 45% 点按 X → 深滚动 → 10% 静止对照 Z（= 下按所见行 + 滚动折叠不变量）→
 * 甩向顶部（落点 0.5h，远离 Z 留在视觉 y≈29px 的光标，避免触发光标拖拽污染实验）→
 * 立即 10% 下压、按住 250ms、抬起 → Y。
 *
 * 场景 A = 就地编辑框（真组件，MessageBubble.InlineMessageEditor，修复挂在 Card）；
 * 场景 B = 主输入框复刻 + blockDescendantFling（与修复后 ChatScreen 输入条同构，
 * 修饰符挂在字段的 LayoutNode 祖先 Column 上，同 blockScrollLeak 的挂点要求）。
 */
class EditorFlingTapBareHarnessInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val question = (0..29).joinToString("\n") { "ROW%02d:abcdefghij".format(it) }
    private val composerDraft = (0..11).joinToString("\n") { "LIN%02d:abcdef".format(it) }

    private val editorMatcher = hasSetTextAction() and hasText("ROW00", substring = true)
    private val composerMatcher = hasSetTextAction() and hasText("LIN00", substring = true)

    private fun markerLine(matcher: androidx.compose.ui.test.SemanticsMatcher, marker: Char): Int = try {
        val node = compose.onNode(matcher)
        node.performTextInput(marker.toString())
        val text = node.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        text.substringBefore(marker).count { it == '\n' }
    } catch (e: AssertionError) {
        -1
    }

    /** 公共协议：基线 → 深滚动 → 静止对照 → 甩向顶部+按住250ms中点按。返回 (x, z, y)。 */
    private fun flingProtocol(
        matcher: androidx.compose.ui.test.SemanticsMatcher,
        deepScrollMoves: Int,
        flickStepPx: Float
    ): Triple<Int, Int, Int> {
        val node = compose.onNode(matcher)
        // 基线：scroll=0 静止点 45%
        node.performTouchInput { down(Offset(center.x, height * 0.45f)); up() }
        compose.waitForIdle()
        val xLine = markerLine(matcher, 'X')

        // 深滚动（末尾慢速收尾，不产生残余 fling）
        node.performTouchInput {
            down(center)
            repeat(deepScrollMoves) { moveBy(Offset(0f, -40f), delayMillis = 25) }
            repeat(2) { moveBy(Offset(0f, -2f), delayMillis = 100) }
            up()
        }
        compose.waitForIdle()

        // 静止对照：内容静止点 10% → 即本次实验下按点的「所见行」
        node.performTouchInput { down(Offset(center.x, height * 0.1f)); up() }
        compose.waitForIdle()
        val zLine = markerLine(matcher, 'Z')

        // 甩向顶部（内容下移）：落点 0.5h 远离光标，快甩三步产生高速度
        node.performTouchInput {
            down(Offset(center.x, height * 0.5f))
            repeat(3) { moveBy(Offset(0f, +flickStepPx), delayMillis = 10) }
            up() // 惯性滑动启动（真实时间运行）
        }
        // 立即下压同一点按位（10%），按住 250ms 让惯性（若未被吞）跑完，再抬起
        node.performTouchInput { down(Offset(center.x, height * 0.1f)) }
        Thread.sleep(250)
        node.performTouchInput { up() }
        compose.waitForIdle()
        val yLine = markerLine(matcher, 'Y')
        return Triple(xLine, zLine, yLine)
    }

    @Test(timeout = 240_000)
    fun inlineEditorFlingTapJumpsToTop() {
        dismissPrivacyDialog(instrumentation)
        val host = awaitResumedHost(compose)
        compose.runOnUiThread {
            host.setContent {
                AgentTheme(darkTheme = false, dynamicColor = false) {
                    InlineMessageEditor(initialText = question, onCancel = {}, onSend = {})
                }
            }
        }
        compose.waitForIdle()
        val (x, z, y) = flingProtocol(editorMatcher, deepScrollMoves = 12, flickStepPx = 45f)
        println("FLING-A 就地编辑框(已修复): 基线X=${x}行 静止对照Z=${z}行 按住250ms滑动中点按Y=${y}行")
        assertTrue("滚动折叠失效：zLine=$z 应比 xLine=$x 深至少 2 行", z >= x + 2)
        assertTrue(
            "惯性冻结失效：Y=$y 行（期望 6-13）。Y≈0 = 甩动惯性穿过点按继续跑、" +
                "抬起映射到顶部（光标跳顶回归）",
            y in 6..13
        )
    }

    @Test(timeout = 240_000)
    fun composerReplicaFlingTapComparison() {
        dismissPrivacyDialog(instrumentation)
        val host = awaitResumedHost(compose)
        compose.runOnUiThread {
            host.setContent {
                AgentTheme(darkTheme = false, dynamicColor = false) {
                    var composerText by remember { mutableStateOf(composerDraft) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp)
                            // 与修复后的主输入框外层 Surface 同款：吞掉子级惯性
                            .blockDescendantFling()
                    ) {
                        BasicTextField(
                            value = composerText,
                            onValueChange = { composerText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp)
                                .padding(top = 10.dp, bottom = 10.dp)
                                .heightIn(min = 44.dp, max = 160.dp),
                            textStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth()) { inner() }
                            }
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val (x, z, y) = flingProtocol(composerMatcher, deepScrollMoves = 4, flickStepPx = 20f)
        println("FLING-B 主输入框(已修复): 基线X=${x}行 静止对照Z=${z}行 按住250ms滑动中点按Y=${y}行")
        assertTrue("滚动折叠失效：zLine=$z 应比 xLine=$x 深至少 2 行", z >= x + 2)
        assertTrue(
            "惯性冻结失效：Y=$y 行（期望 2-4）。Y≈0 = 甩动惯性穿过点按继续跑、" +
                "抬起映射到顶部（光标跳顶回归）",
            y in 2..4
        )
    }
}
