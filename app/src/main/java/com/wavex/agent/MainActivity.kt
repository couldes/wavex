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

import com.wavex.agent.model.AgentConversation
import com.wavex.agent.model.ChatAttachment
import com.wavex.agent.model.ChatMessage
import com.wavex.agent.model.ChatRequestMessage
import com.wavex.agent.model.TREE_ROOT
import com.wavex.agent.ui.drawer.DrawerWidth
import com.wavex.agent.ui.MainTab
import com.wavex.agent.ui.ThemeChoice
import com.wavex.agent.ui.REASONING_LEVELS
import com.wavex.agent.ui.shared.attachmentDisplayName
import com.wavex.agent.ui.AgentApp

// 思考等级："" = 不传参数（跟随模型默认）；极低/低/中/高一一对应 reasoning_effort
// 的 minimal/low/medium/high（gpt-5 系全部档位），纯中文短标签，排版整齐

/**
 * 精确贴底：把列表滚到内容真正的末尾（末条消息底边 == 视口底边）。
 * scrollToItem 的 offset 语义对“末条比视口矮”的场景不可靠（实测会回填上方 item，
 * 停在离底约一屏处），所以用“测量剩余距离 → 滚动”的收敛式：
 * 末条不可见先顶对齐它，再按实际剩余像素补滚（scrollBy 会被内容边界自然鈄住）。
 */

internal class AgentState(store: ProviderStore, conversationStore: ConversationStore?) {
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

/** 共享附件的显示名：优先查询 DISPLAY_NAME（分享 URI 的 lastPathSegment 往往是数字 ID） */
internal fun sharedAttachmentName(context: android.content.Context, uri: android.net.Uri): String {
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
/** 展示名：camera 时间戳命名转成友好文案，其余截短到 18 字 */

/** 共享附件的显示名：优先查询 DISPLAY_NAME（分享 URI 的 lastPathSegment 往往是数字 ID） */
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

/**
 * 抽屉拖拽手势，对标 EhViewer 的 DrawerLayout：
 * - Initial 阶段抢先处理，一旦判定为横向主导手势就立即锁定拦截；
 * - 纵向先主导则本次手势彻底放弃，不干扰列表滚动；
 * - 阈值仅为系统 touchSlop，非常灵敏。
 * 注意：进度面板本身在跟随手指移动，不能用控件本地坐标算速度，
 * 这里用净位移 + 时间戳自己估算速度。
 */

/** 导出对话全文（分享/复制用）：标题 + 我/助手交替，附件只列名字，思考过程不导出 */

/** 右上角「⋯」菜单：底部弹层（同服务商选择器同款 Dialog 模式，无 BottomSheet 抖动） */

/**
 * 容器级「点按/滑动收起键盘」：Initial pass 观察。纯点按（无滚动/无长按）抬起时
 * 收起键盘；滑动越过 touchSlop（纵向主导）也**立即收起**（ChatGPT 式，每次手势一次）。
 *
 * 与逐处加 clickable 的区别：不用枚举所有可点控件，消息列表、代码块、表格、
 * 图片、附件条上点击都生效（它们没消费 down 时这里兜底）。
 * 不会误伤：按钮/气泡等子控件在 Main pass 消费 down/up（那是明确的子控件点击，
 * 不收键盘）；横向主导的滑动不收（抽屉拖拽有自己的收键盘+锁 IME 布局路径，
 * 不能抢在它前面收掉导致锁失效；代码块/表格横滚也不改输入意图），
 * 报备手势（输入胶囊/联网键）与选区激活照旧让路。
 * 报备通道见 keyboardSuppress：tapActive=完全不动（联网键/输入胶囊），
 * imeOnly=只收 IME 不清焦点（局部复制长按，清焦点会连选区一起清掉）。
 */
/** 联网键报备豁免：点击联网键时置位，容器观察器据此跳过收键盘（消费后自动复位） */

/**
 * 报备本次手势不收键盘：down 置位、up 复位，容器观察器（dismissKeyboardOnTap）
 * 据此跳过收键盘。用于联网键（切换开关不想收键盘）与整个输入胶囊
 * （点输入框时容器会先收后由焦点弹开 → 键盘闪一下，报备后不再闪）。
 */

/** 键盘当前是否可见（root insets 检测；view 未挂载时视为不可见） */

/**
 * 输入区待发附件悬浮层（ChatGPT 式）：悬浮在消息列表底部，**不独立占一条**。
 * 旧实现是独立横条：附件卡背后多出一块与页面同色的白带，白带连同附件一起
 * 把聊天区顶上去一截（用户观感："白块也挡住气泡"）。悬浮后只有附件卡自身
 * 挡住其覆盖的气泡区域，周围气泡照常可见。
 * 图片 = 64dp 圆角缩略图；非图片 = 轻透信息卡（图标+文件名+格式）；
 * ✕ 删除无涟漪无动效（点击即删）。
 */

/** 按附件 id 从待发列表里删除（同一文件可添加多次，id 保证删哪个删哪个） */

/**
 * DeepSeek 式功能胶囊：圆角矩形底座 + 图标（可选文字），带状态高亮与
 * 长按 Toast 说明，点击热区大（40dp 高）。
 *
 * suppressKeyboardHide=true 时（联网开关）：在 Initial pass 报备本次手势
 * （keyboardSuppress.tapActive=true），容器的「点按收键盘」观察器据此跳过，
 * 点联网键不再收键盘；手势结束自动复位，不影响其他键。
 */

/**
 * 分支切换器（ChatGPT 式）：同一位置存在多个版本时显示 ‹ 2/3 ›，点击在分叉间切换。
 * wrap 内容宽度（可嵌在 End 对齐的操作行里，和复制按钮并排）。
 */

/**
 * 气泡交互：单击 = onTap（用户消息进编辑 / 模型消息清选区）；
 * 长按交给内部 SelectionContainer 直接进入系统文本选择（复制局部）。
 * 另在 Initial pass 放一个不消费事件的长按观察者：子级选中消费事件后
 * Main pass 的长按检测会取消，只有 Initial 能可靠观测到「长按了」。
 */

/** 气泡内容（用户/模型共用）：思考块、打字点、正文、流式光标 */

/**
 * 附件缩略图（已发送附件条用）：图片直接显示（点击全屏查看）。
 */

/**
 * 用户消息下方的附件展示条：单行从右往左排（第一条贴齐右侧，与用户气泡同向），
 * 超出屏宽才横向滑动；图片 64dp 缩略图、音频/文档为信息卡（图标+文件名+格式）。
 * 已发送为只读（点图片可全屏查看）。
 */

/**
 * 流式光标（参考 Telegram / ChatGPT 光标）：亮度呼吸而非硬性灭亮 ——
 * 1→0.25 缓动往返，光标「常在」不消失，观感更稳。
 * 独立成组件，无限动画不牵连气泡其余部分重组；逐帧只更新 layer。
 */

/**
 * 思考过程区块（默认折叠）：摘要行显示「思考中…」/「已思考 N 字」，点击整块开合。
 * 思考期间不自动展开，只实时更新字数，避免打扰阅读。
 */

/**
 * 全屏图片查看器：单击关闭、双击缩放复位/放大、双指捏合缩放 + 拖动平移。
 * 用全屏 Dialog（黑底），底部显示文件名。
 */

/**
 * 等待首 token 的打字点（参考 ChatGPT / iMessage 输入指示器）：
 * 三个点按 1/3 周期相位错开的正弦上下浮动 + 呼吸，形成波浪感。
 * 全部动画值在 graphicsLayer 内读取，逐帧只更新 layer 不触发重组。
 */

/**
 * 「+ 添加服务商」入口：圆角胶囊 + 淡色主色调背景 + 描边。
 * 比纯文字 TextButton 更像按钮，用户一眼能认出是可点击的添加入口。
 */

/**
 * Mihon 式分段选择器：圆角容器 + 等宽选项，选中项用 primary 胶囊高亮。
 */
/**
 * 主题预览卡（Mihon 式）：卡内画一个迷你页面（状态栏/顶栏/两条消息/底部栏），
 * 选中态用 primary 描边 + 勾选角标，直观看到每种模式的实际观感。
 */
