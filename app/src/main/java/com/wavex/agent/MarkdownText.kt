package com.wavex.agent

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染：代码块、行内代码、加粗、标题、列表、引用。
 * 覆盖模型回复中最常见的格式，避免满屏 ** 和 ## 乱码。
 *
 * 性能（流式输出的关键路径）：
 * - 所有正则预编译为顶层常量（此前每行每次重组都 Pattern.compile，长回复每帧几百次）；
 * - 块切分结果 remember(text)（非流式重组/切 Tab 回来不再重切）；
 * - 行内解析结果按「行文本 + 代码底色」做全局 LruCache：流式期间只有正在生长的
 *   末行会 miss，其余行拿到同一实例 → Text 直接跳过，每帧只重排末行。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    // 代码块标题栏的复制回调（剪贴板/Toast 留给调用方，本文件不碰 Context）
    onCopyCode: (String) -> Unit = {}
) {
    val blocks = remember(text) { splitBlocks(text) }
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is Block.Code -> CodeBlock(block, onCopyCode)
                is Block.Table -> TableBlock(block)
                is Block.Math -> MathBlock(block.latex, color)
                Block.Divider -> HorizontalDivider(
                    thickness = 0.5.dp,
                    color = color.copy(alpha = 0.25f),
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                is Block.Text -> InlineBlock(block.content, color)
            }
            if (index != blocks.lastIndex && block !is Block.Code) Spacer(Modifier.height(6.dp))
        }
    }
}

private sealed interface Block {
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
private val LIST_BULLET = Regex("^[-*+\\u2022] ")
private val LIST_ORDERED = Regex("^\\d+[.．)] ")
private val HORIZONTAL_RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val TABLE_DIVIDER = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

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

private fun splitBlocks(text: String): List<Block> {
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
            // GFM 表格：当前行像表格行且下一行是分隔行（|---|---|）
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
private class InlineResult(val string: AnnotatedString, val contents: Map<String, InlineTextContent>)

/** 行内解析缓存：key = 底色 + 链接色 + 正文色 + 密度 + 行文本（同实例 → Text 跳过重排） */
private val inlineCache = LruCache<String, InlineResult>(4096)

private fun inlineOf(
    line: String,
    codeBg: Color,
    linkColor: Color,
    baseColor: Color,
    density: Density
): InlineResult {
    // 颜色入 key：亮/暗主题下各色不同，避免切主题后命中旧色缓存
    val key = codeBg.value.toString() + "\u0001" + linkColor.value.toString() + "\u0001" +
        baseColor.value.toString() + "\u0001" + density.density.toString() + "\u0001" + line
    inlineCache.get(key)?.let { return it }
    val built = buildInline(line, codeBg, linkColor, baseColor, density)
    inlineCache.put(key, built)
    return built
}

/** 文本块：行首处理标题/列表/引用；连续普通行合并成段落级 Text。
 *  性能：段落划分结果 remember(content) 缓存；每段 AnnotatedString 走全局
 *  LruCache。流式期间只有末段在生长（miss），其余段落每帧直接命中 →
 *  重组几乎零解析。选区性能：一个段落 = 一个 Selectable（不是每行一个）。 */
@Composable
private fun InlineBlock(content: String, baseColor: Color) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val paragraphs = remember(content) { splitParagraphs(content) }
    Column {
        paragraphs.forEach { p ->
            when (p.kind) {
                PKind.Blank -> Spacer(Modifier.height(4.dp))
                PKind.H3 -> inlineText(
                    p.text.removePrefix("### "), codeBg, linkColor, baseColor, density,
                    fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = baseColor
                )
                PKind.H2 -> inlineText(
                    p.text.removePrefix("## "), codeBg, linkColor, baseColor, density,
                    fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = baseColor
                )
                PKind.H1 -> inlineText(
                    p.text.removePrefix("# "), codeBg, linkColor, baseColor, density,
                    fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, color = baseColor
                )
                PKind.Quote -> Row {
                    Spacer(Modifier.width(8.dp))
                    inlineText(
                        p.text.removePrefix("> "), codeBg, linkColor, baseColor, density,
                        fontSize = 14.sp, lineHeight = 20.sp,
                        color = baseColor.copy(alpha = 0.75f)
                    )
                }
                PKind.Bullet -> Row {
                    // 项目符号：保留原文的 -/*/+ 形式为统一的圆点，正文取符号后的内容
                    val content = p.text.substringAfter(' ').trim()
                    Text("•  ", fontSize = 15.sp, lineHeight = 22.sp, color = baseColor)
                    inlineText(
                        content, codeBg, linkColor, baseColor, density,
                        fontSize = 15.sp, lineHeight = 22.sp,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                PKind.Ordered -> {
                    val num = LIST_ORDERED.find(p.text)?.value?.trimEnd(' ', '.', '．', ')') ?: "1."
                    val rest = p.text.substringAfter(" ")
                    Row {
                        Text("$num.  ", fontSize = 15.sp, lineHeight = 22.sp, color = baseColor, fontWeight = FontWeight.Medium)
                        inlineText(
                            rest, codeBg, linkColor, baseColor, density,
                            fontSize = 15.sp, lineHeight = 22.sp,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                // 普通段落：多行已合并成一个字符串，一个 Text = 一个 Selectable
                PKind.Paragraph -> inlineText(
                    p.text, codeBg, linkColor, baseColor, density,
                    fontSize = 15.sp, lineHeight = 22.sp
                )
            }
        }
    }
}

/** 行内 Text：带公式 inlineContent 的统一封装（所有段落共用） */
@Composable
private fun inlineText(
    line: String,
    codeBg: Color,
    linkColor: Color,
    baseColor: Color,
    density: Density,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight? = null,
    color: Color = baseColor
) {
    val result = inlineOf(line, codeBg, linkColor, baseColor, density)
    Text(
        result.string,
        modifier = modifier,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        color = color,
        inlineContent = result.contents
    )
}

/** 段落类型与预划分（remember 缓存；流式期间只有末段变化，其余段直接复用） */
private enum class PKind { Blank, H1, H2, H3, Quote, Bullet, Ordered, Paragraph }
private data class Paragraph(val kind: PKind, val text: String)

private fun splitParagraphs(content: String): List<Paragraph> {
    val out = mutableListOf<Paragraph>()
    val buf = StringBuilder()
    fun flush() {
        if (buf.isNotEmpty()) {
            out.add(Paragraph(PKind.Paragraph, buf.toString().trimEnd('\n')))
            buf.setLength(0)
        }
    }
    content.lines().forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.isBlank() -> { flush(); out.add(Paragraph(PKind.Blank, "")) }
            line.startsWith("#### ") -> { flush(); out.add(Paragraph(PKind.H3, "### " + line.removePrefix("#### "))) }
            line.startsWith("### ") -> { flush(); out.add(Paragraph(PKind.H3, line)) }
            line.startsWith("## ") -> { flush(); out.add(Paragraph(PKind.H2, line)) }
            line.startsWith("# ") -> { flush(); out.add(Paragraph(PKind.H1, line)) }
            line.startsWith("> ") || line.startsWith(">\u3010") || line == ">" -> {
                flush(); out.add(Paragraph(PKind.Quote, if (line.startsWith("> ")) line else "> " + line.removePrefix(">")))
            }
            LIST_BULLET.containsMatchIn(line) -> { flush(); out.add(Paragraph(PKind.Bullet, line)) }
            LIST_ORDERED.containsMatchIn(line) -> { flush(); out.add(Paragraph(PKind.Ordered, line)) }
            else -> { if (buf.isNotEmpty()) buf.append('\n'); buf.append(line) }
        }
    }
    flush()
    return out
}

/** 行内解析：**加粗**、*斜体*、`代码`、~~删除线~~、行内公式、链接——支持嵌套（加粗里可以有公式） */
private fun buildInline(
    text: String,
    codeBg: Color,
    linkColor: Color,
    baseColor: Color,
    density: Density
): InlineResult {
    val contents = LinkedHashMap<String, InlineTextContent>()
    var formulaSeq = 0
    val string = buildAnnotatedString {
        appendInlineSegment(
            text, 0, text.length, null,
            codeBg, linkColor, baseColor, density, contents, { formulaSeq++ }, 0
        )
    }
    return InlineResult(string, contents)
}

/** 嵌套深度上限：防止病态输入（****…）无限递归 */
private const val MAX_INLINE_DEPTH = 4

/**
 * 行内扫描段：扫 [from, to) 区间，公式/链接/样式 span 都限制在该区间内。
 * activeStyle = 当前继承的样式上下文（加粗/斜体叠加后合并传入），
 * 嵌套标记（** inside * 等）通过递归 + 样式合并实现——修复
 * 「加粗段内的 \(…\) 行内公式不渲染」的确定性 bug。
 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlineSegment(
    text: String,
    from: Int,
    to: Int,
    activeStyle: SpanStyle?,
    codeBg: Color,
    linkColor: Color,
    baseColor: Color,
    density: Density,
    contents: MutableMap<String, InlineTextContent>,
    nextId: () -> Int,
    depth: Int
) {
    var i = from
    while (i < to) {
        val c = text[i]
        when {
            // 行内公式：\(…\)（图片占位不受文字样式影响，直接渲染）
            c == '\\' && i + 1 < to && text[i + 1] == '(' -> {
                val end = text.indexOf("\\)", i + 2)
                if (end >= i + 2 && end + 2 <= to) {
                    appendInlineMath(text.substring(i + 2, end), baseColor, density, contents, nextId)
                    i = end + 2
                } else {
                    append(c); i++
                }
            }
            // 行内公式：$…$
            c == '$' && i + 1 < to && text[i + 1] != '$' && text[i + 1] != ' ' && text[i + 1] != '\n' -> {
                val end = text.indexOf('$', i + 1)
                if (end > i + 1 && end < to && (end + 1 >= to || text[end + 1] != '\n')) {
                    appendInlineMath(text.substring(i + 1, end), baseColor, density, contents, nextId)
                    i = end + 1
                } else {
                    append(c); i++
                }
            }
            // [标签](网址)：渲染成可点击链接。
            // 注意：不能用 `?.let{...; null} ?: run{...}` 惯用法——let 恒返回 null，
            // 匹配成功后还会再走 run 分支重复 append 一个字符；链接恰好在文本末尾时
            // i == length 直接越界闪退（线上实际发生过）
            c == '[' -> {
                val m = mdLinkMatch(text, i)
                if (m != null && m.third <= to) {
                    withStyle(activeStyle ?: SpanStyle()) {
                        withLink(LinkAnnotation.Url(m.second, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                            append(m.first)
                        }
                    }
                    i = m.third
                } else {
                    append(c); i++
                }
            }
            // 裸网址（https://… / www.…）：自动识别为可点击链接
            c == 'h' || c == 'w' -> {
                val m = bareUrlMatch(text, i)
                if (m != null && m.third <= to) {
                    withStyle(activeStyle ?: SpanStyle()) {
                        withLink(LinkAnnotation.Url(m.second, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                            append(m.first)
                        }
                    }
                    i = m.third
                } else {
                    append(c); i++
                }
            }
            // ~~删除线~~：内容递归（删除线里可以有公式/加粗）
            text.startsWith("~~", i) && i + 2 <= to -> {
                val end = text.indexOf("~~", i + 2)
                if (end >= i + 2 && end + 2 <= to) {
                    val merged = (activeStyle ?: SpanStyle()).merge(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    withStyle(merged) {
                        appendInlineSegment(text, i + 2, end, merged, codeBg, linkColor, baseColor, density, contents, nextId, depth + 1)
                    }
                    i = end + 2
                } else {
                    append(c); i++
                }
            }
            // **加粗**：内容递归（修复加粗段内公式不渲染）
            text.startsWith("**", i) && i + 2 <= to -> {
                val end = text.indexOf("**", i + 2)
                if (end >= i + 2 && end + 2 <= to) {
                    val merged = (activeStyle ?: SpanStyle()).merge(SpanStyle(fontWeight = FontWeight.Bold))
                    withStyle(merged) {
                        appendInlineSegment(text, i + 2, end, merged, codeBg, linkColor, baseColor, density, contents, nextId, depth + 1)
                    }
                    i = end + 2
                } else {
                    append(c); i++
                }
            }
            // `代码`：内容保持原样，不递归（代码就是字面量）
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end >= i + 1 && end < to) {
                    withStyle(
                        (activeStyle ?: SpanStyle()).merge(
                            SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, background = codeBg)
                        )
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(c); i++
                }
            }
            // *斜体*：内容递归
            c == '*' && i + 1 < to && text[i + 1] != '*' && text[i + 1] != ' ' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && end < to) {
                    val merged = (activeStyle ?: SpanStyle()).merge(SpanStyle(fontStyle = FontStyle.Italic))
                    withStyle(merged) {
                        appendInlineSegment(text, i + 1, end, merged, codeBg, linkColor, baseColor, density, contents, nextId, depth + 1)
                    }
                    i = end + 1
                } else {
                    append(c); i++
                }
            }
            else -> {
                append(c); i++
            }
        }
        // 深度兜底（理论到不了：每层区间严格缩小）
        if (depth > MAX_INLINE_DEPTH) break
    }
}

/** 行内公式：追占位符 + 注册 InlineTextContent（公式渲染为位图） */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlineMath(
    latex: String,
    baseColor: Color,
    density: Density,
    contents: MutableMap<String, InlineTextContent>,
    nextId: () -> Int
) {
    val trimmed = latex.trim()
    if (trimmed.isEmpty()) {
        append("")
        return
    }
    val fontPx = with(density) { 13.sp.toPx() }
    val rendered = MathRenderer.render(trimmed, baseColor.hashCode(), fontPx)
    if (rendered == null) {
        // 解析失败：保留原始 LaTeX 文本（比丢内容好）；打日志定位设备端失败原因
        android.util.Log.w("MathRenderer", "inline render failed: " + trimmed)
        append(trimmed)
        return
    }
    val id = "math-inline-" + nextId()
    val widthSp = with(density) { rendered.widthPx.toSp() }
    val heightSp = with(density) { rendered.heightPx.toSp() }
    contents[id] = InlineTextContent(
        Placeholder(widthSp, heightSp, PlaceholderVerticalAlign.TextCenter)
    ) {
        Image(
            bitmap = rendered.bitmap.asImageBitmap(),
            contentDescription = "公式",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
    appendInlineContent(id, " ")
}

// 预编译：[标签](网址) 与裸网址（终结于空白/括号/常用中文标点）
private val MD_LINK = Regex("\\[([^\\]\\n]+)]\\(([^)\\s]+)\\)")
private val BARE_URL = Regex("(?:https?://|www\\.)[^\\s<>\"'（）【】，。；：！？—]+")

/** 裸网址尾部标点：URL 后紧跟句号/逗号等不属于网址本身 */
private const val URL_TRAIL_PUNCT = ".,;:!?…）)\"'」》」"

/** 从 from 起匹配 [标签](网址)；返回 (显示文本, 跳转地址, 结束下标) */
private fun mdLinkMatch(text: String, from: Int): Triple<String, String, Int>? {
    val m = MD_LINK.matchAt(text, from) ?: return null
    var url = m.groupValues[2]
    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
    return Triple(m.groupValues[1], url, m.range.last + 1)
}

/** 从 from 起匹配裸网址（含尾部标点剥离） */
private fun bareUrlMatch(text: String, from: Int): Triple<String, String, Int>? {
    val m = BARE_URL.matchAt(text, from) ?: return null
    var end = m.range.last + 1
    while (end > from + 1 && text[end - 1] in URL_TRAIL_PUNCT) end--
    val display = text.substring(from, end)
    val url = if (display.startsWith("www.")) "https://$display" else display
    return Triple(display, url, end)
}

/**
 * GFM 表格渲染：外框 + 全网格线 + 跨行严格对齐的列。
 * 对齐的关键：先用 TextMeasurer 预测量每列最宽单元格，再把该宽度固定到
 * 整列的每个单元格上（此前每个单元格各自 widthIn(min=64dp)，各列各行宽度
 * 互不相干，跨行完全对不上）。列对齐支持 :--- / ---: / :---:。
 * 宽表横向滚动（列宽有上限），窄表不拉伸。
 */
@Composable
private fun TableBlock(block: Block.Table) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val baseColor = MaterialTheme.colorScheme.onSurface
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    // 每列统一宽度 = 最宽单元格文本宽 + 左右 padding + 1dp 冗余（防 px/dp 取整后折行）
    val columnWidths: List<Dp> = remember(block, codeBg, linkColor, baseColor, density) {
        val padPx = with(density) { 20.dp.toPx() }
        val slackPx = with(density) { 3.dp.toPx() }
        val minPx = with(density) { 48.dp.toPx() }
        val maxPx = with(density) { 220.dp.toPx() }
        (0 until block.header.size).map { col ->
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
                maxLines = 1
            ).size.width
            block.rows.forEach { row ->
                val cell = inlineOf(row.getOrNull(col) ?: "", codeBg, linkColor, baseColor, density)
                w = maxOf(w, measurer.measure(
                    cell.string,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp, textAlign = align),
                    softWrap = false,
                    maxLines = 1
                ).size.width)
            }
            (w + padPx + slackPx).coerceIn(minPx, maxPx).let { with(density) { it.toDp() } }
        }
    }

    // 只在列总宽真的溢出容器时才套 horizontalScroll：放得下的表格不消费横拖，
    // 其上方/左缘也能划出抽屉（溢出的宽表横向滚动优先）。
    // BoxWithConstraints 拿容器宽度；maxWidth 此处已扣除气泡内边距
    BoxWithConstraints {
        val tableWidth = columnWidths.fold(0.dp) { acc, w -> acc + w } + 2.dp
        val overflow = with(density) { tableWidth.toPx() > maxWidth.toPx() }
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
            HorizontalDivider(thickness = 1.dp, color = borderColor)
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
                    HorizontalDivider(thickness = 0.5.dp, color = borderColor.copy(alpha = 0.5f))
                }
            }
            }
        }
    }
}


// ==================== 代码块：标题栏（语言 + 复制）+ 横向滚动 + 轻量语法着色 ====================

/** 代码着色盘：按代码底色亮度选亮/暗一套，保证两种主题下对比度都够 */
private data class CodeColors(
    val keyword: Color,
    val builtin: Color,
    val string: Color,
    val number: Color,
    val comment: Color
)

private fun codeColors(darkBg: Boolean) = if (darkBg) {
    // One Dark 风格（暗底亮色）
    CodeColors(
        keyword = Color(0xFFC678DD),
        builtin = Color(0xFFE5C07B),
        string = Color(0xFF98C379),
        number = Color(0xFFD19A66),
        comment = Color(0xFF8B95A3)
    )
} else {
    // GitHub Light 风格（亮底深色）
    CodeColors(
        keyword = Color(0xFFCF222E),
        builtin = Color(0xFF8250DF),
        string = Color(0xFF0A306D),
        number = Color(0xFF0550AE),
        comment = Color(0xFF6E7781)
    )
}

private enum class CodeToken { Default, Keyword, Builtin, Str, Number, Comment }

private val BASH_LANGS = setOf("bash", "sh", "shell", "zsh", "console", "terminal")
private val PY_LANGS = setOf("python", "python3", "py")
private val PLAIN_LANGS = setOf("text", "txt", "plain", "plaintext", "output", "log")

private val BASH_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
    "case", "esac", "in", "function", "return", "exit", "break", "continue",
    "export", "local", "readonly", "source", "alias", "set", "unset", "shift",
    "trap", "eval", "exec", "wait"
)
private val BASH_BUILTINS = setOf(
    "echo", "printf", "read", "cd", "ls", "cat", "pwd", "mkdir", "rmdir", "rm", "cp", "mv",
    "touch", "ln", "chmod", "chown", "grep", "sed", "awk", "find", "xargs", "tee", "head",
    "tail", "sort", "uniq", "cut", "tr", "wc", "diff", "patch", "file", "strings", "objdump",
    "readelf", "nm", "xxd", "hexdump", "base64", "openssl", "curl", "wget", "tar", "zip",
    "unzip", "gzip", "git", "python", "python3", "pip", "pip3", "node", "npm", "java", "javac",
    "adb", "make", "cmake", "gcc", "g++", "clang", "go", "cargo", "rustc", "sudo", "apt",
    "apt-get", "yum", "brew", "which", "whereis", "man", "nano", "vim", "env", "export", "uname",
    "whoami", "id", "ps", "kill", "df", "du", "mount", "lsblk", "ping", "ssh", "scp", "rsync",
    "netstat", "systeminfo", "powershell", "cmd"
)
private val PY_KEYWORDS = setOf(
    "def", "class", "import", "from", "as", "return", "if", "elif", "else", "for", "while",
    "in", "not", "and", "or", "is", "None", "True", "False", "try", "except", "finally",
    "with", "pass", "raise", "lambda", "yield", "global", "nonlocal", "assert", "del",
    "async", "await", "break", "continue", "match", "case"
)
private val PY_BUILTINS = setOf(
    "print", "open", "input", "int", "str", "float", "bool", "bytes", "list", "dict", "set",
    "tuple", "len", "range", "enumerate", "zip", "map", "filter", "sorted", "reversed", "sum",
    "min", "max", "abs", "round", "type", "isinstance", "getattr", "setattr", "hasattr",
    "self", "cls", "super", "object", "hash", "hex", "bin", "oct", "ord", "chr", "format",
    "vars", "dir", "id", "iter", "next", "any", "all", "repr", "os", "sys", "re", "json",
    "time", "math", "random", "subprocess", "socket", "struct", "hashlib", "base64",
    "threading", "asyncio", "pathlib", "datetime", "collections", "itertools", "functools"
)
private val GENERIC_KEYWORDS = setOf(
    "function", "func", "fn", "def", "return", "if", "else", "elif", "fi", "then", "for",
    "while", "do", "done", "switch", "case", "break", "continue", "class", "struct", "enum",
    "interface", "impl", "trait", "let", "var", "const", "val", "new", "public", "private",
    "protected", "static", "final", "abstract", "override", "open", "import", "package",
    "namespace", "using", "try", "catch", "except", "finally", "throw", "throws", "yield",
    "async", "await", "this", "self", "super", "null", "nil", "none", "true", "false", "void",
    "int", "uint", "float", "double", "char", "bool", "boolean", "string", "long", "short",
    "byte", "end", "and", "or", "not", "in", "is", "typeof", "instanceof", "extends",
    "implements", "delete", "typeof", "operator", "sizeof", "const"
)

private fun isIdentStart(c: Char) = c.isLetter() || c == '_'
private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_'

/** 高亮结果缓存（流式期间只有生长中的代码块会 miss） */
private val codeCache = LruCache<String, AnnotatedString>(256)

/** 超长代码不着色（每帧重复分词的成本不划算） */
private const val MAX_HIGHLIGHT_CHARS = 20000

private fun highlightCode(content: String, language: String, colors: CodeColors): AnnotatedString {
    val lang = language.trim().lowercase()
    if (lang in PLAIN_LANGS || content.length > MAX_HIGHLIGHT_CHARS) return AnnotatedString(content)
    val key = "\u0001code\u0001$lang\u0001${content.hashCode()}\u0001${content.length}"
    codeCache.get(key)?.let { return it }
    val spans = when (lang) {
        in BASH_LANGS -> tokenizeBash(content, colors)
        in PY_LANGS -> tokenizePython(content, colors)
        else -> tokenizeGeneric(content, colors)
    }
    val built = buildAnnotatedString {
        var last = 0
        for ((start, end, token) in spans) {
            if (start > last) append(content.substring(last, start))
            val style = when (token) {
                CodeToken.Keyword -> SpanStyle(color = colors.keyword, fontWeight = FontWeight.Medium)
                CodeToken.Builtin -> SpanStyle(color = colors.builtin)
                CodeToken.Str -> SpanStyle(color = colors.string)
                CodeToken.Number -> SpanStyle(color = colors.number)
                CodeToken.Comment -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
                CodeToken.Default -> null
            }
            if (style != null) withStyle(style) { append(content.substring(start, end)) }
            else append(content.substring(start, end))
            last = end
        }
        if (last < content.length) append(content.substring(last))
    }
    codeCache.put(key, built)
    return built
}

/** bash/sh：行首命令、关键字/常用命令、字符串、$变量、数字、#注释 */
private fun tokenizeBash(content: String, colors: CodeColors): List<Triple<Int, Int, CodeToken>> {
    val spans = mutableListOf<Triple<Int, Int, CodeToken>>()
    var i = 0
    while (i < content.length) {
        val c = content[i]
        when {
            c == '#' -> {
                val end = content.indexOf('\n', i).let { if (it == -1) content.length else it }
                spans.add(Triple(i, end, CodeToken.Comment)); i = end
            }
            c == '\'' -> {
                // bash 单引号内无转义：遇到下一个 ' 或行尾即结束
                var j = i + 1
                while (j < content.length && content[j] != '\'' && content[j] != '\n') j++
                val stop = if (j < content.length && content[j] == '\'') j else j - 1
                spans.add(Triple(i, stop + 1, CodeToken.Str))
                i = (stop + 1).coerceAtLeast(i + 1) // 空串时也要前进，避免死循环
            }
            c == '"' -> {
                var j = i + 1
                while (j < content.length && content[j] != '"' && content[j] != '\n') {
                    if (content[j] == '\\') j++
                    j++
                }
                val stop = if (j < content.length && content[j] == '"') j else j - 1
                spans.add(Triple(i, stop + 1, CodeToken.Str))
                i = (stop + 1).coerceAtLeast(i + 1) // 空串时也要前进，避免死循环
            }
            c == '$' && i + 1 < content.length && (isIdentStart(content[i + 1]) || content[i + 1] == '{') -> {
                var j = i + 1
                if (content[j] == '{') {
                    val end = content.indexOf('}', j)
                    j = if (end == -1) content.length - 1 else end
                } else while (j + 1 < content.length && isIdentPart(content[j + 1])) j++
                spans.add(Triple(i, j + 1, CodeToken.Number)); i = j + 1
            }
            c.isDigit() -> {
                var j = i
                while (j < content.length && (content[j].isLetterOrDigit() || content[j] == '.')) j++
                spans.add(Triple(i, j, CodeToken.Number)); i = j
            }
            isIdentStart(c) -> {
                var j = i
                while (j < content.length && (isIdentPart(content[j]) || content[j] == '-')) j++
                val word = content.substring(i, j)
                val lineStart = content.lastIndexOf('\n', i).let { if (it == -1) 0 else it + 1 }
                val onlySpacesBefore = content.substring(lineStart, i).isBlank()
                val token = when {
                    word in BASH_KEYWORDS -> CodeToken.Keyword
                    word in BASH_BUILTINS -> CodeToken.Builtin
                    onlySpacesBefore -> CodeToken.Builtin // 行首第一个词 = 命令
                    else -> CodeToken.Default
                }
                if (token != CodeToken.Default) spans.add(Triple(i, j, token))
                i = j
            }
            else -> i++
        }
    }
    return spans
}

/** python：'''/"""三引号串（可跨行）、#注释、@装饰器、关键字/内置名、数字 */
private fun tokenizePython(content: String, colors: CodeColors): List<Triple<Int, Int, CodeToken>> {
    val spans = mutableListOf<Triple<Int, Int, CodeToken>>()
    var i = 0
    while (i < content.length) {
        val c = content[i]
        when {
            content.startsWith("\"\"\"", i) || content.startsWith("'''", i) -> {
                val q = content.substring(i, i + 3)
                val end = content.indexOf(q, i + 3)
                val stop = if (end == -1) content.length else end + 3
                spans.add(Triple(i, stop, CodeToken.Str)); i = stop
            }
            c == '#' -> {
                val end = content.indexOf('\n', i).let { if (it == -1) content.length else it }
                spans.add(Triple(i, end, CodeToken.Comment)); i = end
            }
            c == '\'' || c == '"' -> {
                var j = i + 1
                while (j < content.length && content[j] != c && content[j] != '\n') {
                    if (content[j] == '\\') j++
                    j++
                }
                val stop = if (j < content.length && content[j] == c) j else j - 1
                spans.add(Triple(i, stop + 1, CodeToken.Str))
                i = (stop + 1).coerceAtLeast(i + 1) // 空串时也要前进，避免死循环
            }
            c == '@' && i + 1 < content.length && isIdentStart(content[i + 1]) -> {
                var j = i + 1
                while (j < content.length && isIdentPart(content[j])) j++
                spans.add(Triple(i, j, CodeToken.Builtin)); i = j
            }
            c.isDigit() -> {
                var j = i
                while (j < content.length && (content[j].isLetterOrDigit() || content[j] == '.' || content[j] == '_')) j++
                spans.add(Triple(i, j, CodeToken.Number)); i = j
            }
            isIdentStart(c) -> {
                var j = i
                while (j < content.length && isIdentPart(content[j])) j++
                val word = content.substring(i, j)
                val token = when {
                    word in PY_KEYWORDS -> CodeToken.Keyword
                    word in PY_BUILTINS -> CodeToken.Builtin
                    else -> CodeToken.Default
                }
                if (token != CodeToken.Default) spans.add(Triple(i, j, token))
                i = j
            }
            else -> i++
        }
    }
    return spans
}

/** 通用（未标注语言/kotlin/js/go/c…）：//与/* */注释、字符串、@注解、关键字 */
private fun tokenizeGeneric(content: String, colors: CodeColors): List<Triple<Int, Int, CodeToken>> {
    val spans = mutableListOf<Triple<Int, Int, CodeToken>>()
    var i = 0
    while (i < content.length) {
        val c = content[i]
        when {
            content.startsWith("//", i) -> {
                val end = content.indexOf('\n', i).let { if (it == -1) content.length else it }
                spans.add(Triple(i, end, CodeToken.Comment)); i = end
            }
            content.startsWith("/*", i) -> {
                val end = content.indexOf("*/", i + 2)
                val stop = if (end == -1) content.length else end + 2
                spans.add(Triple(i, stop, CodeToken.Comment)); i = stop
            }
            c == '\'' || c == '"' || c == '`' -> {
                var j = i + 1
                while (j < content.length && content[j] != c && content[j] != '\n') {
                    if (content[j] == '\\') j++
                    j++
                }
                val stop = if (j < content.length && content[j] == c) j else j - 1
                spans.add(Triple(i, stop + 1, CodeToken.Str))
                i = (stop + 1).coerceAtLeast(i + 1) // 空串时也要前进，避免死循环
            }
            c == '@' && i + 1 < content.length && isIdentStart(content[i + 1]) -> {
                var j = i + 1
                while (j < content.length && isIdentPart(content[j])) j++
                spans.add(Triple(i, j, CodeToken.Builtin)); i = j
            }
            c.isDigit() -> {
                var j = i
                while (j < content.length && (content[j].isLetterOrDigit() || content[j] == '.')) j++
                spans.add(Triple(i, j, CodeToken.Number)); i = j
            }
            isIdentStart(c) -> {
                var j = i
                while (j < content.length && isIdentPart(content[j])) j++
                if (content.substring(i, j) in GENERIC_KEYWORDS) {
                    spans.add(Triple(i, j, CodeToken.Keyword))
                }
                i = j
            }
            else -> i++
        }
    }
    return spans
}

/**
 * 代码块：标题栏（语言标签 + 复制按钮）+ 溢出时横向滚动 + 轻量语法着色。
 * 长行不再截断（横向滚），bash/python 等常用语言关键字/字符串/注释着色，
 * text/plain 保持素色。不溢出时不加 horizontalScroll（不消费横拖，抽屉可用）。
 */
@Composable
private fun CodeBlock(block: Block.Code, onCopyCode: (String) -> Unit) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    // 暗色代码底（surfaceVariant 亮度 < 0.5）用暗色盘，否则用亮色盘
    val colors = remember(codeBg) { codeColors(codeBg.luminance() < 0.5f) }
    val highlighted = remember(block.content, block.language, colors) {
        highlightCode(block.content, block.language, colors)
    }
    // 溢出检测：用 TextMeasurer 预测量整段（单行约束）宽度，与容器宽（BoxWithConstraints）比较
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // 不强制 fillMaxWidth：短代码块跟随气泡宽度；溢出检测用 maxWidth（容器约束）不变
    BoxWithConstraints(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(codeBg)
            .border(0.5.dp, borderColor.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
    ) {
        val availablePx = with(density) { this@BoxWithConstraints.maxWidth.toPx() - 24.dp.toPx() }
        Column {
        // 标题栏：语言标签 + 复制
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                block.language.uppercase().ifBlank { "CODE" },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { onCopyCode(block.content) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制代码",
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = borderColor.copy(alpha = 0.5f))
        // 只在内容真的溢出时才套 horizontalScroll：不溢出的代码块不消费横拖，
        // 屏幕左缘/代码块上也能划出抽屉（溢出时长行横向滚动优先）
        val scrollState = rememberScrollState()
        val overflow = remember(highlighted, density, availablePx) {
            val textWidthPx = measurer.measure(
                highlighted,
                style = TextStyle(
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.sp
                ),
                softWrap = false,
                maxLines = 1,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = 100000)
            ).size.width
            textWidthPx > availablePx
        }
        if (overflow) {
            Box(Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
                Text(
                    highlighted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        } else {
            Text(
                highlighted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        }
    }
}
