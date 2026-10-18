package com.wavex.agent.feature.conversation.ui

import android.app.Activity
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds

/**
 * 键盘/IME 的收起、可见性检测与「本次手势是否报备」通道。
 *
 * 从 ChatScreen.kt 拆出（Task 7 Step 3：输入/编辑相关单元分离）：这里只处理 IME 与
 * 手势-键盘关系，不持有会话状态。ChatScreen 的根 Column 挂 [dismissKeyboardOnTap]，
 * 子控件（输入胶囊、联网键、分支切换器、气泡复制按钮）用 [keyboardSuppressReport]
 * 报备「本次手势不要收键盘」。
 */

/**
 * 容器级「点按/滑动收起键盘」：Initial pass 观察。纯点按（无滚动/无长按）抬起时
 * 收起键盘；滑动越过 touchSlop（纵向主导）也**立即收起**（ChatGPT 式，每次手势一次）。
 *
 * 与逐处加 clickable 的区别：不用枚举所有可点控件，消息列表、代码块、表格、
 * 图片、附件条上点击都生效（它们没消费 down 时这里兜底）。
 * 不会误伤：按钮/气泡等子控件在 Main pass 消费 down/up（那是明确的子控件点击，
 * 不收键盘）；横向主导的滑动不收（抽屉拖拽有自己的收键盘+锁 IME 布局路径，
 * 不能抢在它前面收掉导致锁失效；代码块/表格横滚也不改输入意图），
 * 报备手势（输入胶囊/联网键）与选区激活照旧让路。
 * 报备通道见 keyboardSuppress：tapActive=完全不动（联网键/输入胶囊），
 * imeOnly=只收 IME 不清焦点（局部复制长按，清焦点会连选区一起清掉）。
 */
/** 联网键报备豁免：点击联网键时置位，容器观察器据此跳过收键盘（消费后自动复位） */
internal object keyboardSuppress {
    @Volatile var tapActive: Boolean = false
    // 局部复制手势报备：本次手势松手时只收 IME、不清焦点。
    // clearFocus 会级联清掉 Compose 焦点树 → 选区容器失焦 → SelectionManager.onRelease
    // → 长按选好的文本「松手即消失」。
    @Volatile var imeOnly: Boolean = false
}

/**
 * 报备本次手势不收键盘：down 置位、up 复位，容器观察器（dismissKeyboardOnTap）
 * 据此跳过收键盘。用于联网键（切换开关不想收键盘）与整个输入胶囊
 * （点输入框时容器会先收后由焦点弹开 → 键盘闪一下，报备后不再闪）。
 */
internal fun Modifier.keyboardSuppressReport(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        try {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            keyboardSuppress.tapActive = true
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.all { !it.pressed }) break
            }
        } finally {
            // 手势取消/异常时也复位，避免标志卡住导致键盘永远收不了
            keyboardSuppress.tapActive = false
        }
    }
}

/** 键盘当前是否可见（root insets 检测；view 未挂载时视为不可见） */
internal fun isImeVisible(localView: android.view.View?): Boolean {
    val view = localView ?: return false
    return ViewCompat.getRootWindowInsets(view)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
}

fun hideKeyboard(localView: android.view.View?, imeOnly: Boolean = false): Boolean {
    // 先检测（隐藏后 insets 已更新，检测会恒为 false）
    val imeVisible = isImeVisible(localView)
    val token = (localView?.context as? Activity)?.currentFocus?.windowToken
        ?: localView?.windowToken
    if (token != null) {
        val imm = localView?.context?.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager
        imm?.hideSoftInputFromWindow(token, 0)
    }
    // imeOnly（局部复制长按手势松手）：只收 IME 不清焦点 —— clearFocus 会级联清
    // Compose 焦点树，选区容器失焦即触发 SelectionManager.onRelease 清空选区
    //（表现为「长按选好的词一松手就消失」）。
    if (!imeOnly) localView?.clearFocus()
    return imeVisible
}

internal suspend fun PointerInputScope.dismissKeyboardOnTap(
    localView: android.view.View?,
    shouldSkip: () -> Boolean = { false },
    onSelectionTap: (() -> Unit)? = null
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.isConsumed) return@awaitEachGesture
        var tapped = true
        // 滑动收键盘每次手势只触发一次：越过 touchSlop 即收，之后滚动全程不再重复
        var dismissed = false
        // 记录手势时长与初始选区态：选区激活时区分「短按取消选区」与「长按选词让路」。
        // 长按选词路径：SelectionContainer 的 touchSelectionFirstPress 同样用
        // longPressTimeoutMillis 判长按（awaitLongPressOrCancellation），阈值一致才能无缝交接。
        val gestureStart = TimeSource.Monotonic.markNow()
        val selectionMode = shouldSkip()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                // 手指抬起：只处理「未被任何子控件消费的抬起」（Initial pass 先于
                // 子控件的 Main pass，消费结果这一刻还看不到，只能靠报备推断）。
                // 分支优先级：onSelectionTap（选区激活时的短按＝取消局部复制）>
                // shouldSkip（选区激活的长按：完全让路，选区容器的焦点不能被任何
                // clearFocus 级联碰掉）> tapActive（报备不收）> imeOnly（只收 IME）>
                // 默认（收 IME + 清焦点）。
                if (tapped && !event.changes.any { it.isConsumed }) {
                    when {
                        selectionMode && onSelectionTap != null &&
                            gestureStart.elapsedNow() < viewConfiguration.longPressTimeoutMillis.milliseconds ->
                            onSelectionTap()
                        shouldSkip() -> {}
                        keyboardSuppress.tapActive -> {
                            keyboardSuppress.tapActive = false
                        }
                        keyboardSuppress.imeOnly -> {
                            keyboardSuppress.imeOnly = false
                            hideKeyboard(localView, imeOnly = true)
                        }
                        else -> hideKeyboard(localView)
                    }
                }
                break
            }
            // 子控件已消费（按钮点击/滚动/拖拽）或移动过 touchSlop → 不再算纯点按。
            // 滑动本身也收键盘：纵向主导越过 touchSlop 立即收（每次手势一次）——
            // 键盘可见才动手，普通滚动（键盘已关）不碰焦点。
            // 横向主导不收：交给抽屉拖拽自己的收键盘路径（保住它的 imeSettling 锁）；
            // 报备手势（输入胶囊内拖光标/按钮上滑走）与选区激活照旧让路。
            if (change.isConsumed ||
                (abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) ||
                (abs(change.position.y - down.position.y) > viewConfiguration.touchSlop)
            ) {
                tapped = false
                if (!dismissed && !change.isConsumed) {
                    val dx = abs(change.position.x - down.position.x)
                    val dy = abs(change.position.y - down.position.y)
                    if (dy > viewConfiguration.touchSlop && dy >= dx) {
                        dismissed = true
                        when {
                            shouldSkip() -> {}
                            keyboardSuppress.tapActive -> {}
                            else -> if (isImeVisible(localView)) hideKeyboard(localView)
                        }
                    }
                }
            }
        }
    }
}
