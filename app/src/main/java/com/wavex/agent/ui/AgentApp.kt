package com.wavex.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.wavex.agent.feature.conversation.model.AgentConversation
import com.wavex.agent.feature.conversation.viewmodel.ConversationEffect
import com.wavex.agent.core.domain.chat.ReasoningLevels
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.wavex.agent.feature.conversation.ui.ChatScreen
import com.wavex.agent.feature.conversation.ui.AgentDrawer
import com.wavex.agent.feature.conversation.ui.DrawerWidth
import com.wavex.agent.feature.conversation.ui.TabBarHeight
import com.wavex.agent.feature.catalog.CatalogViewModel
import com.wavex.agent.feature.conversation.viewmodel.ConversationViewModel
import com.wavex.agent.feature.usage.UsageViewModel
import com.wavex.agent.feature.catalog.ui.ModelsScreen
import com.wavex.agent.feature.catalog.ui.ArchivedConversationUi
import com.wavex.agent.feature.catalog.ui.SettingsScreen
import com.wavex.agent.core.ui.OverflowMenuRow
import com.wavex.agent.AppContainer
import com.wavex.agent.core.ui.AgentTheme
import com.wavex.agent.feature.conversation.ui.hideKeyboard
import com.wavex.agent.feature.conversation.ui.drawerDragGesture
import androidx.compose.foundation.layout.safeDrawing

internal enum class MainTab { CHAT, USAGE, MODELS, SETTINGS }

// 思考等级："" = 不传参数（跟随模型默认）；低/中/高/极致一一对应 low/medium/high/xhigh，
// 均匀递进。表本体在 engine/ReasoningLevels：payload 映射与旧值归一化共用同一份，避免两处漂移
internal val REASONING_LEVELS = ReasoningLevels.displayLevels

/** 根级 Tab 状态（app 壳自有：壳层导航，不属于任何 feature 域） */
@Composable
internal fun AgentApp(
    conversation: ConversationViewModel,
    catalog: CatalogViewModel,
    usage: UsageViewModel,
    container: AppContainer
) {
    // 根级 Tab 状态：壳层导航（Task 10 起壳层自有，不寄生于任何 feature VM）
    var selectedTab by remember { mutableStateOf(MainTab.CHAT) }

    val darkTheme = when (catalog.themeChoice) {
        CatalogViewModel.ThemeChoice.DARK -> true
        CatalogViewModel.ThemeChoice.LIGHT -> false
        CatalogViewModel.ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }

    // 浅色主题用深色图标、深色主题用浅色图标，导航栏同理由主题切换后的图标外观跟随。
    // 冷启动由 themes.xml 的静态属性兜底（MIUI 会覆盖冷启动期的运行时设置），
    // 这里负责应用内切换主题/系统夜态变化后的跟随更新
    val view = LocalView.current
    if (!view.isInEditMode) {
        LaunchedEffect(darkTheme) {
            val activity = view.context as ComponentActivity
            val lightStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK)
            val darkStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            activity.enableEdgeToEdge(
                statusBarStyle = if (darkTheme) darkStyle else lightStyle,
                navigationBarStyle = if (darkTheme) darkStyle else lightStyle
            )
        }
    }

    AgentTheme(darkTheme = darkTheme, dynamicColor = false) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val view = LocalView.current
        val drawerWidthPx = with(density) { DrawerWidth.toPx() }
        val drawerProgress = remember { Animatable(0f) }

        // 会话域一次性效果（fallback 提示等，Task 6 起 VM 以类型化效果替代 Toast）：
        // app 壳渲染成 Toast；Task 7 移交 feature UI 后本收集器随之移除。
        // SharedFlow 无 replay：收集器在首帧组合即启动，降级提示都发生在用户发送之后。
        val toastContext = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(Unit) {
            conversation.effects.collect { effect ->
                when (effect) {
                    is ConversationEffect.Notice -> android.widget.Toast.makeText(
                        toastContext,
                        effect.text,
                        if (effect.longDuration) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

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
        // 关抽屉：切换会话/新建对话提前到滑走期间应用（而非动画结束）——
        // 新列表的首次组合成本（Markdown 解析、气泡首帧布局）发生在抽屉+遮罩遮挡下，
        // 滑完露出时内容已就位，看不到气泡逐个渲染的过程。
        // 原设计（关完再切，视线先后看到两件事）被实际体验推翻：关完露出的那瞬间
        // 才是渲染过程真正可见的地方。
        val closeDrawer: () -> Unit = {
            conversation.applyPendingConversation()
            conversation.applyPendingNewConversation()
            scope.launch {
                drawerProgress.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium))
            }
        }

        // 抽屉可见性用 derivedStateOf 收敛：若直接在组合里读 drawerProgress.value，
        // 动画/拖拽的每一帧都会重组整屏（遮罩/面板/主内容全量重新求值）。
        // 收敛后只有显示/隐藏翻转那一刻才重组，逐帧工作只剩 graphicsLayer/offset lambda。
        val drawerVisible by remember { androidx.compose.runtime.derivedStateOf { drawerProgress.value > 0.001f } }
        val backClosesDrawer by remember { androidx.compose.runtime.derivedStateOf { drawerProgress.value > 0.01f } }
        // 返回手势统一裁决（Mihon 式）：抽屉开着 → 先关抽屉；非对话 tab → 回对话 tab；
        // 对话 tab → 交给系统（退出应用）。聊天内长按选择等更深层的 BackHandler 注册更晚、
        // 优先级更高，不受影响。
        BackHandler(enabled = backClosesDrawer || selectedTab != MainTab.CHAT) {
            if (backClosesDrawer) closeDrawer() else selectedTab = MainTab.CHAT
        }

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
                AgentMainContent(conversation = conversation, catalog = catalog, usage = usage, selectedTab = selectedTab, onSelectTab = { selectedTab = it }, container = container, onOpenDrawer = openDrawer, imeSettling = { imeSettling })
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
                    AgentDrawer(viewModel = conversation, onCloseDrawer = closeDrawer)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentMainContent(
    conversation: ConversationViewModel,
    catalog: CatalogViewModel,
    usage: UsageViewModel,
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    container: AppContainer,
    onOpenDrawer: () -> Unit,
    imeSettling: () -> Boolean
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // 右上角「⋯」菜单：新建/重命名/删除/分享（对话内快捷操作，不用回抽屉）
    var chatMenuOpen by remember { mutableStateOf(false) }
    var renameConvOpen by remember { mutableStateOf(false) }
    var renameConvText by remember { mutableStateOf("") }
    var deleteConvOpen by remember { mutableStateOf(false) }
    // 四个页面都用 movableContent：切 Tab 时移动已组合的内容而非销毁重建，
    // 回到对话页不再重新解析全部 Markdown（修切 Tab 卡顿）；
    // 页面状态（草稿/编辑态/滚动位置）在各 feature VM，记忆快照照常保留。
    val chatScreen = remember {
        movableContentOf { ChatScreen(modifier = Modifier.fillMaxSize(), viewModel = conversation, conversation = conversation.currentConversation, attachmentSelectionPolicy = container.attachmentSelectionPolicy, attachmentSaver = container.attachmentSaver, attachmentSnapshotter = container.attachmentSnapshotter, imeSettling = imeSettling) }
    }
    val modelsScreen = remember {
        movableContentOf { ModelsScreen(modifier = Modifier.fillMaxSize(), viewModel = catalog, onGoToSettings = { onSelectTab(MainTab.SETTINGS) }) }
    }
    val usageScreen = remember {
        movableContentOf { com.wavex.agent.feature.usage.ui.UsageScreen(modifier = Modifier.fillMaxSize(), viewModel = usage) }
    }
    // 落账刷新闸门：仅用量页可见时响应 repository 落账回调（避免停在聊天页时
    // 每个请求都全量解析 90 天日志——旧实现同款语义）
    LaunchedEffect(Unit) { usage.onPageVisible = { selectedTab == MainTab.USAGE } }

    // 旧备份一次性导入（spec §7.3）：SAF 流读取与解析留在 app 壳（LegacyConversationJson 在
    // data 层，feature/catalog 不依赖 data），逐会话「落盘登记 → 进列表」交给
    // ConversationViewModel.importSnapshots（会话域状态）。
    suspend fun importLegacyFile(uri: android.net.Uri): Int? {
        val raw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { s ->
                    s.bufferedReader().readText()
                }
            } catch (_: Exception) {
                null   // 流打不开 / 读取失败：同「未识别出对话」
            }
        } ?: return null
        val snapshots = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.wavex.agent.core.data.LegacyConversationJson.parse(raw)
        }
        return conversation.importSnapshots(snapshots)
    }
    // 存储授权动作（SDK 30+ 跳系统授权页；26-29 运行时权限弹窗）；授权结果由 MainActivity.onResume 收敛
    val requestWritePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* 授权结果由 onResume 的 refreshSessionStorage 收敛，此处无需处理 */ }
    val openStoragePermissionScreen: () -> Unit = {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${context.packageName}")))
    }
    val settingsScreen = remember(context) {
        movableContentOf {
            // 归档会话从抽屉移入设置页管理：壳层把会话域状态映射成 UI 模型
            // （feature:catalog 不依赖 feature:conversation），取消归档/删除按 id
            // 找回会话对象后复用 VM 既有方法，列表写自动触发落盘。
            val archivedUi = conversation.conversations.filter { it.archived }
                .map { ArchivedConversationUi(it.id, it.title, it.lastActiveAt) }
            SettingsScreen(
                modifier = Modifier.fillMaxSize(),
                viewModel = catalog,
                archivedConversations = archivedUi,
                onUnarchiveConversation = { id ->
                    conversation.conversations.firstOrNull { it.id == id }
                        ?.let { conversation.setArchivedConversation(it, false) }
                },
                onDeleteConversation = { id ->
                    conversation.conversations.firstOrNull { it.id == id }
                        ?.let { conversation.deleteConversation(it) }
                },
                onImportLegacyFile = { uri -> importLegacyFile(uri) },
                onOpenStoragePermissionScreen = openStoragePermissionScreen,
                requestWritePermission = { requestWritePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            )
        }
    }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            topBar = {
                // 顶栏背景用普通背景绘制：material3 1.4 起对 TopAppBar 容器色变化内置
                // 渐变动画，切主题时顶部整块会滞后于页面变色，标题/状态栏文字会短暂
                // 不可见；透明容器 + 静态背景保证顶部与页面同帧变色
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                    TopAppBar(
                        title = {
                        Column {
                            Text(
                                when (selectedTab) {
                                    MainTab.CHAT -> conversation.currentTitle
                                    MainTab.MODELS -> "模型"
                                    MainTab.USAGE -> "用量统计"
                                    MainTab.SETTINGS -> "设置"
                                },
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (selectedTab == MainTab.CHAT) {
                                Text(
                                    conversation.selectedModel,
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
                        if (selectedTab == MainTab.CHAT) {
                            IconButton(onClick = { chatMenuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
                }
            }
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                // 只组合当前页面：切主题/发送时不必重组四个页面。
                // 聊天生成引擎、草稿、滚动位置都提升在 ConversationViewModel，页面销毁重建不丢状态。
                // Material fade-through（Mihon 同款）：旧页先淡出（0→90ms），新页错开淡入（90→200ms），
                // 无缩放。任意时刻最多一页可见、中间穿页面背景色 —— 不像交叉淡入那样两页半透明叠加而发闪。
                AnimatedContent(
                    modifier = Modifier.fillMaxSize(),
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(110, delayMillis = 90)) togetherWith
                            fadeOut(animationSpec = tween(90))
                    },
                    label = "tabFadeThrough"
                ) { tab ->
                    when (tab) {
                        MainTab.CHAT -> chatScreen()
                        MainTab.MODELS -> modelsScreen()
                        MainTab.USAGE -> usageScreen()
                        MainTab.SETTINGS -> settingsScreen()
                    }
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
                selected = selectedTab == MainTab.CHAT,
                icon = Icons.Outlined.Forum,
                label = "对话",
                onClick = { onSelectTab(MainTab.CHAT) }
            )
            SimpleTabItem(
                selected = selectedTab == MainTab.USAGE,
                icon = Icons.Outlined.BarChart,
                label = "用量",
                onClick = { onSelectTab(MainTab.USAGE) }
            )
            SimpleTabItem(
                selected = selectedTab == MainTab.MODELS,
                icon = Icons.Outlined.Memory,
                label = "模型",
                onClick = { onSelectTab(MainTab.MODELS) }
            )
            SimpleTabItem(
                selected = selectedTab == MainTab.SETTINGS,
                icon = Icons.Default.Tune,
                label = "设置",
                onClick = { onSelectTab(MainTab.SETTINGS) }
            )
        }
    }

    // —— 右上角「⋯」对话菜单及其二级弹窗 ——
    if (chatMenuOpen) {
        ChatOverflowMenu(
            onDismiss = { chatMenuOpen = false },
            onNewConversation = {
                chatMenuOpen = false
                conversation.newConversation()
                onSelectTab(MainTab.CHAT)
            },
            onRename = {
                chatMenuOpen = false
                renameConvText = conversation.currentConversation.title
                renameConvOpen = true
            },
            onDelete = {
                chatMenuOpen = false
                deleteConvOpen = true
            },
            onShare = {
                chatMenuOpen = false
                val text = buildTranscript(conversation.currentConversation)
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                }
                context.startActivity(android.content.Intent.createChooser(intent, "分享对话"))
            },
            onCopyTranscript = {
                chatMenuOpen = false
                clipboard.setText(AnnotatedString(buildTranscript(conversation.currentConversation)))
                android.widget.Toast.makeText(context, "已复制全文", android.widget.Toast.LENGTH_SHORT).show()
            },
            onShareMarkdown = {
                chatMenuOpen = false
                scope.launch {
                    val file = try {
                        withContext(Dispatchers.IO) {
                            writeMarkdownExportFile(context, conversation.currentConversation)
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            context, "导出失败：${e.message}", android.widget.Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    val uri = FileProvider.getUriForFile(
                        context, context.packageName + ".fileprovider", file
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/markdown"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        // 部分接收端只从 ClipData 取分享载荷：uri 双写并随带读授权
                        clipData = android.content.ClipData.newRawUri(file.name, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "分享为 Markdown 文件"))
                }
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
                    conversation.renameConversation(conversation.currentConversation, renameConvText)
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
            text = { Text("确定删除「${conversation.currentConversation.title}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    conversation.deleteConversation(conversation.currentConversation)
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

/** 导出对话为 Markdown 文件（分享 .md 用）：标题一级、消息角色二级，正文原样保留（代码块等不转义）；
 *  跳过规则与 buildTranscript 一致：错误消息、空白且无附件的消息不导出，思考过程不导出 */
internal fun buildMarkdownExport(conversation: AgentConversation): String = buildString {
    appendLine("# ${conversation.title}")
    appendLine()
    conversation.messages.forEach { m ->
        if (m.isError) return@forEach
        if (m.text.isBlank() && m.attachments.isEmpty()) return@forEach
        appendLine("## ${if (m.fromUser) "我" else "助手"}")
        appendLine()
        if (m.text.isNotBlank()) {
            appendLine(m.text)
            appendLine()
        }
        if (m.attachments.isNotEmpty()) {
            appendLine("（附件：${m.attachments.joinToString("、") { it.name }}）")
            appendLine()
        }
    }
}

/** 导出文件名：文件系统禁用字符与控制符替换为空格，超长截断，去尾部悬点，空标题兜底 */
internal fun markdownExportFileName(title: String): String {
    val cleaned = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
        .take(50).trim().trimEnd('.', ' ')
    return "${cleaned.ifEmpty { "对话导出" }}.md"
}

/** Markdown 导出落盘到 cache/exports/（同名覆盖：分享临时文件，交给系统随缓存清理） */
internal fun writeMarkdownExportFile(context: android.content.Context, conversation: AgentConversation): java.io.File {
    val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
    return java.io.File(dir, markdownExportFileName(conversation.title))
        .apply { writeText(buildMarkdownExport(conversation)) }
}

@Composable
internal fun ChatOverflowMenu(
    onDismiss: () -> Unit,
    onNewConversation: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onCopyTranscript: () -> Unit,
    onShareMarkdown: () -> Unit
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
                    OverflowMenuRow(Icons.Default.ContentCopy, "复制全文", onCopyTranscript)
                    OverflowMenuRow(Icons.Default.Description, "分享为 Markdown 文件", onShareMarkdown)
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
