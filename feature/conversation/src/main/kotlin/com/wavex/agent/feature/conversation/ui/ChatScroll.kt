package com.wavex.agent.feature.conversation.ui

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp

/**
 * 对话列表的贴底几何与「离底意图」容差。
 *
 * 从 ChatScreen.kt 拆出（Task 7 Step 3：timeline/message interaction 与几何判定分离）：
 * 这里只有纯粹的滚动几何，不持有任何会话状态，可脱离 ChatScreen 单独阅读与测试。
 * 布局观察者（BottomFollowPolicy 的喂数与纠偏）仍留在 ChatScreen，因为它与状态机耦合。
 */

/**
 * 精确贴底：把列表滚到内容真正的末尾（末条消息底边 == 视口底边）。
 * scrollToItem 的 offset 语义对“末条比视口矮”的场景不可靠（实测会回填上方 item，
 * 停在离底约一屏处），所以用“测量剩余距离 → 滚动”的收敛式：
 * 末条不可见先顶对齐它，再按实际剩余像素补滚（scrollBy 会被内容边界自然鈄住）。
 */
internal suspend fun LazyListState.snapToBottom(shouldContinue: () -> Boolean = { true }) {
    // Coalesce streaming updates to a frame. Layout updates are also observed by the caller:
    // a frame boundary alone does not guarantee that Compose has measured the new content.
    withFrameNanos { }
    repeat(3) {
        if (!shouldContinue()) return
        val info = layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        if (info.totalItemsCount == 0 || last == null) return
        if (last.index != info.totalItemsCount - 1) {
            scrollToItem(info.totalItemsCount - 1)
            return@repeat
        }
        val remaining = last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)
        if (remaining <= 2) return
        scroll { scrollBy(remaining.toFloat()) }
    }
}

/**
 * 布局观察者用的逐帧快照：只有这些字段变化才需要重新判定跟随/纠偏。
 * 拆出后仍为 internal（原 private），因为写入在 ChatScreen、比较在同一文件内联展开。
 */
internal data class BottomLayoutSnapshot(
    val messageCount: Int,
    val lastMessageId: String?,
    val textLength: Int,
    val reasoningLength: Int,
    val attachmentCount: Int,
    val viewportBottom: Int,
    val viewportHeightPx: Int,
    val lastIndex: Int,
    val lastOffset: Int,
    val lastSize: Int,
    val totalItemsCount: Int,
    val atBottom: Boolean,
    val pinned: Boolean,
    val userDragging: Boolean,
    val selectionActive: Boolean,
    val scrolling: Boolean,
    val editingScroll: Boolean,
    /** 就地编辑器的「底边 − 视口底边」带号距离；编辑器不在视口内或未在编辑时为 null */
    val editorDistanceFromBottom: Int?
)

internal fun LazyListLayoutInfo.isAtBottom(tolerancePx: Float): Boolean {
    val last = visibleItemsInfo.lastOrNull() ?: return false
    return totalItemsCount > 0 &&
        last.index == totalItemsCount - 1 &&
        (last.offset + last.size).toFloat() <= viewportEndOffset - afterContentPadding + tolerancePx
}

/**
 * 「用户离底意图」的容差（[ChatScreen] 喂给 BottomFollowPolicy.onLayout 的 atBottom）。
 *
 * 不得随意调大：在密度 1.275 的真机上，一次 20dp 的有意小上划实际落在距底 16px。
 * 本值 = 10.2px，所以该划动被记为「已离开底部」；改成 16dp（= 20.4px）后同一划动落回
 * 「还在底部」区间，下一帧就被 snapToBottom 拽回 0.0px——正是本改动要修的原始 bug
 * （已实测）。BottomFollowInstrumentedTest 直接引用本常量钉住这条边界。
 *
 * 和 pinned（实际补滚容差 2px）不同：那是几何测量，本值是意图判定，两者不能合并。
 */
internal val FollowIntentToleranceDp = 8.dp

/**
 * 编辑落点的「算不算离开底部」容差。刻意比 [FollowIntentToleranceDp] 宽：那是几何事实
 * 而非用户意图，编辑一条本来就靠近底部的消息不应被当成跳进历史（否则「回到底部」
 * 按钮先亮后灭）。副作用：距底 [FollowIntentToleranceDp] ~ 一屏之间既不跟随也不出按钮（有意
 * 取舍，EditJumpInstrumentedTest 钉住了靠底不闪按钮这一头）。
 */
internal val EditJumpToleranceDp = 96.dp
