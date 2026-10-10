package com.wavex.agent.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

// internal：单元测试直接验证块切分（表格/代码/数学块的回归测试入口）
@Composable
private fun InlineBlock(content: String, baseColor: Color) {
    // 行内代码底色不能用 surfaceVariant：模型气泡本就是 surfaceVariant，底片会隐形；
    // 改用 onSurface 低透明度（与附件卡同规格），亮暗主题、两种气泡上都可见
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
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
