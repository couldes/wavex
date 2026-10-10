package com.wavex.agent.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 缺省底部固定保留高度（0 = 只让位 IME + 导航栏）；宿主可注入自己的 Tab 栏高度 */
public val DefaultBottomBarHeight: Dp = 0.dp

/**
 * 内容底部让位 Modifier：为「底部固定 UI（Tab 栏/输入栏）+ IME + 导航栏」留出高度。
 *
 * imeSettling：抽屉打开期间（键盘同步收起）把内容直接锁到收起后的最终位置 ——
 * 列表只重排一次，之后不再随逐帧 IME inset 重排。锁值 = base（与 ime=0 时的
 * 公式值相同），锁定解除时无跳变。
 *
 * [bottomBarHeight] 由宿主注入（如 app 壳的 Tab 栏高度），core:ui 不持有壳层布局常量。
 */
@Composable
fun Modifier.bottomInsetClearance(
    bottomBarHeight: Dp = DefaultBottomBarHeight,
    imeSettling: () -> Boolean = { false },
): Modifier {
    val ime = WindowInsets.ime
    val navbar = WindowInsets.navigationBars
    return layout { measurable, constraints ->
        val imePx = ime.getBottom(this)
        val base = navbar.getBottom(this) + bottomBarHeight.roundToPx()
        val clearance = if (imeSettling() && imePx > 0) base else maxOf(imePx, base)
        val placeable = measurable.measure(
            constraints.copy(minHeight = 0, maxHeight = (constraints.maxHeight - clearance).coerceAtLeast(0))
        )
        layout(placeable.width, placeable.height + clearance) {
            placeable.place(0, 0)
        }
    }
}
