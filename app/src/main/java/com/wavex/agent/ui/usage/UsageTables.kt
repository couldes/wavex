package com.wavex.agent.ui.usage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wavex.agent.data.ModelStat
import com.wavex.agent.data.ProviderStat
import com.wavex.agent.data.UsageLogEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 统计明细（plan Task 10）：服务商表 / 模型表 / 调用日志。
 * 表格直接用 Row 组合（数据量小，无需表格库）；
 * 日志收进底部弹层（用量页只留入口行，列表在弹层内 LazyColumn 按需组合），点击行看单条详情。
 */

@Composable
internal fun ProviderStatsTable(stats: List<ProviderStat>) {
    SectionCard(title = "服务商统计") {
        TableHead(listOf("服务商" to 2f, "次数" to 1f, "Tokens" to 1.2f, "成功率" to 1f))
        stats.forEachIndexed { i, s ->
            TableRow(
                cells = listOf(
                    s.providerName to 2f,
                    s.requests.toString() to 1f,
                    formatCount(s.inputTokens + s.outputTokens) to 1.2f,
                    (if (s.requests == 0L) "—" else "${s.success * 100 / s.requests}%") to 1f
                )
            )
            if (i < stats.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
internal fun ModelStatsTable(stats: List<ModelStat>) {
    SectionCard(title = "模型统计") {
        TableHead(listOf("模型" to 2f, "次数" to 1f, "Tokens" to 1.2f))
        stats.forEachIndexed { i, m ->
            TableRow(
                cells = listOf(
                    m.model to 2f,
                    m.requests.toString() to 1f,
                    formatCount(m.inputTokens + m.outputTokens) to 1.2f
                )
            )
            if (i < stats.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(4.dp))
            content()
        }
    }
}

@Composable
private fun TableHead(cells: List<Pair<String, Float>>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        cells.forEach { (label, w) ->
            Text(
                label, Modifier.weight(w), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TableRow(cells: List<Pair<String, Float>>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { i, (text, w) ->
            Text(
                text, Modifier.weight(w),
                fontSize = 12.sp,
                fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

/**
 * 调用日志弹层：样式同设置页「选择服务商」抽屉，倒序列表（LazyColumn 按需组合），
 * 点击行看单条详情（错误摘要完整展示）。详情弹窗与列表弹窗是两个独立窗口，详情置顶。
 */
@Composable
internal fun UsageLogSheet(logs: List<UsageLogEntry>, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<UsageLogEntry?>(null) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // 点卡片以外任意位置（半透明遮罩区）关闭
                .pointerInput(Unit) {
                    detectTapGestures { onDismiss() }
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
                        .padding(top = 14.dp, bottom = 12.dp)
                ) {
                    Text("调用日志", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        "该范围内共 ${logs.size} 条（最近优先）",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(logs) { entry ->
                            UsageLogRow(entry) { selected = entry }
                        }
                    }
                }
            }
        }
    }
    selected?.let { e -> UsageLogDetailDialog(e) { selected = null } }
}

@Composable
private fun UsageLogRow(entry: UsageLogEntry, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.statusCode in 200..299) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${entry.providerName} · ${entry.model}",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                )
                Text(
                    "${fmtTime(entry.createdAt)} · ${kindLabel(entry.kind)} · ${entry.statusCode}" +
                        if (entry.error.isNotBlank()) " · ${entry.error.take(40)}" else "",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
            Text(
                "${formatCount(entry.inputTokens)}/${formatCount(entry.outputTokens)}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun UsageLogDetailDialog(e: UsageLogEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kindLabel(e.kind) + "调用详情", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                DetailRow("时间", fmtTime(e.createdAt))
                DetailRow("服务商", e.providerName)
                DetailRow("模型", e.model)
                DetailRow("状态码", e.statusCode.toString())
                DetailRow("耗时", "${e.latencyMs} ms")
                DetailRow("输入 Tokens", e.inputTokens.toString())
                DetailRow("输出 Tokens", e.outputTokens.toString())
                if (e.error.isNotBlank()) DetailRow("错误", e.error)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

internal fun kindLabel(kind: String): String = when (kind) {
    "chat" -> "对话"
    "title" -> "标题"
    "probe" -> "探测"
    else -> kind
}

private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")

internal fun fmtTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)
