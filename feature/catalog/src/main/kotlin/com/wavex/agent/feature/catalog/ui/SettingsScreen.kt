package com.wavex.agent.feature.catalog.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import com.wavex.agent.feature.catalog.CatalogViewModel
import com.wavex.agent.feature.catalog.CatalogViewModel.ThemeChoice
import com.wavex.agent.feature.catalog.PROVIDER_PRESETS
import com.wavex.agent.core.domain.chat.ReasoningLevels
import com.wavex.agent.core.ui.bottomInsetClearance
import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.domain.port.ModelService

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
    requestWritePermission: () -> Unit
) {
    var editing by remember { mutableStateOf<Provider?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    // 服务商列表收进底部弹层（抽屉式）：设置页只留当前服务商卡片，不再被 N 张卡挤满
    var showProviderSheet by remember { mutableStateOf(false) }
    // 长按删除的目标（选择服务商弹层内长按 = 删除确认）
    var deleteProviderTarget by remember { mutableStateOf<Provider?>(null) }

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
                    Text(
                        "「默认」由模型自行决定；不支持的模型会自动忽略。",
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
                            Text("从旧备份文件导入", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("选择旧的备份 json/bak 文件，导入为新会话（不覆盖现有对话）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
internal fun AddProviderChip(onClick: () -> Unit) {
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
internal fun ProviderCard(
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
internal fun ProviderEditDialog(
    initial: Provider?,
    onDismiss: () -> Unit,
    onSave: (Provider) -> Unit,
    modelService: ModelService
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
    // 第一行是结论，第二行起是 App 推断出来的细节（协议/鉴权头/最终地址/耗时）
    var testLines by remember { mutableStateOf<List<String>>(emptyList()) }
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
        name = name.trim(),
        baseUrl = baseUrl.trim().ifBlank {
            // 新增时留空 = 采用预设官方地址；自定义预设/编辑留空则保持空（由下方校验拦截）
            if (isNew) selectedPreset?.baseUrl.orEmpty() else initial?.baseUrl.orEmpty()
        },
        apiKey = apiKey.trim(),
        model = initial?.model
            ?: PROVIDER_PRESETS.firstOrNull { it.label == presetLabel }?.defaultModel?.takeIf { it.isNotBlank() }
            ?: ""
    )

    Dialog(
        onDismissRequest = onDismiss,
        // 独立窗口默认 decorFitsSystemWindows=true：键盘弹出时窗口被一次性 resize、
        // 弹窗瞬间重新居中，高表单在缩小后的空间里重排——「顶上去卡一下」。
        // 关掉窗口自适应后由 imePadding() 消费 IME inset，弹窗随键盘动画平滑上移
        // （同会话页「放大编辑」弹窗的修复）。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // 点遮罩区（卡片以外任意位置）关闭 = AlertDialog 点外部取消
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .imePadding()
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    // 消费卡片区内的点击，避免冒泡到遮罩误关
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp
            ) {
                Column {
                    // 表单可内部滚动：键盘弹出后剩余高度不足时内容不顶出卡片
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp)
                    ) {
                        Text(
                            if (isNew) "添加服务商" else "编辑服务商",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
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
                            label = { Text("名称") },
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
                                        testLines = listOf(
                                            if (p.baseUrl.isBlank()) "请填写 Base URL（或选择一个服务商预设后留空）"
                                            else "请填写 API Key"
                                        )
                                    } else {
                                        scope.launch {
                                            testing = true
                                            testLines = emptyList()
                                            // 体检内部会依次试 /models 与极小对话请求，并自动处理鉴权头方言
                                            testLines = modelService.probe(p).summaryLines()
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
                        testLines.forEachIndexed { index, line ->
                            Text(
                                line,
                                fontSize = if (index == 0) 12.sp else 11.sp,
                                color = when {
                                    // 详情行只是说明「App 替你选了什么」，不做成败色
                                    index > 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                                    testLines.first().startsWith("连接成功") -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.error
                                },
                                modifier = if (index > 0) Modifier.padding(top = 2.dp) else Modifier
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        TextButton(
                            onClick = {
                                val p = buildProvider()
                                // 名称必填；Base URL 仍可选（预设回填官方地址），两者的报错提示区分开
                                if (name.isBlank()) {
                                    testLines = listOf("请填写名称")
                                    return@TextButton
                                }
                                if (p.baseUrl.isBlank() || p.apiKey.isBlank()) {
                                    testLines = listOf(
                                        if (p.baseUrl.isBlank()) "请填写 Base URL（或选择一个服务商预设后留空）"
                                        else "请填写 API Key"
                                    )
                                    return@TextButton
                                }
                                if (p.model.isBlank()) {
                                    // 自定义服务商未指定默认模型：自动拉取模型列表选第一个，失败则留空由用户在模型页选择
                                    testing = true
                                    scope.launch {
                                        val models = modelService.fetchModels(p)
                                        testing = false
                                        onSave(if (models.isNullOrEmpty()) p else p.copy(model = models.first()))
                                    }
                                } else {
                                    onSave(p)
                                }
                            }
                        ) { Text("保存") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingSectionTitle(text: String) {
    Text(text, Modifier.padding(top = 18.dp, bottom = 8.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SegmentedControl(
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

@Composable
internal fun RowScope.ThemePreviewCard(
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
