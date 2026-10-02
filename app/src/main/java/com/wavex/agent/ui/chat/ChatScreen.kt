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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.text.input.ImeAction
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import com.wavex.agent.data.AttachmentLoader
import com.wavex.agent.data.AttachmentSaver

/**
 * 精确贴底：把列表滚到内容真正的末尾（末条消息底边 == 视口底边）。
 * scrollToItem 的 offset 语义对“末条比视口矮”的场景不可靠（实测会回填上方 item，
 * 停在离底约一屏处），所以用“测量剩余距离 → 滚动”的收敛式：
 * 末条不可见先顶对齐它，再按实际剩余像素补滚（scrollBy 会被内容边界自然鈄住）。
 */
internal suspend fun androidx.compose.foundation.lazy.LazyListState.snapToBottom() {
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
 * 切换会话的级联入场：按序号错峰淡入 + 轻微上移（从上到下节奏）。
 * - 是否参与动画在**组合期一次性定死**（active 翻转不改变组合树结构）：
 *   结构若随 active 分支切换，气泡内部的 remember(text) 块切分/段落缓存会全部失效，
 *   窗口结束时全列表重解析 → 每次切换必现一次卡顿尖峰（已踩过的坑，别退回去）；
 * - graphicsLayer 只动 alpha/translationY，不触发布局重排；
 * - 错峰 = index×45ms、上限 400ms：首屏最后一条也在 0.6s 内完成，不拖沓。
 */
@Composable
private fun ConversationEntrance(active: Boolean, index: Int, content: @Composable () -> Unit) {
    // played 在首次组合时定死：窗口内 = false（播动画），窗口外/回看 = true（直接显示）
    var played by remember { mutableStateOf(!active) }
    val progress = remember { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!played) {
            played = true
            delay((index * 45L).coerceAtMost(400L))
            progress.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        }
    }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 12.dp.toPx()
        }
    ) { content() }
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
    // key(conversation.id)：切换会话时重置，避免 A 会话的编辑行号落到 B 会话的同位置
    var editingIndex by remember(conversation.id) { mutableStateOf<Int?>(null) }
    // 切换会话的级联入场窗口：窗口内首次组合的气泡按序号从上到下逐个入场（ConversationEntrance），
    // 把新列表首帧组合（Markdown 解析/气泡布局）的不可控过程变成有节奏的动画。
    // 窗口起点用 remember(conversation.id) 在组合期同步重置 —— 不能用 LaunchedEffect 置位：
    // effect 在组合完成后才跑，item 首次组合会先读到 false，级联动画就永远不播了（踩过的坑）。
    // 800ms 后窗口自然过期：滚动后新组合的项直接显示，回看不重播；单调时钟不触发额外重组。
    var entranceStart by remember(conversation.id) {
        mutableStateOf(android.os.SystemClock.elapsedRealtime())
    }
    val entranceActive = android.os.SystemClock.elapsedRealtime() - entranceStart < 800L
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
            // 视口高度变化（键盘弹出/收起）时，以「变化前是否贴底」决定是否跟随：
            // 键盘弹出让视口变矮、LazyColumn 锚点不动 → 底部被裁掉（awayFromBottom 会被
            // 误判为用户上翻）；收起后视口变高 → 底部多出大片空白。“贴底”只在当时的
            // 视口高度下成立，所以不能在变化后读 awayFromBottom，要沿用变化前的基准。
            // 跟随方式：按每帧视口高度差 dispatchRawDelta 同步滚动，让末条消息跟着键盘
            // 逐帧升降（ChatGPT 式）——实测 IME inset 逐帧到达（Redmi/API 33 约 290ms
            // 逐帧 14→888px），只有列表重新锚定被推迟到动画结束后才做，表现为
            // 「键盘先升起来，顿一下消息才跟着跳上去」。逐帧跟随后无收尾跳变。
            // 选词状态下同样不动：视口变化时的强制贴底正是「局部复制时界面上下跳动」
            // 的来源；用户正在选词，位置不能动。
            // suppress 的清除靠 collectLatest 逐帧取消重挂：动画结束后最后一帧的
            // delay(150) 才能活下来，按钮在整个键盘动画期间不再闪现。
            var suppressBottomButton by remember(conversation.id) { mutableStateOf(false) }
            LaunchedEffect(conversation.id) {
                var lastHeight = -1
                var wasAtBottom = true
                snapshotFlow {
                    val info = listState.layoutInfo
                    (info.viewportEndOffset - info.viewportStartOffset) to !awayFromBottom
                }
                    .collectLatest { (height, atBottom) ->
                        when {
                            // 首帧：只记录基准（避免进入会话被误判为“视口变化”而强拉到底）
                            lastHeight == -1 -> { lastHeight = height; wasAtBottom = atBottom }
                            // 高度未变：贴底态翻转（贴底完成/用户滚动）→ 更新基准、解除抑制
                            height == lastHeight -> {
                                wasAtBottom = atBottom
                                suppressBottomButton = false
                            }
                            // 高度变了（键盘逐帧动画）：沿用变化前的贴底态决策
                            else -> {
                                val delta = height - lastHeight
                                lastHeight = height
                                if (wasAtBottom && !selectionLikelyActive && messages.isNotEmpty()) {
                                    suppressBottomButton = true
                                    // 视口收缩（键盘弹出）时 LazyColumn 只锚定顶部、底部被裁，
                                    // 必须自己同帧向前补滚 delta 让末条消息贴住键盘上沿。
                                    // 视口扩大（键盘收起）时不要补滚：measure 会把超出最大
                                    // 滚动量的偏移自动扣回（底部保持貃合），再补一遍会
                                    // 每帧多退一份，键盘落定后整体早了约一个键盘高度
                                    // （实测 Redmi/API 33：收起后末条消息被留在屏幕外）。
                                    // dispatchRawDelta 不走滚动循环：不置 isScrollInProgress、无动画；
                                    // 内容顶到边界时剩余位移被吞掉，下一帧从实际位置继续，自收敛。
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
            Box(Modifier.weight(1f)) {
                // 切换会话：硬切 + 级联入场（下方 ConversationEntrance）。
                // 新列表的逐帧组合成本（Markdown 解析/首帧布局）是物理存在的，
                // 叠淡/错开淡入都无法消除——索性把过程变成有节奏的动画：
                // 可见气泡按序号从上到下逐个入场。
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
                    ConversationEntrance(entranceActive, index) {
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
                // 悬浮提示：仅当用户自己上滑翻历史时出现（"回到底部"）。
                // 生成开始已自动带回底部，不再用"新回复中"提示。
                androidx.compose.animation.AnimatedVisibility(
                    visible = awayFromBottom && !suppressBottomButton,
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
        ImageViewerDialog(
            attachment = attachment,
            onDismiss = { viewingImage = null },
            onSaveRequest = { saveConfirm = attachment }
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
