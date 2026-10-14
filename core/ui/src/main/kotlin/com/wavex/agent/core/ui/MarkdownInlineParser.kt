package com.wavex.agent.core.ui

import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

internal class InlineResult(val string: AnnotatedString, val contents: Map<String, InlineTextContent>)

/** 内联内容（行内公式）→ 测量用 Placeholder 区间。
 *  appendInlineContent 的备用文本只是一个空格，纯文本测量会把公式算成 1 个空格宽，
 *  列宽随之偏窄，渲染时公式位图在单元格边缘被截断（列宽必须含占位盒真实宽度）。 */
internal fun AnnotatedString.inlinePlaceholderRanges(
    contents: Map<String, InlineTextContent>
): List<AnnotatedString.Range<Placeholder>> =
    getStringAnnotations(0, length).mapNotNull { ann ->
        contents[ann.item]?.let { AnnotatedString.Range(it.placeholder, ann.start, ann.end) }
    }

/** 行内解析缓存：key = 底色 + 链接色 + 正文色 + 密度 + 行文本（同实例 → Text 跳过重排） */
private val inlineCache = LruCache<String, InlineResult>(4096)

internal fun inlineOf(
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
internal fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlineSegment(
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
                // 闭合 $ 前不能是空格：排除「$5 优惠 $10」这类货币金额被当公式
                //（公式定界符紧贴内容，金额里两个 $ 之间是普通文本）
                if (end > i + 1 && end < to && text[end - 1] != ' ' && (end + 1 >= to || text[end + 1] != '\n')) {
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
                if (m != null && m.end <= to) {
                    withStyle(activeStyle ?: SpanStyle()) {
                        withLink(LinkAnnotation.Url(m.url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                            val label = text.substring(m.labelFrom, m.labelTo)
                            val siteName = linkSiteName(label, m.url)
                            // 换成站点名就直接写；没换说明标签是模型写的文本，递归解析里面的 **粗**/`码`/公式
                            if (siteName != label) append(siteName)
                            else appendInlineSegment(text, m.labelFrom, m.labelTo, activeStyle, codeBg, linkColor, baseColor, density, contents, nextId, depth + 1)
                        }
                    }
                    i = m.end
                } else {
                    append(c); i++
                }
            }
            // ![说明](图片直链)：模型只发了链接没发图片本体，按链接渲染（不出缩略图，
            // 提取层已不接管远程图）；说明为空（![](url)）时显示 URL 本身。
            // 独立正则：MD_LINK 标签组要求非空，空说明形态走这里。
            // 越界防护同上：match 成功才整体消费，否则只吃一个字符
            c == '!' && i + 1 < to && text[i + 1] == '[' -> {
                val m = MD_IMAGE.matchAt(text, i)
                if (m != null && m.range.last + 1 <= to) {
                    var url = m.groupValues[2]
                    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                    withStyle(activeStyle ?: SpanStyle()) {
                        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                            val label = m.groupValues[1]
                            if (label.isBlank()) {
                                // ![](url) 无说明：显示 URL 本身（链接形态保底）
                                append(url)
                            } else {
                                val siteName = linkSiteName(label, url)
                                // 同 [链接]：站点名直接写；模型写的文本递归解析行内符号
                                if (siteName != label) append(siteName)
                                else appendInlineSegment(text, i + 2, i + 2 + label.length, activeStyle, codeBg, linkColor, baseColor, density, contents, nextId, depth + 1)
                            }
                        }
                    }
                    i = m.range.last + 1
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

/** Markdown 链接解析结果：标签在原文中的区间 + 补全协议后的跳转地址 + 整段结束下标 */
internal data class MdLink(val labelFrom: Int, val labelTo: Int, val url: String, val end: Int)

/** 从 from 起匹配 [标签](网址)；标签区间用于递归解析标签内的行内符号 */
internal fun mdLinkMatch(text: String, from: Int): MdLink? {
    val m = MD_LINK.matchAt(text, from) ?: return null
    var url = m.groupValues[2]
    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
    val labelFrom = from + 1
    return MdLink(labelFrom, labelFrom + m.groupValues[1].length, url, m.range.last + 1)
}

/** ![说明](图片直链)：同 MD_LINK 但允许空说明（![](url)），且 ! 属于匹配本体 */
private val MD_IMAGE = Regex("!\\[([^\\]\\n]*)]\\(([^)\\s]+)\\)")

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
