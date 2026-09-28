package com.wavex.agent.ui.drawer

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.PointerEventPass
import com.wavex.agent.state.WavexViewModel
import com.wavex.agent.R
import com.wavex.agent.ui.chat.keyboardSuppressReport
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.input.pointer.positionChange

internal val DrawerWidth = 320.dp
internal val TabBarHeight = 58.dp
internal const val FlingThreshold = 400f

/** 图片附件后缀判断（预编译正则：此前每次组合都重新编译一遍） */
internal val IMAGE_NAME_REGEX = Regex(".*\\.(png|jpg|jpeg|webp|gif|bmp)$")

@Composable
internal fun AgentDrawer(state: WavexViewModel, onCloseDrawer: () -> Unit) {
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
                    // 退出管理模式（删除动作已移到底部大按钮）
                    Surface(
                        onClick = {
                            selectMode = false
                            selectedIds.clear()
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            "退出",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
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
                    // 管理模式下大按钮 = 删除已选（原「全选」旁的删除职责移到这里）
                    deleteTargets = selectedIds.mapNotNull { id ->
                        state.conversations.firstOrNull { it.id == id }
                    }
                } else {
                    // 新建也先关抽屉后切换，与选会话节奏一致
                    state.deferNewConversation(onCloseDrawer)
                }
            },
            // 管理模式下未选中任何项时置灰，避免点了没反应的困惑
            enabled = !selectMode || selectedIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(14.dp),
            // 删除是危险操作：管理模式下用 error 配色明示
            colors = if (selectMode) ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.error
            ) else ButtonDefaults.buttonColors()
        ) {
            Icon(if (selectMode) Icons.Default.Delete else Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (selectMode) "删除" else "新建对话")
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
internal fun DrawerConversationItem(
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

/**
 * 抽屉拖拽手势，对标 EhViewer 的 DrawerLayout：
 * - Initial 阶段抢先处理，一旦判定为横向主导手势就立即锁定拦截；
 * - 纵向先主导则本次手势彻底放弃，不干扰列表滚动；
 * - 阈值仅为系统 touchSlop，非常灵敏。
 * 注意：进度面板本身在跟随手指移动，不能用控件本地坐标算速度，
 * 这里用净位移 + 时间戳自己估算速度。
 */
internal fun Modifier.drawerDragGesture(
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
