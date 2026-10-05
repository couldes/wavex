package com.wavex.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wavex.agent.model.AgentConversation
import com.wavex.agent.model.ChatAttachment
import com.wavex.agent.model.ChatMessage
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.input.pointer.PointerEventPass
import com.wavex.agent.state.WavexViewModel
import com.wavex.agent.sharedAttachmentName
import com.wavex.agent.ui.bottomInputClearance
import com.wavex.agent.ui.chat.rememberCameraLauncher
import com.wavex.agent.ui.chat.InputToolChip
import com.wavex.agent.ui.chat.PendingAttachmentsOverlay
import com.wavex.agent.ui.chat.removePendingAttachment
import com.wavex.agent.ui.chat.MessageBubble
import com.wavex.agent.ui.chat.MessageAttachmentsRow
import com.wavex.agent.ui.chat.InlineMessageEditor
import com.wavex.agent.ui.shared.ImageViewerDialog
import com.wavex.agent.ui.shared.StaggeredEntrance
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.text.input.ImeAction
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import com.wavex.agent.data.AttachmentLoader
import com.wavex.agent.data.AttachmentSaver

/**
 * 精确贴底：把列表滚到内容真正的末尾（末条消息底边 == 视口底边）。
 * scrollToItem 的 offset 语义对“末条比视口矮”的场景不可靠（实测会回填上方 item，
 * 停在离底约一屏处），所以用“测量剩余距离 → 滚动”的收敛式：
 * 末条不可见先顶对齐它，再按实际剩余像素补滚（scrollBy 会被内容边界自然鈄住）。
 */
internal suspend fun LazyListState.snapToBottom(shouldContinue: () -> Boolean = { true }) {
    // Coalesce streaming updates to a frame. Layout updates are also observed by the caller:
    // a frame boundary alone does not guarantee that Compose has measured the new content.
    withFrameNanos { }
    repeat(3) {
        if (!shouldContinue()) return
        val info = layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        if (info.totalItemsCount == 0 || last == null) return
        if (last.index != info.totalItemsCount - 1) {
            scrollToItem(info.totalItemsCount - 1)
            return@repeat
        }
        val remaining = last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)
        if (remaining <= 2) return
        scroll { scrollBy(remaining.toFloat()) }
    }
}

private data class BottomLayoutSnapshot(
    val messageCount: Int,
    val lastMessageId: String?,
    val textLength: Int,
    val reasoningLength: Int,
    val attachmentCount: Int,
    val viewportBottom: Int,
    val viewportHeightPx: Int,
    val lastIndex: Int,
    val lastOffset: Int,
    val lastSize: Int,
    val totalItemsCount: Int,
    val atBottom: Boolean,
    val pinned: Boolean,
    val userDragging: Boolean,
    val selectionActive: Boolean,
    val scrolling: Boolean,
    val editingScroll: Boolean
)

internal fun androidx.compose.foundation.lazy.LazyListLayoutInfo.isAtBottom(tolerancePx: Float): Boolean {
    val last = visibleItemsInfo.lastOrNull() ?: return false
    return totalItemsCount > 0 &&
        last.index == totalItemsCount - 1 &&
        (last.offset + last.size).toFloat() <= viewportEndOffset - afterContentPadding + tolerancePx
}

/**
 * 「用户离底意图」的容差（[ChatScreen] 喂给 BottomFollowPolicy.onLayout 的 atBottom）。
 *
 * 不得随意调大：在密度 1.275 的真机上，一次 20dp 的有意小上划实际落在距底 16px。
 * 本值 = 10.2px，所以该划动被记为「已离开底部」；改成 16dp（= 20.4px）后同一划动落回
 * 「还在底部」区间，下一帧就被 snapToBottom 拽回 0.0px——正是本改动要修的原始 bug
 * （已实测）。BottomFollowInstrumentedTest 直接引用本常量钉住这条边界。
 *
 * 和 pinned（实际补滚容差 2px）不同：那是几何测量，本值是意图判定，两者不能合并。
 */
internal val FollowIntentToleranceDp = 8.dp

/**
 * 编辑落点的「算不算离开底部」容差。刻意比 [FollowIntentToleranceDp] 宽：那是几何事实
 * 而非用户意图，编辑一条本来就靠近底部的消息不应被当成跳进历史（否则「回到底部」
 * 按钮先亮后灭）。副作用：距底 [FollowIntentToleranceDp] ~ 一屏之间既不跟随也不出按钮（有意
 * 取舍，EditJumpInstrumentedTest 钉住了靠底不闪按钮这一头）。
 */
internal val EditJumpToleranceDp = 96.dp

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
internal object keyboardSuppress {
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
internal fun Modifier.keyboardSuppressReport(): Modifier = pointerInput(Unit) {
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

/** 键盘当前是否可见（root insets 检测；view 未挂载时视为不可见） */
internal fun isImeVisible(localView: android.view.View?): Boolean {
    val view = localView ?: return false
    return androidx.core.view.ViewCompat.getRootWindowInsets(view)
        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
}

internal fun hideKeyboard(localView: android.view.View?, imeOnly: Boolean = false): Boolean {
    // 先检测（隐藏后 insets 已更新，检测会恒为 false）
    val imeVisible = isImeVisible(localView)
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
 * 新消息到达识别：找出「尾部追加」的新消息，为它们触发与切换会话同款的
 * 入场动画（见 ChatScreen 内 arrivalTracker）。
 * 纯普通字段、不进快照：写入只在重组期由 ChatScreen 幂等更新，读取发生在同帧的
 * item 组合（Lazy 在 measure 阶段组合 item，晚于 body）与后续帧，不会引入重组循环。
 */
private class BubbleArrivalTracker {
    /** 上次结构快照（消息 id 序列）；流式正文增长不改 id，无需更新 */
    var prevIds: List<String> = emptyList()
    /** 尾部追加且尚未被 item 认领的消息 id → 批内序号（0 起）；序号×错峰间隔决定入场延迟 */
    var pending: MutableMap<String, Int> = mutableMapOf()

    /** 认领：返回批内序号（null = 已认领过或不属于本批新到达）；认领即移除，不会重播 */
    fun claim(id: String): Int? = pending.remove(id)
}

@Composable
internal fun BranchSwitcher(
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
internal fun ChatScreen(
    modifier: Modifier = Modifier,
    state: WavexViewModel,
    conversation: AgentConversation,
    imeSettling: () -> Boolean = { false }
) {
    val context = LocalContext.current
    val localView = androidx.compose.ui.platform.LocalView.current
    val clipboardManager = LocalClipboardManager.current
    val messages = conversation.messages
    // 本对话是否在生成：A 生成时切到 B，B 不再被全局锁（输入/发送/编辑均可用）
    val isGenerating = state.isGeneratingIn(conversation.id)
    // —— 新消息入场动效（与切换会话同款：淡入 + 轻微上浮） ——
    // 追踪「尾部追加」的新消息：发送的消息与回复占位到达时播一次入场。
    // 判定规则：列表增长且**前缀完全不变**才算尾部追加 ——
    // 切分支 ‹ › / 编辑重发会改写中段（rebuildFrom）→ 不弹；
    // 流式正文增长 id 序列不变 → 不弹；
    // 打开/切换会话时 tracker 重置为当前全量 → 不弹（入场由 StaggeredEntrance 负责）。
    val arrivalTracker = remember(conversation.id) {
        BubbleArrivalTracker().apply { prevIds = messages.map { it.id } }
    }
    // 结构变化才做 O(n) 对比：流式期间 size 与末位 id 都不变，直接跳过
    if (messages.size != arrivalTracker.prevIds.size ||
        messages.lastOrNull()?.id != arrivalTracker.prevIds.lastOrNull()
    ) {
        val ids = messages.map { it.id }
        arrivalTracker.pending =
            if (ids.size > arrivalTracker.prevIds.size &&
                ids.subList(0, arrivalTracker.prevIds.size) == arrivalTracker.prevIds
            ) {
                // 批内序号：同一帧追加的多条（用户消息+回复占位）按序错峰入场。
                // 存显式序号而非靠列表位置：先认领者移除后不影响后认领者的序号。
                mutableMapOf<String, Int>().apply {
                    ids.subList(arrivalTracker.prevIds.size, ids.size)
                        .forEachIndexed { i, id -> put(id, i) }
                }
            } else mutableMapOf()
        arrivalTracker.prevIds = ids
    }
    // key(conversation.id)：切换会话时重置，避免 A 会话的编辑行号落到 B 会话的同位置
    var editingIndex by remember(conversation.id) { mutableStateOf<Int?>(null) }
    // 首次冷启动不做消息级淡入：启动图退出后再让已有消息逐个出现会造成闪屏。
    // 只在已经显示过一个会话后切换到另一个会话时播放级联入场。
    var lastDisplayedConversationId by remember { mutableStateOf<String?>(null) }
    val entranceStart = remember(conversation.id) {
        if (lastDisplayedConversationId != null) android.os.SystemClock.elapsedRealtime() else null
    }
    val entranceActive = entranceStart?.let {
        android.os.SystemClock.elapsedRealtime() - it < 800L
    } == true
    LaunchedEffect(conversation.id) {
        lastDisplayedConversationId = conversation.id
    }
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
            attachments.add(ChatAttachment(uri.toString(), name))
        }
        rejected?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    // ---- 附件保存到本机：图片→相册（长按确认后），非图片→下载目录（卡片右下角下载图标） ----
    var pendingSave by remember { mutableStateOf<Pair<ChatAttachment, Boolean>?>(null) } //附件 to 是否存相册

    fun doSave(att: ChatAttachment, toGallery: Boolean) {
        scope.launch {
            val outcome = if (toGallery) AttachmentSaver.saveToGallery(context, att.uri, att.name)
            else AttachmentSaver.saveToDownloads(context, att.uri, att.name)
            val msg = when (outcome) {
                is AttachmentSaver.SaveOutcome.Saved ->
                    if (toGallery) "已保存到相册 ${AttachmentSaver.GALLERY_DIR}/${outcome.display}"
                    else "已保存到 ${AttachmentSaver.DOWNLOAD_DIR}/${outcome.display}"
                is AttachmentSaver.SaveOutcome.Failed -> "保存失败：${outcome.reason}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val pending = pendingSave
        pendingSave = null
        if (pending != null && grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true) {
            doSave(pending.first, pending.second)
        } else {
            Toast.makeText(context, "没有存储权限，无法保存", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestSave(att: ChatAttachment, toGallery: Boolean) {
        // API 29+（scoped storage）无需权限；≤28 需要 WRITE_EXTERNAL_STORAGE 运行时授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            doSave(att, toGallery)
        } else {
            pendingSave = att to toGallery
            storagePermissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
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
    // 放大编辑：正文超长时输入框右上角出现放大图标，点开全屏编辑器
    // （实时同步 input：取消只是收起不丢字，发送直接走主发送路径）
    var expandedEditOpen by remember { mutableStateOf(false) }
    // 长按图片 → 保存到相册确认框（用户点「保存」后走存储权限检查 → AttachmentSaver）
    var saveConfirm by remember { mutableStateOf<ChatAttachment?>(null) }
    // 拍照确认弹窗的待定照片（拍照返回 → 确认弹窗 → 加入附件）；提升到 ChatScreen 作用域
    val launchCamera = rememberCameraLauncher { uri ->
        attachments.add(ChatAttachment(uri.toString(), "camera_${System.currentTimeMillis()}.jpg"))
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
            // 纵向滑动（翻消息列表）同样收起键盘：越过 touchSlop 即收，见 dismissKeyboardOnTap。
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
        // 「用户离底意图」的容差：只有真正贴底才算还在底部，往上划一点点就是明确的阅读
        // 位置。实际补滚只容忍 2px（pinned），不能累积到 96dp 才追赶。
        val followIntentTolerancePx = with(LocalDensity.current) { FollowIntentToleranceDp.toPx() }
        // 编辑落点的「算不算离开底部」用宽容差：那是几何事实而非用户意图，编辑一条
        // 本来就靠近底部的消息不应被当成跳进历史（否则按钮先亮后灭）。
        val editJumpTolerancePx = with(LocalDensity.current) { EditJumpToleranceDp.toPx() }
        if (messages.isNotEmpty()) {
            val bottomPolicy = remember(conversation.id, listState) { BottomFollowPolicy() }
            var showReturnToBottom by remember(conversation.id, listState) { mutableStateOf(false) }
            var suppressBottomButton by remember(conversation.id, listState) { mutableStateOf(false) }
            val userDragActive = remember(conversation.id, listState) { mutableStateOf(false) }
            // 就地编辑跳转滚动进行中（声明在观察者之前，供 snapshotFlow 读取）
            var editScrollActive by remember(conversation.id, listState) { mutableStateOf(false) }

            // 只把真实的手指拖动视为“用户离开底部”。程序化 snap/键盘跟随也会改变
            // LazyListState，但不会改写用户意图。
            LaunchedEffect(conversation.id, listState) {
                listState.interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is DragInteraction.Start -> {
                            userDragActive.value = true
                            bottomPolicy.onUserScroll()
                            showReturnToBottom = bottomPolicy.showReturnToBottom
                        }
                        is DragInteraction.Stop, is DragInteraction.Cancel -> {
                            // The fling may still be running. Rejoin only after scrolling settles.
                            userDragActive.value = false
                        }
                    }
                }
            }

            // 同时观察内容版本和最后一项的测量结果。内容状态先变、布局后变时，第二个
            // 事件仍会触发纠偏，因此不会拿旧 layoutInfo 判断 nearBottom 后就停止跟随。
            LaunchedEffect(conversation.id, listState) {
                var previous: BottomLayoutSnapshot? = null
                snapshotFlow {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    val lastMessage = messages.lastOrNull()
                    val viewportBottom = info.viewportEndOffset - info.afterContentPadding
                    BottomLayoutSnapshot(
                        messageCount = messages.size,
                        lastMessageId = lastMessage?.id,
                        textLength = lastMessage?.text?.length ?: 0,
                        reasoningLength = lastMessage?.reasoning?.length ?: 0,
                        attachmentCount = lastMessage?.attachments?.size ?: 0,
                        viewportBottom = viewportBottom,
                        viewportHeightPx = info.viewportEndOffset - info.viewportStartOffset,
                        lastIndex = last?.index ?: -1,
                        lastOffset = last?.offset ?: 0,
                        lastSize = last?.size ?: 0,
                        totalItemsCount = info.totalItemsCount,
                        atBottom = info.isAtBottom(followIntentTolerancePx),
                        pinned = info.isAtBottom(2f),
                        userDragging = userDragActive.value,
                        selectionActive = selectionLikelyActive,
                        scrolling = listState.isScrollInProgress,
                        editingScroll = editScrollActive
                    )
                }
                    .conflate()
                    .collect { current ->
                        val before = previous
                        previous = current
                        // “回到底部”按钮的阈值 = 一整屏列表视口高；键盘逐帧改变视口，
                        // 所以每帧刷新，后续 onLayout/onContentChanged 的可见性判定都用新阈值。
                        bottomPolicy.returnButtonThresholdPx = current.viewportHeightPx.toFloat()
                        val contentChanged = before == null ||
                            current.messageCount != before.messageCount ||
                            current.lastMessageId != before.lastMessageId ||
                            current.textLength != before.textLength ||
                            current.reasoningLength != before.reasoningLength ||
                            current.attachmentCount != before.attachmentCount
                        val layoutChanged = before == null ||
                            current.viewportBottom != before.viewportBottom ||
                            current.lastIndex != before.lastIndex ||
                            current.lastOffset != before.lastOffset ||
                            current.lastSize != before.lastSize ||
                            current.totalItemsCount != before.totalItemsCount

                        if (current.totalItemsCount > 0 && !current.userDragging && !current.scrolling &&
                            !current.editingScroll
                        ) {
                            bottomPolicy.onLayout(atBottom = current.atBottom)
                        }
                        if (contentChanged) bottomPolicy.onContentChanged()
                        showReturnToBottom = bottomPolicy.showReturnToBottom

                        val interactionChanged = before != null && (
                            current.userDragging != before.userDragging ||
                                current.selectionActive != before.selectionActive ||
                                current.scrolling != before.scrolling
                        )
                        val needsCorrection = before != null &&
                            (contentChanged || layoutChanged || interactionChanged) &&
                            bottomPolicy.shouldFollowBottom &&
                            !current.pinned &&
                            !current.selectionActive &&
                            !current.userDragging &&
                            !current.scrolling &&
                            !current.editingScroll
                        if (needsCorrection) {
                            listState.snapToBottom {
                                bottomPolicy.shouldFollowBottom &&
                                    !selectionLikelyActive && !userDragActive.value
                            }
                        }
                    }
            }
            // 就地编辑时把正在编辑的消息滚到可视区，避免发送按钮被键盘挡住。
            // 整段滚动期间用 editingScroll 挂起布局观察者的贴底判定与纠偏：
            // 动画前后落点的「贴底(±96dp)」是几何事实而非用户意图——若不挡，
            // onLayout(atBottom=true) 会把跳转意图洗掉：按钮先亮后灭（闪一下），
            // needsCorrection 还会把列表拽回底部、和编辑滚动互相打架。
            // 意图断言放在动画结束后按真实落点分类：仍在贴底容差内（贴底消息、
            // 短尾巴）→ 不算离开底部，不出按钮、跟随照旧；真正滚离了底部 →
            // 明确跳进历史，按钮出现并保持（与手动翻页一致）。
            LaunchedEffect(editingIndex) {
                val ei = editingIndex
                if (ei != null && ei < messages.size) {
                    editScrollActive = true
                    try {
                        listState.animateScrollToItem(ei)
                        if (ei < messages.size - 1 &&
                            !listState.layoutInfo.isAtBottom(editJumpTolerancePx)
                        ) {
                            bottomPolicy.onJumpedAwayFromBottom()
                            showReturnToBottom = bottomPolicy.showReturnToBottom
                        }
                    } finally {
                        editScrollActive = false
                    }
                }
            }
            // 生成开始时自动滚到输出位置：不管用户当时在哪儿都带回底部，
            // 之后的流式跟随逻辑接手。（切到正在生成的会话也会触发：initial composition 同样跑）
            LaunchedEffect(conversation.id, isGenerating) {
                if (isGenerating && messages.isNotEmpty()) {
                    // Establish intent before waiting: a drag while preparing the placeholder
                    // must still be able to cancel the pending follow.
                    bottomPolicy.onReturnToBottom()
                    showReturnToBottom = bottomPolicy.showReturnToBottom
                    // 关键：等占位气泡（列表末尾的助手消息）真的出现再跳。
                    // 引擎协程是异步调度的：本 effect 启动时 messages 末尾可能还是用户消息，
                    // 直接跳会落在旧底部（看起来"没回底"）；totalItemsCount == 数据条数，
                    // 用它等是等不到的（永远立即满足）。
                    val sizeAtStart = messages.size
                    val placeholderReady = messages.lastOrNull()?.let { !it.fromUser } == true
                    if (!placeholderReady) {
                        snapshotFlow { messages.size }.first { it > sizeAtStart }
                    }
                    listState.snapToBottom {
                        bottomPolicy.shouldFollowBottom && !userDragActive.value && !selectionLikelyActive
                    }
                }
            }
            // “回到底部”悬浮键的显示完全由 BottomFollowPolicy 决定：距底净滚动超过
            // 一整屏阈值，且最近一次滚动朝回底部方向。不再用 derivedStateOf 的 96dp
            // 几何门槛——那正是“轻轻一划就出按钮”的来源；真实滚动增量经 nestedScroll
            // 逐帧喂给 policy，显示/隐藏翻转时才重组，滚动帧本身不触发本组合。
            val scrollIntentObserver = remember(conversation.id, listState, bottomPolicy) {
                object : NestedScrollConnection {
                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource
                    ): Offset {
                        // consumed.y > 0：手指往下滑（内容上移）= 翻历史；
                        // < 0：手指往上滑（内容下移）= 回底部。只有回底部方向的
                        // 滚动才允许点亮按钮（判定在 policy）：翻历史是阅读，不打扰。
                        // 只统计被列表消费掉的位移：贴底回弹（overscroll）不计入距离。
                        // 编辑框内部滚动也会把它的 consumed 报上来（NestedScrollNode.onPostScroll
                        // 把 selfConsumed 加进 parent 的 consumed），列表没动却会改写方向/距离，
                        // 所以必须用 isScrollInProgress 门控：真实划动和 fling 都在
                        // scrollableState.scroll{} 内走
                        // performScroll → dispatchPostScroll（标志必为 true），而编辑框吐上来的那笔
                        // 发生在列表自己未滚动时，直接丢掉。键盘跟随用的 listState.dispatchRawDelta
                        // 不经 nested scroll，不会被误丢。
                        // 前提：子级的泄漏已被 blockScrollLeak 在到达列表前吞掉（列表真的没动）。
                        // 将来若在列表内加会垂直泄滚的子容器，需重新审视这个门控：那种场景下列表
                        // 会被 performRawScroll 拖动而标志仍为 false，会计漏。
                        if (listState.isScrollInProgress) {
                            bottomPolicy.onUserScrolled(consumed.y)
                            showReturnToBottom = bottomPolicy.showReturnToBottom
                        }
                        return Offset.Zero
                    }
                }
            }
            // 视口高度变化（键盘弹出/收起）时，以「变化前是否贴底」决定是否跟随：
            // 键盘弹出让视口变矮、LazyColumn 锚点不动 → 底部被裁掉；收起后视口变高 →
            // 底部多出大片空白。“贴底”只在当时的视口高度下成立，所以跟随与否沿用
            // BottomFollowPolicy 的用户意图基准（shouldFollowBottom），不读变化后的几何。
            // 跟随方式：按每帧视口高度差 dispatchRawDelta 同步滚动，让末条消息跟着键盘
            // 逐帧升降（ChatGPT 式）——实测 IME inset 逐帧到达（Redmi/API 33 约 290ms
            // 逐帧 14→888px），只有列表重新锚定被推迟到动画结束后才做，表现为
            // 「键盘先升起来，顿一下消息才跟着跳上去」。逐帧跟随后无收尾跳变。
            // 选词状态下同样不动：视口变化时的强制贴底正是「局部复制时界面上下跳动」
            // 的来源；用户正在选词，位置不能动。
            // suppress 的清除靠 collectLatest 逐帧取消重挂：动画结束后最后一帧的
            // delay(150) 才能活下来，按钮在整个键盘动画期间不再闪现。
            LaunchedEffect(conversation.id, listState) {
                var lastHeight = -1
                snapshotFlow {
                    val info = listState.layoutInfo
                    info.viewportEndOffset - info.viewportStartOffset
                }
                    .collectLatest { height ->
                        when {
                            // 首帧：只记录基准（避免进入会话被误判为“视口变化”而强拉到底）
                            lastHeight == -1 -> lastHeight = height
                            // 高度未变：几何状态已经稳定，结束键盘动画的按钮抑制
                            height == lastHeight -> suppressBottomButton = false
                            // 高度变了（键盘逐帧动画）：沿用 BottomFollowPolicy 的用户意图
                            else -> {
                                val delta = height - lastHeight
                                lastHeight = height
                                if (bottomPolicy.shouldFollowBottom && !userDragActive.value) {
                                    if (!selectionLikelyActive && messages.isNotEmpty()) {
                                        suppressBottomButton = true
                                        // 视口收缩（键盘弹出）时 LazyColumn 只锚定顶部、底部被裁，
                                        // 必须自己同帧向前补滚 delta 让末条消息贴住键盘上沿。
                                        // 视口扩大（键盘收起）时不要补滚：measure 会把超出最大
                                        // 滚动量的偏移自动扣回；再补一遍会每帧多退一份。
                                        if (delta < 0 && !listState.isScrollInProgress) {
                                            listState.dispatchRawDelta(-delta.toFloat())
                                        }
                                        kotlinx.coroutines.delay(150)
                                        suppressBottomButton = false
                                    }
                                }
                            }
                        }
                    }
            }
            Box(Modifier.weight(1f)) {
                // 切换会话：硬切 + 级联入场（下方 StaggeredEntrance）。
                // 新列表的逐帧组合成本（Markdown 解析/首帧布局）是物理存在的，
                // 叠淡/错开淡入都无法消除——索性把过程变成有节奏的动画：
                // 可见气泡按序号从上到下逐个入场。
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .nestedScroll(scrollIntentObserver)
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
                itemsIndexed(
                    messages,
                    key = { _, message -> message.id },
                    // 按气泡形态分型：滚动回收时同型复用，减少不同结构的重组/测量开销
                    contentType = { _, message ->
                        when {
                            message.isError && !message.fromUser -> "error"
                            message.fromUser -> "user"
                            message.text.isBlank() && message.reasoning.isBlank() && message.attachments.isNotEmpty() -> "attachmentsOnly"
                            else -> "assistant"
                        }
                    }
                ) { index, message ->
                    // 认领入场资格：本 item 实例首次组合时一次性判定（pending 已按
                    // 「尾部追加」筛过）；认领即移除 → 滚回重看、流式重组都不会重播。
                    // Lazy 在 measure 阶段才组合 item，晚于上方 body 的 pending 更新。
                    val arrival = remember(message.id) { arrivalTracker.claim(message.id) }
                    // 入场动画双触发（互斥：切换会话重置 tracker → pending 空 → arrival 恒 null）：
                    // - 切换会话：entranceActive=true，可见 item 按序号从上到下错峰入场（既有行为）
                    // - 新到达消息：同款淡入+上浮，按批内序号错峰 —— 用户消息先入场，
                    //   回复占位（批内序号 1）晚 180ms 入场，不再同帧一起浮上来
                    StaggeredEntrance(
                        active = entranceActive || arrival != null,
                        index = if (entranceActive) index else arrival ?: 0,
                        staggerMs = if (entranceActive) 45L else 180L
                    ) {
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
                            // 纯附件消息（正文与思考均空，如纯结构化图回复）不渲染空气泡，只渲染附件条（用户/模型消息同规则）；
                            // 正文图片/文件占位已替换为文件名，带正文时正常渲染气泡
                            val showBubble = !(message.text.isBlank() && message.reasoning.isBlank() && message.attachments.isNotEmpty())
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
                            // 附件展示条：放在气泡下方（用户消息从右往左排；模型消息左对齐
                            // 贴齐气泡，显示模型返回的图片缩略图与文件卡片），超出屏宽才横向滑动
                            if (message.attachments.isNotEmpty()) {
                                if (showBubble) Spacer(Modifier.height(4.dp))
                                MessageAttachmentsRow(
                                    attachments = message.attachments,
                                    alignEnd = message.fromUser,
                                    onImageClick = { viewingImage = it },
                                    onFileClick = if (message.fromUser) null else { att ->
                                        openGeneratedFile(context, att)
                                    },
                                    onFileDownload = { att -> requestSave(att, toGallery = false) }
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
                // 悬浮提示：显示由 BottomFollowPolicy 决定——距底净滚动超过一整屏、
                // 且最近一次滚动朝回底部方向（"回到底部"）；翻历史方向不出现，
                // 一滑回翻历史立即隐藏。
                // 生成开始已自动带回底部，不再用"新回复中"提示。
                androidx.compose.animation.AnimatedVisibility(
                    visible = showReturnToBottom && !suppressBottomButton,
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
                            // 仅在选区激活时清选区：clearTextSelection 会把焦点抢到屏外
                            // 哨兵 → 输入框失焦 → 键盘被关。平时点击只贴底，键盘保持打开
                            // （键盘已由 keyboardSuppressReport 向容器观察器报备豁免）。
                            if (selectionLikelyActive) clearTextSelection()
                            bottomPolicy.onReturnToBottom()
                            showReturnToBottom = bottomPolicy.showReturnToBottom
                            scope.launch {
                                // 直接一步到位贴底（用户明确不要动画）
                                listState.snapToBottom {
                                    bottomPolicy.shouldFollowBottom && !userDragActive.value && !selectionLikelyActive
                                }
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
                    // Box 容器：承载超长文本时右上角浮出的「放大编辑」入口
                    Box(Modifier.fillMaxWidth()) {
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp)
                                // 输入框向上扩高（顶部内边距大、贴底近）：点击区域远离下方图标行，
                                // 也消除胶囊顶部的白色空隙感
                                .padding(top = 10.dp, bottom = 10.dp)
                                // 高度上限：超过约 7 行后停止长高、改为框内滚动。无上限时
                                // 超长输入会无限增高（输入区是非 weight 子项、先按全屏测量），
                                // 把消息列表挤没、输入框占满整屏——此 bug 曾回归过一次
                                .heightIn(min = 44.dp, max = 160.dp),
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
                        if (input.length >= EXPAND_EDIT_THRESHOLD) {
                            // 放大编辑入口：正文超过阈值后浮在右上角（点开全屏编辑器）。
                            // 不透明小圆底：避免与底下第一行文字叠在一起看不清
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 4.dp, end = 8.dp)
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                        CircleShape
                                    )
                                    .clickable { expandedEditOpen = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.OpenInFull,
                                    contentDescription = "放大编辑",
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
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
        ImageViewerDialog(
            attachment = attachment,
            onDismiss = { viewingImage = null },
            onSaveRequest = { saveConfirm = attachment }
        )
    }

    // 放大编辑：全屏编辑超长输入（左取消 / 右发送，同就地编辑器的布局）。
    // 编辑实时同步回输入框：取消只是收起，发送直接发出并关闭
    if (expandedEditOpen) {
        ExpandedInputEditorDialog(
            text = input,
            onTextChange = { input = it },
            canSend = (input.isNotBlank() || attachments.isNotEmpty()) && !isGenerating,
            onCancel = { expandedEditOpen = false },
            onSend = {
                sendMessage()
                expandedEditOpen = false
            }
        )
    }

    // 长按图片 → 保存到相册确认
    saveConfirm?.let { att ->
        AlertDialog(
            onDismissRequest = { saveConfirm = null },
            title = { Text("保存到相册") },
            text = { Text("将「${att.name}」保存到相册 ${AttachmentSaver.GALLERY_DIR}/ ？") },
            confirmButton = {
                TextButton(onClick = {
                    saveConfirm = null
                    requestSave(att, toGallery = true)
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { saveConfirm = null }) { Text("取消") }
            }
        )
    }
}

internal suspend fun PointerInputScope.dismissKeyboardOnTap(
    localView: android.view.View?,
    shouldSkip: () -> Boolean = { false },
    onSelectionTap: (() -> Unit)? = null
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.isConsumed) return@awaitEachGesture
        var tapped = true
        // 滑动收键盘每次手势只触发一次：越过 touchSlop 即收，之后滚动全程不再重复
        var dismissed = false
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
            // 子控件已消费（按钮点击/滚动/拖拽）或移动过 touchSlop → 不再算纯点按。
            // 滑动本身也收键盘：纵向主导越过 touchSlop 立即收（每次手势一次）——
            // 键盘可见才动手，普通滚动（键盘已关）不碰焦点。
            // 横向主导不收：交给抽屉拖拽自己的收键盘路径（保住它的 imeSettling 锁）；
            // 报备手势（输入胶囊内拖光标/按钮上滑走）与选区激活照旧让路。
            if (change.isConsumed ||
                (abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) ||
                (abs(change.position.y - down.position.y) > viewConfiguration.touchSlop)
            ) {
                tapped = false
                if (!dismissed && !change.isConsumed) {
                    val dx = abs(change.position.x - down.position.x)
                    val dy = abs(change.position.y - down.position.y)
                    if (dy > viewConfiguration.touchSlop && dy >= dx) {
                        dismissed = true
                        when {
                            shouldSkip() -> {}
                            keyboardSuppress.tapActive -> {}
                            else -> if (isImeVisible(localView)) hideKeyboard(localView)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 正文超过这个字数后，输入框右上角浮出「放大编辑」入口。
 * 输入框约 7 行（160dp）封顶，120 字已接近/超过封顶，框内滚动编辑体验变差，
 * 提前一点给出全屏编辑入口。
 */
private const val EXPAND_EDIT_THRESHOLD = 120

/**
 * 放大编辑：全屏编辑超长输入。顶部「取消 / 字数 / 发送」（左取消右发送，
 * 与就地编辑器同款布局），中间占满全屏的多行编辑区。
 * 不自动聚焦弹键盘：打开时可能只是想先通读检查，点编辑区再唤起键盘。
 * 文本实时同步回输入框（单一数据源）：取消＝收起不丢字；发送＝直接发出，
 * 发送条件与主输入栏一致（有文字或附件、非生成中）。
 */
@Composable
private fun ExpandedInputEditorDialog(
    text: String,
    onTextChange: (String) -> Unit,
    canSend: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
                ) { Text("取消") }
                Spacer(Modifier.weight(1f))
                Text(
                    "${text.length} 字",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onSend,
                    enabled = canSend,
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
                ) { Text("发送") }
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)
                )
            )
        }
    }
}

/**
 * 模型返回的非图片文件经 FileProvider 调系统应用打开（intent 带原始扩展名推导的 MIME）；
 * 无应用可处理时 Toast 提示。仅 file:// 生成文件走此路径（远程链接维持可点击现状）。
 */
internal fun openGeneratedFile(context: android.content.Context, attachment: com.wavex.agent.model.ChatAttachment) {
    val path = android.net.Uri.parse(attachment.uri).path ?: return
    try {
        val file = java.io.File(path)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val intent = Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    } catch (_: Exception) {
        // 路径不在 FileProvider 声明范围 / 无应用可打开等一律提示，不崩
        Toast.makeText(context, "无法打开该文件", Toast.LENGTH_SHORT).show()
    }
}
