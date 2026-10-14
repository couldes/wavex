package com.wavex.agent.feature.conversation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavex.agent.feature.conversation.model.AgentConversation
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.PointerEventPass
import com.wavex.agent.feature.conversation.viewmodel.ConversationViewModel
import com.wavex.agent.feature.conversation.state.DateGroup
import com.wavex.agent.feature.conversation.R
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.input.pointer.positionChange
import java.time.LocalDate

val DrawerWidth = 320.dp
val TabBarHeight = 58.dp
internal const val FlingThreshold = 400f

/**
 * 抽屉列表分区（[buildDrawerSections] 的产物，derivedStateOf 缓存）。
 * 语义约定（与旧组合期内联计算逐条等价）：
 * - [displayed]：搜索时标题或任一消息文本命中（含归档、忽略大小写）；无搜索 = 全列表；
 * - [pinned]/[recent]：无搜索时置顶区 + 未置顶未归档区（均活跃降序）；搜索时置顶为空、
 *   recent 平铺全部命中；
 * - [dateGroups]：无搜索时的活跃日期分组（桶序 = 首次出现序，组内保持活跃序）；搜索时为空。
 */
internal data class DrawerSections(
    val displayed: List<AgentConversation>,
    val pinned: List<AgentConversation>,
    val recent: List<AgentConversation>,
    /** 全选范围 = 当前可见条目（置顶 + 最近） */
    val visible: List<AgentConversation>,
    val dateGroups: List<DrawerDateSection>,
)

/** 无搜索时的日期分组段：标题行 + 组内会话 */
internal data class DrawerDateSection(val group: DateGroup, val items: List<AgentConversation>)

/**
 * 关键词过滤与列表分区的纯函数（可单测；时钟由 [today] 注入）。
 * 输入列表约定已按活跃降序（ConversationOrder 不变量），分组桶按首次出现序天然有序。
 */
internal fun buildDrawerSections(
    conversations: List<AgentConversation>,
    query: String,
    today: LocalDate,
): DrawerSections {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        val pinned = conversations.filter { it.pinned }
        val recent = conversations.filter { !it.pinned && !it.archived }
        val dateGroups = recent.groupBy { DateGroup.of(it.lastActiveAt, today) }
            .map { (group, items) -> DrawerDateSection(group, items) }
        return DrawerSections(
            displayed = conversations.toList(),
            pinned = pinned,
            recent = recent,
            visible = pinned + recent,
            dateGroups = dateGroups,
        )
    }
    val displayed = conversations.filter { conversation ->
        conversation.title.contains(trimmed, ignoreCase = true) ||
            conversation.children.values.any { siblings ->
                siblings.any { it.text.contains(trimmed, ignoreCase = true) }
            }
    }
    return DrawerSections(
        displayed = displayed,
        pinned = emptyList(),
        recent = displayed,
        visible = displayed,
        dateGroups = emptyList(),
    )
}

@Composable
fun AgentDrawer(viewModel: ConversationViewModel, onCloseDrawer: () -> Unit) {
    // 批量管理模式；每项右侧重命名图标；长按=操作弹窗（置顶/归档/删除，删除仍走确认框；重命名走右侧图标）
    var selectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var renameFor by remember { mutableStateOf<AgentConversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTargets by remember { mutableStateOf<List<AgentConversation>?>(null) }
    // 长按操作弹窗的目标会话（null = 弹窗关闭）
    var menuFor by remember { mutableStateOf<AgentConversation?>(null) }

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
                    // 软件品牌图标（与启动器/闪屏同构：白气泡 + 渐变大波，满幅版适配圆形底）
                    Image(
                        painter = painterResource(R.drawable.ic_agent_brand),
                        contentDescription = null,
                        modifier = Modifier.size(46.dp)
                    )
                }
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.app_name_full), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${viewModel.selectedModel} · 已连接",
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

        // 关键词过滤与列表分区收敛进 derivedStateOf：只在会话列表结构/消息文本/搜索词
        // 变化时重算，且结果结构相等时不触发重组 —— 抽屉开着时后台流式逐帧写消息、
        // 长按菜单开关、批量选中变化都不再重扫全部消息文本（大历史下这是抽屉打开时
        // 的主要掉帧源）。归档会话不进分区，管理入口在设置页「已归档会话」；
        // 搜索时平铺结果（含归档命中）、分区标题隐藏。
        val sections by remember {
            derivedStateOf { buildDrawerSections(viewModel.conversations, searchQuery, LocalDate.now()) }
        }
        val trimmedQuery = searchQuery.trim()
        val searchBlank = searchQuery.isBlank()

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
                            if (sections.visible.isNotEmpty() && selectedIds.size == sections.visible.size) selectedIds.clear()
                            else {
                                selectedIds.clear()
                                // 全选只选当前可见列表（搜索时即筛选结果）
                                selectedIds.addAll(sections.visible.map { it.id })
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            if (sections.visible.isNotEmpty() && selectedIds.size == sections.visible.size) "取消全选" else "全选",
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
                        // 固定行恢复「最近对话」区域标题；列表内保留 置顶 + 日期分组小标题；搜索时显示结果数
                        if (searchQuery.isBlank()) "最近对话" else "搜索结果（${sections.displayed.size}）",
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
                val conversationItem: @Composable LazyItemScope.(AgentConversation) -> Unit = { conversation ->
                    DrawerConversationItem(
                        title = conversation.title,
                        selected = conversation.id == viewModel.currentConversationId,
                        selectMode = selectMode,
                        checked = selectedIds.contains(conversation.id),
                        pinned = conversation.pinned,
                        archived = conversation.archived,
                        menuExpanded = menuFor?.id == conversation.id,
                        onMenuDismiss = { if (menuFor?.id == conversation.id) menuFor = null },
                        onClick = {
                            if (selectMode) {
                                if (selectedIds.contains(conversation.id)) selectedIds.remove(conversation.id)
                                else selectedIds.add(conversation.id)
                            } else {
                                // 先关抽屉，抽屉退场后再切内容（避免两次剧烈变化叠加）
                                viewModel.deferSelectConversation(conversation, onCloseDrawer)
                            }
                        },
                        onLongClick = {
                            // 长按弹操作弹窗（置顶/归档/删除；重命名在条目右侧图标）
                            if (!selectMode) menuFor = conversation
                        },
                        onRename = { menuFor = null; renameFor = conversation; renameText = conversation.title },
                        onTogglePin = { menuFor = null; viewModel.setPinnedConversation(conversation, !conversation.pinned) },
                        onToggleArchive = { menuFor = null; viewModel.setArchivedConversation(conversation, !conversation.archived) },
                        onDelete = { menuFor = null; deleteTargets = listOf(conversation) }
                    )
                }
                if (sections.pinned.isNotEmpty()) {
                    item(key = "drawer-section-pinned") { DrawerSectionLabel("置顶") }
                }
                items(sections.pinned, key = { it.id }, itemContent = conversationItem)
                if (searchBlank) {
                    // 未置顶区按活跃日期分组（今天/昨天/7 天内/30 天内/更早）：
                    // 分组在 derivedStateOf 内预算（桶序 = 首次出现序，组内保持活跃序），
                    // 抽屉任何无关重组都不再重算
                    sections.dateGroups.forEach { (group, groupItems) ->
                        item(key = "drawer-date-${group.name}") { DrawerSectionLabel(group.label) }
                        items(groupItems, key = { it.id }, itemContent = conversationItem)
                    }
                } else {
                    items(sections.recent, key = { it.id }, itemContent = conversationItem)
                }
                if (sections.displayed.isEmpty()) {
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
        if (selectMode) {
            // 批量归档：次级操作放在删除上方；归档可逆（设置页可撤销），无需确认框
            Button(
                onClick = {
                    viewModel.archiveConversations(selectedIds.toList())
                    selectedIds.clear()
                },
                enabled = selectedIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(Icons.Outlined.Archive, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("归档")
            }
            Spacer(Modifier.height(10.dp))
        }
        Button(
            onClick = {
                if (selectMode) {
                    // 管理模式下大按钮 = 删除已选（原「全选」旁的删除职责移到这里）
                    deleteTargets = selectedIds.mapNotNull { id ->
                        viewModel.conversations.firstOrNull { it.id == id }
                    }
                } else {
                    // 新建也先关抽屉后切换，与选会话节奏一致
                    viewModel.deferNewConversation(onCloseDrawer)
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
                    viewModel.renameConversation(conv, renameText)
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
                    targets.forEach { viewModel.deleteConversation(it) }
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

/** 分区小标题（置顶 / 最近对话 / 日期分组）：与固定行标签同款 11sp 加粗样式 */
@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp
    )
}

@Composable
internal fun DrawerConversationItem(
    title: String,
    selected: Boolean,
    selectMode: Boolean,
    checked: Boolean,
    pinned: Boolean,
    archived: Boolean,
    menuExpanded: Boolean,
    onMenuDismiss: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit
) {
    Box {
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
                // 置顶会话用实心图钉、归档会话用归档图标；选中与置顶都提亮为 primary
                Icon(
                    when {
                        archived -> Icons.Outlined.Archive
                        pinned -> Icons.Filled.PushPin
                        else -> Icons.Outlined.Forum
                    },
                    contentDescription = when {
                        archived -> "已归档"
                        pinned -> "已置顶"
                        else -> null
                    },
                    modifier = Modifier.size(18.dp),
                    tint = when {
                        selected -> MaterialTheme.colorScheme.primary
                        archived -> MaterialTheme.colorScheme.onSurfaceVariant
                        pinned -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Text(
                title,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 归档会话标题弱化显示
                color = when {
                    selected -> MaterialTheme.colorScheme.primary
                    archived -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
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
        // 长按操作弹窗：置顶 / 归档 / 删除（重命名在条目右侧编辑图标，不在此重复；
        // 删除为危险操作，回调后仍走「删除对话」确认框，用 error 配色明示）
        if (menuExpanded) {
            AlertDialog(
                onDismissRequest = onMenuDismiss,
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 已归档会话与置顶互斥，不提供置顶项
                        if (!archived) {
                            DrawerActionRow(
                                icon = if (pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                                label = if (pinned) "取消置顶" else "置顶",
                                onClick = onTogglePin
                            )
                        }
                        DrawerActionRow(
                            icon = if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                            label = if (archived) "取消归档" else "归档",
                            onClick = onToggleArchive
                        )
                        DrawerActionRow(
                            icon = Icons.Default.Delete,
                            label = "删除",
                            container = MaterialTheme.colorScheme.errorContainer,
                            // 与抽屉底部「删除」大按钮同款：errorContainer 底 + error 前景
                            content = MaterialTheme.colorScheme.error,
                            onClick = onDelete
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {}
            )
        }
    }
}

/**
 * 长按操作弹窗里的一行动作：圆角矩形 tile（16dp 大圆角），
 * 行高 48dp 达到最小触控目标；块间间隔由调用方 spacedBy(6.dp) 拉开；
 * 默认 tertiary 色调，置顶/归档共用一套（12% 透明底 + tertiary 前景）；
 * 删除行传 errorContainer 底 + error 前景，与抽屉底部「删除」大按钮一致。
 */
@Composable
private fun DrawerActionRow(
    icon: ImageVector,
    label: String,
    container: Color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
    content: Color = MaterialTheme.colorScheme.tertiary,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = container,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = content)
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 15.sp, color = content)
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
fun Modifier.drawerDragGesture(
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
