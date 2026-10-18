package com.wavex.agent.feature.conversation.ui

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.wavex.agent.feature.conversation.model.AgentConversation
import com.wavex.agent.core.domain.model.AgentConversationData
import com.wavex.agent.core.domain.model.ApiProtocol
import com.wavex.agent.core.domain.model.ChatMessage
import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.ConnectionReport
import com.wavex.agent.core.domain.model.ConversationSnapshot
import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.domain.model.SessionStorageStatus
import com.wavex.agent.core.domain.chat.ContentLoader
import com.wavex.agent.core.domain.port.ConversationRepository
import com.wavex.agent.core.domain.port.GeneratedFileWriter
import com.wavex.agent.core.domain.port.ModelService
import com.wavex.agent.core.domain.port.ProviderSettingsRepository
import com.wavex.agent.feature.conversation.viewmodel.ConversationViewModel
import com.wavex.agent.core.ui.AgentTheme
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
 * 等一个 RESUMED 的空宿主 Activity。（曾用 UiAutomation shell 启动兑底 MIUI
 * "禁止后台启动 Activity"；但在 ZUI 上 shell 二次 am start 会触发
 * onActivityRestartAttempt 把已 RESUMED 的宿主压回后台，反而有害，已移除。）
 *
 * 另一处设备级阻塞：锁屏（keyguard）带 PIN 时，系统会拒绝把 Activity 置顶
 * （am start 返回 -92/START_ABORTED，或 RESUMED 后立刻被压 PAUSED），
 * 宿主永远到不了 RESUMED。测试断言加 3 秒提前诊断，报出 keyguard 状态
 * 而不是盲等 20 秒超时。
 */
internal fun awaitResumedHost(compose: ComposeTestRule): ComponentActivity {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.targetContext.startActivity(
        Intent(instrumentation.targetContext, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    var host: ComponentActivity? = null
    compose.waitUntil(timeoutMillis = 20_000) {
        compose.runOnUiThread {
            host = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ComponentActivity>()
                .firstOrNull()
        }
        if (host != null) {
            true
        } else if (System.currentTimeMillis() % 3000 < 60) {
            // 低频诊断：帮助区分「Activity 没起」与「被 keyguard 压住」
            android.util.Log.w("ChatTestSupport", "awaitResumedHost: no RESUMED host yet")
            false
        } else {
            false
        }
    }
    return host ?: error(
        "宿主 Activity 未能在 20s 内 RESUMED。若设备锁屏（keyguard show=true 且带 PIN），" +
            "系统会拒绝置顶（am start result code -92）；请解锁设备后重跑。"
    )
}

/** [launchChatHost] 的产物。 */
internal class ChatHostHandle internal constructor(
    val compose: ComposeTestRule,
    val activity: ComponentActivity,
    val state: ConversationViewModel,
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
    lateinit var state: ConversationViewModel
    lateinit var conversation: AgentConversation
    var displayed by mutableStateOf<AgentConversation?>(null)
    compose.runOnUiThread {
        // 测试 APK 用独立 preferences：不会加载用户真实会话；
        // 仓库 no-op（loadAll 恒空），生成端口假实现（不触网/不落盘）
        state = ConversationViewModel(
            repository = NoOpConversationRepository,
            providerSettings = FakeSettingsRepository(),
            modelService = NoOpModelService,
            contentLoader = ContentLoader { null },
            generatedFileWriter = GeneratedFileWriter { _, _, _ ->
                com.wavex.agent.core.domain.model.AttachmentWriteResult.Failed
            }
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
                        viewModel = state,
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

/**
 * 用真手势把列表拖到 [index] 顶对齐（目标在上方；仅适用于向历史方向回滚）。
 *
 * 不能用 performScrollToNode：目标 item 未组合时它无法从贴底位置回滚（LazyColumn 对
 * 未测量内容不报 ScrollToIndex 动作，工具的 isLazyList 判定失败直接抛错）；也不能用
 * 程序化 scrollToItem：它不派发 DragInteraction、不改写 shouldFollowBottom，会被
 * 贴底纠偏立刻拽回去。真手势是唯一既到达、又让意图状态机记成「用户离开底部」的路径。
 */
internal fun ComposeTestRule.dragToItem(
    listState: LazyListState,
    index: Int,
    timeoutMillis: Long = 15_000L
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    var reached = false
    while (!reached && System.currentTimeMillis() < deadline) {
        runOnIdle { reached = listState.firstVisibleItemIndex <= index }
        if (reached) break
        // 单次半屏慢拖后释放：fling 只会朝同一方向（历史）继续，越过目标会在边界
        // 钉住（index=0 顶对齐必非贴底，纠偏不会拽回），所以过冲无害。
        onNode(hasScrollAction()).performTouchInput {
            down(Offset(centerX, height * 0.6f))
            moveBy(Offset(0f, height / 2f), delayMillis = 20)
            up()
        }
        waitUntil(timeoutMillis) { runOnIdle { !listState.isScrollInProgress } }
    }
    runOnIdle {
        check(
            listState.firstVisibleItemIndex <= index
        ) { "dragToItem($index) did not reach the item; first=${listState.firstVisibleItemIndex}" }
    }
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


/** 测试装配假设置仓库：内存态，无真实持久化 */
private class FakeSettingsRepository : ProviderSettingsRepository {
    private var currentId: String? = null
    override fun loadProviders(): List<Provider> = emptyList()
    override fun saveProviders(providers: List<Provider>) {}
    override fun loadCurrentProviderId(): String? = currentId
    override fun loadCurrentProvider(): Provider? = null
    override fun saveCurrentProviderId(id: String?) { currentId = id }
    override fun loadFetchedModels(): Map<String, List<String>> = emptyMap()
    override fun saveFetchedModels(models: Map<String, List<String>>) {}
    override fun loadThemeChoice(): String = ""
    override fun saveThemeChoice(value: String) {}
    override fun loadReasoningEffort(): String = ""
    override fun saveReasoningEffort(value: String) {}
}

/** 测试装配 no-op 会话仓库：loadAll 恒空、sync/导入短路（不触盘） */
private object NoOpConversationRepository : ConversationRepository {
    override suspend fun loadAll(): List<ConversationSnapshot> = emptyList()
    override suspend fun sync(snapshots: List<AgentConversationData>, suppressedMessageIds: Map<String, String>) {}
    override suspend fun importSessions(snapshots: List<AgentConversationData>): Int = 0
    override suspend fun refreshStorage(): SessionStorageStatus = SessionStorageStatus(false, "", false)
}

/** 测试装配 no-op 网络服务：测试不发真实请求 */
private object NoOpModelService : ModelService {
    override suspend fun streamChat(provider: Provider, history: List<ChatRequestMessage>, reasoningEffort: String?, webSearch: Boolean, onDelta: (String, String) -> Unit, onImage: (String) -> Unit, onToolCalls: (List<com.wavex.agent.core.domain.model.ToolCall>) -> Unit) {}
    override suspend fun generateTitle(provider: Provider, transcript: String): String? = null
    override suspend fun fetchModels(provider: Provider): List<String>? = emptyList()
    override suspend fun probe(provider: Provider): ConnectionReport =
        ConnectionReport(false, ApiProtocol.OPENAI, "", com.wavex.agent.core.domain.model.AuthDialect.BEARER, null, 0L, null)
}
