package com.wavex.agent.ui.usage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavex.agent.data.UsageRange
import com.wavex.agent.data.clampCustomRange
import com.wavex.agent.state.UsagePageData
import com.wavex.agent.state.WavexViewModel
import com.wavex.agent.ui.bottomInputClearance
import java.time.Instant
import java.time.ZoneId

/**
 * 用量统计页（底部 tab）：设置页「数据」区入口可快捷跳转。
 * 单一 LazyColumn：日期范围 → 概览卡 → 筛选 → 图表/表格/日志。
 * 数据整体存在 WavexViewModel.usageData（无异步加载态，聚合在 UsageStore 内存完成）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsageScreen(modifier: Modifier = Modifier, state: WavexViewModel) {
    // 切到本 tab 即刷新一次（ movableContent 移动不重跑 LaunchedEffect(Unit)，
    // 改用 selectedTab 作 key：每次进入 tab都会重新触发）
    LaunchedEffect(state.selectedTab) { state.refreshUsageData() }

    var customPickerOpen by remember { mutableStateOf(false) }
    // 调用日志弹层：用量页只留入口行，最多 50 条记录的列表收进弹层，页面不再被拉长
    var showLogsSheet by remember { mutableStateOf(false) }

    // 布局：让位 Spacer 独立放列表之后（与模型/设置页同款，给底部 tab 栏留空间）
    Column(modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 日期范围 ----
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf(
                        UsageRange.Today to "今天",
                        UsageRange.Yesterday to "昨天",
                        UsageRange.Last7Days to "近7天",
                        UsageRange.Last30Days to "近30天",
                        UsageRange.ThisMonth to "本月"
                    )
                    presets.forEach { (range, label) ->
                        FilterChip(
                            selected = state.usageRange == range,
                            onClick = { state.changeUsageRange(range) },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                    // 自定义 chip：选中态需「是 Custom 实例」而非实例相等
                    FilterChip(
                        selected = state.usageRange is UsageRange.Custom,
                        onClick = { customPickerOpen = true },
                        label = {
                            val c = state.usageRange as? UsageRange.Custom
                            Text(if (c != null) "${fmtDay(c.startMs)}~${fmtDay(c.endMs)}" else "自定义", fontSize = 12.sp)
                        }
                    )
                }
            }

            // ---- 概览卡 ----
            item { OverviewCards(state.usageData) }

            // ---- 筛选（Provider + kind） ----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("按服务商", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.usageProviderFilter == null,
                            onClick = { state.changeUsageProviderFilter(null) },
                            label = { Text("全部", fontSize = 12.sp) }
                        )
                        // 选项来自当前区间内的 providerStats（所见即可筛）
                        state.usageData.providerStats.forEach { ps ->
                            FilterChip(
                                selected = state.usageProviderFilter == ps.providerId,
                                onClick = {
                                    state.changeUsageProviderFilter(if (state.usageProviderFilter == ps.providerId) null else ps.providerId)
                                },
                                label = { Text(ps.providerName, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }

            // ---- 图表 / 表格 / 日志入口（plan Task 10；日志列表在 UsageLogSheet 弹层） ----
            item { Text("趋势", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            item { UsageTrendChart(state.usageData.trends) }
            item { ProviderStatsTable(state.usageData.providerStats) }
            item { ModelStatsTable(state.usageData.modelStats) }
            // 调用日志入口卡：副标题直接反映该范围内有无记录（无记录时不可点）
            item {
                val logs = state.usageData.recentLogs
                Card(
                    Modifier.fillMaxWidth().clickable(enabled = logs.isNotEmpty()) { showLogsSheet = true },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("调用日志", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (logs.isEmpty()) "该范围内暂无调用记录" else "共 ${logs.size} 条 · 点击查看明细",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Spacer(Modifier.bottomInputClearance())
    }

    if (customPickerOpen) {
        CustomRangeDialog(
            onDismiss = { customPickerOpen = false },
            onConfirm = { s, e ->
                customPickerOpen = false
                // 防呆上限 30 天（spec：不是数据上限）
                val (cs, ce) = clampCustomRange(s, e)
                state.changeUsageRange(UsageRange.Custom(cs, ce))
            }
        )
    }

    if (showLogsSheet) {
        UsageLogSheet(state.usageData.recentLogs) { showLogsSheet = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(onDismiss: () -> Unit, onConfirm: (Long, Long) -> Unit) {
    val pickerState = rememberDateRangePickerState()
    // 必须用 DatePickerDialog 而非 AlertDialog 装载 DateRangePicker（BOM 无 DateRangePickerDialog
    // 便捷封装，但 DatePickerDialog 正是官方指定的容器）：
    // - AlertDialog 的 text 面板在手机上只有 ~290dp 宽，而月份格子是 7×48dp 固定尺寸（336dp+），
    //   装不下 → 末列被裁/重叠（排版混乱），点按落点错位（选不了日期）；
    // - DatePickerDialog 用 requiredWidth(360dp) + heightIn(max=568dp)（M3 modal tokens），
    //   恰好容纳日历网格，确定/取消按钮也不会被挤出屏幕。
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = pickerState.selectedStartDateMillis != null && pickerState.selectedEndDateMillis != null,
                onClick = {
                    // DatePicker 给的是 UTC 00:00：转本地整天避免时区漂移
                    val zone = ZoneId.systemDefault()
                    val s = Instant.ofEpochMilli(pickerState.selectedStartDateMillis!!).atZone(zone).toLocalDate()
                    val e = Instant.ofEpochMilli(pickerState.selectedEndDateMillis!!).atZone(zone).toLocalDate()
                    onConfirm(s.atStartOfDay(zone).toInstant().toEpochMilli(), e.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1)
                }
            ) { Text("确定") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } }
    ) {
        // showModeToggle=false：范围选择不需要输入模式切换；weight(1f) 让本体占满
        // 容器剩余高度（对话框已用 token 限高，本体在内部自行滚动月份列表）。
        // 标题/ headline 自定义内边距：官方预留 64dp 给模式切换图标（已关），
        // 改为 12dp 与日历内容（DatePickerHorizontalPadding）左对齐。
        DateRangePicker(
            state = pickerState,
            showModeToggle = false,
            title = {
                androidx.compose.material3.DateRangePickerDefaults.DateRangePickerTitle(
                    displayMode = pickerState.displayMode,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp)
                )
            },
            headline = {
                // 官方 headline 是 28sp 大字 Row{起, -, 止}，360dp 对话框装不下两个完整
                // 日期，会在日期中间折行（实测断成 “…– 2026年10 / 月8日”）。数字为半宽，
                // 16sp 下两个完整日期仅 ≈254dp，单行放得下；maxLines=1 兼底不折行。
                // 时区必须与网格一致（DatePicker 内部以 UTC 整天为格）。
                val zone = java.time.ZoneId.of("UTC")
                fun label(millis: Long?): String? = millis?.let {
                    java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
                        .let { d -> "${d.year}年${d.monthValue}月${d.dayOfMonth}日" }
                }
                val s = label(pickerState.selectedStartDateMillis)
                val e = label(pickerState.selectedEndDateMillis)
                val text = when {
                    s != null && e != null -> "$s – $e"
                    s != null -> "$s – 结束日期"
                    else -> "开始日期 – 结束日期"
                }
                Text(
                    text,
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            modifier = Modifier.weight(1f)
        )
    }
}

/** 三张概览卡（spec：请求次数 / 成功率 / 总Tokens）；消息数 = kind 筛选选「对话」的视角 */
@Composable
private fun OverviewCards(data: UsagePageData) {
    val s = data.summary
    val rate = if (s.requests == 0L) "—" else "${s.success * 100 / s.requests}%"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricCard(Modifier.weight(1f), "请求次数", formatCount(s.requests), "成功 ${formatCount(s.success)}")
        MetricCard(Modifier.weight(1f), "成功率", rate, "取消/失败计入分母")
        MetricCard(Modifier.weight(1f), "总 Tokens", formatCount(s.inputTokens + s.outputTokens), "输入 ${formatCount(s.inputTokens)} · 输出 ${formatCount(s.outputTokens)}")
    }
}

@Composable
private fun MetricCard(modifier: Modifier, label: String, value: String, footnote: String) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp, 12.dp)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(4.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.size(2.dp))
            Text(footnote, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

// ---- Task 10 组件在 UsageCharts.kt / UsageTables.kt ----

/** 大数缩写：1234 → 1.2k，1234567 → 1.23M（概览卡/图表轴共用，Task 10 一起提走） */
internal fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.2fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

internal fun fmtDay(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let { "%d/%d".format(it.monthValue, it.dayOfMonth) }
