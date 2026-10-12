package com.wavex.agent.core.ui

import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 列宽测量结果全局缓存：remember 是组合级的，item 滚出视口即失效；
 * 回滚/二次经过时 TextMeasurer 要把每个单元格重新布局一遍（实测 9-36ms/表，
 * 90Hz 下整帧超预算，长会话滚动时每张表都卡一下）。全局缓存后每张表只在
 * 首次组合时测量一次。key 覆盖全部影响宽度的输入：单元格文本、对齐、
 * 颜色（经 inlineOf 影响行内占位）、密度。流式期间变化中的表会生成
 * 临时 key，有限占用缓存位。
 */
private val tableColumnWidthCache = LruCache<String, List<Dp>>(256)

/** 列宽缓存键；与 [measureTableColumnWidths] 的输入一一对应 */
internal fun tableColumnWidthsKey(
    block: Block.Table,
    codeBg: Color,
    linkColor: Color,
    baseColor: Color,
    density: Density,
): String = buildString {
    append(codeBg.value).append('\u0001')
    append(linkColor.value).append('\u0001')
    append(baseColor.value).append('\u0001')
    append(density.density).append('\u0001')
    append("h:").append(block.header.joinToString("\u0002")).append('\u0001')
    append("r:").append(block.rows.joinToString("\u0001") { it.joinToString("\u0002") }).append('\u0001')
    append("a:").append(block.alignments.joinToString("\u0002") { it.value.toString() })
}

/**
 * 每列统一宽度 = 最宽单元格文本宽 + 左右 padding + 1dp 冗余（防 px/dp 取整后折行）。
 * 只在列宽缓存 miss 时调用（TextMeasurer 逐格测量，一张表几 ms 到几十 ms）。
 */
private fun measureTableColumnWidths(
    block: Block.Table,
    codeBg: Color,
    linkColor: Color,
    baseColor: Color,
    density: Density,
    measurer: TextMeasurer,
): List<Dp> {
    val padPx = with(density) { 20.dp.toPx() }
    val slackPx = with(density) { 3.dp.toPx() }
    val minPx = with(density) { 48.dp.toPx() }
    val maxPx = with(density) { 220.dp.toPx() }
    return (0 until block.header.size).map { col ->
        val align = block.alignments.getOrNull(col) ?: TextAlign.Left
        val headText = inlineOf(block.header.getOrNull(col) ?: "", codeBg, linkColor, baseColor, density)
        // letterSpacing 必须与下方单元格 Text 一致（显式 0）：
        // Text 会合并 M3 bodyLarge 的 0.5sp 字间距，若测量时用默认值，
        // 实际渲染比测量宽出每字符 1px+，末字符会被挤折行（实测 Python→Pytho/n）
        var w = measurer.measure(
            headText.string,
            style = TextStyle(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                letterSpacing = 0.sp,
                fontWeight = FontWeight.Bold
            ),
            softWrap = false,
            maxLines = 1,
            placeholders = headText.string.inlinePlaceholderRanges(headText.contents)
        ).size.width
        block.rows.forEach { row ->
            val cell = inlineOf(row.getOrNull(col) ?: "", codeBg, linkColor, baseColor, density)
            w = maxOf(w, measurer.measure(
                cell.string,
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp, textAlign = align),
                softWrap = false,
                maxLines = 1,
                placeholders = cell.string.inlinePlaceholderRanges(cell.contents)
            ).size.width)
        }
        (w + padPx + slackPx).coerceIn(minPx, maxPx).let { with(density) { it.toDp() } }
    }
}

/**
 * GFM 表格渲染：外框 + 全网格线 + 跨行严格对齐的列。
 * 对齐的关键：先用 TextMeasurer 预测量每列最宽单元格，再把该宽度固定到
 * 整列的每个单元格上（此前每个单元格各自 widthIn(min=64dp)，各列各行宽度
 * 互不相干，跨行完全对不上）。列对齐支持 :--- / ---: / :---:。
 * 宽表横向滚动（列宽有上限），窄表不拉伸。
 */
@Composable
internal fun TableBlock(block: Block.Table) {
    // 同 InlineBlock：onSurface 低透明度，表格所在气泡（surfaceVariant）上可见
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val linkColor = MaterialTheme.colorScheme.primary
    val baseColor = MaterialTheme.colorScheme.onSurface
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    // 每列统一宽度 = 最宽单元格文本宽 + 左右 padding + 1dp 冗余（防 px/dp 取整后折行）；
    // 结果入全局缓存（见 tableColumnWidthCache），二次组合零测量成本
    val columnWidths: List<Dp> = remember(block, codeBg, linkColor, baseColor, density) {
        val key = tableColumnWidthsKey(block, codeBg, linkColor, baseColor, density)
        tableColumnWidthCache.get(key) ?: measureTableColumnWidths(
            block, codeBg, linkColor, baseColor, density, measurer
        ).also { tableColumnWidthCache.put(key, it) }
    }

    // 只在列总宽真的溢出容器时才套 horizontalScroll：放得下的表格不消费横拖，
    // 其上方/左缘也能划出抽屉（溢出的宽表横向滚动优先）。
    // BoxWithConstraints 拿容器宽度；maxWidth 此处已扣除气泡内边距
    BoxWithConstraints {
        val tableWidth = columnWidths.fold(0.dp) { acc, w -> acc + w } + 2.dp
        val overflow = with(density) { tableWidth.toPx() > maxWidth.toPx() }
        // 行/分隔线的精确内容宽：HorizontalDivider 内部是 fillMaxWidth，不锁宽
        // 会填满气泡内容宽，把整个表格外框撑成满宽（小表格两侧多出大片空白）
        val rowsWidth = columnWidths.fold(0.dp) { acc, w -> acc + w }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
        ) {
            Column(
                Modifier
                    .then(if (overflow) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            ) {
            // 竖线：在每行背景上按列宽累加位置画（各列统一宽度后跨行严格对齐）
            val verticals: Modifier = Modifier.drawBehind {
                var x = 0f
                val stroke = 0.5.dp.toPx()
                columnWidths.dropLast(1).forEach { w ->
                    x += w.toPx()
                    drawLine(borderColor.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                }
            }
            // 表头（background 在前 drawBehind 在后：先铺底色再画竖线，否则竖线被底色盖住）
            Row(Modifier.background(headerBg).then(verticals)) {
                block.header.forEachIndexed { col, cell ->
                    val result = inlineOf(cell, codeBg, linkColor, baseColor, density)
                    Text(
                        result.string,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        letterSpacing = 0.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = block.alignments.getOrNull(col) ?: TextAlign.Left,
                        inlineContent = result.contents,
                        modifier = Modifier
                            .width(columnWidths[col])
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.width(rowsWidth),
                thickness = 1.dp,
                color = borderColor
            )
            block.rows.forEachIndexed { rowIndex, row ->
                Row(verticals) {
                    block.header.indices.forEach { col ->
                        val result = inlineOf(row.getOrNull(col) ?: "", codeBg, linkColor, baseColor, density)
                        Text(
                            result.string,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            letterSpacing = 0.sp,
                            textAlign = block.alignments.getOrNull(col) ?: TextAlign.Left,
                            inlineContent = result.contents,
                            modifier = Modifier
                                .width(columnWidths[col])
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                if (rowIndex != block.rows.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.width(rowsWidth),
                        thickness = 0.5.dp,
                        color = borderColor.copy(alpha = 0.5f)
                    )
                }
            }
            }
        }
    }
}


// ==================== 代码块：标题栏（语言 + 复制）+ 横向滚动 + 轻量语法着色 ====================

/** 代码着色盘：按代码底色亮度选亮/暗一套，保证两种主题下对比度都够 */
