package com.wavex.agent.ui.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavex.agent.data.TrendPoint
import java.time.LocalDate

/**
 * 每日趋势柱状图（plan Task 10）：Canvas 手绘零依赖。
 * 柱高按「输入+输出 token」占比；点击柱子弹出当日数值明细。
 * spec：点柱子浮出当日明细（无跨天钻取，弹窗即终点）。
 */
@Composable
internal fun UsageTrendChart(trends: List<TrendPoint>) {
    var selected by remember { mutableStateOf<TrendPoint?>(null) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (trends.size < 2) {
                Text(
                    "数据不足两天，暂无趋势",
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ChartCanvas(
                    trends = trends,
                    barColor = MaterialTheme.colorScheme.primary,
                    axisColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    onTapDay = { selected = it }
                )
                Spacer(Modifier.height(6.dp))
                // 首尾标注（中间刻度在柱状图下意义不大）：日桶显示日期，小时桶显示小时
                Row(Modifier.fillMaxWidth()) {
                    Text(fmtTrendLabel(trends.first().key), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(fmtTrendLabel(trends.last().key), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    selected?.let { day ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(fmtTrendLabel(day.key), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    DetailRow("请求次数", day.requests.toString())
                    DetailRow("输入 Tokens", day.inputTokens.toString())
                    DetailRow("输出 Tokens", day.outputTokens.toString())
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }
        )
    }
}

@Composable
private fun ChartCanvas(
    trends: List<TrendPoint>,
    barColor: Color,
    axisColor: Color,
    modifier: Modifier,
    onTapDay: (TrendPoint) -> Unit
) {
    val maxTokens = trends.maxOf { it.inputTokens + it.outputTokens }.coerceAtLeast(1L)
    val n = trends.size
    Canvas(
        modifier.pointerInput(trends) {
            // 点按位置换算柱索引：等宽槽位均分，无需额外状态
            detectTapGestures { offset ->
                val idx = (offset.x / (size.width / n)).toInt().coerceIn(0, n - 1)
                onTapDay(trends[idx])
            }
        }
    ) {
        // 布局：n 根柱子等宽均分宽度；柱体占槽位 62%，两侧留缝
        val slot = size.width / n
        val barW = slot * 0.62f
        val axisY = size.height - 1.dp.toPx()
        // 底轴线
        drawLine(axisColor, Offset(0f, axisY), Offset(size.width, axisY), strokeWidth = 1.dp.toPx())
        trends.forEachIndexed { i, t ->
            val tokens = t.inputTokens + t.outputTokens
            val h = (tokens / maxTokens.toFloat()) * (size.height - 10.dp.toPx())
            val x = i * slot + (slot - barW) / 2f
            // 零值日画 2dp 的基线小点，保持「日历连续感」
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, axisY - h.coerceAtLeast(2.dp.toPx())),
                size = Size(barW, h.coerceAtLeast(2.dp.toPx())),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
            )
        }
    }
}

@Composable
internal fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 趋势轴标签：ISO 日期 → "1月15日"；小时标签（"14时"）原样返回（图表轴/弹窗标题共用） */
internal fun fmtTrendLabel(key: String): String = try {
    val d = LocalDate.parse(key)
    "${d.monthValue}月${d.dayOfMonth}日"
} catch (_: Exception) {
    key
}
