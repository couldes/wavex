package com.wavex.agent

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wavex.agent.ui.theme.AgentTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class MainTab { CHAT, MODELS, SETTINGS }
private enum class ThemeChoice { SYSTEM, LIGHT, DARK }
// 思考等级："" = 不传参数（跟随模型默认）；极低/低/中/高一一对应 reasoning_effort
// 的 minimal/low/medium/high（gpt-5 系全部档位），纯中文短标签，排版整齐
private val REASONING_LEVELS = listOf(
    "" to "默认",
    "minimal" to "极低",
    "low" to "低",
    "medium" to "中",
    "high" to "高"
)
private data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val fromUser: Boolean,
    val attachments: List<ChatAttachment> = emptyList(),
    val isError: Boolean = false,
    // 模型思考过程（reasoning_content 流式增量）：气泡里渲染成可折叠的「已思考 N 字」区块
    val reasoning: String = ""
)

/**
 * 对话 = 消息分叉树（ChatGPT 式）：
 * - children: 父消息 id -> 子消息列表（同一位置的所有版本），根挂在 TREE_ROOT 下；
 * - activeChild: 父消息 id -> 当前激活的子消息 id（每个节点记住自己走的是哪个孩子）；
 * - messages: 当前活跃路径，渲染与请求都只看它；切换版本时按 activeChild 重建。
 * 编辑重发不再销毁旧内容，而是新增兄弟版本，可随时 ‹ › 切回。
 */
private class AgentConversation(
    val id: String,
    initialTitle: String
) {
    var title by mutableStateOf(initialTitle)

    val children = androidx.compose.runtime.mutableStateMapOf<String, SnapshotStateList<ChatMessage>>()
    val activeChild = androidx.compose.runtime.mutableStateMapOf<String, String>()

    /** 当前活跃路径（渲染 / 发送历史都用它） */
    val messages = mutableStateListOf<ChatMessage>()

    fun childrenOf(parentId: String): SnapshotStateList<ChatMessage> =
        children.getOrPut(parentId) { mutableStateListOf() }

    private fun parentIdAt(index: Int): String =
        if (index <= 0) TREE_ROOT else messages[index - 1].id

    /** 追加到路径末尾（同时登记进树）。所有消息入列必须走这里，保证树一致。 */
    fun appendMessage(msg: ChatMessage) {
        val parentId = messages.lastOrNull()?.id ?: TREE_ROOT
        childrenOf(parentId).add(msg)
        activeChild[parentId] = msg.id
        messages.add(msg)
    }

    /** 就地更新路径上 index 处的消息（树里同步替换，id 不变）；越界安全（取消竞态时占位可能已移除） */
    fun updateMessageAt(index: Int, msg: ChatMessage) {
        if (index < 0 || index >= messages.size) return
        messages[index] = msg
        children[parentIdAt(index)]?.let { siblings ->
            val i = siblings.indexOfFirst { it.id == msg.id }
            if (i >= 0) siblings[i] = msg
        }
    }

    /** 在 index 处插入新版本（编辑重发）：旧版本及其后续分支全部保留在树上 */
    fun insertVersionAt(index: Int, msg: ChatMessage) {
        val parentId = parentIdAt(index)
        childrenOf(parentId).add(msg)
        activeChild[parentId] = msg.id
        rebuildFrom(index)
    }

    /** 切换到 index 处的另一个版本：旧分支保留，路径按各节点记忆的活跃子消息重建 */
    fun switchVersionAt(index: Int, sibling: ChatMessage) {
        activeChild[parentIdAt(index)] = sibling.id
        rebuildFrom(index)
    }

    /** 从 index 起按 activeChild 重建路径（index 之前保持不动） */
    private fun rebuildFrom(index: Int) {
        while (messages.size > index) messages.removeAt(messages.size - 1)
        var parentId = if (index == 0) TREE_ROOT else messages[index - 1].id
        while (true) {
            val nextId = activeChild[parentId] ?: break
            val node = children[parentId]?.firstOrNull { it.id == nextId } ?: break
            messages.add(node)
            parentId = node.id
        }
    }

    /** 加载持久化数据后重建整条路径 */
    fun loadTree(
        nodes: Map<String, ChatMessage>,
        children: Map<String, List<String>>,
        activeChild: Map<String, String>
    ) {
        children.forEach { (parent, ids) ->
            val list = childrenOf(parent)
            ids.forEach { id -> nodes[id]?.let { list.add(it) } }
        }
        this.activeChild.putAll(activeChild)
        messages.clear() // O(1)：之前 removeAt(0) 循环是 O(n²)
        rebuildFrom(0)
    }

    /** 从路径和树上移除 index 处的消息（取消生成时的空占位），并把活跃子切回兄弟版本 */
    fun removeMessageAt(index: Int) {
        val parentId = parentIdAt(index)
        val msg = messages.getOrNull(index) ?: return
        messages.removeAt(index)
        children[parentId]?.removeAll { it.id == msg.id }
        if (activeChild[parentId] == msg.id) {
            val rest = children[parentId]
            if (rest.isNullOrEmpty()) activeChild.remove(parentId)
            else activeChild[parentId] = rest.last().id
        }
        rebuildFrom(index)
    }

    /** index 处的所有兄弟版本（含当前），>1 时显示 ‹ k/n › 切换器 */
    fun siblingsOf(index: Int): List<ChatMessage> = children[parentIdAt(index)] ?: emptyList()

    /** 内容指纹：用于持久化去抖（标题+消息数+末条长度+树规模），避免流式期间每帧全量比较 */
    fun fingerprint(): String {
        val last = messages.lastOrNull()
        val treeSize = children.values.sumOf { it.size }
        return "$title|${messages.size}|${last?.text?.length ?: 0}|${last?.reasoning?.length ?: 0}|${last?.attachments?.size ?: 0}|$treeSize"
    }
}

/**
 * 精确贴底：把列表滚到内容真正的末尾（末条消息底边 == 视口底边）。
 * scrollToItem 的 offset 语义对“末条比视口矮”的场景不可靠（实测会回填上方 item，
 * 停在离底约一屏处），所以用“测量剩余距离 → 滚动”的收敛式：
 * 末条不可见先顶对齐它，再按实际剩余像素补滚（scrollBy 会被内容边界自然鈄住）。
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.snapToBottom() {
    repeat(3) {
        val info = layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        if (info.totalItemsCount == 0 || last == null) return
        if (last.index != info.totalItemsCount - 1) {
            scrollToItem(info.totalItemsCount - 1)
            return@repeat
        }
        val remaining = last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)
        if (remaining > 2) scroll { dispatchRawDelta(remaining.toFloat()) }
    }
}

private class AgentState(store: ProviderStore, conversationStore: ConversationStore?) {
    var themeChoice by mutableStateOf(ThemeChoice.SYSTEM)
    var selectedTab by mutableStateOf(MainTab.CHAT)

    // 思考等级（持久化）与联网搜索开关（会话级）
    var reasoningEffort by mutableStateOf("")
    var webSearch by mutableStateOf(false)

    // 生成引擎提升到全局状态：切 Tab/主题时对话页可安全离开组合树，
    // 流式协程不依附于某个页面的生命周期（修主题切换卡顿：不再全树重组）
    val engineScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )

    // 每个对话独立的输入草稿 / 附件草稿 / 滚动位置
    val inputDrafts = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val attachmentDrafts = androidx.compose.runtime.mutableStateMapOf<String, SnapshotStateList<ChatAttachment>>()
    val listStates = androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.foundation.lazy.LazyListState>()
    // 外部写入草稿（系统分享文本）时自增，通知 ChatScreen 重新读草稿到输入框
    val draftVersions = androidx.compose.runtime.mutableStateMapOf<String, Int>()

    fun draftInputFor(conversationId: String): String = inputDrafts[conversationId] ?: ""
    fun setDraftInput(conversationId: String, value: String) {
        if (value.isBlank()) inputDrafts.remove(conversationId) else inputDrafts[conversationId] = value
    }
    fun attachmentsFor(conversationId: String): SnapshotStateList<ChatAttachment> =
        attachmentDrafts.getOrPut(conversationId) { mutableStateListOf() }
    fun listStateFor(conversationId: String): androidx.compose.foundation.lazy.LazyListState =
        listStates.getOrPut(conversationId) { androidx.compose.foundation.lazy.LazyListState() }

    // 模型页状态也提升：切 Tab 不重新拉取 /models，不丢搜索词
    var modelsFetched by mutableStateOf<Map<String, List<String>>>(emptyMap())
    var modelsLoading by mutableStateOf<Map<String, Boolean>>(emptyMap())
    var modelsSearchQuery by mutableStateOf("")
    var modelsShowManual by mutableStateOf(false)
    var modelsManualModel by mutableStateOf("")

    // 按会话跟踪生成状态：多个对话可同时流式（A 生成时切到 B，B 照常能发）。
    // activeGenerations 是 Compose 可观察集合，不能再用单个 generatingIn 覆盖不同会话。
    private val activeGenerations = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private val streamJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    fun isGeneratingIn(conversationId: String): Boolean = activeGenerations.containsKey(conversationId)

    fun stopGeneration(conversationId: String) {
        // 仅停止指定会话（唯一调用点就是当前会话的停止钮）：
        // 不再做“任取第一个运行中任务”的兕底——Map 迭代顺序不确定，
        // 多会话并发时会停错对话
        val job = synchronized(streamJobs) { streamJobs.remove(conversationId) }
        activeGenerations.remove(conversationId)
        job?.cancel()
    }

    /**
     * 生成引擎：构建历史（每条用户消息独立加载附件）→ SSE 流式 → 帧驱动打字机。
     * 运行在 engineScope，与页面组合无关；每个对话独立一条协程，互不阻塞。
     */
    fun startGeneration(context: android.content.Context, conversation: AgentConversation) {
        // 同一对话不叠加生成；不同对话各自开一条流（并发）
        if (activeGenerations.containsKey(conversation.id)) return
        val provider = currentProvider ?: return
        val messages = conversation.messages
        val convId = conversation.id
        // 先登记再启动协程，防止快速双击/切回同一会话时追加第二条用户消息。
        activeGenerations[convId] = true
        val job = engineScope.launch {
            val assistantIndex = messages.size
            // 占位气泡的索引：若插入了「不支持的附件」提示，占位会被推后一位
            var placeholderIndex = assistantIndex
            try {
                // 逐条解析历史：每条用户消息独立加载自己的附件（图片→视觉消息、音频→input_audio、
                // 文本→内联）。之前只给最后一条用户消息附图，多轮发图时早前的图片会丢失。
                val history = mutableListOf<ChatRequestMessage>()
                var unreadable: String? = null
                for (i in 0 until assistantIndex) {
                    val m = messages[i]
                    if (m.isError) continue
                    if (m.text.isBlank() && m.attachments.isEmpty()) continue
                    if (m.fromUser && m.attachments.isNotEmpty()) {
                        val images = mutableListOf<String>()
                        var audioUrl: String? = null
                        var text = m.text
                        m.attachments.forEach { attachment ->
                            when (val loaded = AttachmentLoader.loadContent(context, attachment)) {
                                null -> if (unreadable == null) unreadable = attachment.name
                                else -> when (loaded.first) {
                                    "image" -> images.add(loaded.second)
                                    "audio" -> if (audioUrl == null) audioUrl = "x-audio:${loaded.second}"
                                    // PDF 原生 base64 上传（x-pdf: 前缀），模型层不支持时降级链处理
                                    "pdf" -> images.add("x-pdf:${loaded.second}")
                                    "text" -> text += (if (text.isBlank()) "" else "\n") + loaded.second
                                }
                            }
                        }
                        // 只发音频不带文字时部分模型会无视音频直接空谈：
                        // 自动补一句简短指令，明确告知「这是一段音频」
                        if (audioUrl != null && text.isBlank()) {
                            text = "（这段对话附带了一段音频，请先听取它的内容再回答）"
                        }
                        history.add(ChatRequestMessage("user", text, images + listOfNotNull(audioUrl)))
                    } else {
                        history.add(ChatRequestMessage(if (m.fromUser) "user" else "assistant", m.text))
                    }
                }
                unreadable?.let {
                    // 软件能收的格式挑选时不拦（见 loadContent），能到这里只剩文件失效
                    conversation.appendMessage(ChatMessage(text = "无法读取「$it」的内容，文件可能已被移动或删除", fromUser = false, isError = true))
                }
                conversation.appendMessage(ChatMessage(text = "", fromUser = false))
                placeholderIndex = messages.size - 1

                val target = StringBuilder()
                var shown = 0
                // 思考过程单独缓冲：qwen3/gpt-6 等会先流式吐 reasoning_content 再吐正文，
                // 存进 ChatMessage.reasoning，由气泡渲染成可折叠区块（正文到达后自动收起）
                val reasoningBuf = StringBuilder()
                var reasoningShown = 0
                val typewriter = launch {
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        // 自适应节奏：中转站常把大段内容塞进少数 chunk（几十上百字/chunk），
                        // 固定 28%/帧永远追不上 → 观感「卡一下全屏出」。
                        // 落后量大时降帧率（16→32→48→64ms）、步长加大（28%→45%），
                        // 少量落后时保持原来的 16ms/28% 手感。
                        val lag = (target.length - shown) + (reasoningBuf.length - reasoningShown)
                        kotlinx.coroutines.delay(
                            when {
                                lag > 800 -> 64L
                                lag > 300 -> 48L
                                lag > 120 -> 32L
                                else -> 16L
                            }
                        )
                        val full = target.length
                        val rFull = reasoningBuf.length
                        if (shown < full || reasoningShown < rFull) {
                            // 步长随落后量自适应（至少 1 字）：温和渐进 vs 快速追赶
                            val big = lag > 300
                            val step = maxOf(1, ((full - shown) * (if (big) 0.45f else 0.28f)).toInt())
                            shown = minOf(full, shown + step)
                            val rStep = maxOf(1, ((rFull - reasoningShown) * (if (big) 0.45f else 0.28f)).toInt())
                            reasoningShown = minOf(rFull, reasoningShown + rStep)
                            conversation.updateMessageAt(
                                placeholderIndex,
                                messages[placeholderIndex].copy(
                                    text = target.substring(0, shown),
                                    reasoning = reasoningBuf.substring(0, reasoningShown)
                                )
                            )
                        }
                    }
                }
                val collectDeltas: (String, String) -> Unit = { delta, reasoningDelta ->
                    if (reasoningDelta.isNotEmpty()) reasoningBuf.append(reasoningDelta)
                    if (delta.isNotEmpty()) target.append(delta)
                }
                // 每次都带参数发送：被拒时按参数逐个降级重试并各自 Toast 提示。
                // （曾做过“记住被拒参数”的缓存，但中转站报错文案千奇百怪难维护，已弃用）
                val effortParam: String? = reasoningEffort.ifBlank { null }
                val webParam: Boolean = webSearch
                // 中转站的参数拒绝报文：英文关键词或中文（可能 GBK 乱码）
                fun isParamError(msg: String) = msg.contains("HTTP 4") &&
                    (msg.contains("reasoning") || msg.contains("effort") || msg.contains("tool") ||
                        msg.contains("web_search") || msg.contains("thinking") || msg.contains("unsupported") ||
                        msg.contains("not support") || msg.contains("不支持"))
                // 模型不收某类内容：识别为对应内容的关键词才算，避免误伤普通参数报错。
                // 消息里明确告知「模型不支持」，与「软件不支持」区分开
                fun isModelError(msg: String, audio: Boolean = false, pdf: Boolean = false): Boolean = msg.contains("HTTP 4") && (
                    when {
                        audio -> msg.contains("input_audio") || msg.contains("audio") || msg.contains("音频")
                        pdf -> msg.contains("pdf") || msg.contains("document") || msg.contains("文件")
                        else -> msg.contains("image") || msg.contains("image_url") || msg.contains("vision") ||
                            msg.contains("visual") || msg.contains("图片") || msg.contains("图像")
                    }
                    )
                // 音频/图片降级版历史：分别去掉对应附件后重发（其余内容保留）。
                // lazy：无对应附件的请求永不构建
                val noAudioHistory by lazy {
                    history.map { m ->
                        ChatRequestMessage(m.role, m.text, m.imageDataUrls.filterNot { it.startsWith("x-audio:") })
                    }
                }
                val historyHasAudio = history.any { m -> m.imageDataUrls.any { it.startsWith("x-audio:") } }
                val noImageHistory by lazy {
                    history.map { m ->
                        ChatRequestMessage(m.role, m.text, m.imageDataUrls.filterNot { !it.startsWith("x-audio:") })
                    }
                }
                val historyHasImage = history.any { m -> m.imageDataUrls.any { !it.startsWith("x-audio:") && !it.startsWith("x-pdf:") } }
                val historyHasPdf = history.any { m -> m.imageDataUrls.any { it.startsWith("x-pdf:") } }
                val noPdfHistory by lazy {
                    history.map { m ->
                        ChatRequestMessage(m.role, m.text, m.imageDataUrls.filterNot { it.startsWith("x-pdf:") })
                    }
                }
                // 附件占位文字（去附件后历史可能变空消息，替换为可读说明）
                val fallbackNote: (List<ChatRequestMessage>) -> List<ChatRequestMessage> = { hs ->
                    hs.map { m ->
                        if (m.role == "user" && m.text.isBlank() && m.imageDataUrls.isEmpty())
                            ChatRequestMessage("user", "（此条消息附带了本模型不支持的附件，已忽略附件内容）")
                        else m
                    }
                }

                // 递归降级：音频 → 图片 → 思考等级 → 联网搜索，逐层剔除被模型拒绝的部分。
                // 错误分类：isModelError(msg) = 模型不收这类内容（格式本身没问题，软件能处理）；
                // 其余 HTTP 4xx = 参数不支持。每次降级 Toast 明确告知用户原因与处理方式
                suspend fun send(h: List<ChatRequestMessage>, effort: String?, web: Boolean, audioDropped: Boolean, imageDropped: Boolean, pdfDropped: Boolean = false) {
                    try {
                        ApiClient.streamChat(provider, h, reasoningEffort = effort, webSearch = web, onDelta = collectDeltas)
                    } catch (e: Exception) {
                        val msg = e.message ?: ""
                        when {
                            historyHasAudio && !audioDropped && isModelError(msg, audio = true) -> {
                                Toast.makeText(context, "当前模型不支持音频输入，已忽略音频继续回答", Toast.LENGTH_LONG).show()
                                send(fallbackNote(noAudioHistory), effort, web, audioDropped = true, imageDropped)
                            }
                            historyHasImage && !imageDropped && isModelError(msg, audio = false) -> {
                                Toast.makeText(context, "当前模型不支持图片输入，已忽略图片继续回答（如需看图请换支持视觉的模型）", Toast.LENGTH_LONG).show()
                                send(fallbackNote(noImageHistory), effort, web, audioDropped, imageDropped = true)
                            }
                            historyHasPdf && !pdfDropped && isModelError(msg, pdf = true) -> {
                                Toast.makeText(context, "当前模型不支持 PDF 输入，请改用图片发送或换支持的模型", Toast.LENGTH_LONG).show()
                                send(fallbackNote(noPdfHistory), effort, web, audioDropped, imageDropped, pdfDropped = true)
                            }
                            effort != null && isParamError(msg) -> {
                                Toast.makeText(context, "当前模型不支持所选思考等级，已忽略该设置继续回答", Toast.LENGTH_SHORT).show()
                                send(h, null, web, audioDropped, imageDropped)
                            }
                            web && isParamError(msg) -> {
                                Toast.makeText(context, "当前模型不支持联网搜索，已忽略该设置继续回答", Toast.LENGTH_SHORT).show()
                                // 保留 effort：联网被拒不代表思考等级被拒，之前误传 null 把思考等级也静默丢掉
                                send(h, effort, false, audioDropped, imageDropped)
                            }
                            else -> throw e
                        }
                    }
                }
                try {
                    send(history, effortParam, webParam, audioDropped = false, imageDropped = false)
                    // 流正常结束：先等打字机把残余内容追平再取消，避免「结尾几百字
                    // 一帧涌出」的跳动；带上最后一段延迟的余量，多数情况一轮即满足
                    while (shown < target.length || reasoningShown < reasoningBuf.length) {
                        kotlinx.coroutines.delay(16)
                    }
                } finally {
                    typewriter.cancel()
                }
                // 收尾：确保全部显示（正常路径此处与打字机已写内容一致，无视觉变化；
                // 仅兕底异常时序；取消竞态时占位可能已被移除，需判空）
                messages.getOrNull(placeholderIndex)?.let {
                    conversation.updateMessageAt(placeholderIndex, it.copy(text = target.toString(), reasoning = reasoningBuf.toString()))
                }
                if (messages.getOrNull(placeholderIndex)?.text.isNullOrBlank() &&
                    messages.getOrNull(placeholderIndex)?.reasoning.isNullOrBlank() &&
                    placeholderIndex < messages.size
                ) {
                    conversation.updateMessageAt(placeholderIndex, ChatMessage(text = "（模型没有返回内容）", fromUser = false, isError = true))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户点了停止：保留已生成的部分；完全没内容则移除占位并切回旧分支
                if (messages.getOrNull(placeholderIndex)?.text.isNullOrBlank() && placeholderIndex < messages.size) {
                    conversation.removeMessageAt(placeholderIndex)
                }
            } catch (e: Exception) {
                val msg = e.message?.take(300) ?: "网络错误"
                messages.getOrNull(placeholderIndex)?.let {
                    conversation.updateMessageAt(placeholderIndex, it.copy(
                        text = "请求失败：$msg",
                        isError = true
                    ))
                }
            } finally {
                streamJobs.remove(convId)
                activeGenerations.remove(convId)
                // 首轮问答完成后自动命名（ChatGPT/Gemini 式），失败回退为首条消息截断
                maybeAutoTitle(conversation)
            }
        }
        streamJobs[convId] = job
    }

    /**
     * 自动命名：只在标题还是「新对话」时执行（手动改名后不覆盖），
     * 用当前模型生成 ≤14 字标题；调用失败回退为首条用户消息前 28 字。
     */
    private fun maybeAutoTitle(conversation: AgentConversation) {
        if (conversation.title != "新对话") return
        val firstUser = conversation.messages.firstOrNull { it.fromUser && !it.isError } ?: return
        if (firstUser.text.isBlank() && firstUser.attachments.isEmpty()) return
        val provider = currentProvider ?: return
        val firstAssistant = conversation.messages.firstOrNull { !it.fromUser && !it.isError }?.text ?: ""
        engineScope.launch {
            val generated = ApiClient.generateTitle(provider, firstUser.text, firstAssistant)
            conversation.title = generated
                ?: firstUser.text.take(28).ifBlank {
                    // 纯附件消息：用附件名生成标题（拍照附件用友好名）
                    firstUser.attachments.firstOrNull()?.let { attachmentDisplayName(it.name).take(20) } ?: "新对话"
                }
        }
    }

    // 服务商（ccswitch 式）：可添加多个，一键切换。
    val providers = mutableStateListOf<Provider>()
    var currentProviderId by mutableStateOf<String?>(null)
    val currentProvider: Provider?
        get() = providers.firstOrNull { it.id == currentProviderId }

    val selectedModel: String
        get() = currentProvider?.model?.takeIf { it.isNotBlank() } ?: "未配置"

    private val store: ProviderStore = store

    init {
        providers.addAll(store.loadProviders())
        currentProviderId = store.loadCurrentProviderId()?.takeIf { saved ->
            providers.any { it.id == saved }
        }
        reasoningEffort = store.loadReasoningEffort()
    }

    fun selectProvider(provider: Provider) {
        currentProviderId = provider.id
        store.saveCurrentProviderId(provider.id)
    }

    fun upsertProvider(provider: Provider) {
        val isNew = providers.none { it.id == provider.id }
        if (isNew) providers.add(provider)
        else providers[providers.indexOfFirst { it.id == provider.id }] = provider
        store.saveProviders(providers)
        // 仅在新增且当前未选中时自动切换；编辑已有服务商（包括非当前的那个）
        // 不改变当前选中——之前 index >= 0 时误把「编辑非当前服务商」变成了切换选中
        if (isNew && (currentProviderId == null || providers.size == 1)) selectProvider(provider)
    }

    fun removeProvider(provider: Provider) {
        providers.removeAll { it.id == provider.id }
        store.saveProviders(providers)
        if (currentProviderId == provider.id) {
            currentProviderId = providers.firstOrNull()?.id
            currentProviderId?.let { store.saveCurrentProviderId(it) }
        }
    }

    fun updateModel(model: String) {
        val provider = currentProvider ?: return
        providers[providers.indexOfFirst { it.id == provider.id }] =
            provider.copy(model = model)
        store.saveProviders(providers)
    }

    val conversations = mutableStateListOf(
        AgentConversation("welcome", "新对话")
    )
    var currentConversationId by mutableStateOf("welcome")
    val currentConversation: AgentConversation
        get() = conversations.firstOrNull { it.id == currentConversationId } ?: conversations.first()
    val currentTitle: String
        get() = currentConversation.title

    // 对话持久化：启动时加载磁盘，之后列表/消息变更时防抖写回。
    // 主题切换等配置变更不再重建 Activity（manifest 已声明 configChanges），
    // 但进程回收/用户杀进程后对话仍在。
    private val conversationStore = conversationStore

    init {
        conversationStore?.load()?.let { snapshots ->
            if (snapshots.isNotEmpty()) {
                conversations.clear()
                snapshots.forEach { snap ->
                    val conv = AgentConversation(snap.id, snap.title)
                    val nodes = LinkedHashMap<String, ChatMessage>()
                    snap.tree.nodes.forEach { (id, m) ->
                        nodes[id] = ChatMessage(
                            id = id,
                            text = m.text,
                            fromUser = m.fromUser,
                            isError = m.isError,
                            reasoning = m.reasoning,
                            attachments = m.attachments.map { ChatAttachment(android.net.Uri.parse(it.first), it.second) }
                        )
                    }
                    conv.loadTree(nodes, snap.tree.children, snap.tree.activeChild)
                    conversations.add(conv)
                }
                currentConversationId = conversations.first().id
            }
        }
        // 快照写观察 + 轮询防抖（兼顾覆盖面与流畅度）：
        // 全局写观察回调会在“每一次快照写”时触发——包括流式打字机逐帧更新、
        // TypingDots/StreamingCursor 无限动画逐帧写值、抽屉 Animatable 逐帧动画、
        // 列表滚动。回调里绝不能做重活（旧实现在这里每帧构建全量指纹字符串，
        // 动画/流式期间主线程每帧分配大字符串 → 掉帧），只置一个脏标记（零分配）。
        // 真正的指纹比对 + 序列化 + 写盘由 init 里启动的单一轮询协程完成。
        if (conversationStore != null) {
            androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver { _ ->
                persistDirty = true
            }
            engineScope.launch {
                while (isActive) {
                    delay(500)
                    if (!persistDirty) continue
                    persistDirty = false
                    persistNow()
                }
            }
        }
    }
    private var lastPersistFingerprint: String = ""

    /** 脏标记：写观察回调与显式调用点都只置位，不触发任何分配 */
    @Volatile private var persistDirty = false

    /** 显式调用点（新建/删除/改名等）只需置脏，轮询协程统一处理 */
    fun schedulePersist() { persistDirty = true }

    /** 指纹比对 + 防抖写盘：内容没变的周期直接跳过，避免流式输出每帧写 */
    private suspend fun persistNow() {
        if (conversationStore == null) return
        val key = conversations.joinToString("\u0001") { it.id + "\u0002" + it.fingerprint() }
        if (key == lastPersistFingerprint) return
        lastPersistFingerprint = key
        // JSON 序列化 + 写盘放 IO 线程：之前在主线程序列化全部对话，
        // 长对话时每 500ms 一次主线程卡顿（流式收尾时最明显）
        withContext(Dispatchers.IO) {
            conversationStore.save(
            conversations.map { c ->
                // 平铺化分叉树：所有登记过的节点 + 父子关系 + 每个节点的活跃子消息
                val nodes = LinkedHashMap<String, StoredMessage>()
                c.children.forEach { (_, list) ->
                    list.forEach { m ->
                        nodes[m.id] = StoredMessage(
                            id = m.id,
                            text = m.text,
                            fromUser = m.fromUser,
                            isError = m.isError,
                            reasoning = m.reasoning,
                            attachments = m.attachments.map { it.uri.toString() to it.name }
                        )
                    }
                }
                AgentConversationData(
                    id = c.id,
                    title = c.title,
                    nodes = nodes,
                    children = c.children.filterValues { it.isNotEmpty() }.mapValues { (_, list) -> list.map { it.id } },
                    activeChild = c.activeChild.toMap()
                )
            }
            )
        }
    }

    fun newConversation() {
        val conversation = AgentConversation(java.util.UUID.randomUUID().toString(), "新对话")
        conversations.add(0, conversation)
        currentConversationId = conversation.id
        selectedTab = MainTab.CHAT
        schedulePersist()
    }

    fun selectConversation(conversation: AgentConversation) {
        currentConversationId = conversation.id
        selectedTab = MainTab.CHAT
    }

    /** 点会话先关抽屉、后切内容（拆两步）：避免「抽屉退出+内容瞬变」叠在一起。
     * 先记录目标会话，抽屉关到几乎看不见时才真正切换。 */
    var pendingConversationId by mutableStateOf<String?>(null)
    fun deferSelectConversation(conversation: AgentConversation, onCloseDrawer: () -> Unit) {
        if (conversation.id == currentConversationId) { onCloseDrawer(); return }
        pendingConversationId = conversation.id
        onCloseDrawer()
    }
    /** 抽屉关闭后调用：真正应用切换 */
    fun applyPendingConversation() {
        val id = pendingConversationId ?: return
        val conv = conversations.firstOrNull { it.id == id } ?: run { pendingConversationId = null; return }
        pendingConversationId = null
        selectConversation(conv)
    }
    /** 新建对话也走「先关抽屉后切内容」：与选会话体验一致 */
    var pendingNewConversation by mutableStateOf(false)
    fun deferNewConversation(onCloseDrawer: () -> Unit) {
        pendingNewConversation = true
        onCloseDrawer()
    }
    fun applyPendingNewConversation() {
        if (!pendingNewConversation) return
        pendingNewConversation = false
        newConversation()
    }

    fun deleteConversation(conversation: AgentConversation) {
        // 若该会话还在流式生成，先停止：否则协程继续往已删除的会话对象里追加消息
        stopGeneration(conversation.id)
        conversations.removeAll { it.id == conversation.id }
        // 清理按会话缓存的草稿/附件/滚动位置，避免长会话列表累积内存
        inputDrafts.remove(conversation.id)
        attachmentDrafts.remove(conversation.id)
        listStates.remove(conversation.id)
        draftVersions.remove(conversation.id)
        if (currentConversationId == conversation.id) {
            currentConversationId = conversations.firstOrNull()?.id
                ?: newConversationInternal().id
        }
        schedulePersist()
    }

    fun renameConversation(conversation: AgentConversation, newTitle: String) {
        conversation.title = newTitle.trim().ifEmpty { "新对话" }
        schedulePersist()
    }

    fun changeReasoningEffort(v: String) {
        reasoningEffort = v
        store.saveReasoningEffort(v)
    }

    private fun newConversationInternal(): AgentConversation {
        val conversation = AgentConversation(java.util.UUID.randomUUID().toString(), "新对话")
        // 与 newConversation 一致插到列表头部（抽屉里新对话总在最上面）
        conversations.add(0, conversation)
        return conversation
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var agentState: AgentState

    override fun onCreate(savedInstanceState: Bundle?) {
        // 长按选词结束后 foundation 会异步启动 TextClassifier（"智能选词"）回写选区，
        // 若用户此刻已清除选择，回写会复活已清空的选区并使浮动工具条闪现（只有"全选"）。
        // 关闭该功能以根除此问题；代价是长按选中的是单词本身而非系统智能扩展的词组。
        @OptIn(ExperimentalFoundationApi::class)
        run {
            ComposeFoundationFlags.isSmartSelectionEnabled = false
            // 新上下文菜单管线中，浮动工具条由 SnapshotStateObserver 观察选区状态并在变化时
            // actionMode.invalidate()：清除选区（selection=null）会触发菜单重建——Copy 因禁用
            // 被移除、SelectAll 因 subselections 为空仍保留——与排队的 finish() 竞态，
            // 产生“只有全选”的浮条闪现。切回旧管线（showMenu/hide 直接同步控制，无 invalidate）根除。
            ComposeFoundationFlags.isNewContextMenuEnabled = false
        }
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        agentState = AgentState(ProviderStore(this), ConversationStore(this))
        enableEdgeToEdge()
        setContent { AgentApp(agentState) }
        handleSharedIntent(intent)
        splashScreen.setOnExitAnimationListener { provider ->
            // 内容从轻微下沉的位置回弹到原位，遮罩淡出（参考 Mihon，修复页面向下偏移的问题）。
            // 整个回调包 runCatching：MIUI 可能不走标准启动图标动画路径，此时
            // splashscreen 1.0.1 的 ViewImpl31.iconView 是 `platformView.iconView!!`，
            // 直接抛 NPE 连累主进程闪退（旧包 v0.13 实际发生过）；动画失败也不能挡住进入应用。
            var fadeStarted = false
            runCatching {
                val content = findViewById<android.view.View>(android.R.id.content)
                content.translationY = 16f * resources.displayMetrics.density
                content.animate()
                    .translationY(0f)
                    .setDuration(200L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
                provider.iconView.translationY = 0f
                provider.view.animate()
                    .alpha(0f)
                    .setDuration(200L)
                    .withEndAction { runCatching { provider.remove() } }
                    .start()
                fadeStarted = true
            }
            // 动画没起来（iconView 为 null 等）：启动画面视图必须移除，否则会一直盖在内容上
            if (!fadeStarted) runCatching { provider.remove() }
        }
    }
    /** 应用在后台时再收到分享（singleTask）：交给现有会话处理 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE ->
                agentState.consumeSharedIntent(this, intent)
        }
    }
}

private val DrawerWidth = 320.dp
private val TabBarHeight = 58.dp
private const val FlingThreshold = 400f

/** 图片附件后缀判断（预编译正则：此前每次组合都重新编译一遍） */
private val IMAGE_NAME_REGEX = Regex(".*\\.(png|jpg|jpeg|webp|gif|bmp)$")

/** 音频格式后缀集合（展示「音频 · ext」标签用） */
private val AUDIO_EXT_SET = setOf("MP3", "WAV", "M4A", "AAC", "OGG", "FLAC", "MPEG", "X-WAV")

/** 附件格式标签：优先文件扩展名；扩展名缺失/过长时回退到 MIME 子类型（如 pdf） */
private fun attachmentExtLabel(context: android.content.Context, attachment: ChatAttachment): String {
    val ext = attachment.name.substringAfterLast('.', "").uppercase()
    if (ext.isNotBlank() && ext.length <= 5) return ext
    return runCatching {
        context.contentResolver.getType(attachment.uri)
            ?.substringAfterLast('/')?.uppercase()
    }.getOrNull().takeIf { !it.isNullOrBlank() && it != "PLAIN" && it != "OCTET-STREAM" } ?: "文件"
}

/** 展示名：camera 时间戳命名转成友好文案，其余截短到 18 字 */
private fun attachmentDisplayName(name: String): String {
    if (name.startsWith("camera_") && name.endsWith(".jpg")) {
        val stamp = name.removePrefix("camera_").removeSuffix(".jpg").toLongOrNull()
        if (stamp != null) {
            return "拍照 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(stamp))
        }
        return "拍照"
    }
    return name.takeLast(18)
}
private fun isImageAttachment(context: android.content.Context, attachment: ChatAttachment): Boolean {
    val byMime = runCatching {
        context.contentResolver.getType(attachment.uri)?.startsWith("image/") == true
    }.getOrDefault(false)
    return byMime || IMAGE_NAME_REGEX.matches(attachment.name.lowercase())
}

/** 共享附件的显示名：优先查询 DISPLAY_NAME（分享 URI 的 lastPathSegment 往往是数字 ID） */
private fun sharedAttachmentName(context: android.content.Context, uri: android.net.Uri): String {
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
    }
    // 兑底：uri 路径形如 primary:Download/xxx.pdf 或 content/…/1，
    // 取最后一段真实文件名，避免「model 看到的还是 uri 路径」
    val fallback = uri.lastPathSegment ?: return "附件"
    return fallback.substringAfterLast('/').substringAfterLast(':').ifBlank { "附件" }
}

/**
 * 接收系统分享：文本 → 填入当前会话输入框（可继续编辑）；
 * 文件/图片 → 加入当前会话附件列表。多份分享逐个追加。
 */
private fun AgentState.consumeSharedIntent(context: android.content.Context, intent: Intent) {
    when (intent.action) {
        Intent.ACTION_SEND -> {
            val uri: android.net.Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            when {
                uri != null -> addSharedAttachment(context, uri)
                // 纯文本分享：追加到输入框草稿（保留已输入内容）并通知输入框重读
                else -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { text ->
                    val merged = (draftInputFor(currentConversationId) + "\n" + text).trim()
                    setDraftInput(currentConversationId, merged)
                    draftVersions[currentConversationId] = (draftVersions[currentConversationId] ?: 0) + 1
                }
            }
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            val uris: ArrayList<android.net.Uri>? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            uris?.forEach { addSharedAttachment(context, it) }
        }
    }
}

private fun AgentState.addSharedAttachment(context: android.content.Context, uri: android.net.Uri) {
    // 第一层筛选（软件级）：分享来的软件不支持的类型直接报错丢弃，不上传浪费时间
    val name = sharedAttachmentName(context, uri)
    val reason = AttachmentLoader.unsupportedReason(context, uri, name)
    if (reason != null) {
        Toast.makeText(context, "「$name」$reason", Toast.LENGTH_LONG).show()
        return
    }
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // 分享来的临时授权不支持持久化，本次会话内仍可读取
    }
    attachmentsFor(currentConversationId).add(ChatAttachment(uri, name))
}

/**
 * 底部让位：键盘弹出时贴住键盘上沿，关闭时为底部 Tab + 导航栏留位。
 * 在布局阶段读取 IME insets（不触发重组），键盘动画期间不会卡顿。
 */
@Composable
private fun Modifier.bottomInputClearance(imeSettling: () -> Boolean = { false }): Modifier {
    val ime = WindowInsets.ime
    val navbar = WindowInsets.navigationBars
    return layout { measurable, constraints ->
        val imePx = ime.getBottom(this)
        val base = navbar.getBottom(this) + TabBarHeight.roundToPx()
        // imeSettling：抽屉打开期间（键盘同步收起）把输入栏直接锁到收起后的最终
        // 位置 —— 消息列表只重排一次，之后不再随逐帧 IME inset 重排。
        // 锁值 = base（与 ime=0 时的公式值相同），锁定解除时无跳变。
        val clearance = if (imeSettling() && imePx > 0) base else maxOf(imePx, base)
        val placeable = measurable.measure(
            constraints.copy(minHeight = 0, maxHeight = (constraints.maxHeight - clearance).coerceAtLeast(0))
        )
        layout(placeable.width, placeable.height + clearance) {
            placeable.place(0, 0)
        }
    }
}

/**
 * 抽屉拖拽手势，对标 EhViewer 的 DrawerLayout：
 * - Initial 阶段抢先处理，一旦判定为横向主导手势就立即锁定拦截；
 * - 纵向先主导则本次手势彻底放弃，不干扰列表滚动；
 * - 阈值仅为系统 touchSlop，非常灵敏。
 * 注意：进度面板本身在跟随手指移动，不能用控件本地坐标算速度，
 * 这里用净位移 + 时间戳自己估算速度。
 */
private fun Modifier.drawerDragGesture(
    progress: Animatable<Float, *>,
    widthPx: Float,
    scope: CoroutineScope,
    onDragStart: () -> Unit = {}
): Modifier = this.pointerInput(progress, widthPx, onDragStart) {
    val settleSpec = spring<Float>(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)

    fun settle(velocityPx: Float, accumulated: Float) {
        val deadZone = widthPx * 0.06f
        val target = when {
            velocityPx > FlingThreshold -> 1f
            velocityPx < -FlingThreshold -> 0f
            accumulated > deadZone -> 1f
            accumulated < -deadZone -> 0f
            progress.value >= 0.5f -> 1f
            else -> 0f
        }
        scope.launch {
            progress.animateTo(target, settleSpec, initialVelocity = velocityPx / widthPx)
        }
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val trackedPointer = down.id
        val downPosition = down.position
        var canIntercept = true   // 纵向先占主导则放弃
        var tracking = false      // 已锁定为横向拖拽
        var childTookOver = false // 子控件（横向滚动/附件长按拖拽等）已接管
        var crossed = false       // 已越过锁定门槛，但还差一个事件的让位窗口
        var accumulated = 0f
        var lastTime = 0L
        var lastAccum = 0f
        var recentVelocity = 0f  // px/s，指数平滑
        val slop = viewConfiguration.touchSlop
        // 抽屉锁定门槛略高于子控件的 touchSlop：横向滚动（附件条）等子控件
        // 会在 Main pass 消费事件，抽屉在下一事件的 Initial pass 事后让位；
        // 空白处拖动无人消费，抽屉照常整屏跟手打开。
        // 能横向滚动的代码块/表格/附件条仍会赢（有真实滚动可做）；
        // 不溢出的则由代码块/表格侧不加 horizontalScroll，不再消费 → 抽屉可接管
        val lockSlop = slop * 1.25f
        var prevChanges: List<androidx.compose.ui.input.pointer.PointerInputChange> = emptyList()

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == trackedPointer } ?: break
            // 上一事件已被子控件在 Main/Final pass 消费（横向滚动、长按拖动等）：让位，不再争夺
            if (!tracking && prevChanges.any { it.isConsumed }) childTookOver = true
            if (childTookOver) {
                if (event.changes.all { !it.pressed }) break else continue
            }
            if (!tracking) {
                val dx = abs(change.position.x - downPosition.x)
                val dy = abs(change.position.y - downPosition.y)
                // 抽屉已打开时，关闭手势门槛放宽（对标 EhViewer：横向位移过 slop 即接管，
                // 允许较大纵向分量，只有明显纵向主导才放弃）；抽屉关闭时保持严格横向判定。
                val closing = progress.value > 0.01f
                when {
                    dy > slop && dy > dx && (dy > dx * 2 || !closing) -> canIntercept = false
                }
                if (canIntercept && dx > lockSlop && (dx > dy || (closing && dy <= dx * 2))) {
                    if (crossed) {
                        // 越过门槛后的第二个事件仍无子控件消费 → 锁定抽屉拖拽。
                        // 延迟一个事件是关键：快速一笔甩过两个 slop 时（同一事件内），
                        // 横向滚动等子控件在本事件 Main pass 消费后，下一事件才让抽屉放弃
                        tracking = true
                        // 优化：键盘打开时拖出抽屉 → 拖拽锁定即收起键盘（与汉堡键一致），
                        // 收键盘引发视口变化不影响抽屉跟手
                        onDragStart()
                        accumulated = change.position.x - downPosition.x
                        lastTime = change.uptimeMillis
                        lastAccum = accumulated
                    } else {
                        crossed = true
                    }
                }
            }

            if (tracking) {
                val delta = if (change.isConsumed) 0f else change.positionChange().x
                change.consume()
                accumulated += delta
                val now = change.uptimeMillis
                val dt = (now - lastTime) / 1000f
                if (dt > 0.002f) {
                    val instant = (accumulated - lastAccum) / dt
                    recentVelocity = if (recentVelocity == 0f) instant
                    else recentVelocity * 0.7f + instant * 0.3f
                    lastTime = now
                    lastAccum = accumulated
                }
                scope.launch {
                    progress.snapTo((progress.value + delta / widthPx).coerceIn(0f, 1f))
                }
            }

            if (event.changes.all { !it.pressed }) {
                if (tracking) settle(recentVelocity, accumulated)
                break
            }
            // 记录本事件，下一轮检查是否被子控件消费（横向滚动等）
            prevChanges = event.changes
        }
    }
}

@Composable
private fun AgentApp(state: AgentState) {
    val darkTheme = when (state.themeChoice) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }

    AgentTheme(darkTheme = darkTheme, dynamicColor = false) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val view = androidx.compose.ui.platform.LocalView.current
        val drawerWidthPx = with(density) { DrawerWidth.toPx() }
        val drawerProgress = remember { Animatable(0f) }

        // 键盘打开时唤起抽屉 → 键盘同步收起、抽屉立即弹出（两者动作叠加，
        // 但各自动画都流畅 —— 成熟 IM 应用的标准做法，不做人为延迟：
        // 延迟会让点击到抽屉出现有明显空档，体感「卡」）。
        // 流畅度关键：
        // 1) imeOnly=true：只收 IME 不 clearFocus，避免输入框焦点闪断重挂；
        // 2) 抽屉打开全程把 bottomInputClearance 的 IME 分量锁在收起后高度：
        //    输入栏在第 0 帧就落到最终位置，之后不再随 IME 逐帧 inset 跳动，
        //    消息列表只重排一次（IME inset 逐帧变化会带着 LazyColumn 重排）；
        //    imeSettling 在抽屉动画结束时才解除，与 IME 退场动画时长对齐。
        // 3) 拖拽跟手（onDragStart）路径同样生效：手指已在拉抽屉。
        var imeSettling by remember { mutableStateOf(false) }
        fun beginImeSettling() {
            // 锁 450ms：覆盖 IME 退场动画（~300ms，MIUI 略长）；锁值与收起后高度
            // 相同，到时无缝过渡（见 bottomInputClearance）
            imeSettling = true
            scope.launch {
                delay(450)
                imeSettling = false
            }
        }
        val openDrawer: () -> Unit = {
            val keyboardWasOpen = hideKeyboard(view, imeOnly = true)
            if (keyboardWasOpen) {
                // 先置锁（同帧生效，第 0 帧就是最终布局），再开抽屉
                beginImeSettling()
            }
            scope.launch {
                drawerProgress.animateTo(1f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium))
            }
        }
        // 关抽屉：动画结束时若挂着待切换会话，则此刻才真正切内容。
        // 效果：视线先看到抽屉滑走（一件事），再看到内容就位（第二件事），
        // 而不是两个变化叠在同一瞬间。
        val closeDrawer: () -> Unit = {
            scope.launch {
                drawerProgress.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium))
                state.applyPendingConversation()
                state.applyPendingNewConversation()
            }
        }

        // 抽屉可见性用 derivedStateOf 收敛：若直接在组合里读 drawerProgress.value，
        // 动画/拖拽的每一帧都会重组整屏（遮罩/面板/主内容全量重新求值）。
        // 收敛后只有显示/隐藏翻转那一刻才重组，逐帧工作只剩 graphicsLayer/offset lambda。
        val drawerVisible by remember { androidx.compose.runtime.derivedStateOf { drawerProgress.value > 0.001f } }
        val backClosesDrawer by remember { androidx.compose.runtime.derivedStateOf { drawerProgress.value > 0.01f } }
        BackHandler(enabled = backClosesDrawer, onBack = closeDrawer)

        Box(Modifier.fillMaxSize()) {
            // 主内容：整屏都可向右拖动来跟手打开抽屉（EhViewer 式高灵敏度拦截）。
            Box(
                Modifier
                    .fillMaxSize()
                    .drawerDragGesture(drawerProgress, drawerWidthPx, scope) {
                        // 左滑拖出抽屉锁定时收起键盘（与汉堡键行为一致）；
                        // 同时锁 IME 布局：拖拽跟手期间列表静止，不被逐帧 inset 重排拖慢
                        if (hideKeyboard(view, imeOnly = true)) beginImeSettling()
                    }
            ) {
                AgentMainContent(state = state, onOpenDrawer = openDrawer, imeSettling = { imeSettling })
            }

            // 遮罩：轻量半透明（Mihon 风格），点击或拖动均可关闭。
            if (drawerVisible) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.3f * drawerProgress.value.coerceIn(0f, 1f) }
                        .background(Color.Black)
                        .drawerDragGesture(drawerProgress, drawerWidthPx, scope)
                        .pointerInput(closeDrawer) {
                            detectTapGestures { closeDrawer() }
                        }
                )
            }

            // 抽屉面板。位置用 offset 平移（布局期读取，命中测试与绘制始终一致）。
            // 只在展开时才组合：关着时不参与重组（切主题更快），
            // 关闭动画结束时 progress≈0、面板已基本移出屏幕，卸载无闪现。
            if (drawerVisible) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(DrawerWidth)
                        .fillMaxHeight()
                        .offset { IntOffset(((drawerProgress.value - 1f) * drawerWidthPx).roundToInt(), 0) }
                        .drawerDragGesture(drawerProgress, drawerWidthPx, scope),
                    shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp
                ) {
                    AgentDrawer(state = state, onCloseDrawer = closeDrawer)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentMainContent(state: AgentState, onOpenDrawer: () -> Unit, imeSettling: () -> Boolean) {
    val context = LocalContext.current
    // 右上角「⋯」菜单：新建/重命名/删除/分享（对话内快捷操作，不用回抽屉）
    var chatMenuOpen by remember { mutableStateOf(false) }
    var renameConvOpen by remember { mutableStateOf(false) }
    var renameConvText by remember { mutableStateOf("") }
    var deleteConvOpen by remember { mutableStateOf(false) }
    // 三个页面都用 movableContent：切 Tab 时移动已组合的内容而非销毁重建，
    // 回到对话页不再重新解析全部 Markdown（修切 Tab 卡顿）；
    // 页面状态（草稿/编辑态/滚动位置）本来就在 AgentState，记忆快照照常保留。
    val chatScreen = remember {
        movableContentOf { ChatScreen(modifier = Modifier.fillMaxSize(), state = state, conversation = state.currentConversation, imeSettling = imeSettling) }
    }
    val modelsScreen = remember {
        movableContentOf { ModelsScreen(modifier = Modifier.fillMaxSize(), state = state) }
    }
    val settingsScreen = remember {
        movableContentOf { SettingsScreen(modifier = Modifier.fillMaxSize(), state = state) }
    }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                when (state.selectedTab) {
                                    MainTab.CHAT -> state.currentTitle
                                    MainTab.MODELS -> "模型"
                                    MainTab.SETTINGS -> "设置"
                                },
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (state.selectedTab == MainTab.CHAT) {
                                Text(
                                    state.selectedModel,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "打开侧边栏")
                        }
                    },
                    actions = {
                        if (state.selectedTab == MainTab.CHAT) {
                            IconButton(onClick = { chatMenuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { innerPadding ->
            // 只组合当前页面：切主题/发送时不必重组三个页面（修卡顿）。
            // 聊天生成引擎、草稿、滚动位置都提升在 AgentState，页面销毁重建不丢状态。
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                when (state.selectedTab) {
                    // 切对话直接硬切（淡入/淡出实测都会闪，用户不要）。
                    // movableContent 保证切回时内容直接移动，不重新解析 Markdown。
                    MainTab.CHAT -> chatScreen()
                    MainTab.MODELS -> modelsScreen()
                    MainTab.SETTINGS -> settingsScreen()
                }
            }
        }

        // 底部 Tab 常驻屏幕底部，键盘弹出时被键盘窗口直接盖住，无需额外动画。
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
                .height(TabBarHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SimpleTabItem(
                selected = state.selectedTab == MainTab.CHAT,
                icon = Icons.Outlined.Forum,
                label = "对话",
                onClick = { state.selectedTab = MainTab.CHAT }
            )
            SimpleTabItem(
                selected = state.selectedTab == MainTab.MODELS,
                icon = Icons.Outlined.Memory,
                label = "模型",
                onClick = { state.selectedTab = MainTab.MODELS }
            )
            SimpleTabItem(
                selected = state.selectedTab == MainTab.SETTINGS,
                icon = Icons.Default.Tune,
                label = "设置",
                onClick = { state.selectedTab = MainTab.SETTINGS }
            )
        }
    }

    // —— 右上角「⋯」对话菜单及其二级弹窗 ——
    if (chatMenuOpen) {
        ChatOverflowMenu(
            onDismiss = { chatMenuOpen = false },
            onNewConversation = {
                chatMenuOpen = false
                state.newConversation()
            },
            onRename = {
                chatMenuOpen = false
                renameConvText = state.currentConversation.title
                renameConvOpen = true
            },
            onDelete = {
                chatMenuOpen = false
                deleteConvOpen = true
            },
            onShare = {
                chatMenuOpen = false
                val text = buildTranscript(state.currentConversation)
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                }
                context.startActivity(android.content.Intent.createChooser(intent, "分享对话"))
            }
        )
    }
    if (renameConvOpen) {
        AlertDialog(
            onDismissRequest = { renameConvOpen = false },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = renameConvText,
                    onValueChange = { renameConvText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.renameConversation(state.currentConversation, renameConvText)
                    renameConvOpen = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameConvOpen = false }) { Text("取消") }
            }
        )
    }
    if (deleteConvOpen) {
        AlertDialog(
            onDismissRequest = { deleteConvOpen = false },
            title = { Text("删除对话") },
            text = { Text("确定删除「${state.currentConversation.title}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    state.deleteConversation(state.currentConversation)
                    deleteConvOpen = false
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConvOpen = false }) { Text("取消") }
            }
        )
    }
}

/** 导出对话全文（分享/复制用）：标题 + 我/助手交替，附件只列名字，思考过程不导出 */
private fun buildTranscript(conversation: AgentConversation): String = buildString {
    appendLine("# ${conversation.title}")
    appendLine()
    conversation.messages.forEach { m ->
        if (m.isError) return@forEach
        if (m.text.isBlank() && m.attachments.isEmpty()) return@forEach
        val who = if (m.fromUser) "我" else "助手"
        val atts = if (m.attachments.isEmpty()) "" else "（附件：${m.attachments.joinToString("、") { it.name }}）"
        appendLine("$who：${m.text}$atts")
        appendLine()
    }
}

/** 右上角「⋯」菜单：底部弹层（同服务商选择器同款 Dialog 模式，无 BottomSheet 抖动） */
@Composable
private fun ChatOverflowMenu(
    onDismiss: () -> Unit,
    onNewConversation: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        "对话操作",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp)
                    )
                    OverflowMenuRow(Icons.Default.Add, "新建对话", onNewConversation)
                    OverflowMenuRow(Icons.Default.Edit, "重命名对话", onRename)
                    OverflowMenuRow(Icons.Default.Share, "分享对话", onShare)
                    OverflowMenuRow(Icons.Default.Delete, "删除对话", onDelete, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun OverflowMenuRow(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color? = null) {
    val color = tint ?: MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, Modifier.size(19.dp), tint = color)
        Text(
            label,
            fontSize = 14.sp,
            color = color,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun RowScope.SimpleTabItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    // 图标颜色随选中状态平滑过渡（唯一保留的动效）
    val iconColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "tabIconColor"
    )

    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
            Text(
                label,
                fontSize = 11.sp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun AgentDrawer(state: AgentState, onCloseDrawer: () -> Unit) {
    // 批量管理模式；每项右侧重命名图标；长按=删除确认
    var selectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var renameFor by remember { mutableStateOf<AgentConversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTargets by remember { mutableStateOf<List<AgentConversation>?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // 软件图标（与启动器/闪屏同构的正弦波）
                    Image(
                        painter = painterResource(R.drawable.ic_agent_splash),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.app_name_full), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${state.selectedModel} · 已连接",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 历史搜索：按标题与消息内容过滤（会话多了以后找历史不再靠翻）
        var searchQuery by remember { mutableStateOf("") }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            placeholder = { Text("搜索历史对话…", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.keyboardSuppressReport().size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "清除搜索", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            textStyle = TextStyle(fontSize = 14.sp)
        )

        // 关键词过滤：标题或任一消息文本命中即保留（直接在组合中计算，
        // 会话/消息/关键词任意变化都会重新过滤；量级在毫秒内）
        val trimmedQuery = searchQuery.trim()
        val displayedConversations = if (trimmedQuery.isEmpty()) {
            state.conversations.toList()
        } else {
            state.conversations.filter { conversation ->
                conversation.title.contains(trimmedQuery, ignoreCase = true) ||
                    conversation.children.values.any { siblings ->
                        siblings.any { it.text.contains(trimmedQuery, ignoreCase = true) }
                    }
            }
        }

        // 最近对话区：标题行/批量管理固定，仅会话列表滚动。
        Column(Modifier.weight(1f).fillMaxWidth()) {
            if (selectMode) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 ${selectedIds.size} 个",
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 加大的操作按钮（好按）
                    Surface(
                        onClick = {
                            if (displayedConversations.isNotEmpty() && selectedIds.size == displayedConversations.size) selectedIds.clear()
                            else {
                                selectedIds.clear()
                                // 全选只选当前列表（搜索时即筛选结果）
                                selectedIds.addAll(displayedConversations.map { it.id })
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            if (displayedConversations.isNotEmpty() && selectedIds.size == displayedConversations.size) "取消全选" else "全选",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = {
                            if (selectedIds.isNotEmpty()) {
                                deleteTargets = selectedIds.mapNotNull { id ->
                                    state.conversations.firstOrNull { it.id == id }
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            "删除",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            } else {
                // 标题行右侧的批量管理入口（加大触点，好按）；搜索时不显示，避免全选范围歧义
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (searchQuery.isBlank()) "最近对话" else "搜索结果（${displayedConversations.size}）",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (searchQuery.isBlank()) {
                        Surface(
                            onClick = {
                                selectedIds.clear()
                                selectMode = true
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Checklist, contentDescription = null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text("批量管理", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            // 仅会话列表滚动，标题与批量管理行不随之移动。
            // LazyColumn 按需组合：会话多时抽屉打开/滚动不再全量构建全部条目。
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(displayedConversations, key = { it.id }) { conversation ->
                    DrawerConversationItem(
                        title = conversation.title,
                        selected = conversation.id == state.currentConversationId,
                        selectMode = selectMode,
                        checked = selectedIds.contains(conversation.id),
                        onClick = {
                            if (selectMode) {
                                if (selectedIds.contains(conversation.id)) selectedIds.remove(conversation.id)
                                else selectedIds.add(conversation.id)
                            } else {
                                // 先关抽屉，抽屉退场后再切内容（避免两次剧烈变化叠加）
                                state.deferSelectConversation(conversation, onCloseDrawer)
                            }
                        },
                        onLongClick = {
                            // 长按直接弹删除确认（重命名有专属图标、批量有专属入口）
                            if (!selectMode) deleteTargets = listOf(conversation)
                        },
                        onRename = { renameFor = conversation; renameText = conversation.title }
                    )
                }
                if (displayedConversations.isEmpty()) {
                    item {
                        Text(
                            if (searchQuery.isBlank()) "暂无对话" else "没有包含「${trimmedQuery}」的对话",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                if (selectMode) {
                    selectMode = false
                    selectedIds.clear()
                } else {
                    // 新建也先关抽屉后切换，与选会话节奏一致
                    state.deferNewConversation(onCloseDrawer)
                }
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(if (selectMode) Icons.Default.Close else Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (selectMode) "退出管理" else "新建对话")
        }
        Spacer(Modifier.height(12.dp))
    }

    renameFor?.let { conv ->
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.renameConversation(conv, renameText)
                    renameFor = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameFor = null }) { Text("取消") }
            }
        )
    }

    deleteTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { deleteTargets = null },
            title = { Text("删除对话") },
            text = {
                Text(
                    if (targets.size == 1) "确定删除「${targets[0].title}」吗？此操作不可恢复。"
                    else "确定删除选中的 ${targets.size} 个对话吗？此操作不可恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    targets.forEach { state.deleteConversation(it) }
                    selectedIds.clear()
                    selectMode = false
                    deleteTargets = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargets = null }) { Text("取消") }
            }
        )
    }
}
@Composable
private fun DrawerConversationItem(
    title: String,
    selected: Boolean,
    selectMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    checked -> MaterialTheme.colorScheme.secondaryContainer
                    selected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                    else -> Color.Transparent
                }
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            // 管理模式下整行加高，复选框与文字更大更好点
            .padding(horizontal = 10.dp, vertical = if (selectMode) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectMode) {
            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.size(26.dp))
        } else {
            Icon(
                Icons.Outlined.Forum,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            title,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = if (selectMode) 15.sp else 14.sp
        )
        if (!selectMode) {
            // 每个对话右侧的重命名符号（加大触点）
            Surface(
                onClick = onRename,
                shape = CircleShape,
                color = Color.Transparent
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "重命名对话",
                    modifier = Modifier.padding(8.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
@Composable
private fun ChatScreen(
    modifier: Modifier = Modifier,
    state: AgentState,
    conversation: AgentConversation,
    imeSettling: () -> Boolean = { false }
) {
    val context = LocalContext.current
    val localView = androidx.compose.ui.platform.LocalView.current
    val clipboardManager = LocalClipboardManager.current
    val messages = conversation.messages
    // 本对话是否在生成：A 生成时切到 B，B 不再被全局锁（输入/发送/编辑均可用）
    val isGenerating = state.isGeneratingIn(conversation.id)
    // key(conversation.id)：切换会话时重置，避免 A 会话的编辑行号落到 B 会话的同位置
    var editingIndex by remember(conversation.id) { mutableStateOf<Int?>(null) }
    // 长按任意消息 = 直接选中文本（复制局部）。原生选区没有公开的清除 API（Selection
    // 内部态），清空靠**焦点级联**：把焦点移到屏外哨兵节点 → 选区容器失焦 →
    // SelectionManager.onRelease → 选区与浮层工具栏同步拆掉。
    // （不要退回「重建 SelectionContainer」方案：容器拆除时正在组合的选区手势协程被
    // 取消，其 onCancel 路径会 notifySelectionUpdateEnd → showToolbar=true 把工具条
    // 再 show 一次、随后 onRelease 又 hide —— 这就是取消时「全选」浮条闪现的来源。）
    val selectionClearer = remember { androidx.compose.ui.focus.FocusRequester() }
    val attachments = state.attachmentsFor(conversation.id)
    var input by remember(conversation.id) { mutableStateOf(state.draftInputFor(conversation.id)) }
    val scope = rememberCoroutineScope()
    val provider = state.currentProvider
    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        var rejected: String? = null
        uris.forEach { uri ->
            // 第一层筛选（软件级）：转不成 API 载荷的格式（PDF/未知类型等）直接报错，
            // 模型肯定收不到，不浪费时间上传；软件能处理的放行，
            // 模型层是否支持由发送后的降级链（第二层）判断
            val name = sharedAttachmentName(context, uri)
            val reason = AttachmentLoader.unsupportedReason(context, uri, name)
            if (reason != null) {
                if (rejected == null) rejected = reason
                return@forEach
            }
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // 某些文件提供器不支持持久化授权，但本次读取仍可用。
            }
            attachments.add(ChatAttachment(uri, name))
        }
        rejected?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    fun sendMessage() {
        if ((input.isNotBlank() || attachments.isNotEmpty()) && !isGenerating) {
            if (provider == null || provider.baseUrl.isBlank() || provider.apiKey.isBlank()) {
                conversation.appendMessage(ChatMessage(text = "", fromUser = true, attachments = attachments.toList()))
                conversation.appendMessage(ChatMessage(text = "请先在「设置」中添加服务商（选一个预设或填入 Base URL，再填入 API Key）后再开始对话。", fromUser = false, isError = true))
                input = ""
                attachments.clear()
                return
            }
            val text = input.trim()
            // 只发附件不带文字时，不再多一条空的用户气泡：附件本身即消息
            if (text.isNotBlank() || attachments.isNotEmpty()) {
                conversation.appendMessage(ChatMessage(text = text, fromUser = true, attachments = attachments.toList()))
            }
            input = ""
            attachments.clear()
            state.startGeneration(context, conversation)
            // 发送后保持键盘打开，避免收起动画带来的卡顿，也能看着流式输出
        }
    }
    // 离开页面/切对话时把草稿写回全局（输入框状态提升，切 Tab 不丢字）
    LaunchedEffect(conversation.id) {
        snapshotFlow { input }.collect { state.setDraftInput(conversation.id, it) }
    }
    // 系统分享文本进入草稿时，立即读到输入框（版本号由 consumeSharedIntent 自增）
    val draftShareVersion = state.draftVersions[conversation.id] ?: 0
    LaunchedEffect(draftShareVersion) {
        if (draftShareVersion > 0) input = state.draftInputFor(conversation.id)
    }

    // 复制局部时按返回键退出；selectionLikelyActive 由气泡长按回调点亮（长按即开始选词），
    // 同时是「选区模式」标志：流式跟随暂停、返回键拦截、点空白即退出都由它驱动
    var selectionLikelyActive by remember { mutableStateOf(false) }

    // 清选区：失焦级联 → SelectionManager.onRelease 同步拆选区+工具条（无闪现）。
    // 哨兵未挂载（极端时序）时 requestFocus 抛异常，直接吞掉 —— 下一次清选区会重试。
    fun clearTextSelection() {
        selectionLikelyActive = false
        runCatching { selectionClearer.requestFocus() }
    }
    // 全屏查看的图片附件（点气泡/输入栏里的图片缩略图打开）
    var viewingImage by remember { mutableStateOf<ChatAttachment?>(null) }
    // 拍照确认弹窗的待定照片（拍照返回 → 确认弹窗 → 加入附件）；提升到 ChatScreen 作用域
    val launchCamera = rememberCameraLauncher { uri ->
        attachments.add(ChatAttachment(uri, "camera_${System.currentTimeMillis()}.jpg"))
    }
    BackHandler(enabled = selectionLikelyActive) {
        clearTextSelection()
    }

    // 编辑态清理：越界（消息被删）或进入生成态时自动退出编辑。
    // 用 LaunchedEffect 而非组合期直写 —— 组合中写状态会立刻再触发重组（反模式）
    LaunchedEffect(editingIndex, isGenerating, messages.size) {
        val ei = editingIndex
        if (ei != null && (ei >= messages.size || isGenerating)) editingIndex = null
    }

    Column(
        modifier
            .fillMaxSize()
            // 任意位置点击都收起键盘（不只消息列表空白处）：
            // 在 Initial pass 静默观察，只有「未被任何子控件消费的纯点击」才收起 ——
            // 按钮/气泡/图片等子控件消费了 down/up 就不收（那是明确的子控件点击）；
            // 代码块/表格/附件条的横向滑动走了 touchSlop，也不会触发。
            // 联网键例外：其回调在点击时向 keyboardSuppress 报备本次手势，观察器跳过。
            // 选区例外：选区激活时的纯点按改为「取消局部复制」（见 onSelectionTap），
            // 长按选词手势松手仍完全让路（imeOnly 报备）。
            .pointerInput(Unit) {
                // 选区激活时的纯点按（非长按，手势短于 longPressTimeout）＝取消局部复制：
                // 走 onSelectionTap（清焦点级联拆选区）。长按松手仍完全让路：
                // 长按选词 → 选区容器持有焦点，若此时 clearFocus 会级联清除
                // 选区容器焦点 → SelectionManager.onRelease → 刚选好的选区消失。
                dismissKeyboardOnTap(
                    localView,
                    shouldSkip = { selectionLikelyActive },
                    onSelectionTap = {
                        clearTextSelection()
                        hideKeyboard(localView)
                    },
                )
            },
    ) {
        // 清选区哨兵：clearTextSelection() 把焦点移到这里（屏外 0 尺寸、无视觉、不占布局），
        // 选区容器失焦即被 SelectionManager.onRelease 清空。必须常驻组合（放在任何条件分支之外）。
        Box(
            Modifier
                .requiredSize(0.dp)
                .focusRequester(selectionClearer)
                .focusable()
        )
        val listState = state.listStateFor(conversation.id)
        // 「贴底」判定死区（px）：流式逐帧重排时末条底边会短暂超出视口几 px～几十 px，
        // 阈值太小会让「回到底部」键反复闪现/消失（跳动），也会让跟随逻辑反复拉回
        val bottomTolerancePx = with(LocalDensity.current) { 96.dp.toPx() }
        if (messages.isNotEmpty()) {
            // 流式跟随：用快照流收集，文本增长时直接贴底（无动画重启的抖动）；
            // 仅在用户本来就贴底时跟随：若已上翻，不再强行拉回（尊重阅读位置）。
            // 选区激活（selectionLikelyActive）时也暂停：长按选中后拖动选区
            // 手柄需要静止的列表 —— 流式每帧 snapToBottom 会把选区所在文本
            // 疯狂拉走（「局部复制时界面快速滑动」在生成中的直接来源）。
            LaunchedEffect(conversation.id) {
                snapshotFlow {
                    val last = messages.lastOrNull()
                    Triple(messages.size, last?.text?.length ?: 0, last?.reasoning?.length ?: 0)
                }
                    .collect { (size, _, _) ->
                        if (selectionLikelyActive) return@collect
                        val info = listState.layoutInfo
                        val lastVisible = info.visibleItemsInfo.lastOrNull()
                        val viewportBottom = info.viewportEndOffset - info.afterContentPadding
                        val nearBottom = size == 1 || (
                            lastVisible != null && lastVisible.index >= size - 2 &&
                                lastVisible.offset + lastVisible.size <= viewportBottom + bottomTolerancePx
                            )
                        if (nearBottom) listState.snapToBottom()
                    }
            }
            // 就地编辑时把正在编辑的消息滚到可视区，避免发送按钮被键盘挡住
            LaunchedEffect(editingIndex) {
                val ei = editingIndex
                if (ei != null && ei < messages.size) {
                    listState.animateScrollToItem(ei)
                }
            }
            // 生成开始时自动滚到输出位置：不管用户当时在哪儿都带回底部，
            // 之后的流式跟随逻辑接手。（切到正在生成的会话也会触发：initial composition 同样跑）
            LaunchedEffect(conversation.id, isGenerating) {
                if (isGenerating && messages.isNotEmpty()) {
                    // 关键：等占位气泡（列表末尾的助手消息）真的出现再跳。
                    // 引擎协程是异步调度的：本 effect 启动时 messages 末尾可能还是用户消息，
                    // 直接跳会落在旧底部（看起来"没回底"）；totalItemsCount == 数据条数，
                    // 用它等是等不到的（永远立即满足）。
                    val sizeAtStart = messages.size
                    val placeholderReady = messages.lastOrNull()?.let { !it.fromUser } == true
                    if (!placeholderReady) {
                        snapshotFlow { messages.size }.first { it > sizeAtStart }
                    }
                    listState.snapToBottom()
                }
            }
            // “回到底部”悬浮键的显示条件：列表底部不可见就显示（不管末条消息多高）。
            // 必须用 derivedStateOf：layoutInfo 每个滚动帧都变，直接读进组合会让整屏每帧重组
            // （修滚动卡顿）；包一层后只有显示/隐藏翻转时才重组。
            // 96dp 死区（≈一指宽）：流式重排每帧的微小底边超出不再翻转显示态，
            // 修“生成中回到底部键一直闪”的问题。
            // key(listState)：切会话后 listState 换新对象；不带 key 的 remember 会一直捕获
            // 旧会话的 listState —— 旧列表停在半空时，新会话里图标永远显示且无法消除
            // （旧 layoutInfo 不再变化，派生值不再重算）。
            val awayFromBottom by remember(conversation.id, listState) {
                androidx.compose.runtime.derivedStateOf {
                    val info = listState.layoutInfo
                    val lastItem = info.visibleItemsInfo.lastOrNull()
                    val total = info.totalItemsCount
                    if (total == 0 || lastItem == null) false
                    else lastItem.index != total - 1 ||
                        // 末条可见但它的底边超出视口超过死区（长消息滚到一半也该显示）；
                        // 死区与跟随判定用同一个 96dp 容差：两处阈值不一致（旧值 260px）
                        // 会出现「按钮显示但跟随逻辑认为还贴底」的互相打架
                        lastItem.offset + lastItem.size >
                        info.viewportEndOffset - info.afterContentPadding + bottomTolerancePx
                }
            }
            // 视口高度变化（键盘弹出/收起）时，跟随态就重新贴底：
            // “贴底”只在当时的视口高度下成立。发送时键盘开着视口矮，收起后视口变高，
            // LazyColumn 锚点不动 → 底部多出大片空白，看起来“根本没回到底部”。
            // 防抖 80ms 避开键盘逐帧动画；用户上翻中（away=true）不抢滚动。
            LaunchedEffect(conversation.id) {
                snapshotFlow {
                    listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                }
                    .distinctUntilChanged()
                    .collectLatest {
                        kotlinx.coroutines.delay(80)
                        // 选词状态下不抢滚动：键盘收起导致视口变高时的强制贴底
                        // 正是「局部复制时界面上下跳动」的来源；用户正在选词，位置不能动
                        if (!selectionLikelyActive && !awayFromBottom && messages.isNotEmpty()) listState.snapToBottom()
                    }
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 复制局部时点列表空白处退出：清焦点级联拆选区；
                        // 同时收起键盘（翻看历史时不再需要手动关键盘）
                        .pointerInput(conversation.id) {
                            detectTapGestures {
                                clearTextSelection()
                                hideKeyboard(localView)
                            }
                        },
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    if (editingIndex == index && !isGenerating) {
                        // 就地编辑（ChatGPT 式）：气泡变成输入框，取消/发送两个按钮
                        InlineMessageEditor(
                            initialText = message.text,
                            onCancel = { editingIndex = null },
                            onSend = { newText ->
                                // 编辑重发 = 新建分支：旧版本与其后续对话都保留在树上，可 ‹ › 切回
                                conversation.insertVersionAt(
                                    index,
                                    messages[index].copy(
                                        id = java.util.UUID.randomUUID().toString(),
                                        text = newText.trim()
                                    )
                                )
                                editingIndex = null
                                state.startGeneration(context, conversation)
                            }
                        )
                    } else {
                        Column {
                            // 纯附件消息（无文字）不渲染空气泡，只渲染附件条；
                            // 有文字（或有思考/回复内容）的正常渲染气泡
                            val showBubble = !(message.fromUser && message.text.isBlank() && message.attachments.isNotEmpty())
                            if (showBubble) {
                                MessageBubble(
                                    message = message,
                                    streaming = isGenerating && index == messages.lastIndex && !message.fromUser,
                                    // 长按任意消息 = 直接选中文本（复制局部）；
                                    // 点用户消息 = 就地编辑重发；点模型消息 = 清选区
                                    onLongPressObserve = { selectionLikelyActive = true },
                                    onTap = {
                                        if (message.fromUser) {
                                            if (!isGenerating) editingIndex = index
                                        } else if (selectionLikelyActive) {
                                            clearTextSelection()
                                        }
                                    }
                                )
                            }
                            // 附件展示条：放在用户消息气泡下方（独立于气泡不挤占对话），
                            // 从右往左排（第一条贴齐右侧），超出屏宽才横向滑动
                            if (message.fromUser && message.attachments.isNotEmpty()) {
                                if (showBubble) Spacer(Modifier.height(4.dp))
                                MessageAttachmentsRow(
                                    attachments = message.attachments,
                                    onImageClick = { viewingImage = it }
                                )
                            }
                            // 操作行常驻渲染（不再依赖 !isGenerating 消隐）：
                            // 之前流式期间隐藏、完成瞬间所有可见消息同时长出一行按钮，
                            // 列表整体位移 → 「回复完成后界面跳一下」的主因。
                            // 生成中只禁用分支切换器（切换会破坏引擎的路径索引），
                            // 复制按钮无副作用，全程可用。
                            if (showBubble) {
                                if (message.fromUser) {
                                    // 用户消息下方：复制按钮 + 分支切换器（问题的多版本 / 该问题的多个回答）
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val next = messages.getOrNull(index + 1)
                                        if (next != null && !next.fromUser) {
                                            val aSiblings = conversation.siblingsOf(index + 1)
                                            val aPos = aSiblings.indexOfFirst { it.id == next.id }
                                            if (aSiblings.size > 1 && aPos >= 0) {
                                                BranchSwitcher(
                                                    position = aPos,
                                                    total = aSiblings.size,
                                                    enabled = !isGenerating,
                                                    onPrev = {
                                                        clearTextSelection()
                                                        conversation.switchVersionAt(index + 1, aSiblings[aPos - 1])
                                                    },
                                                    onNext = {
                                                        clearTextSelection()
                                                        conversation.switchVersionAt(index + 1, aSiblings[aPos + 1])
                                                    }
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            }
                                        }
                                        val qSiblings = conversation.siblingsOf(index)
                                        val qPos = qSiblings.indexOfFirst { it.id == message.id }
                                        if (qSiblings.size > 1 && qPos >= 0) {
                                            BranchSwitcher(
                                                position = qPos,
                                                total = qSiblings.size,
                                                enabled = !isGenerating,
                                                onPrev = {
                                                    clearTextSelection()
                                                    conversation.switchVersionAt(index, qSiblings[qPos - 1])
                                                },
                                                onNext = {
                                                    clearTextSelection()
                                                    conversation.switchVersionAt(index, qSiblings[qPos + 1])
                                                }
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(message.text))
                                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.keyboardSuppressReport().size(30.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = "复制全文",
                                                modifier = Modifier.size(15.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                } else if (!message.isError && (message.text.isNotBlank() || !isGenerating)) {
                                    // 模型回复下方的操作行：与气泡同宽（气泡已铺满整行），仅复制全文；
                                    // 报错/系统提示的复制入口在卡片内部，不再另起一行残留图标。
                                    // 有内容就常驻渲染（流式期间复制的是当前已生成的部分），
                                    // 完成瞬间不再长出新行 → 列表底部无高度突变
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(message.text))
                                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.keyboardSuppressReport().size(30.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = "复制全文",
                                                modifier = Modifier.size(15.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
                // 附件悬浮层：悬浮在消息列表底部（不独立占位，背后的气泡照常可见）。
                // 与「回到底部」键并存：浮层居底，按钮在浮层上方避开
                // 参考 ChatGPT 附件托盘：从底部滑升出现（与「回到底部」胶囊同向、
                // 同节奏）；清空后直接移除（与胶囊一致，不拖尾）。
                // AnimatedVisibility 常驻组合，visible 翻转时才有进入动画
                androidx.compose.animation.AnimatedVisibility(
                    visible = attachments.isNotEmpty(),
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(
                        animationSpec = tween(200, easing = FastOutSlowInEasing)
                    ) { it / 3 },
                    exit = ExitTransition.None,
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    PendingAttachmentsOverlay(
                        attachments = attachments,
                        onRemove = { removePendingAttachment(attachments, it) },
                        onImageClick = { viewingImage = it }
                    )
                }
                // 悬浮提示：仅当用户自己上滑翻历史时出现（"回到底部"）。
                // 生成开始已自动带回底部，不再用"新回复中"提示。
                androidx.compose.animation.AnimatedVisibility(
                    visible = awayFromBottom,
                    enter = androidx.compose.animation.fadeIn(),
                    // 消失不拖尾：贴底后直接移除（旧 fadeOut+shrink 动画让按钮多挂约 0.3s，
                    // 看起来反应迟钝）
                    exit = androidx.compose.animation.ExitTransition.None,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 有附件浮层时按钮上移让位（浮层高 64dp + 间距）
                        .padding(bottom = if (attachments.isNotEmpty()) 86.dp else 10.dp)
                ) {
                    Surface(
                        onClick = {
                            clearTextSelection()
                            scope.launch {
                                // 直接一步到位贴底（用户明确不要动画）
                                listState.snapToBottom()
                            }
                        },
                        // 报备不收键盘：贴底动作不应影响键盘状态
                        modifier = Modifier.keyboardSuppressReport(),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    ) {
                        Row(
                            Modifier.padding(start = 13.dp, end = 15.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "回到底部",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.ArrowDownward,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        } else {
            // 空会话：附件浮层也悬浮在底部（与有消息时一致，不再独立占一条）
            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                // 与有消息时同款进出场（见上方注释）
                androidx.compose.animation.AnimatedVisibility(
                    visible = attachments.isNotEmpty(),
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(
                        animationSpec = tween(200, easing = FastOutSlowInEasing)
                    ) { it / 3 },
                    exit = ExitTransition.None
                ) {
                    PendingAttachmentsOverlay(
                        attachments = attachments,
                        onRemove = { removePendingAttachment(attachments, it) },
                        onImageClick = { viewingImage = it }
                    )
                }
            }
        }

        // 输入区：文本框占满整行（每行可容纳的字更多），附件/联网/发送按钮收进文本框下沿一行（ChatGPT 式）
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 10.dp)
                .padding(top = 0.dp, bottom = 6.dp)
        ) {
            // 一体化输入条（ChatGPT/Gemini 式）：圆角胶囊容器内，上行是文本框，
            // 下行左侧附件/联网小图标、右侧发送圆钮。视觉上是一个整体，不再是裸露的控件拼盘。
            // keyboardSuppressReport：点输入框时容器的收键盘观察器会先收起、
            // 焦点逻辑再弹开 → 键盘闪一下；报备后容器跳过，键盘状态由焦点自然控制
            Surface(
                modifier = Modifier.fillMaxWidth().keyboardSuppressReport(),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
            ) {
                Column {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                            // 输入框向上扩高（顶部内边距大、贴底近）：点击区域远离下方图标行，
                            // 也消除胶囊顶部的白色空隙感
                            .padding(top = 10.dp, bottom = 10.dp)
                            .heightIn(min = 44.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)
                        ),
                        decorationBox = { inner ->
                            // 占位文字与真实输入区放进同一个 Box、同一套字体规格：
                            // 两者都在容器内垂直居中，光标与「输入消息…」逐像素对齐。
                            // （旧写法占位自己套 34dp 盒子居中、而输入区被 Compose 钉在顶部，
                            // 导致光标比占位文字高出几个 dp）
                            Box(
                                Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (input.isEmpty()) {
                                    Text(
                                        "输入消息…",
                                        fontSize = 16.sp,
                                        lineHeight = 22.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                inner()
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        // 不设 onSend：回车键保持换行（聊天输入多行是常态）；
                        // 发送走右侧圆钮，避免单行 IME 动作吞掉换行
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 附件：DeepSeek 式胶囊钮（长按有 Toast 说明）
                        InputToolChip(
                            icon = Icons.Default.Add,
                            label = "附件",
                            contentDescription = "添加附件",
                            hint = "发送图片或文件给模型（支持多选）",
                            enabled = !isGenerating,
                            suppressKeyboardHide = true
                        ) { attachmentPicker.launch(arrayOf("*/*")) }
                        // 拍照：快门后返回，应用内大按钮确认（重拍/使用），不再用系统相机的小 ✓
                        InputToolChip(
                            icon = Icons.Default.PhotoCamera,
                            label = "拍照",
                            contentDescription = "拍照并作为附件",
                            hint = "拍照发给模型（拍照后可确认或重拍）",
                            enabled = !isGenerating,
                            suppressKeyboardHide = true
                        ) { launchCamera() }
                        // 联网搜索：开启时胶囊高亮。点击前报备 keyboardSuppress：
                        // 容器的收键盘观察器在 Initial pass 看不到子控件消费（祖先先于后代），
                        // 会把这次点按当纯点按 —— 报备后它跳过，键盘保持打开，开关状态由
                        // 胶囊高亮即时反馈，用户可继续打字或直接发送
                        InputToolChip(
                            icon = Icons.Default.TravelExplore,
                            label = "联网",
                            contentDescription = if (state.webSearch) "关闭联网搜索" else "开启联网搜索",
                            hint = if (state.webSearch) "联网搜索已开启：回答前会先搜索最新网页" else "联网搜索已关闭：仅用模型自身知识回答",
                            active = state.webSearch,
                            enabled = !isGenerating,
                            suppressKeyboardHide = true
                        ) { state.webSearch = !state.webSearch }
                        Spacer(Modifier.weight(1f))
                        // 发送/停止：小圆钮，紧贴右下角。
                        // 参考 ChatGPT 发送⇄停止变形：背景色与图标都平滑过渡，
                        // 硬切改 Crossfade（150ms，足够快又不闪）
                        val sendActive = isGenerating || input.isNotBlank() || attachments.isNotEmpty()
                        val sendBg by animateColorAsState(
                            targetValue = if (sendActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            animationSpec = tween(150),
                            label = "sendBg"
                        )
                        Box(
                            Modifier
                                .keyboardSuppressReport()
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(sendBg, CircleShape)
                                .clickable {
                                    if (isGenerating) {
                                        state.stopGeneration(conversation.id)
                                    } else {
                                        sendMessage()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Crossfade(
                                targetState = isGenerating,
                                animationSpec = tween(150),
                                label = "sendIcon"
                            ) { generating ->
                                Icon(
                                    if (generating) Icons.Default.Stop else Icons.Default.ArrowUpward,
                                    contentDescription = if (generating) "停止" else "发送",
                                    Modifier.size(18.dp),
                                    tint = if (sendActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 键盘关闭时为底部 Tab 留位；键盘弹出时贴住键盘上沿，随系统 IME 动画逐帧变化。
        // 抽屉唤起触发的 IME 退场窗口期内锁定最终高度（见 AgentApp.imeSettling）。
        Spacer(Modifier.bottomInputClearance(imeSettling))
    }

    // 全屏图片查看器（点消息里/输入栏里的图片打开）
    viewingImage?.let { attachment ->
        ImageViewerDialog(attachment = attachment, onDismiss = { viewingImage = null })
    }
}

/**
 * 容器级「任意位置点按收起键盘」：Initial pass 观察，只在未被子控件消费的纯点击时收起。
 * 选区激活时，短按取消局部复制，长按选词则完全让选区系统处理。
 *
 * 与逐处加 clickable 的区别：不用枚举所有可点控件，消息列表、代码块、表格、
 * 图片、附件条上点击都生效（它们没消费 down 时这里兜底）。
 * 不会误伤按钮/气泡等子控件明确处理的点击。抽屉拖拽、横向滚动和已激活的文本选区
 * 由各自手势处理逻辑负责。
 * 报备通道见 keyboardSuppress：tapActive=完全不动（联网键/输入胶囊），
 * imeOnly=只收 IME 不清焦点（局部复制长按，清焦点会连选区一起清掉）。
 */
/** 联网键报备豁免：点击联网键时置位，容器观察器据此跳过收键盘（消费后自动复位） */
private object keyboardSuppress {
    @Volatile var tapActive: Boolean = false
    // 局部复制手势报备：本次手势松手时只收 IME、不清焦点。
    // clearFocus 会级联清掉 Compose 焦点树 → 选区容器失焦 → SelectionManager.onRelease
    // → 长按选好的文本「松手即消失」。
    @Volatile var imeOnly: Boolean = false
}

/**
 * 报备本次手势不收键盘：down 置位、up 复位，容器观察器（dismissKeyboardOnTap）
 * 据此跳过收键盘。用于联网键（切换开关不想收键盘）与整个输入胶囊
 * （点输入框时容器会先收后由焦点弹开 → 键盘闪一下，报备后不再闪）。
 */
private fun Modifier.keyboardSuppressReport(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        try {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            keyboardSuppress.tapActive = true
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.all { !it.pressed }) break
            }
        } finally {
            // 手势取消/异常时也复位，避免标志卡住导致键盘永远收不了
            keyboardSuppress.tapActive = false
        }
    }
}

private suspend fun PointerInputScope.dismissKeyboardOnTap(
    localView: android.view.View?,
    shouldSkip: () -> Boolean = { false },
    onSelectionTap: (() -> Unit)? = null
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.isConsumed) return@awaitEachGesture
        var tapped = true
        // 记录手势时长与初始选区态：选区激活时区分「短按取消选区」与「长按选词让路」。
        // 长按选词路径：SelectionContainer 的 touchSelectionFirstPress 同样用
        // longPressTimeoutMillis 判长按（awaitLongPressOrCancellation），阈值一致才能无缝交接。
        val gestureStart = kotlin.time.TimeSource.Monotonic.markNow()
        val selectionMode = shouldSkip()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                // 手指抬起：只处理「未被任何子控件消费的抬起」（Initial pass 先于
                // 子控件的 Main pass，消费结果这一刻还看不到，只能靠报备推断）。
                // 分支优先级：onSelectionTap（选区激活时的短按＝取消局部复制）>
                // shouldSkip（选区激活的长按：完全让路，选区容器的焦点不能被任何
                // clearFocus 级联碰掉）> tapActive（报备不收）> imeOnly（只收 IME）>
                // 默认（收 IME + 清焦点）。
                if (tapped && !event.changes.any { it.isConsumed }) {
                    when {
                        selectionMode && onSelectionTap != null &&
                            gestureStart.elapsedNow() < viewConfiguration.longPressTimeoutMillis.milliseconds ->
                            onSelectionTap()
                        shouldSkip() -> {}
                        keyboardSuppress.tapActive -> {
                            keyboardSuppress.tapActive = false
                        }
                        keyboardSuppress.imeOnly -> {
                            keyboardSuppress.imeOnly = false
                            hideKeyboard(localView, imeOnly = true)
                        }
                        else -> hideKeyboard(localView)
                    }
                }
                break
            }
            // 子控件已消费（按钮点击/滚动/拖拽）或移动过 touchSlop → 不再算纯点按
            if (change.isConsumed ||
                (abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) ||
                (abs(change.position.y - down.position.y) > viewConfiguration.touchSlop)
            ) tapped = false
        }
    }
}

private fun hideKeyboard(localView: android.view.View?, imeOnly: Boolean = false): Boolean {
    // 先检测（隐藏后 insets 已更新，检测会恒为 false）
    val imeVisible = androidx.core.view.ViewCompat.getRootWindowInsets(localView ?: return false)
        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
    val token = (localView?.context as? android.app.Activity)?.currentFocus?.windowToken
        ?: localView?.windowToken
    if (token != null) {
        val imm = localView?.context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(token, 0)
    }
    // imeOnly（局部复制长按手势松手）：只收 IME 不清焦点 —— clearFocus 会级联清
    // Compose 焦点树，选区容器失焦即触发 SelectionManager.onRelease 清空选区
    //（表现为「长按选好的词一松手就消失」）。
    if (!imeOnly) localView?.clearFocus()
    return imeVisible
}

/**
 * 输入区待发附件悬浮层（ChatGPT 式）：悬浮在消息列表底部，**不独立占一条**。
 * 旧实现是独立横条：附件卡背后多出一块与页面同色的白带，白带连同附件一起
 * 把聊天区顶上去一截（用户观感："白块也挡住气泡"）。悬浮后只有附件卡自身
 * 挡住其覆盖的气泡区域，周围气泡照常可见。
 * 图片 = 64dp 圆角缩略图；非图片 = 轻透信息卡（图标+文件名+格式）；
 * ✕ 删除无涟漪无动效（点击即删）。
 */
@Composable
private fun PendingAttachmentsOverlay(
    attachments: List<ChatAttachment>,
    onRemove: (ChatAttachment) -> Unit,
    onImageClick: (ChatAttachment) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        attachments.forEach { attachment ->
            val isImage = remember(attachment.uri, attachment.name) { isImageAttachment(context, attachment) }
            if (isImage) {                // 图片与删除标志包进同一个 Box：✕ 叠在图片右上角（此前两者是
                // Row 兄弟节点，✕ 掉到图片右侧去了，这是 bug）
                Box {
                    coil.compose.AsyncImage(
                        model = coil.request.ImageRequest.Builder(context)
                            .data(attachment.uri)
                            .size(256)
                            .build(),
                        contentDescription = attachment.name,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onImageClick(attachment) },
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                    // 删除标志：黑色半透明圆底 + 白 ✕（无涟漪，叠右上角）
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onRemove(attachment) }
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "删除附件",
                            modifier = Modifier.size(13.dp),
                            tint = Color.White
                        )
                    }
                }
            } else {
                val ext = remember(attachment.uri, attachment.name) { attachmentExtLabel(context, attachment) }
                val isAudio = ext in AUDIO_EXT_SET
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    // 实心底：浮层背后是聊天气泡，半透明底会透出文字（无投影）
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.height(64.dp)
                ) {
                    Row(
                        Modifier.padding(start = 10.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Checklist,
                            contentDescription = if (isAudio) "音频附件" else "文档附件",
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                attachmentDisplayName(attachment.name),
                                maxLines = 1,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 130.dp)
                            )
                            Text(
                                if (isAudio) "音频 · $ext" else ext,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onRemove(attachment) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "删除附件", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** 按附件 id 从待发列表里删除（同一文件可添加多次，id 保证删哪个删哪个） */
private fun removePendingAttachment(attachments: MutableList<ChatAttachment>, target: ChatAttachment) {
    attachments.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }?.let { attachments.removeAt(it) }
}

@Composable
private fun rememberCameraLauncher(onCaptured: (android.net.Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    var pendingPhotoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = pendingPhotoUri
        pendingPhotoUri = null
        if (ok && uri != null) onCaptured(uri)
    }
    return launch@{
        // 父目录必须先存在（相机进程只创建文件不建目录）；mkdirs 对已存在目录
        // 只是一次 stat，同步执行避免「后台还没建好目录 → 相机写文件失败」的竞态。
        // 文件本身由相机进程创建，这里不预创建
        val photoFile = java.io.File(
            java.io.File(context.filesDir, "camera"),
            "camera_${System.currentTimeMillis()}.jpg"
        )
        photoFile.parentFile?.mkdirs()
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", photoFile)
        pendingPhotoUri = uri
        try {
            launcher.launch(uri)
        } catch (_: Exception) {
            pendingPhotoUri = null
            Toast.makeText(context, "没有可用的相机应用", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * DeepSeek 式功能胶囊：圆角矩形底座 + 图标（可选文字），带状态高亮与
 * 长按 Toast 说明，点击热区大（40dp 高）。
 *
 * suppressKeyboardHide=true 时（联网开关）：在 Initial pass 报备本次手势
 * （keyboardSuppress.tapActive=true），容器的「点按收键盘」观察器据此跳过，
 * 点联网键不再收键盘；手势结束自动复位，不影响其他键。
 */
@Composable
private fun InputToolChip(
    icon: ImageVector,
    label: String? = null,
    contentDescription: String,
    active: Boolean = false,
    enabled: Boolean = true,
    hint: String? = null,
    suppressKeyboardHide: Boolean = false,
    onClick: () -> Unit
) {
    val hint = hint ?: contentDescription
    val context = LocalContext.current
            Row(
        Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(11.dp))
            .then(if (suppressKeyboardHide) Modifier.keyboardSuppressReport() else Modifier)
            .background(
                when {
                    !enabled -> Color.Transparent
                    active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
                }
            )
            .combinedClickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = { Toast.makeText(context, hint, Toast.LENGTH_SHORT).show() }
            )
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            Modifier.size(19.dp),
            tint = when {
                active -> MaterialTheme.colorScheme.primary
                enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            }
        )
        if (label != null) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 分支切换器（ChatGPT 式）：同一位置存在多个版本时显示 ‹ 2/3 ›，点击在分叉间切换。
 * wrap 内容宽度（可嵌在 End 对齐的操作行里，和复制按钮并排）。
 */
@Composable
private fun BranchSwitcher(
    position: Int,
    total: Int,
    enabled: Boolean = true,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev, enabled = enabled && position > 0, modifier = Modifier.keyboardSuppressReport().size(28.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一个版本",
                        modifier = Modifier.size(18.dp),
                        tint = if (position > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${position + 1}/$total",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
                IconButton(onClick = onNext, enabled = enabled && position < total - 1, modifier = Modifier.keyboardSuppressReport().size(28.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一个版本",
                        modifier = Modifier.size(18.dp),
                        tint = if (position < total - 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineMessageEditor(
    initialText: String,
    onCancel: () -> Unit,
    onSend: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth(0.92f)
    ) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 8,
                shape = RoundedCornerShape(14.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) { Text("取消") }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = { if (text.isNotBlank()) onSend(text) },
                    enabled = text.isNotBlank(),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
                ) { Text("发送") }
            }
        }
    }
}

/**
 * 气泡交互：单击 = onTap（用户消息进编辑 / 模型消息清选区）；
 * 长按交给内部 SelectionContainer 直接进入系统文本选择（复制局部）。
 * 另在 Initial pass 放一个不消费事件的长按观察者：子级选中消费事件后
 * Main pass 的长按检测会取消，只有 Initial 能可靠观测到「长按了」。
 */
@Composable
private fun Modifier.bubbleClickable(
    onTap: () -> Unit,
    onLongPressObserve: () -> Unit
): Modifier {
    // 无涟漪无按压反馈（干净点击）
    val interactionSource = remember { MutableInteractionSource() }
    // 回调经 rememberUpdatedState 传给 pointerInput(Unit)：长按触发后的重组
    // 不会重启手势检测（旧实现以 lambda 为 key，长按那次重组会中途重启检测器）。
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPressObserve by rememberUpdatedState(onLongPressObserve)
    return this
        .combinedClickable(
            onClick = { currentOnTap() },
            interactionSource = interactionSource,
            indication = null
        )
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var imeReported = false
                try {
                    var longPressed = false
                    var moved = false
                    // 阶段一：Initial pass 观测（不消费），判定是否纯长按。
                    // 关键：长按成立必须用 withTimeoutOrNull 判定（与系统选词的
                    // awaitLongPressOrCancellation 同机制），不能靠等下一个事件的时间戳
                    // —— 手指完全静止时，超时后到抬起前可能一个事件都不发（up 就是
                    // 第一个事件），等事件会把标志位置位拖到抬起之后，根观察器（祖先、
                    // Initial pass 先于本节点看到 up）那时仍走 clearFocus → 选区消失。
                    val movedBeforeTimeout = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: return@withTimeoutOrNull true
                            if (!change.pressed) return@withTimeoutOrNull true // 超时前抬起：普通点按
                            val dx = abs(change.position.x - down.position.x)
                            val dy = abs(change.position.y - down.position.y)
                            if (dx > viewConfiguration.touchSlop || dy > viewConfiguration.touchSlop) {
                                return@withTimeoutOrNull true // 超时前移出 slop：滚动/拖拽
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") true
                    }
                    if (movedBeforeTimeout == null) {
                        // 超时未被取消 → 长按成立（与系统选词同一时刻，无事件也成立）
                        longPressed = true
                        currentOnLongPressObserve() // 仅观测：不消费，选区由内部 SelectionContainer 自己接管
                        // imeOnly 报备必须在长按成立的那一刻置位（不能等抬起时）：
                        // 根观察器（祖先）在 Initial pass 先于本节点看到抬起，那时若
                        // imeOnly 为 false 会走 clearFocus → 焦点级联 → 选区容器失焦 →
                        // SelectionManager.onRelease → 选区消失。
                        keyboardSuppress.imeOnly = true
                        imeReported = true
                        // 长按成立后继续观测移动：拖出 slop = 拖选区/拖手柄，抬起交给选区系统
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dx = abs(change.position.x - down.position.x)
                            val dy = abs(change.position.y - down.position.y)
                            if (dx > viewConfiguration.touchSlop || dy > viewConfiguration.touchSlop) {
                                moved = true
                                break
                            }
                        }
                    }
                    // 阶段二：纯长按（未拖动）的抬起，在 Main pass 吞掉。
                    // 关键顺序：Main pass 是后代先于祖先 —— 选区系统（气泡内部）先处理完抬起
                    // 保留选区；随后本修饰符消费抬起，阻止 combinedClickable 与
                    // LazyColumn 的点按检测把它判成点击（onTap → clearTextSelection 会清选区）。
                    if (longPressed && !moved) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                        }
                    }
                } finally {
                    // 手势结束（抬起/取消/异常）一律复位，避免标志卡住导致后续手势不收焦点
                    if (imeReported) keyboardSuppress.imeOnly = false
                }
            }
        }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    streaming: Boolean = false,
    // 单击：用户消息进编辑，模型消息清选区；长按（观察）标记选区可能激活
    onTap: () -> Unit,
    onLongPressObserve: () -> Unit
) {
    // 系统提示/报错：居中小卡片样式，带图标与「复制错误信息」入口，
    // 与模型回复明显区分（不再像“模型回了两条”），也不在卡下另起一行复制钮。
    // 仍留在消息记录里：可回看、可复制，返回键/点空白可清选区。
    if (message.isError && message.fromUser == false) {
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .blockBringIntoView()
                    .bubbleClickable(onTap = onTap, onLongPressObserve = onLongPressObserve)
            ) {
                Row(
                    Modifier.padding(start = 11.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        message.text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(2.dp))
                    // 复制收进卡片右侧（垂直居中），不再孤零零挂在气泡下方
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable {
                                clipboard.setText(AnnotatedString(message.text))
                                Toast.makeText(context, "已复制错误信息", Toast.LENGTH_SHORT).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "复制错误信息",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
        return
    }
    if (message.fromUser) {
        // 用户消息：右对齐窄气泡（不占满宽度，保留对话感）
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom
        ) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .blockBringIntoView()
                    .bubbleClickable(onTap = onTap, onLongPressObserve = onLongPressObserve)
            ) {
                BubbleContent(message, streaming, onTap)
            }
        }
    } else {
        // 模型消息：头像单独一行，气泡在头像下方铺满整行 —— 长代码块/表格
        // 可用到整行宽度（旧布局头像常驻左侧，挤掉 38dp 且代码块/表格被迫换行）
        Column(Modifier.fillMaxWidth()) {
            Surface(
                Modifier.size(30.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // 气泡宽度自适应内容：短回复（如「你好！」）不再撑满整行；
            // 含代码块/表格/多行长内容时才铺满（代码块需要整行宽度横向滚动）。
            // 等待首 token 的占位阶段：收成窄胶囊（宽度包住打字点）
            val placeholderPhase = streaming && message.text.isBlank() && message.reasoning.isBlank()
            val needsFullWidth = !placeholderPhase && (
                message.text.contains("```") ||
                    message.text.contains("\n|") || message.text.trimStart().startsWith("|") ||
                    message.text.length > 160
                )
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .then(
                        when {
                            placeholderPhase -> Modifier.widthIn(min = 64.dp)
                            needsFullWidth -> Modifier.fillMaxWidth()
                            else -> Modifier
                        }
                    )
                    .blockBringIntoView()
                    .bubbleClickable(onTap = onTap, onLongPressObserve = onLongPressObserve)
            ) {
                BubbleContent(message, streaming, onTap)
            }
        }
    }
}

/** 气泡内容（用户/模型共用）：思考块、打字点、正文、流式光标 */
@Composable
private fun BubbleContent(
    message: ChatMessage,
    streaming: Boolean,
    onTap: () -> Unit
) {
    Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current
        // 思考过程（可折叠）：思考阶段自动展开实时显示，正文开始后自动收起成一行摘要
        if (message.reasoning.isNotBlank()) {
            ReasoningBlock(message = message, streaming = streaming)
            Spacer(Modifier.height(6.dp))
        }
        if (streaming && message.text.isBlank() && message.reasoning.isBlank()) {
            // 等待首个 token：三个跳动的小点（思考增量到达后切换为思考区块）
            TypingDots()
        } else {
            // 所有消息常态可长按选中（复制局部）
            val content: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.Bottom) {
                    val textColor = when {
                        message.isError -> MaterialTheme.colorScheme.onErrorContainer
                        message.fromUser -> Color.White
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    if (message.fromUser || message.isError) {
                        Text(
                            message.text,
                            color = textColor,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                    } else {
                        // 助手消息用 Markdown 渲染（代码块/表格/加粗/标题/列表…）；
                        // weight(1f, fill=false)：非流式时不强制铺满，MarkdownText 内容自适应宽度；
                        // 代码块标题栏的复制按钮回调在这里接剪贴板
                        MarkdownText(
                            message.text,
                            color = textColor,
                            modifier = Modifier.weight(1f, fill = false),
                            onCopyCode = { code ->
                                clipboard.setText(AnnotatedString(code))
                                Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
            // 所有消息常态可长按选中（复制局部）；取消选区走焦点级联（见 clearTextSelection），
            // 不再重建容器。LazyColumn 只组合可见项，屏外气泡不注册选区，无滚动性能负担。
            SelectionContainer { content() }
            if (streaming) {
                // 独立 composable：无限动画只在自身范围内重组，不牵连整个气泡
                StreamingCursor()
            }
        }
    }
}

/**
 * 附件缩略图（已发送附件条用）：图片直接显示（点击全屏查看）。
 */
@Composable
private fun AttachmentThumbnail(
    attachment: ChatAttachment,
    onImageClick: (ChatAttachment) -> Unit
) {
    val context = LocalContext.current
    coil.compose.AsyncImage(
        model = coil.request.ImageRequest.Builder(context)
            .data(attachment.uri)
            .size(256) // 降采样：72dp 预览用不到原图，多图不再解码超时/内存抖动
            .build(),
        contentDescription = attachment.name,
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onImageClick(attachment) },
        contentScale = androidx.compose.ui.layout.ContentScale.Crop
    )
}

/**
 * 用户消息下方的附件展示条：单行从右往左排（第一条贴齐右侧，与用户气泡同向），
 * 超出屏宽才横向滑动；图片 64dp 缩略图、音频/文档为信息卡（图标+文件名+格式）。
 * 已发送为只读（点图片可全屏查看）。
 */
@Composable
private fun MessageAttachmentsRow(
    attachments: List<ChatAttachment>,
    onImageClick: (ChatAttachment) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    // 从右往左排：列表反转后渲染（第一条在右端、贴齐上方气泡），新附件向左累加；
    // 内容超出屏宽时自动滚到右端（第一条附件在可视区内，紧邻气泡正下方）。
    // 之前只读一次 maxValue（常为 0）且用 scrollTo 会打断手势；改为内容变宽后
    // 快照流观察到并轻推到右端，仍在滚动/拖动时不打扰
    LaunchedEffect(attachments) {
        snapshotFlow { scrollState.maxValue }.collectLatest { max ->
            if (max > 0 && !scrollState.isScrollInProgress) {
                delay(50)
                scrollState.scrollTo(max)
            }
        }
    }
    Row(
        Modifier
            // 必须铺满整行：Arrangement.End 才能把附件推到右端（从右往左排）
            .fillMaxWidth()
            // 溢出才挂 horizontalScroll，5 张以内必然不溢出（尺寸有上限）
            .then(if (attachments.size > 5) Modifier.horizontalScroll(scrollState) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.Bottom
    ) {
        attachments.asReversed().forEach { attachment ->
            if (remember(attachment.uri, attachment.name) { isImageAttachment(context, attachment) }) {
                AttachmentThumbnail(
                    attachment = attachment,
                    onImageClick = onImageClick
                )
            } else {
                val ext = remember(attachment.uri, attachment.name) { attachmentExtLabel(context, attachment) }
                val isAudio = ext in AUDIO_EXT_SET
                // 非图片信息卡：恢复原 Surface 轻透底色
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    modifier = Modifier.height(64.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Checklist,
                            contentDescription = if (isAudio) "音频附件" else "文档附件",
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(7.dp))
                        Column {
                            Text(
                                attachment.name,
                                maxLines = 1,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp)
                            )
                            Text(
                                if (isAudio) "音频 · $ext" else ext,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 流式光标（参考 Telegram / ChatGPT 光标）：亮度呼吸而非硬性灭亮 ——
 * 1→0.25 缓动往返，光标「常在」不消失，观感更稳。
 * 独立成组件，无限动画不牵连气泡其余部分重组；逐帧只更新 layer。
 */
@Composable
private fun StreamingCursor() {
    val alpha by androidx.compose.animation.core.rememberInfiniteTransition(label = "cursor")
        .animateFloat(
            initialValue = 1f, targetValue = 0.25f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(530, easing = FastOutSlowInEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "cursorAlpha"
        )
    Box(
        Modifier
            .padding(start = 2.dp)
            .width(3.dp)
            .height(17.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.5.dp))
            // 逐帧只更新 layer，不再每帧重组本组件（读 alpha 状态延迟到绘制阶段）
            .graphicsLayer { this.alpha = alpha }
    )
}

/**
 * 思考过程区块（默认折叠）：摘要行显示「思考中…」/「已思考 N 字」，点击整块开合。
 * 思考期间不自动展开，只实时更新字数，避免打扰阅读。
 */
@Composable
private fun ReasoningBlock(message: ChatMessage, streaming: Boolean) {
    val thinking = streaming && message.text.isBlank()
    var expanded by remember(message.id) { mutableStateOf(false) }
    Column(
        Modifier
            // 跟随气泡宽度（短回复气泡不铺满整行时思考块也不强制铺满）
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // 箭头旋转动画值（只在展开状态翻转时重组一次，不再 0°/180° 硬切）
        val chevronRotation by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            label = "chevronRotation"
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                if (thinking) "思考中… ${message.reasoning.length} 字" else "已思考 ${message.reasoning.length} 字",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(start = 5.dp)
                    .weight(1f)
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = chevronRotation },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 展开收起：纵向展开/收起 + 淡入淡出（ChatGPT「已思考」折叠区同款）。
        // 旧实现 if(expanded) 硬切：内容突然出现/消失带得整个气泡高度跳变
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(200, easing = FastOutSlowInEasing)) + fadeOut(tween(140))
        ) {
            // 思考内容同样走 Markdown 解析：模型思考里常带 **/##/列表等标记，
            // 纯文本渲染会满屏符号；流式期间 remember+LruCache 已控制开销
            val clipboard = LocalClipboardManager.current
            val context = LocalContext.current
            MarkdownText(
                text = message.reasoning,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onCopyCode = { code ->
                    clipboard.setText(AnnotatedString(code))
                    Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

/**
 * 全屏图片查看器：单击关闭、双击缩放复位/放大、双指捏合缩放 + 拖动平移。
 * 用全屏 Dialog（黑底），底部显示文件名。
 */
@Composable
private fun ImageViewerDialog(
    attachment: ChatAttachment,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var scale by remember(attachment.uri) { mutableStateOf(1f) }
        var offset by remember(attachment.uri) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val scope = rememberCoroutineScope()
        // 进行中的双击缩放动画：捏合开始时取消，避免两路写入同一状态互相拉扯
        var scaleAnimJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        val transformState = androidx.compose.foundation.gestures.rememberTransformableState { zoomChange, panChange, _ ->
            scaleAnimJob?.cancel()
            scale = (scale * zoomChange).coerceIn(1f, 6f)
            offset = if (scale > 1f) offset + panChange else androidx.compose.ui.geometry.Offset.Zero
        }
        // 双击缩放：Google Photos 式补间（220ms FastOutSlowIn），不再硬跳变；
        // 双指捏合仍直接跟手（回调先取消本动画，无延迟）
        fun animateDoubleTapZoom() {
            val startScale = scale
            val startOffset = offset
            val targetScale = if (startScale > 1f) 1f else 2.5f
            val targetOffset = if (targetScale == 1f) androidx.compose.ui.geometry.Offset.Zero else startOffset
            scaleAnimJob = scope.launch {
                animate(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = tween(220, easing = FastOutSlowInEasing)
                ) { progress, _ ->
                    scale = lerp(startScale, targetScale, progress)
                    offset = androidx.compose.ui.geometry.Offset(
                        lerp(startOffset.x, targetOffset.x, progress),
                        lerp(startOffset.y, targetOffset.y, progress)
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = { animateDoubleTapZoom() }
                    )
                }
                .transformable(transformState)
        ) {
            coil.compose.AsyncImage(
                model = attachment.uri,
                contentDescription = attachment.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
            Text(
                attachment.name,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
            )
        }
    }
}

/**
 * 等待首 token 的打字点（参考 ChatGPT / iMessage 输入指示器）：
 * 三个点按 1/3 周期相位错开的正弦上下浮动 + 呼吸，形成波浪感。
 * 全部动画值在 graphicsLayer 内读取，逐帧只更新 layer 不触发重组。
 */
@Composable
private fun TypingDots() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "typingPhase"
    )
    Row(modifier = Modifier.padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        // 相位错开 2π/3：正弦上下浮动；叠加 0.55–1 呼吸透明度
                        val wave = sin(phase - i * (2 * PI.toFloat() / 3f))
                        translationY = -3.dp.toPx() * wave
                        alpha = lerp(0.55f, 1f, (wave + 1f) / 2f)
                    }
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

@Composable
private fun ModelsScreen(
    modifier: Modifier,
    state: AgentState
) {
    val provider = state.currentProvider
    val scope = rememberCoroutineScope()
    // 拉取状态单一来源：AgentState 的快照 map（可观察，切 Tab 后 movableContent 重建时仍在）。
    // 之前这里还有一份本地 remember 副本，与 map 双向同步，容易漂移。
    val fetchedModels = state.modelsFetched[provider?.id]
    val loading = state.modelsLoading[provider?.id] ?: false
    // 手动输入面板的展开态与草稿单一来源：直接委托到 AgentState 的属性
    // （此前本地 remember + 全局 map 双份状态，双向同步容易漂移）
    var showManual by state::modelsShowManual
    var manualModel by state::modelsManualModel

    fun refresh() {
        val p = provider ?: return
        if (p.baseUrl.isBlank() || p.apiKey.isBlank()) return
        scope.launch {
            state.modelsLoading = state.modelsLoading + (p.id to true)
            val models = ApiClient.fetchModels(p)
            // 拉取失败（null）不写入：保留空缺让 UI 继续走预设模型回退，
            // 之前把 emptyList() 写进去会让「暂无模型列表」卡住到下次切换服务商
            if (models != null) state.modelsFetched = state.modelsFetched + (p.id to models)
            state.modelsLoading = state.modelsLoading + (p.id to false)
        }
    }

    LaunchedEffect(provider?.id, provider?.apiKey) {
        if (provider != null && provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank()) {
            if (fetchedModels == null) refresh()
        }
    }

    if (provider == null) {
        // 未配置服务商：引导去设置，不暴露任何复杂操作
        Column(
            modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Outlined.Memory, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("还没有配置服务商", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                "在设置里添加一个服务商后，模型列表会自动同步过来",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Button(
                onClick = { state.selectedTab = MainTab.SETTINGS },
                modifier = Modifier.padding(top = 16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("去设置")
            }
        }
        return
    }

    val preset by remember(provider.baseUrl) {
        mutableStateOf(PROVIDER_PRESETS.firstOrNull { it.baseUrl == provider.baseUrl })
    }
    val modelIds = (fetchedModels ?: preset?.fallbackModels)
        ?.filter { it.isNotBlank() }
        ?: listOf(provider.model).filter { it.isNotBlank() }
    val selectedModel = provider.model
    var searchQuery by remember { mutableStateOf(state.modelsSearchQuery) }
    LaunchedEffect(Unit) {
        snapshotFlow { searchQuery }.collect { state.modelsSearchQuery = it }
    }
    val displayedModels = remember(modelIds, searchQuery) {
        if (searchQuery.isBlank()) modelIds
        else modelIds.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
    }

    // 布局：让位 Spacer 独立放列表之后（与聊天页同款）。
    // bottomInputClearance 直接挂 LazyColumn 会与外层 fillMaxSize 的
    // minHeight=maxHeight 约束冲突，键盘弹出或内容不足一屏时列表整体下移。
    Column(modifier) {
    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索模型…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(10.dp))
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(provider.name.ifEmpty { "未命名服务商" }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        if (loading) "正在同步模型列表…" else (fetchedModels?.let { "已同步 ${it.size} 个模型" } ?: "使用默认模型列表（连接后自动同步）"),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { refresh() }, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新模型列表")
                }
            }
        }
        items(displayedModels) { name ->
            ModelCard(
                name = name,
                selected = name == selectedModel,
                onClick = { state.updateModel(name) }
            )
        }
        if (displayedModels.isEmpty()) {
            item {
                Text(
                    if (loading) "正在同步模型列表…" else "暂无模型列表，请在下方手动输入模型名",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
        if (displayedModels.isEmpty() && searchQuery.isNotBlank()) {
            item {
                Text(
                    "没有匹配「${searchQuery.trim()}」的模型",
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        item {
            if (showManual) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("模型名", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            // 关闭手动输入：之前展开后没有任何退出的途径
                            IconButton(onClick = { showManual = false; manualModel = "" }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "收起", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = manualModel,
                                onValueChange = { manualModel = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("例如 kimi-k2-0711-preview") },
                                singleLine = true
                            )
                            TextButton(
                                onClick = {
                                    if (manualModel.isNotBlank()) {
                                        state.updateModel(manualModel.trim())
                                        manualModel = ""
                                        showManual = false
                                    }
                                },
                                modifier = Modifier.padding(start = 8.dp)
                            ) { Text("使用") }
                        }
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { showManual = true }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("  手动输入模型名", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    Spacer(Modifier.bottomInputClearance())
    }
}

@Composable
private fun ModelCard(name: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = if (selected) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                name,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    modifier: Modifier,
    state: AgentState
) {
    var editing by remember { mutableStateOf<Provider?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    // 服务商列表收进底部弹层（抽屉式）：设置页只留当前服务商卡片，不再被 N 张卡挤满
    var showProviderSheet by remember { mutableStateOf(false) }
    // 长按删除的目标（选择服务商弹层内长按 = 删除确认）
    var deleteProviderTarget by remember { mutableStateOf<Provider?>(null) }

    Column(modifier) {
    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
 ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SettingSectionTitle("服务商")
                Spacer(Modifier.weight(1f))
                AddProviderChip(onClick = { showAdd = true })
            }
            Text(
                "添加后点击卡片即可一键切换，配置只保存在本机",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
        }
        if (state.providers.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { showAdd = true }
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Outlined.Memory, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Text("还没有服务商", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("添加一个后就能开始对话", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        } else {
            // 设置页只显示当前服务商；切换/管理全部服务商走底部弹层
            state.currentProvider?.let { currentProvider ->
                item(key = currentProvider.id) {
                    ProviderCard(
                        provider = currentProvider,
                        selected = true,
                        onClick = { showProviderSheet = true },
                        onEdit = { editing = currentProvider }
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (state.providers.size > 1) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { showProviderSheet = true }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Memory, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "全部 ${state.providers.size} 个服务商",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            SettingSectionTitle("对话")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("思考等级", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "控制模型回答前的思考深度，「默认」由模型自行决定；不支持的模型会自动忽略",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    // Mihon 式分段选择：圆角容器内放选项，选中项胶囊高亮
                    SegmentedControl(
                        options = REASONING_LEVELS,
                        selected = state.reasoningEffort,
                        onSelect = { state.changeReasoningEffort(it) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        item {
            SettingSectionTitle("外观")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("主题", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    // 主题预览卡（Mihon 风格）：每张卡内是迷你页面模型（顶栏/气泡/底栏）
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThemePreviewCard("跟随系统", ThemeChoice.SYSTEM, state.themeChoice, darkPreview = isSystemInDarkTheme()) { state.themeChoice = it }
                        ThemePreviewCard("亮色", ThemeChoice.LIGHT, state.themeChoice, darkPreview = false) { state.themeChoice = it }
                        ThemePreviewCard("暗色", ThemeChoice.DARK, state.themeChoice, darkPreview = true) { state.themeChoice = it }
                    }
                }
            }
        }
    }
    Spacer(Modifier.bottomInputClearance())
    }

    if (showAdd) {
        ProviderEditDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { state.upsertProvider(it); showAdd = false }
        )
    }
    editing?.let { editingProvider ->
        ProviderEditDialog(
            initial = editingProvider,
            onDismiss = { editing = null },
            onSave = { state.upsertProvider(it); editing = null }
        )
    }
    deleteProviderTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteProviderTarget = null },
            title = { Text("删除服务商") },
            text = { Text("确定删除「${target.name.ifEmpty { "未命名" }}」吗？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    state.removeProvider(target)
                    deleteProviderTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteProviderTarget = null }) { Text("取消") }
            }
        )
    }
    if (showProviderSheet) {
        // 抽屉式服务商列表：用 Dialog 实现（无 BottomSheet 的物理回弹/惯性误关），
        // 底部圆角卡片外观，列表再长也只在内部滚动
        Dialog(
            onDismissRequest = { showProviderSheet = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    // 点卡片以外任意位置（半透明遮罩区）关闭
                    .pointerInput(Unit) {
                        detectTapGestures { showProviderSheet = false }
                    }
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // 消费卡片区内的点击，避免冒泡到遮罩误关
                        .pointerInput(Unit) {
                            detectTapGestures { }
                        },
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .navigationBarsPadding()
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("选择服务商", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(
                                    "长按服务商可删除",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            AddProviderChip(onClick = { showAdd = true })
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(state.providers, key = { it.id }) { provider ->
                                val selected = provider.id == state.currentProviderId
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                        .combinedClickable(
                                            onClick = {
                                                state.selectProvider(provider)
                                                showProviderSheet = false
                                            },
                                            // 长按 = 删除确认（编辑弹层里不再放删除按钮，避免误触）
                                            onLongClick = { deleteProviderTarget = provider }
                                        )
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            provider.name.ifEmpty { "未命名" },
                                            fontSize = 14.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            provider.baseUrl.removePrefix("https://").removePrefix("http://"),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            provider.model,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (selected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "当前服务商",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp)
                                        )
                                    }
                                    IconButton(onClick = { editing = provider }) {
                                        Icon(Icons.Default.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「+ 添加服务商」入口：圆角胶囊 + 淡色主色调背景 + 描边。
 * 比纯文字 TextButton 更像按钮，用户一眼能认出是可点击的添加入口。
 */
@Composable
private fun AddProviderChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)), shape)
            .clickable(onClick = onClick)
            .padding(start = 11.dp, end = 13.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "添加服务商",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ProviderCard(
    provider: Provider,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = if (selected) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    provider.name.ifEmpty { "未命名" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    provider.baseUrl.removePrefix("https://").removePrefix("http://"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(provider.model, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "当前服务商", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProviderEditDialog(
    initial: Provider?,
    onDismiss: () -> Unit,
    onSave: (Provider) -> Unit
) {
    val isNew = initial == null
    var presetLabel by remember {
        mutableStateOf(
            initial?.let { p -> PROVIDER_PRESETS.firstOrNull { it.baseUrl == p.baseUrl }?.label }
                ?: PROVIDER_PRESETS[0].label
        )
    }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun applyPreset(label: String) {
        presetLabel = label
        if (name.isBlank() || PROVIDER_PRESETS.any { it.name == name }) {
            PROVIDER_PRESETS.firstOrNull { it.label == label }?.let { name = it.name }
        }
        // Base URL 不自动回填：输入框只展示示例（placeholder），
        // 留空保存时自动采用所选服务商的官方地址，用户只需要填 API Key
    }

    /** 所选预设的官方地址（自定义预设为空串） */
    val selectedPreset = PROVIDER_PRESETS.firstOrNull { it.label == presetLabel }

    fun buildProvider() = Provider(
        id = initial?.id ?: java.util.UUID.randomUUID().toString(),
        name = name.ifBlank { presetLabel },
        baseUrl = baseUrl.trim().ifBlank {
            // 新增时留空 = 采用预设官方地址；自定义预设/编辑留空则保持空（由下方校验拦截）
            if (isNew) selectedPreset?.baseUrl.orEmpty() else initial?.baseUrl.orEmpty()
        },
        apiKey = apiKey.trim(),
        model = initial?.model
            ?: PROVIDER_PRESETS.firstOrNull { it.label == presetLabel }?.defaultModel?.takeIf { it.isNotBlank() }
            ?: ""
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加服务商" else "编辑服务商") },
        text = {
            Column {
                if (isNew) {
                    Text("快速开始", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    // 自动换行的 chip 流：预设增减都不怕挤爆一行（修「自定义」被挤出/截断）
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        PROVIDER_PRESETS.forEach { preset ->
                            AssistChip(
                                onClick = { applyPreset(preset.label) },
                                label = { Text(preset.label, fontSize = 11.sp) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (presetLabel == preset.label) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    // 只作为示例展示，不预填；留空保存时自动采用所选服务商的官方地址
                    placeholder = {
                        Text(selectedPreset?.baseUrl?.takeIf { it.isNotBlank() } ?: "https://api.example.com/v1")
                    },
                    supportingText = {
                        // 只保留一条提示，不展示具体地址
                        if (selectedPreset?.baseUrl?.isNotBlank() == true) {
                            Text(
                                "不填则自动使用官方地址",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton({ showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showKey) "隐藏 API Key" else "显示 API Key"
                            )
                        }
                    }
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "只保存在本机",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val p = buildProvider()
                            if (p.baseUrl.isBlank() || p.apiKey.isBlank()) {
                                testResult =
                                    if (p.baseUrl.isBlank()) "请填写 Base URL（或选择一个服务商预设后留空）"
                                    else "请填写 API Key"
                            } else {
                                scope.launch {
                                    testing = true
                                    testResult = null
                                    testResult = when (val models = ApiClient.fetchModels(p)) {
                                        null ->
                                            // /models 不可用时（如 DeepSeek 的 /anthropic 网关），
                                            // 改发一个极小对话请求验证连通性，不误报「连接失败」
                                            when (val pingErr = ApiClient.ping(p)) {
                                                null -> "连接成功（未开放模型列表，可手动输入模型名）"
                                                else -> "连接失败：$pingErr"
                                            }
                                        else -> "连接成功，" + models.size + " 个模型可用"
                                    }
                                    testing = false
                                }
                            }
                        },
                        enabled = !testing
                    ) {
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Text("测试连接", fontSize = 13.sp)
                        }
                    }
                }
                testResult?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = if (it.startsWith("连接成功")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = buildProvider()
                    if (p.baseUrl.isBlank() || p.apiKey.isBlank()) {
                        testResult =
                            if (p.baseUrl.isBlank()) "请填写 Base URL（或选择一个服务商预设后留空）"
                            else "请填写 API Key"
                        return@TextButton
                    }
                    if (p.model.isBlank()) {
                        // 自定义服务商未指定默认模型：自动拉取模型列表选第一个，失败则留空由用户在模型页选择
                        testing = true
                        scope.launch {
                            val models = ApiClient.fetchModels(p)
                            testing = false
                            onSave(if (models.isNullOrEmpty()) p else p.copy(model = models.first()))
                        }
                    } else {
                        onSave(p)
                    }
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SettingSectionTitle(text: String) {
    Text(text, Modifier.padding(top = 18.dp, bottom = 8.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
/**
 * Mihon 式分段选择器：圆角容器 + 等宽选项，选中项用 primary 胶囊高亮。
 */
@Composable
private fun SegmentedControl(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
/**
 * 主题预览卡（Mihon 式）：卡内画一个迷你页面（状态栏/顶栏/两条消息/底部栏），
 * 选中态用 primary 描边 + 勾选角标，直观看到每种模式的实际观感。
 */
@Composable
private fun RowScope.ThemePreviewCard(
    label: String,
    choice: ThemeChoice,
    selected: ThemeChoice,
    darkPreview: Boolean,
    onSelected: (ThemeChoice) -> Unit
) {
    // 预览用的固定配色（不跟随动态色，保证每个选项看起来稳定）
    val bg = if (darkPreview) Color(0xFF141414) else Color(0xFFF7F7F7)
    val bar = if (darkPreview) Color(0xFF232323) else Color(0xFFFDFDFD)
    val bubbleOut = if (darkPreview) Color(0xFF3A3A3A) else Color(0xFFE8E8E8)
    val accent = if (darkPreview) Color(0xFF9EC6FF) else Color(0xFF2E6BE6)
    val text = if (darkPreview) Color(0xFFBBBBBB) else Color(0xFF666666)
    val isSelected = choice == selected

    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onSelected(choice) }
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.62f)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp)
                )
        ) {
            Column(Modifier.fillMaxSize().padding(6.dp)) {
                // 迷你顶栏
                Row(
                    Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(3.dp)).background(bar),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.padding(start = 2.dp).size(4.dp).background(text, CircleShape))
                    Box(Modifier.padding(start = 3.dp).size(width = 22.dp, height = 3.dp).clip(RoundedCornerShape(1.5.dp)).background(text.copy(alpha = 0.5f)))
                }
                Spacer(Modifier.height(5.dp))
                // 两条消息气泡（收/发）
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(Modifier.size(width = 42.dp, height = 9.dp).clip(RoundedCornerShape(4.dp)).background(accent))
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(width = 52.dp, height = 9.dp).clip(RoundedCornerShape(4.dp)).background(bubbleOut))
                Spacer(Modifier.weight(1f))
                // 底部 Tab
                Row(
                    Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(3.dp)).background(bar),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Box(Modifier.size(4.dp).background(accent, CircleShape))
                    Box(Modifier.size(4.dp).background(text.copy(alpha = 0.6f), CircleShape))
                    Box(Modifier.size(4.dp).background(text.copy(alpha = 0.6f), CircleShape))
                }
            }
            if (isSelected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(14.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = "已选择", modifier = Modifier.size(10.dp), tint = Color.White)
                }
            }
        }
        Text(
            label,
            fontSize = 11.sp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
        )
    }
}
