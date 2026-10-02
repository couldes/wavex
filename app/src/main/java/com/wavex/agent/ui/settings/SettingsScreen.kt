package com.wavex.agent.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
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
import com.wavex.agent.state.WavexViewModel
import com.wavex.agent.ui.ThemeChoice
import com.wavex.agent.ui.REASONING_LEVELS
import com.wavex.agent.ui.bottomInputClearance
import com.wavex.agent.data.Provider
import com.wavex.agent.network.ApiClient
import com.wavex.agent.data.PROVIDER_PRESETS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    modifier: Modifier,
    state: WavexViewModel
) {
    var editing by remember { mutableStateOf<Provider?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    // 服务商列表收进底部弹层（抽屉式）：设置页只留当前服务商卡片，不再被 N 张卡挤满
    var showProviderSheet by remember { mutableStateOf(false) }
    // 长按删除的目标（选择服务商弹层内长按 = 删除确认）
    var deleteProviderTarget by remember { mutableStateOf<Provider?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // ---- 自动备份文件夹（SAF tree）：卸载重装后仍存在、可恢复的可靠备份层 ----
    // 状态声明在恢复弹窗之前：恢复确认弹窗要根据「文件夹可用」显示快照说明
    var backupStatus by remember { mutableStateOf(state.backupFolderStatus()) }
    val canSnapshot = backupStatus.configured && backupStatus.accessible

    // ---- 自动备份文件夹操作（状态声明在上方导入弹窗之前） ----
    var pendingFolderRestore by remember { mutableStateOf<Uri?>(null) }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        var picked = false
        if (uri != null) {
            picked = state.setBackupFolder(uri)
            Toast.makeText(context, if (picked) "备份文件夹已设置" else "无法访问所选文件夹", Toast.LENGTH_SHORT).show()
        }
        backupStatus = state.backupFolderStatus()
        if (picked) scope.launch {
            // 设置成功后立即备份一次：让用户当场确认链路通了，而不是等下次内容变更
            if (!state.backupToFolderNow()) Toast.makeText(context, "首次备份失败，请检查文件夹", Toast.LENGTH_SHORT).show()
            backupStatus = state.backupFolderStatus()
        }
    }
    pendingFolderRestore?.let { tree ->
        AlertDialog(
            onDismissRequest = { pendingFolderRestore = null },
            title = { Text("从备份文件夹恢复") },
            text = { Text(
                "将扫描文件夹内的备份文件，从最新一份有效备份恢复，并替换当前全部 ${state.conversations.size} 个对话。当前对话会被覆盖，确定继续吗？" +
                    if (canSnapshot) "\n替换前会先把当前对话自动快照到备份文件夹，恢复错了可再恢复回来。" else ""
            ) },
            confirmButton = {
                TextButton(onClick = {
                    pendingFolderRestore = null
                    scope.launch {
                        val n = state.restoreFromBackupFolder(tree)
                        Toast.makeText(context, if (n > 0) "已恢复 $n 个对话" else "文件夹里没有可恢复的备份", Toast.LENGTH_SHORT).show()
                        backupStatus = state.backupFolderStatus()
                    }
                }) { Text("恢复", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingFolderRestore = null }) { Text("取消") } }
        )
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
                        "「默认」由模型自行决定；不支持的模型会自动忽略。\n" +
                            "Kimi/GLM/Qwen 只分开/关：低~极致效果相同，均开启思考；DeepSeek 思考由模型名决定",
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
                        ThemePreviewCard("跟随系统", ThemeChoice.SYSTEM, state.themeChoice, darkPreview = isSystemInDarkTheme()) { state.changeThemeChoice(it) }
                        ThemePreviewCard("亮色", ThemeChoice.LIGHT, state.themeChoice, darkPreview = false) { state.changeThemeChoice(it) }
                        ThemePreviewCard("暗色", ThemeChoice.DARK, state.themeChoice, darkPreview = true) { state.changeThemeChoice(it) }
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
                    // 自动备份文件夹：SAF 选定用户目录（卸载重装后文件仍在，可从中恢复）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { folderLauncher.launch(null) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("备份文件夹", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                when {
                                    !backupStatus.configured -> "未设置：卸载重装后对话会丢失，点击选择"
                                    !backupStatus.accessible -> "之前选的文件夹已无法访问，点击重新授权"
                                    else -> (backupStatus.label ?: "已设置") + when (val at = backupStatus.lastAt) {
                                        null -> " · 尚未备份"
                                        else -> " · 最近备份 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(at))
                                    }
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    if (backupStatus.configured) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        // 立即备份：绕过自动节流，当场验证备份链路
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val ok = state.backupToFolderNow()
                                        Toast.makeText(context, if (ok) "已备份到文件夹" else "备份失败：文件夹不可访问", Toast.LENGTH_SHORT).show()
                                        backupStatus = state.backupFolderStatus()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("立即备份", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("现在把全部对话备份到所选文件夹", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        // 从备份文件夹恢复：卸载重装后的找回路径
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { state.backupFolderUri()?.let { pendingFolderRestore = it } }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("从备份文件夹恢复", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("扫描文件夹里的备份，替换当前全部对话", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        // 停止自动备份：解除文件夹授权（文件夹里的备份文件保留）
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    state.setBackupFolder(null)
                                    backupStatus = state.backupFolderStatus()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("停止自动备份", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("解除备份文件夹（已备份的文件保留）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
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
                                    testLines = ApiClient.probe(p).summaryLines()
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
        },
        confirmButton = {
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
