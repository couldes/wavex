package com.wavex.agent.core.ui

import androidx.compose.ui.text.style.TextAlign

internal sealed interface Block {
    data class Text(val content: String) : Block
    data class Code(val language: String, val content: String) : Block
    /** LaTeX 数学块（$$…$$ / \[…\]，可跨行） */
    data class Math(val latex: String) : Block
    /** 水平分隔线（--- / *** / ___） */
    object Divider : Block
    /** GFM 表格：首行表头、第二行分隔（---）、后续数据行；单元格支持行内格式；
     *  对齐取自分隔行的 :--- / ---: / :---: */
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val alignments: List<TextAlign>
    ) : Block
}

// 预编译正则（避免组合/流式路径上反复 Pattern.compile）
private val FENCE_START = Regex("^```(\\w*)")
internal val LIST_BULLET = Regex("^[-*+\\u2022] ")
internal val LIST_ORDERED = Regex("^\\d+[.．)] ")
private val HORIZONTAL_RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
// GFM 分隔行：每个单元格 = 可选冒号 + 至少一个短横线 + 可选冒号（|-|-| 也合法）
private val TABLE_DIVIDER = Regex("^\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")

/** 按竖线切分表格行（忽略转义竖线 \|），去首尾空段 */
private fun splitTableRow(line: String): List<String> {
    val cells = mutableListOf<String>()
    val sb = StringBuilder()
    var escaped = false
    for (ch in line) {
        when {
            escaped -> { sb.append(ch); escaped = false }
            ch == '\\' -> escaped = true
            ch == '|' -> { cells.add(sb.toString().trim()); sb.clear() }
            else -> sb.append(ch)
        }
    }
    cells.add(sb.toString().trim())
    // 首尾空段来自行首/行尾的边界竖线
    val from = if (cells.isNotEmpty() && cells.first().isEmpty()) 1 else 0
    val to = if (cells.size > from && cells.last().isEmpty()) cells.size - 1 else cells.size
    return cells.subList(from, to).map { it.replace("\\|", "|") }
}

private fun looksLikeTableRow(line: String): Boolean =
    line.trim().startsWith("|") && line.trim().endsWith("|") && line.count { it == '|' } >= 2

/** 分隔行单元格 → 列对齐：:---: 居中、---: 右、默认左 */
private fun dividerAlign(seg: String): TextAlign = when {
    seg.startsWith(":") && seg.endsWith(":") -> TextAlign.Center
    seg.endsWith(":") -> TextAlign.Right
    else -> TextAlign.Left
}

internal fun splitBlocks(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = text.lines()
    var i = 0
    val buffer = StringBuilder()
    fun flushText() {
        if (buffer.isNotBlank()) {
            blocks.add(Block.Text(buffer.toString().trimEnd()))
        }
        buffer.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        when {
            FENCE_START.containsMatchIn(line.trim()) -> {
                flushText()
                val language = FENCE_START.find(line.trim())?.groupValues?.get(1) ?: ""
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                i++ // 跳过收尾 ```
                blocks.add(Block.Code(language, code.toString().trimEnd()))
            }
            // 水平分隔线：--- / *** / ___（三个及以上；预编译顶层常量，
            // 旧写法在逐行循环里内联编译 Pattern，流式期间每帧重复编译）
            HORIZONTAL_RULE.matches(line.trim()) -> {
                flushText()
                blocks.add(Block.Divider)
                i++
            }
            // 块级数学：$$…$$ 或 \[…\]（可跨行；同行的成对定界符也识别）
            line.trim().startsWith("$$") || line.trim().startsWith("\\[") -> {
                flushText()
                val trimmed = line.trim()
                val opener = if (trimmed.startsWith("$$")) "$$" else "\\["
                val closer = if (opener == "$$") "$$" else "\\]"
                val latex = StringBuilder()
                var closed = false
                val inner = trimmed.removePrefix(opener)
                if (inner.endsWith(closer)) {
                    // 单行成对：$$ x^2 $$ / \[ x \]
                    latex.append(inner.removeSuffix(closer).trim())
                    closed = true
                    i++
                } else {
                    // 跨行：收集到含结尾定界符的行为止（流式未闭合则到 EOF）
                    if (inner.isNotBlank()) latex.appendLine(inner)
                    i++
                    while (i < lines.size) {
                        val l = lines[i].trim()
                        if (l.endsWith(closer)) {
                            val body = l.removeSuffix(closer)
                            if (body.isNotBlank()) latex.appendLine(body)
                            closed = true
                            i++
                            break
                        }
                        latex.appendLine(lines[i])
                        i++
                    }
                }
                val latexText = latex.toString().trim()
                if (latexText.isNotBlank()) blocks.add(Block.Math(latexText))
                if (!closed) i = lines.size
            }
            // GFM 表格：当前行像表格行且下一行是分隔行（|---|---| / |-|-|）
            looksLikeTableRow(line) && i + 1 < lines.size && TABLE_DIVIDER.matches(lines[i + 1].trim()) -> {
                flushText()
                val header = splitTableRow(line)
                val aligns = splitTableRow(lines[i + 1].trim()).map { dividerAlign(it) }
                i += 2 // 跳过表头和分隔行
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && looksLikeTableRow(lines[i])) {
                    val cells = splitTableRow(lines[i])
                    rows.add(cells)
                    i++
                }
                blocks.add(Block.Table(header, rows, aligns))
            }
            else -> {
                buffer.appendLine(line)
                i++
            }
        }
    }
    flushText()
    return blocks
}
/** 行内解析结果：文本 + 行内公式占位（Text 的 inlineContent 用） */
