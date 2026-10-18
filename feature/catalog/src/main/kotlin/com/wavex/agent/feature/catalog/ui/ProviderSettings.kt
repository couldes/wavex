package com.wavex.agent.feature.catalog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.domain.port.ModelService
import com.wavex.agent.feature.catalog.PROVIDER_PRESETS
import kotlinx.coroutines.launch

/**
 * 服务商卡片、编辑弹窗与其键盘跟随放置。
 * 从 SettingsScreen.kt 拆出（Task 8 Step 3）。
 */

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
    onEdit: () -> Unit,
    onDuplicate: () -> Unit
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
            // 复制：同 Base URL + Key 再存一份（换 Key / 换模型的变体入口），不用重填
            IconButton(onClick = onDuplicate) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制服务商",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 弹窗卡片的键盘跟随（最小位移版）。
 *
 * 旧实现 `align(Center) + imePadding()`：IME 高度成为卡片自身底部 padding，
 * 「卡片+padding」整块在满屏窗口里居中，键盘一弹卡片就整体上移 IME/2；
 * 矮屏手机上「卡片自然高 + IME > 窗口高」，卡片被压满可用区后被居中对齐
 * 钉在 y=0 —— 盖住状态栏、表单骤然进入滚动，观感即「弹一下顶到手机顶部」。
 *
 * 现在的策略（同会话列表编辑跟随的最小滚动原则）：
 * - 键盘顶边未碰到卡片底部：卡片保持居中，一动不动；
 * - 碰到后：随键盘 1:1 上移，只移「底边贴住键盘顶边」的最小量；
 * - 窗口高 − IME − 状态栏不足以容纳整卡：卡片封顶为可用高度（表单内部
 *   滚动兜底），顶部停在状态栏之下，永不越过屏幕顶端。
 */
private fun Modifier.dialogImePlacement(ime: WindowInsets, topInset: WindowInsets): Modifier =
    layout { measurable, constraints ->
        val imeBottom = ime.getBottom(this)
        val topPx = topInset.getTop(this)
        val available = (constraints.maxHeight - imeBottom - topPx).coerceAtLeast(0)
        val placeable = measurable.measure(
            constraints.copy(minHeight = 0, maxHeight = available)
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.placeRelative(
                0,
                providerDialogTop(constraints.maxHeight, placeable.height, imeBottom, topPx)
            )
        }
    }

/** 卡片顶部 y（px）：居中位与「底边贴键盘」取小者，再夹到状态栏之下。 */
internal fun providerDialogTop(
    windowHeight: Int,
    cardHeight: Int,
    imeBottom: Int,
    topInset: Int
): Int = maxOf(
    topInset,
    minOf((windowHeight - cardHeight) / 2, windowHeight - imeBottom - cardHeight)
)

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
        // 预设不再内置默认模型：留空保存时统一自动拉取模型列表选第一个（见下方保存逻辑）
        model = initial?.model ?: ""
    )

    Dialog(
        onDismissRequest = onDismiss,
        // 独立窗口默认 decorFitsSystemWindows=true：键盘弹出时窗口被一次性 resize、
        // 弹窗瞬间重新居中，高表单在缩小后的空间里重排——「顶上去卡一下」。
        // 关掉窗口自适应后由下方 dialogImePlacement 消费 IME inset：键盘碰到
        // 卡片前不动，之后随键盘最小位移上移（矮屏不再整卡钉到屏幕顶端）。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val imeInsets = WindowInsets.ime
        val topInsets = WindowInsets.statusBars
        Box(
            Modifier
                .fillMaxSize()
                // 点遮罩区（卡片以外任意位置）关闭 = AlertDialog 点外部取消
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .dialogImePlacement(imeInsets, topInsets)
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
                            // 预设横向单行滑动：比换行流少占一行高度，小屏上键盘弹出后
                            // 卡片更难被压缩；超出宽度的预设左右滑可及，不会截断丢失
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PROVIDER_PRESETS.forEach { preset ->
                                    val selected = presetLabel == preset.label
                                    // 圆角矩形小块：胶囊 chip 在横滑一行里视觉太重，
                                    // 改用与 SegmentedControl 同族的圆角矩形语汇，高度也更矮
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.secondaryContainer
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                            .clickable { applyPreset(preset.label) }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            preset.label,
                                            fontSize = 11.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
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
                                    // 未指定模型（预设也不再内置默认值）：自动拉取模型列表选第一个，失败则留空由用户在模型页选择
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

