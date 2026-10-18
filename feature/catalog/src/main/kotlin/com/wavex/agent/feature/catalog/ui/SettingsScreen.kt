package com.wavex.agent.feature.catalog.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import com.wavex.agent.feature.catalog.CatalogViewModel
import com.wavex.agent.feature.catalog.CatalogViewModel.ThemeChoice
import com.wavex.agent.core.domain.chat.EffectiveReasoning
import com.wavex.agent.core.domain.chat.ReasoningLevels
import com.wavex.agent.core.ui.bottomInsetClearance
import com.wavex.agent.core.domain.model.ApiProtocols
import com.wavex.agent.core.domain.model.Provider

/**
 * 设置页「已归档会话」条目。app 壳从会话域映射成此模型（feature:catalog 不依赖
 * feature:conversation），归档/删除动作也由壳层按 id 转发回会话域。
 */
data class ArchivedConversationUi(
    val id: String,
    val title: String,
    val lastActiveAt: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier,
    viewModel: CatalogViewModel,
    /** 旧备份 SAF 导入（SAF 流读取+解析留在 app 壳）；返回导入数，失败为 null */
    onImportLegacyFile: suspend (Uri) -> Int?,
    /** 打开系统「所有文件访问」授权页（存储设置行，授权页返回由宿主 onResume 收敛） */
    onOpenStoragePermissionScreen: () -> Unit,
    /** 运行时写权限弹窗（SDK 26-29）；授权结果由宿主 onResume 收敛 */
    requestWritePermission: () -> Unit,
    /** 归档会话列表（抽屉不再展示归档区，管理入口从这里进） */
    archivedConversations: List<ArchivedConversationUi>,
    /** 取消归档（会话回到抽屉列表） */
    onUnarchiveConversation: (String) -> Unit,
    /** 删除归档会话（不可恢复，弹层内已有二次确认） */
    onDeleteConversation: (String) -> Unit
) {
    var editing by remember { mutableStateOf<Provider?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    // 服务商列表收进底部弹层（抽屉式）：设置页只留当前服务商卡片，不再被 N 张卡挤满
    var showProviderSheet by remember { mutableStateOf(false) }
    // 长按删除的目标（选择服务商弹层内长按 = 删除确认）
    var deleteProviderTarget by remember { mutableStateOf<Provider?>(null) }
    // 已归档会话管理弹层（抽屉归档区移除后的管理入口）
    var showArchivedSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val modelService = viewModel.modelService
    // ---- 会话存储授权（per-session JSONL 目录）：公共目录需要「所有文件访问」（SDK 30+）
    // 或 WRITE_EXTERNAL_STORAGE（SDK 26-29）。授权页/权限弹窗返回后由 MainActivity.onResume
    // 统一调 refreshSessionStorage（重解析目录、必要时搬移会话文件、刷新状态），此处不重复刷。
    val openStoragePermissionScreen: () -> Unit = {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            onOpenStoragePermissionScreen()
        } else {
            requestWritePermission()
        }
    }
    // 旧备份一次性导入（spec §7.3）：SAF 单次挑文件（OpenDocument），不取持久授权；
    // 每个旧会话以新 id 导入为新会话文件并追加到列表尾部，不覆盖现有对话。取消选择（null）不动。
    val importScope = rememberCoroutineScope()
    val importLegacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importScope.launch {
            // 错误边界：导入内部已挡读/写 IO 异常并优雅返回，此处兜底残余异常（如 OOME），
            // 不让未捕获异常杀进程。CancellationException（导入中关闭页面取消 importScope）
            // 必须先重抛、按取消传播，不能落进失败分支弹「导入失败」toast（final review）。
            val n = try {
                onImportLegacyFile(uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            Toast.makeText(
                context,
                when {
                    n == null -> "导入失败，请重试"
                    n > 0 -> "已导入 $n 个对话"
                    else -> "未识别出对话"
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

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
        if (viewModel.providers.isEmpty()) {
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
            viewModel.currentProvider?.let { currentProvider ->
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
            if (viewModel.providers.size > 1) {
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
                            "全部 ${viewModel.providers.size} 个服务商",
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
                    // 说明文案按当前模型的真实支持形态生成：开/关族不该被告知「有档位」，
                    // 模型决定型更不该让人以为选了就生效（用户最容易踩的就是这个静默失败）
                    Text(
                        reasoningHintOf(viewModel.reasoningEffort, viewModel.currentProvider),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    // Mihon 式分段选择：圆角容器内放选项，选中项胶囊高亮
                    SegmentedControl(
                        options = ReasoningLevels.displayLevels,
                        selected = viewModel.reasoningEffort,
                        onSelect = { viewModel.changeReasoningEffort(it) }
                    )
                    // 当前档位在本模型下的实际含义（与对话页状态条同一套推导，不是另写一份）
                    viewModel.currentProvider?.let { p ->
                        val effective = EffectiveReasoning.preview(
                            requested = viewModel.reasoningEffort,
                            model = p.model,
                            protocol = ApiProtocols.detect(p.baseUrl),
                        )
                        Text(
                            "本模型实际：${effective.label}　—　${effective.detail}",
                            fontSize = 11.sp,
                            color = if (effective.applied) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // 归档会话管理入口：归档的对话沉出抽屉列表，从这里找回/删除
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().clickable { showArchivedSheet = true }
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Archive, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("已归档会话", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "归档的对话移出抽屉列表，可在此取消归档或删除",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "${archivedConversations.size}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                        ThemePreviewCard("跟随系统", ThemeChoice.SYSTEM, viewModel.themeChoice, darkPreview = isSystemInDarkTheme()) { viewModel.changeThemeChoice(it) }
                        ThemePreviewCard("亮色", ThemeChoice.LIGHT, viewModel.themeChoice, darkPreview = false) { viewModel.changeThemeChoice(it) }
                        ThemePreviewCard("暗色", ThemeChoice.DARK, viewModel.themeChoice, darkPreview = true) { viewModel.changeThemeChoice(it) }
                    }
                }
            }
        }
        item {
            SettingSectionTitle(SearchSettingsText.SECTION_TITLE)
            // v1 只读（spec §7.3）：搜索源切换留给后续版本，这里只让用户知道
            // 「搜的是什么源」「流量走哪里」——中转站不参与是这套方案的核心卖点
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.TravelExplore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            SearchSettingsText.SOURCE_LINE,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            SearchSettingsText.LOCAL_NOTE,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            SettingSectionTitle("数据")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    val storageStatus = viewModel.sessionStorageStatus
                    // 行 1：存储位置状态（只读展示；仍在私有目录时点击 = 前往授权，与行 2 同一动作）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !storageStatus.usingPublic) { openStoragePermissionScreen() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("会话存储位置", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            // 空 path 显示「…」占位（Task 7 watch-item c）：初始哨兵/刷新失败期间可能为空串
                            Text(
                                if (storageStatus.usingPublic) "公共目录 ${storageStatus.path.ifEmpty { "…" }}（卸载重装后保留）"
                                else "应用私有目录（卸载重装后丢失）",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!storageStatus.usingPublic) {
                            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (!storageStatus.usingPublic) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        // 行 2：仅私有目录时显示——引导开启「所有文件访问」（SDK 30+）/ 写权限（SDK 26-29）
                        Row(
                            Modifier.fillMaxWidth().clickable { openStoragePermissionScreen() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("开启所有文件访问", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("开启后对话文件移入公共目录，卸载重装不丢失", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // 行 3：旧备份一次性导入（spec §7.3）：与存储档位无关，恒可导入
                    Row(
                        Modifier.fillMaxWidth().clickable { importLegacyLauncher.launch(arrayOf("*/*")) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("从外部文件导入", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("选择外部 json/bak 文件，导入为新会话（不覆盖现有对话）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    Spacer(Modifier.bottomInsetClearance(bottomBarHeight = 58.dp))
    }

    if (showAdd) {
        ProviderEditDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { viewModel.upsertProvider(it); showAdd = false },
            modelService = modelService
        )
    }
    editing?.let { editingProvider ->
        ProviderEditDialog(
            initial = editingProvider,
            onDismiss = { editing = null },
            onSave = { viewModel.upsertProvider(it); editing = null },
            modelService = modelService
        )
    }
    deleteProviderTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteProviderTarget = null },
            title = { Text("删除服务商") },
            text = { Text("确定删除「${target.name.ifEmpty { "未命名" }}」吗？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeProvider(target)
                    deleteProviderTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteProviderTarget = null }) { Text("取消") }
            }
        )
    }
    if (showArchivedSheet) {
        ArchivedConversationsDialog(
            archived = archivedConversations,
            onUnarchive = onUnarchiveConversation,
            onDelete = onDeleteConversation,
            onDismiss = { showArchivedSheet = false }
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
                            items(viewModel.providers, key = { it.id }) { provider ->
                                val selected = provider.id == viewModel.currentProviderId
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                        .combinedClickable(
                                            onClick = {
                                                viewModel.selectProvider(provider)
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
