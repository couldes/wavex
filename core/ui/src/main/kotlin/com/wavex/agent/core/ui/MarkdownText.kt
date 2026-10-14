package com.wavex.agent.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
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
//
// 整块合并渲染（性能关键）：整条消息只产生 1 个 Text 节点、1 个段落区间。
//
// 背景（真机 atrace，长会话冷首过）：旧实现「每个段落/标题/列表项各一个 Text」，
// LazyColumn 在 measure 阶段才组合 item，于是新气泡滚入视口的那一帧要同步测量
// 数百个从未布局过的节点——实测单帧 340 次 Text 测量、帧长 637ms。
// 三次消融拟合出的代价模型（拟合精确）：
//   单帧 ≈ 21ms + 0.80ms × Text 节点数 + 1.01ms × 段落区间数
// 成本由「对象个数」而非「文字量」决定（每个段落区间 = 一个 Android StaticLayout），
// 所以合并成单个 Text 后：374 节点/374 区间 → 1 节点/1 区间，637ms → 22ms。
//
// 为什么不用 ParagraphStyle 表达逐行样式：段落区间边界会制造幻影空行
// （实测 A\nB 切成两段 → 渲染出 4 行），且每个区间本身要 1ms。整块只用
// SpanStyle 逐行上样式，结构完全交给 '\n'。
//
// 代价：行高改由字体自然度量决定，比旧实现的显式 lineHeight 紧约 12%——由
// [BLOCK_LINE_HEIGHT_SHIFT] 逐行补偿回旧行高（正文逐像素对齐，回归测试钉住）；
// 空行高度由基准字号 [BLOCK_BASE_FONT_SIZE] 决定（3sp ≈ 5px = 旧 Spacer(4.dp)）。

/** 合并渲染的基准字号：只决定「空行」高度（空行没有字形，取段落基准度量）。
 *  字体自然行高 ≈ 1.333×字号，所以 3sp → 4dp，与旧实现的空行 Spacer(4.dp) 等高；
 *  该比例与屏幕密度无关（sp 随密度缩放），故固定 3sp 即可，不需按 density 换算。 */
private val BLOCK_BASE_FONT_SIZE = 3.sp

/**
 * 正文行高补偿：抬高每行高度，复现旧实现显式 `lineHeight = 22.sp` 的行距。
 *
 * 为什么需要：不指定 lineHeight 时行高由字体自然度量决定（15sp ≈ 1.31em），
 * 比旧实现（22sp/15sp ≈ 1.47em）紧约 12%。整条消息变矮会改变滚动几何，
 * 进而影响「回到底部」按钮的「一屏距离」阈值等既有行为（已有回归测试钉住）。
 *
 * 为什么用 baselineShift 而不是 lineHeight：段落级 lineHeight 会连空行一起顶高
 * （空行就没间隙了），而 baselineShift 是 MetricAffectingSpan，只抬高所在行。
 *
 * 机制（TB331FC 隔离实验实证，勿凭直觉改）：span 携带的 baselineShift 抬升行高时，
 * 度量取的是**段落基准字号**的度量，而不是 span 自身的 fontSize——
 *   基准 3sp + span(15sp, 0.22)：只抬 ~0.8px，被行高取整吞掉，等于没补（Switch 回归）；
 *   基准 15sp + span(15sp, 0.22)：抬 3.9px（25→29px，正是旧行距盒）。
 * 字体度量随字号线性缩放，故把比例按 15/3 放大后，3sp 基准下的抬升与
 * 「基准=正文」时逐像素相等：1.1 × ascent(3sp) = 0.22 × ascent(15sp) ≈ 3.9px。
 * 标定（TB331FC，density 1.275）：不补偿时 15sp 行高 25px，旧实现 29px；
 * 补偿后 25+3.9 = 28.9 → 29px，正文与旧实现逐像素对齐。标题/引用沿用同一
 * 绝对抬升，比旧实现的 21/23/20sp 盒高 1-2px——标题行数少视觉无感，
 * 正文才是滚动几何的主体，行高必须对齐而不能“差不多”。
 */
private const val BLOCK_LINE_HEIGHT_SHIFT = 0.22f * 15f / 3f

/**
 * 整块内联构建：把所有段落拼成一个 AnnotatedString（0 个段落区间），
 * 每行样式用 SpanStyle 承载；行内公式/链接经 [appendInlineSegment] 走既有解析。
 */
private fun buildInlineBlock(
    content: String,
    codeBg: Color,
    linkColor: Color,
    baseColor: Color,
    density: Density
): InlineResult {
    val paragraphs = splitParagraphs(content)
    val contents = LinkedHashMap<String, InlineTextContent>()
    var formulaSeq = 0
    val nextId = { formulaSeq++ }
    val builder = AnnotatedString.Builder()

    fun line(start: Int, size: TextUnit, weight: FontWeight?, color: Color) {
        builder.addStyle(
            SpanStyle(
                fontSize = size,
                fontWeight = weight,
                color = color,
                // 行高补偿：只作用于内容行，空行（无 span）保持基准字号的高度
                baselineShift = BaselineShift(BLOCK_LINE_HEIGHT_SHIFT)
            ),
            start,
            builder.length
        )
    }

    paragraphs.forEachIndexed { index, p ->
        if (index > 0) builder.append('\n')
        when (p.kind) {
            // 空行：只留换行，高度由基准字号决定（3sp ≈ 4dp = 旧 Spacer(4.dp)）
            PKind.Blank -> Unit
            PKind.H3, PKind.H2, PKind.H1 -> {
                val size = when (p.kind) {
                    PKind.H1 -> 17.sp
                    PKind.H2 -> 16.sp
                    else -> 15.sp
                }
                val start = builder.length
                val body = p.text.substringAfter(' ')
                builder.appendInlineSegment(
                    body, 0, body.length, null,
                    codeBg, linkColor, baseColor, density, contents, nextId, 0
                )
                line(start, size, FontWeight.Bold, baseColor)
            }
            PKind.Quote -> {
                val start = builder.length
                val body = p.text.removePrefix("> ")
                builder.appendInlineSegment(
                    body, 0, body.length, null,
                    codeBg, linkColor, baseColor, density, contents, nextId, 0
                )
                line(start, 14.sp, null, baseColor.copy(alpha = 0.75f))
            }
            PKind.Bullet -> {
                val start = builder.length
                // 项目符号：保留原文的 -/*/+ 形式为统一的圆点，正文取符号后的内容
                builder.append("•  ")
                val body = p.text.substringAfter(' ').trim()
                builder.appendInlineSegment(
                    body, 0, body.length, null,
                    codeBg, linkColor, baseColor, density, contents, nextId, 0
                )
                line(start, 15.sp, null, baseColor)
            }
            PKind.Ordered -> {
                val start = builder.length
                val num = LIST_ORDERED.find(p.text)?.value?.trimEnd(' ', '.', '．', ')') ?: "1."
                builder.append("$num.  ")
                val body = p.text.substringAfter(' ')
                builder.appendInlineSegment(
                    body, 0, body.length, null,
                    codeBg, linkColor, baseColor, density, contents, nextId, 0
                )
                line(start, 15.sp, null, baseColor)
            }
            // 普通段落：多行已合并成一个字符串，整块共用一个 Text
            PKind.Paragraph -> {
                val start = builder.length
                builder.appendInlineSegment(
                    p.text, 0, p.text.length, null,
                    codeBg, linkColor, baseColor, density, contents, nextId, 0
                )
                line(start, 15.sp, null, baseColor)
            }
        }
    }
    return InlineResult(builder.toAnnotatedString(), contents)
}

/**
 * 整块行内渲染的跨组合 LRU（性能关键）：气泡被 LazyColumn 回收后，切回会话/滚动回看
 * 不再整块重建 AnnotatedString（段落划分 → 行内扫描 → span 装配全免）。
 * key 与 inlineOf 同约定（底色/链接色/正文色 + 密度，另加 fontScale —— 公式占位盒的
 * sp 换算同时受两者影响），颜色入 key 防切主题串色。
 *
 * 流式防护：打字机逐帧以上一帧内容的增长前缀制造新 key，照单全收会以每帧一条的速度
 * 冲刷整个缓存（边流式边切会话时缓存形同虚设）。判定为「流式生长」（新 key 是上次
 * 尝试 key 的严格增长前缀且距上次尝试 < 500ms）的 put 被跳过 —— 流式气泡本就每帧
 * 必然 miss 重建，缓存它没有收益；打开久置会话的批量装载（无关 key）不受影响，
 * 流结束后的重访（等长 key）照常入缓存。组合只在主线程发生，仍加锁以策安全。
 */
internal object InlineBlockCache {
    private const val MAX_ENTRIES = 128
    private const val MAX_KEY_CHARS = 12_000
    private const val STREAM_WINDOW_MS = 500L

    /** 时间源（单测注入虚拟时钟） */
    internal var nowMs: () -> Long = System::currentTimeMillis
    internal val maxEntriesForTest: Int get() = MAX_ENTRIES
    internal val maxKeyCharsForTest: Int get() = MAX_KEY_CHARS

    private val map = object : LinkedHashMap<String, InlineResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, InlineResult>): Boolean =
            size > MAX_ENTRIES
    }
    private var lastAttemptKey: String? = null
    private var lastAttemptAt = 0L

    fun get(key: String): InlineResult? = synchronized(map) { map[key] }

    fun put(key: String, value: InlineResult) {
        synchronized(map) {
            val now = nowMs()
            val prev = lastAttemptKey
            val streamingGrowth = prev != null && key.length > prev.length &&
                key.startsWith(prev) && now - lastAttemptAt < STREAM_WINDOW_MS
            lastAttemptKey = key
            lastAttemptAt = now
            if (!streamingGrowth && key.length <= MAX_KEY_CHARS) map[key] = value
        }
    }

    fun clear() {
        synchronized(map) {
            map.clear()
            lastAttemptKey = null
            lastAttemptAt = 0L
        }
    }
}

/** 整块渲染取用入口（internal 供单测钉住取用语义）：命中直接复用，未命中构建并入缓存（流式生长帧除外）。 */
internal fun inlineBlockOf(
    content: String,
    codeBg: Color,
    linkColor: Color,
    baseColor: Color,
    density: Density
): InlineResult {
    val key = codeBg.value.toString() + "\u0001" + linkColor.value.toString() + "\u0001" +
        baseColor.value.toString() + "\u0001" + density.density.toString() + "\u0001" +
        density.fontScale.toString() + "\u0001" + content
    InlineBlockCache.get(key)?.let { return it }
    val built = buildInlineBlock(content, codeBg, linkColor, baseColor, density)
    InlineBlockCache.put(key, built)
    return built
}

@Composable
private fun InlineBlock(content: String, baseColor: Color) {
    // 行内代码底色不能用 surfaceVariant：模型气泡本就是 surfaceVariant，底片会隐形；
    // 改用 onSurface 低透明度（与附件卡同规格），亮暗主题、两种气泡上都可见
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val linkColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    // 颜色/密度必须入 key：切主题时 content 不变，漏掉就会留下旧色（亮暗主题串色）。
    // 整块结果经 InlineBlockCache 跨组合复用：气泡被回收后切回会话/滚动回看免整块重建
    val result = remember(content, codeBg, linkColor, baseColor, density) {
        inlineBlockOf(content, codeBg, linkColor, baseColor, density)
    }
    Text(
        result.string,
        // 必须显式覆盖 style：默认继承 MaterialTheme 的 bodyLarge，其 lineHeight=24sp
        // 会把空行也顶成整行高、段落间隙消失（已实测）。
        // 只清 lineHeight，其余属性（letterSpacing 等）仍从 LocalTextStyle 继承，
        // 保持与旧实现一致的字体度量。
        style = LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified),
        fontSize = BLOCK_BASE_FONT_SIZE,
        color = baseColor,
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
