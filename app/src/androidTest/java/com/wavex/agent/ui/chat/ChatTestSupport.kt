package com.wavex.agent.ui.chat

import android.app.Instrumentation
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.wavex.agent.data.ProviderStore
import com.wavex.agent.model.AgentConversation
import com.wavex.agent.model.ChatMessage
import com.wavex.agent.state.WavexViewModel
import com.wavex.agent.ui.theme.AgentTheme
import kotlinx.coroutines.CoroutineScope

/**
 * 聊天界面 androidTest 的公共脚手架。
 *
 * 抽它的理由不是"整洁"：同样的宿主启动样板在 6 处逐字重复、隐私弹窗处理 3 处、
 * 锚点轮询 2 处，其中已有两份完全相同、另一份只是折行不同 —— ROM 一换行为就要改多处，
 * 迟早漂移。这里只收**各测试确实一致**的部分；各测试特有的装配（流式协程、
 * InterceptPlatformTextInput、需要 tail 消息的场景）留在测试文件里。
 */

/**
 * ZUI 等 ROM 在 instrumentation 查询已安装应用时会弹系统隐私对话框
 * （"正在尝试读取应用列表"），每次重装测试包都会重置授权。它抢走窗口焦点后，
 * Compose 测试规则检查的是对话框窗口而不是被测应用，所有交互静默失效。
 * 外来窗口持焦时点「允许」放行。
 */
internal fun dismissPrivacyDialog(instrumentation: Instrumentation) {
    val targetPackage = instrumentation.targetContext.packageName
    repeat(10) {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        if (root.packageName == targetPackage) return
        val allow = root.findAccessibilityNodeInfosByText("允许")
            .firstOrNull { it.isVisibleToUser }
        if (allow != null) {
            val b = android.graphics.Rect()
            allow.getBoundsInScreen(b)
            instrumentation.uiAutomation.executeShellCommand(
                "input tap ${b.centerX()} ${b.centerY()}"
            ).close()
        }
        Thread.sleep(250)
    }
}

/**
 * 等一个 RESUMED 的空宿主 Activity。部分 ROM 禁止 instrumentation 后台启动 Activity
 * （MIUI "Abort background activity starts"），所以 startActivity 之外再走一次
 * UiAutomation 的 shell 启动（以 shell uid 执行，不受该限制）。
 */
internal fun awaitResumedHost(compose: ComposeTestRule): ComponentActivity {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.targetContext.startActivity(
        Intent(instrumentation.targetContext, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    instrumentation.uiAutomation.executeShellCommand(
        "am start -n ${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity"
    ).close()
    var host: ComponentActivity? = null
    compose.waitUntil(timeoutMillis = 20_000) {
        compose.runOnUiThread {
            host = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ComponentActivity>()
                .firstOrNull()
        }
        host != null
    }
    return host!!
}

/** [launchChatHost] 的产物。 */
internal class ChatHostHandle internal constructor(
    val compose: ComposeTestRule,
    val activity: ComponentActivity,
    val state: WavexViewModel,
    val conversation: AgentConversation,
    private val show: (AgentConversation) -> Unit
) {
    /** 被测会话的列表状态（ChatScreen 按会话 id 缓存）。 */
    val listState: LazyListState get() = state.listStateFor(conversation.id)

    /** 换掉界面上显示的会话（切换会话后阅读位置是否保持一类断言）。 */
    fun showConversation(conversation: AgentConversation) =
        compose.runOnUiThread { show(conversation) }

    fun finish() = compose.runOnUiThread { activity.finish() }
}

/**
 * 起宿主、造一个只含 [messages] 的会话、挂上 [ChatScreen]。
 *
 * @param contentWrapper 包在 ChatScreen 外的额外组合层（如 InterceptPlatformTextInput）。
 * @param onScope 捕获宿主 CompositionScope（需要程序化滚动时用）。
 */
internal fun launchChatHost(
    compose: ComposeTestRule,
    conversationId: String,
    title: String,
    messages: List<ChatMessage>,
    contentWrapper: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
    onScope: (CoroutineScope) -> Unit = {}
): ChatHostHandle {
    val host = awaitResumedHost(compose)
    lateinit var state: WavexViewModel
    lateinit var conversation: AgentConversation
    var displayed by mutableStateOf<AgentConversation?>(null)
    compose.runOnUiThread {
        // 测试 APK 用独立 preferences：不会加载用户真实会话
        state = WavexViewModel(
            ProviderStore(InstrumentationRegistry.getInstrumentation().context),
            conversationStore = null
        )
        conversation = AgentConversation(conversationId, title)
        messages.forEach { conversation.appendMessage(it) }
        displayed = conversation
    }
    compose.runOnUiThread {
        host.setContent {
            AgentTheme(darkTheme = false, dynamicColor = false) {
                val scope = rememberCoroutineScope()
                contentWrapper {
                    ChatScreen(
                        modifier = Modifier,
                        state = state,
                        conversation = displayed!!
                    )
                }
                onScope(scope)
            }
        }
    }
    compose.waitForIdle()
    return ChatHostHandle(compose, host, state, conversation) { displayed = it }
}

/** 列表锚点（首可见项 + 偏移）：断言"列表到底动没动"的最小充分观测。 */
internal fun ComposeTestRule.anchor(listState: LazyListState): Pair<Int, Int> {
    var index = -1
    var offset = -1
    runOnIdle {
        index = listState.firstVisibleItemIndex
        offset = listState.firstVisibleItemScrollOffset
    }
    return index to offset
}

/** 轮询到列表停下：编辑跳转、键盘改视口、fling 都必须先落定再测量。 */
internal fun ComposeTestRule.awaitSettled(
    listState: LazyListState,
    stableRounds: Int = 15
): Pair<Int, Int> {
    waitUntil(timeoutMillis = 10_000) { runOnIdle { !listState.isScrollInProgress } }
    var last = anchor(listState)
    repeat(stableRounds) {
        Thread.sleep(200)
        waitForIdle()
        val now = anchor(listState)
        if (now == last) return last
        last = now
    }
    return last
}
