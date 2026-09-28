package com.wavex.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wavex.agent.model.AgentConversation
import com.wavex.agent.model.ChatAttachment
import com.wavex.agent.model.ChatMessage
import com.wavex.agent.model.ChatRequestMessage
import com.wavex.agent.model.TREE_ROOT
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material3.Surface
import androidx.compose.runtime.movableContentOf
import com.wavex.agent.ui.chat.ChatScreen
import com.wavex.agent.ui.drawer.AgentDrawer
import com.wavex.agent.ui.drawer.DrawerWidth
import com.wavex.agent.ui.drawer.TabBarHeight
import com.wavex.agent.ui.models.ModelsScreen
import com.wavex.agent.ui.settings.SettingsScreen
import com.wavex.agent.ui.shared.OverflowMenuRow
import com.wavex.agent.state.WavexViewModel
import com.wavex.agent.ui.theme.AgentTheme
import kotlin.math.roundToInt
import com.wavex.agent.ui.chat.hideKeyboard
import com.wavex.agent.ui.drawer.drawerDragGesture
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntSize

internal enum class MainTab { CHAT, MODELS, SETTINGS }

internal enum class ThemeChoice { SYSTEM, LIGHT, DARK }

// 思考等级："" = 不传参数（跟随模型默认）；极低/低/中/高一一对应 reasoning_effort
// 的 minimal/low/medium/high（gpt-5 系全部档位），纯中文短标签，排版整齐
internal val REASONING_LEVELS = listOf(
    "" to "默认",
    "minimal" to "极低",
    "low" to "低",
    "medium" to "中",
    "high" to "高"
)

@Composable
internal fun AgentApp(state: WavexViewModel) {
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
internal fun AgentMainContent(state: WavexViewModel, onOpenDrawer: () -> Unit, imeSettling: () -> Boolean) {
    val context = LocalContext.current
    // 右上角「⋯」菜单：新建/重命名/删除/分享（对话内快捷操作，不用回抽屉）
    var chatMenuOpen by remember { mutableStateOf(false) }
    var renameConvOpen by remember { mutableStateOf(false) }
    var renameConvText by remember { mutableStateOf("") }
    var deleteConvOpen by remember { mutableStateOf(false) }
    // 三个页面都用 movableContent：切 Tab 时移动已组合的内容而非销毁重建，
    // 回到对话页不再重新解析全部 Markdown（修切 Tab 卡顿）；
    // 页面状态（草稿/编辑态/滚动位置）本来就在 WavexViewModel，记忆快照照常保留。
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
            // 聊天生成引擎、草稿、滚动位置都提升在 WavexViewModel，页面销毁重建不丢状态。
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
internal fun buildTranscript(conversation: AgentConversation): String = buildString {
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

@Composable
internal fun ChatOverflowMenu(
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
internal fun RowScope.SimpleTabItem(
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
internal fun Modifier.bottomInputClearance(imeSettling: () -> Boolean = { false }): Modifier {
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
