package com.wavex.agent.core.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** 公式位图缓存：key = latex + 颜色 + 字号。一张公式位图约几十 KB，128 条 ≈ 几 MB 上限 */
    private val cache = LruCache<String, RenderedMath>(128)

    class RenderedMath(val bitmap: Bitmap, val widthPx: Int, val heightPx: Int)

    /** 缓存键；peek/render 共用，颜色与字号参与（亮暗主题、字号缩放各自成键） */
    private fun cacheKey(latex: String, colorInt: Int, fontPx: Float) =
        "v2-$colorInt\u0001$fontPx\u0001$latex"

    /**
     * JLatexMath 内部有共享静态状态（字体/解析缓存），并发渲染不安全：
     * 所有渲染经此锁串行化。
     */
    private val renderLock = Any()

    /**
     * 后台串行渲染队列：MathBlock 首帧未命中缓存时把渲染投到这里，
     * 主线程永不承担复杂公式的解析 + 位图成本（实测可达 300ms+，
     * 在 LazyList measure 内直接整帧冻结）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val renderDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val warmUpScope = CoroutineScope(SupervisorJob() + renderDispatcher)

    @Volatile
    private var warmUpRequested = false

    /**
     * 库预热：JLatexMath 首次渲染要加载字体并建立解析缓存（实测 ~320ms），
     * 落在主线程就是一次整帧冻结——首条含行内公式的消息在组合期同步走
     * [render]（行内公式无法像块级那样先占位后换图），会话打开帧或上行
     * 冷首过的中间都可能撞上。App 启动即投后台串行队列渲一条平凡公式，
     * 把这笔成本挪离主线程；重复调用是空操作。行内 miss 与预热互斥等锁，
     * 最坏情况与无预热时持平，不会双倍付费。
     */
    fun warmUp() {
        if (warmUpRequested) return
        warmUpRequested = true
        warmUpScope.launch { render("0", 0xFF000000.toInt(), 13f) }
    }

    /** 只查缓存不渲染：命中返回位图，未命中返回 null（调用方决定回退/异步渲染） */
    fun peek(latex: String, colorInt: Int, fontPx: Float): RenderedMath? {
        if (latex.isBlank() || latex.length > 3000) return null
        return cache.get(cacheKey(latex, colorInt, fontPx))
    }

    /**
     * 解析 LaTeX 并渲染成位图。失败返回 null（调用方回退为原文本显示）。
     * 可在任意线程调用（内部经 [renderLock] 串行化）；主线程调用时若后台正在
     * 渲染复杂公式，最多等当前这一条完成 —— 仅发生在行内公式缓存 miss 时
     * （行内公式普遍简单，罕见）。LruCache 保证同一公式只渲染一次。
     */
    fun render(latex: String, colorInt: Int, fontPx: Float): RenderedMath? {
        if (latex.isBlank() || latex.length > 3000) return null
        val key = cacheKey(latex, colorInt, fontPx)
        cache.get(key)?.let { return it }
        synchronized(renderLock) {
            // 双检：等锁期间可能已被另一线程渲染完
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
                // 渲染失败回退为原始 LaTeX 文本（不崩溃、不丢内容）
                null
            }
        }
    }
}

/**
 * 块级公式：整行居中显示；宽超容器时等比缩小。
 * 解析失败或后台渲染未完成时回退为等宽原文本（原始 LaTeX 仍可读）。
 */
@Composable
fun MathBlock(latex: String, color: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // 块级公式字号略大于正文（display 样式视觉上与 15sp 正文协调）
    val fontPx = with(density) { 17.sp.toPx() }
    val colorInt = color.hashCode()
    // 首帧只查缓存；未命中先以等宽 LaTeX 原文占位（复用下方失败回退路径），
    // 渲染转投后台串行线程，完成后换位图 —— 复杂公式的解析+位图成本不再冻结
    // 主线程（实测 300ms+，长会话上行回滚逐条触发，是滚动卡顿的主源）。
    // delay：流式中公式文本逐段到达，等 50ms 稳定再渲染，避免每个前缀都渲染一遍；
    // 期间 item 滚出视口或公式继续变化，协程即被取消，不浪费后台算力。
    // NonCancellable：渲染一旦开始就完成并入库，下次经过直接命中缓存。
    var rendered by remember(latex, colorInt, fontPx) {
        mutableStateOf(MathRenderer.peek(latex, colorInt, fontPx))
    }
    LaunchedEffect(latex, colorInt, fontPx) {
        if (rendered == null) {
            delay(50)
            rendered = withContext(NonCancellable + MathRenderer.renderDispatcher) {
                MathRenderer.render(latex, colorInt, fontPx)
            }
        }
    }
    val current = rendered
    if (current == null) {
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
    val widthDp = with(density) { current.widthPx.toDp() }
    val heightDp = with(density) { current.heightPx.toDp() }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier.padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        val displayWidth = if (widthDp > maxWidth) maxWidth else widthDp
        Image(
            bitmap = current.bitmap.asImageBitmap(),
            contentDescription = "数学公式",
            modifier = Modifier
                .width(displayWidth)
                .height(heightDp * (displayWidth / widthDp)),
            contentScale = ContentScale.Fit
        )
    }
}
