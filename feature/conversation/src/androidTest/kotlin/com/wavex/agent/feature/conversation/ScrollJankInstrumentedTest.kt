package com.wavex.agent.feature.conversation.ui

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import com.wavex.agent.core.domain.model.ChatMessage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 滚动帧耗时测量（诊断工具，不是 CI 门槛）。
 *
 * 复现用户报告：「长会话中从底部往顶部划会卡顿，从顶部往底部划顺畅」。
 *
 * 会话打开时贴底（snapToBottom 播种），因此视口上方的 item **从未被组合过**。
 * 假设 H1：bottom→top 是冷启动 pass——每帧要在主线程同步组合新进入视口的 item
 * （Markdown 解析、JLatexMath 公式渲染、代码高亮），帧耗时超过 90Hz 预算（≈11.1ms）
 * 就掉帧；top→bottom 发生在上行 pass 之后，组合/测量知识已经存在，因此顺畅。
 *
 * 判别设计（区分「方向问题」与「冷启动问题」）：
 *  - Pass A：bottom→top（冷）——H1 预测大量 ≥12ms 帧；
 *  - Pass B：top→bottom（暖）——H1 预测少量；
 *  - Pass C：bottom→top 再走一遍（暖）——若 H1 成立则与 B 同量级；
 *    若 C 仍然卡，则是方向相关机制（如贴底纠偏/prefetch 不对称），另查。
 *
 * 只做软断言（到达边界、采到帧），阈值判定由人读日志完成——性能断言上 CI 会 flaky。
 * 帧耗时用平台 API Window.addOnFrameMetricsAvailableListener 逐 pass 挂/摘，pass 间过渡帧不计入。
 *
 * 手动运行（默认跳过，不进 connected 门禁；单次 ~40s/变体）。点亮无需改代码：
 *  1. `./gradlew :feature:conversation:connectedDebugAndroidTest
 *      -Pandroid.testInstrumentationRunnerArguments.scrollJank=true
 *      -Pandroid.testInstrumentationRunnerArguments.class=com.wavex.agent.feature.conversation.ui.ScrollJankInstrumentedTest`；
 *  2. 内容消融变体加 `-Pandroid.testInstrumentationRunnerArguments.content=text`
 *     （可选 nocode/notable/nomath，见 [variant]）；
 *  3. 读 logcat tag=ScrollJank 的 `PASS A/B/C` 摘要行与 tag=ItemPerf 的逐块计时。
 *
 * 为什么不用 @Ignore / Assume 门控：AndroidJUnitRunner + AGP 的 connected XML 把两者都记成
 * **空 failure 条目**（类级 @Ignore 连方法名都记成 null；方法级 @Ignore 与假设失败能保住
 * 方法名）——本工具链（runner 1.6.1 / AGP 9.4.1）没有 skipped 分类可用。因此默认走静默
 * 早退：connected 门禁里记为通过（毫秒级），不产生任何 failure 条目。
 *
 * 设备状态敏感性：awaitSettled 卡在「列表静止但 isScrollInProgress=true」且逐 fling
 * 位置无振荡时，先怀疑硬件幽灵输入而不是滚动逻辑——曾在 TB331FC 上遇到 digitizer
 * 幽灵多指拖拽（logcat 的 ViewRootImpl 持续 enqueue motion、getevent 持续吐
 * ABS_MT 事件），`adb reboot` 复位后恢复。
 */
class ScrollJankInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /**
     * 单窗口帧耗时采集器：回调在后台 handler 线程，TOTAL_DURATION 纳秒存入同步列表。
     * FrameMetrics 只在回调内有效，回调内立刻取值拷贝。
     */
    private class FrameCollector(private val window: Window) {
        private val thread = HandlerThread("frame-metrics").apply { start() }
        private val frames = ArrayList<Long>()
        private val listener = Window.OnFrameMetricsAvailableListener { _, fm, _ ->
            val totalNanos = fm.getMetric(FrameMetrics.TOTAL_DURATION)
            synchronized(frames) { frames.add(totalNanos) }
        }

        init {
            window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }

        fun snapshot(): List<Long> = synchronized(frames) { frames.toList() }

        fun detach() {
            window.removeOnFrameMetricsAvailableListener(listener)
            thread.quitSafely()
        }
    }

    /** 消融变体：由 runner 参数 content 决定（full/text/nocode/notable/nomath）。
     *  逐个去除内容特性，定位首次组合 300ms 冻结的源头。 */
    private val variant: String =
        InstrumentationRegistry.getArguments().getString("content", "full")

    /** scrollJank=true 的 runner 参数才点亮诊断；默认静默早退（无 skipped 分类可用，见类注释） */
    private val manualRun: Boolean =
        InstrumentationRegistry.getArguments().getString("scrollJank") == "true"

    /** 单条助手消息：标题+段落+行内公式+块级公式+代码块+表格+列表（真实长会话的代表性重量） */
    private fun assistantMessage(i: Int): ChatMessage {
        val sb = StringBuilder()
        sb.append("### 第 $i 节：滚动渲染与帧调度\n")
        sb.append("本节讨论 **合成管线**、*栅格化路径* 与 `LazyColumn` 的逐帧预算。结论先行：先测量、\n")
        sb.append("后优化、再做判别性检查。渲染目标是在给定刷新率下把每帧耗时压进预算，超出预算的帧\n")
        sb.append("会被合成器跳过，表现为滚动中的顿挫感。\n")
        if (variant != "nomath" && variant != "text") {
            // LaTeX 统一用 \(…\)/\[…\] 定界符：$ 后跟字母会被 Kotlin 字符串插值吃掉
            sb.append("\\[J = \\sum_{k=1}^{n} \\max\\left(0,\\ t_k - \\frac{1000}{r}\\right)\\]\n")
            sb.append("其中 \\(t_k\\) 为第 \\(k\\) 帧的耗时，\\(r\\) 为刷新率，预算 = 1000/r 毫秒。\n")
        }
        if (variant != "nocode" && variant != "text") {
            sb.append("```kotlin\n")
            sb.append("fun measureFrame(pass: Int, frames: List<Long>): FrameStats {\n")
            sb.append("    val budgetMs = 1000.0 / refreshRate\n")
            sb.append("    val janky = frames.count { it / 1e6 > budgetMs }\n")
            sb.append("    val p95 = frames.sorted()[((frames.size - 1) * 0.95).toInt()]\n")
            sb.append("    return FrameStats(pass, frames.size, janky, p95)\n")
            sb.append("}\n")
            sb.append("```\n")
        }
        if (variant != "notable" && variant != "text") {
            sb.append("| 指标 | 预算 | 说明 |\n")
            sb.append("| --- | --- | --- |\n")
            sb.append("| 单帧总耗时 | 11.1ms | 90Hz 屏 |\n")
            sb.append("| 组合 | <3ms | 单个 item 首次组合 |\n")
            sb.append("| 测量布局 | <4ms | 单个 item 首次测量 |\n")
            sb.append("| 公式渲染 | <2ms | 缓存命中后趋近 0 |\n")
        }
        if (variant == "text") {
            // 补齐篇幅：用更多段落保持消息长度可比
            repeat(6) { j ->
                sb.append("这是第 $j 段补充内容，讨论 **合成管线**、*栅格化路径* 与 `LazyColumn` 的")
                sb.append("逐帧预算与帧调度机制，说明滚动性能的测量方法与优化路径。\n")
            }
        }
        sb.append("- 长会话打开时只组合视口内的 item，上方历史从未组合\n")
        sb.append("- 回滚方向的 item 首次组合发生在滚动帧内\n")
        sb.append("- 公式位图与代码高亮在主线程同步执行\n")
        sb.append("- 掉帧判定以 Choreographer 时间戳为准\n")
        return ChatMessage(text = sb.toString(), fromUser = false)
    }

    private fun buildConversation(rounds: Int): List<ChatMessage> = buildList {
        repeat(rounds) { i ->
            add(ChatMessage(text = "问：第 $i 节讲了什么？帮我总结一下要点。", fromUser = true))
            add(assistantMessage(i))
        }
    }

    /** 一个 pass：挂帧监听 → 连续 fling 到边界 → 摘监听 → 返回帧耗时（纳秒）列表。
     *  逐 fling 记录列表位置：如果上行被“拽回底部”，位置会反复振荡，直接可见。 */
    private fun flingPass(
        handle: ChatHostHandle,
        towardTop: Boolean,
        tag: String,
        maxFlings: Int = 48
    ): List<Long> {
        val collector = FrameCollector(handle.activity.window)
        try {
            var flings = 0
            while (flings < maxFlings) {
                val canContinue = compose.runOnIdle {
                    if (towardTop) handle.listState.canScrollBackward
                    else handle.listState.canScrollForward
                }
                if (!canContinue) {
                    Log.i("ScrollJank", "$tag: boundary reached after $flings flings")
                    break
                }
                compose.onNode(hasScrollAction()).performTouchInput {
                    // 列表手势语义：手指下划（swipeDown）= 内容下移 = 露出上方历史 = 向列表顶部滚；
                    // 手指上划（swipeUp）= 向列表末尾滚。towardTop 必须配 swipeDown。
                    if (towardTop) {
                        swipeDown(startY = height * 0.15f, endY = height * 0.85f, durationMillis = 250)
                    } else {
                        swipeUp(startY = height * 0.85f, endY = height * 0.15f, durationMillis = 250)
                    }
                }
                compose.awaitSettled(handle.listState)
                flings++
                val pos = compose.runOnIdle {
                    Triple(
                        handle.listState.firstVisibleItemIndex,
                        handle.listState.firstVisibleItemScrollOffset,
                        handle.listState.layoutInfo.totalItemsCount
                    )
                }
                Log.i("ScrollJank", "$tag fling#$flings -> first=${pos.first} off=${pos.second}/${pos.third}")
            }
            if (flings >= maxFlings) {
                Log.w("ScrollJank", "$tag: exhausted maxFlings=$maxFlings")
            }
        } finally {
            collector.detach()
        }
        return collector.snapshot()
    }

    /** 帧耗时列表 → 摘要。≥12ms 即错过 90Hz 垂直同步，≥23ms 连错两帧以上。 */
    private fun summarize(label: String, frames: List<Long>): String {
        if (frames.isEmpty()) return "$label: no frames captured"
        val sorted = frames.sorted()
        val j12 = sorted.count { it >= 12_000_000L }
        val j23 = sorted.count { it >= 23_000_000L }
        val p50 = sorted[sorted.size / 2] / 1_000_000.0
        val p95 = sorted[((sorted.size - 1) * 0.95).toInt()] / 1_000_000.0
        val max = sorted.last() / 1_000_000.0
        val pct = j12 * 100.0 / sorted.size
        val line = "$label: frames=${sorted.size}, >=12ms=$j12 (${pct}%), >=23ms=$j23, " +
            "p50=${p50}ms, p95=${p95}ms, max=${max}ms"
        Log.i("ScrollJank", line)
        return line
    }

    @Test(timeout = 900_000)
    fun coldUpwardPassShouldJankMoreThanWarmPasses() {
        if (!manualRun) {
            Log.i("ScrollJank", "skipped: 诊断工具默认不进 connected 门禁（scrollJank=true 点亮，见类注释）")
            return
        }
        dismissPrivacyDialog(instrumentation)
        val handle = launchChatHost(
            compose = compose,
            conversationId = "scroll-jank-test",
            title = "Scroll jank test",
            messages = buildConversation(rounds = 30), // 61 条消息
        )
        // 等贴底播种完成（pristine → snapToBottom），避免首帧播种帧混进 Pass A
        compose.awaitSettled(handle.listState)

        val a = flingPass(handle, towardTop = true, tag = "PASS-A")   // 冷：bottom→top
        val b = flingPass(handle, towardTop = false, tag = "PASS-B")  // 暖：top→bottom
        val c = flingPass(handle, towardTop = true, tag = "PASS-C")   // 暖：bottom→top 再来一遍

        val report = listOf(
            summarize("PASS A [$variant] bottom->top (cold)", a),
            summarize("PASS B [$variant] top->bottom (warm)", b),
            summarize("PASS C [$variant] bottom->top (warm)", c)
        ).joinToString("\n")
        println("==== ScrollJank report ====\n$report\n===========================")

        // 软断言：到达边界且每个 pass 都采到了帧
        val reachedTop = compose.runOnIdle { handle.listState.firstVisibleItemIndex == 0 }
        assertTrue(reachedTop)
        assertTrue(a.isNotEmpty() && b.isNotEmpty() && c.isNotEmpty())
        handle.finish()
    }
}
