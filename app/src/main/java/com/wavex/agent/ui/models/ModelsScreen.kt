package com.wavex.agent.ui.models

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import com.wavex.agent.state.WavexViewModel
import com.wavex.agent.ui.MainTab
import com.wavex.agent.ui.bottomInputClearance
import com.wavex.agent.network.ApiClient
import com.wavex.agent.data.PROVIDER_PRESETS

@Composable
internal fun ModelsScreen(
    modifier: Modifier,
    state: WavexViewModel
) {
    val provider = state.currentProvider
    val scope = rememberCoroutineScope()
    // 拉取状态单一来源：WavexViewModel 的快照 map（可观察，切 Tab 后 movableContent 重建时仍在）。
    // 之前这里还有一份本地 remember 副本，与 map 双向同步，容易漂移。
    val fetchedModels = state.modelsFetched[provider?.id]
    val loading = state.modelsLoading[provider?.id] ?: false
    // 手动输入面板的展开态与草稿单一来源：直接委托到 WavexViewModel 的属性
    // （此前本地 remember + 全局 map 双份状态，双向同步容易漂移）
    var showManual by state::modelsShowManual
    var manualModel by state::modelsManualModel

    fun refresh() {
        val p = provider ?: return
        if (p.baseUrl.isBlank() || p.apiKey.isBlank()) return
        scope.launch {
            state.modelsLoading = state.modelsLoading + (p.id to true)
            val models = ApiClient.fetchModels(p)
            // 拉取失败（null）不写入：保留原列表（持久化缓存或预设兑底）继续展示，
            // 之前把 emptyList() 写进去会让「暂无模型列表」卡住到下次切换服务商
            if (models != null) state.recordFetchedModels(p.id, models)
            state.modelsLoading = state.modelsLoading + (p.id to false)
        }
    }

    LaunchedEffect(provider?.id, provider?.apiKey) {
        // 每次进页都后台重校验：先显示持久化的上次列表，拉到新结果才原地更新。
        // 冷启动首帧不再闪预设兑底名单；列表没变时无感。
        if (provider != null && provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank()) {
            refresh()
        }
    }

    if (provider == null) {
        // 未配置服务商：引导去设置，不暴露任何复杂操作
        Column(
            modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Outlined.Memory, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("还没有配置服务商", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                "在设置里添加一个服务商后，模型列表会自动同步过来",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Button(
                onClick = { state.selectedTab = MainTab.SETTINGS },
                modifier = Modifier.padding(top = 16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("去设置")
            }
        }
        return
    }

    val preset by remember(provider.baseUrl) {
        mutableStateOf(PROVIDER_PRESETS.firstOrNull { it.baseUrl == provider.baseUrl })
    }
    val modelIds = (fetchedModels ?: preset?.fallbackModels)
        ?.filter { it.isNotBlank() }
        ?: listOf(provider.model).filter { it.isNotBlank() }
    val selectedModel = provider.model
    var searchQuery by remember { mutableStateOf(state.modelsSearchQuery) }
    LaunchedEffect(Unit) {
        snapshotFlow { searchQuery }.collect { state.modelsSearchQuery = it }
    }
    val displayedModels = remember(modelIds, searchQuery) {
        if (searchQuery.isBlank()) modelIds
        else modelIds.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
    }

    // 布局：让位 Spacer 独立放列表之后（与聊天页同款）。
    // bottomInputClearance 直接挂 LazyColumn 会与外层 fillMaxSize 的
    // minHeight=maxHeight 约束冲突，键盘弹出或内容不足一屏时列表整体下移。
    Column(modifier) {
    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索模型…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(10.dp))
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(provider.name.ifEmpty { "未命名服务商" }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        if (loading) "正在同步模型列表…" else (fetchedModels?.let { "已同步 ${it.size} 个模型" } ?: "使用默认模型列表（连接后自动同步）"),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { refresh() }, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新模型列表")
                }
            }
        }
        items(displayedModels) { name ->
            ModelCard(
                name = name,
                selected = name == selectedModel,
                onClick = { state.updateModel(name) }
            )
        }
        if (displayedModels.isEmpty()) {
            item {
                Text(
                    if (loading) "正在同步模型列表…" else "暂无模型列表，请在下方手动输入模型名",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
        if (displayedModels.isEmpty() && searchQuery.isNotBlank()) {
            item {
                Text(
                    "没有匹配「${searchQuery.trim()}」的模型",
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        item {
            if (showManual) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("模型名", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            // 关闭手动输入：之前展开后没有任何退出的途径
                            IconButton(onClick = { showManual = false; manualModel = "" }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "收起", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = manualModel,
                                onValueChange = { manualModel = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("例如 kimi-k2-0711-preview") },
                                singleLine = true
                            )
                            TextButton(
                                onClick = {
                                    if (manualModel.isNotBlank()) {
                                        state.updateModel(manualModel.trim())
                                        manualModel = ""
                                        showManual = false
                                    }
                                },
                                modifier = Modifier.padding(start = 8.dp)
                            ) { Text("使用") }
                        }
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { showManual = true }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("  手动输入模型名", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    Spacer(Modifier.bottomInputClearance())
    }
}

@Composable
internal fun ModelCard(name: String, selected: Boolean, onClick: () -> Unit) {
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
                modifier = Modifier.size(40.dp),
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
            Text(
                name,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
