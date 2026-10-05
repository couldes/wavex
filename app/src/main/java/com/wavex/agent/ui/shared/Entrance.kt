package com.wavex.agent.ui.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 入场动效（淡入 + 轻微上浮 + 错峰）——全局共用一份“动画语言”：
 * 聊天气泡（切会话级联/新消息到达）、恢复备份选择列表等都用它。
 * - 是否参与动画在**组合期一次性定死**（active 翻转不改变组合树结构）：
 *   结构若随 active 分支切换，内容子树会被换父重挂、内部 remember 全部失效
 *   （聊天气泡因此全列表重解析 → 每次切换必现一次卡顿尖峰，已踩过的坑，别退回去）；
 * - graphicsLayer 只动 alpha/translationY，不触发布局重排；
 * - 错峰 = index×staggerMs、上限 400ms：首屏最后一条也在 0.6s 内完成，不拖沓。
 */
@Composable
internal fun StaggeredEntrance(
    active: Boolean,
    index: Int,
    // 错峰间隔：切会话级联用默认 45ms；新消息入场传 180ms（用户消息先落定，回复随后）
    staggerMs: Long = 45L,
    content: @Composable () -> Unit
) {
    // played 在首次组合时定死：窗口内 = false（播动画），窗口外/回看 = true（直接显示）
    var played by remember { mutableStateOf(!active) }
    val progress = remember { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!played) {
            played = true
            delay((index * staggerMs).coerceAtMost(400L))
            progress.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        }
    }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 12.dp.toPx()
        }
    ) { content() }
}
