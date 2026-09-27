package com.wavex.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import org.scilab.forge.jlatexmath.TeXIcon
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color as JlmColor

/**
 * LaTeX 数学公式渲染（JLatexMath 内核，离线、无 WebView）。
 * 块级公式（$$…$$ / \[…\]）→ MathBlock：整行居中，宽度自适应；
 * 行内公式（$…$ / \(…\)）→ 由 MarkdownText 的 InlineTextContent 承载。
 * 渲染结果（位图）走 LruCache：流式期间只有正在生长的末条公式重新解析。
 */
object MathRenderer {
    /** 公式位图缓存：key = latex + 颜色 + 字号 */
    private val cache = LruCache<String, RenderedMath>(64)

    class RenderedMath(val bitmap: Bitmap, val widthPx: Int, val heightPx: Int)

    /**
     * 解析 LaTeX 并渲染成位图。失败返回 null（调用方回退为原文本显示）。
     * 在组合线程同步执行：单次解析 + 位图绘制约几 ms（实测缓存命中后零成本），
     * LruCache 保证流式期间同一公式只渲染一次。
     */
    fun render(latex: String, colorInt: Int, fontPx: Float): RenderedMath? {
        if (latex.isBlank() || latex.length > 3000) return null
        val key = "v2-$colorInt\u0001$fontPx\u0001$latex"
        cache.get(key)?.let { return it }
        return try {
            val icon: TeXIcon = TeXFormula(latex)
                .createTeXIcon(TeXConstants.STYLE_DISPLAY, fontPx)
            icon.setForeground(JlmColor(colorInt))
            // getIconHeight 已含 box 高度 + depth（基线以下延伸）+ insets，
            // paintIcon(x,y) 以 (x, y+insets.top) 为内容顶部绘制 —— 位图高度不能再加
            // iconDepth，否则底部多一段空白，公式整体上浮（实测下标/分数往上移）
            val w = icon.iconWidth.coerceAtLeast(1)
            val h = icon.iconHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val g2d = AndroidGraphics2D()
            g2d.setCanvas(canvas)
            icon.paintIcon(null, g2d, 0, 0)
            val rendered = RenderedMath(bmp, w, h)
            cache.put(key, rendered)
            rendered
        } catch (t: Throwable) {
            // 渲染失败回退为原始 LaTeX 文本（不崩渎、不丢内容）
            null
        }
    }
}

/**
 * 块级公式：整行居中显示；宽超容器时等比缩小。
 * 解析失败回退为等宽原文本（原始 LaTeX 仍可读，流式中半截公式自然过渡）。
 */
@Composable
fun MathBlock(latex: String, color: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // 块级公式字号略大于正文（display 样式视觉上与 15sp 正文协调）
    val fontPx = with(density) { 17.sp.toPx() }
    val colorInt = color.hashCode()
    val rendered = remember(latex, colorInt, fontPx) {
        MathRenderer.render(latex, colorInt, fontPx)
    }
    if (rendered == null) {
        Text(
            latex,
            color = color,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = modifier.padding(vertical = 4.dp)
        )
        return
    }
    // 宽度跟随公式本身（短公式不再拉一条大空隙）；超过容器宽度时等比缩小。
    // 宽高同步缩放 + ContentScale.Fit，缩放后不留空白、不变形
    val widthDp = with(density) { rendered.widthPx.toDp() }
    val heightDp = with(density) { rendered.heightPx.toDp() }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier.padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        val displayWidth = if (widthDp > maxWidth) maxWidth else widthDp
        Image(
            bitmap = rendered.bitmap.asImageBitmap(),
            contentDescription = "数学公式",
            modifier = Modifier
                .width(displayWidth)
                .height(heightDp * (displayWidth / widthDp)),
            contentScale = ContentScale.Fit
        )
    }
}
