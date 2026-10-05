package com.wavex.agent

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * 吞掉子级泄漏出来的「残余滚动量 / 残余速度」，请求到本节点为止，不再向祖先传播。
 *
 * 背景：就地编辑气泡里的输入框自带垂直滚动（BasicTextField 内部 `.scrollable(...)`，
 * foundation 1.10.4 的 BasicTextField.kt:444-461）。它吃不下的高度会经
 * `ScrollingLogic.performScroll` → `ScrollableState.dispatchPostScroll`
 * （ScrollableState.kt:794-805）上抛给父链，划动结束的惯性残余同样外抛
 * （ScrollableState.kt:833-843：fling 撞到边界会取消自己，让「上方任意 nested scroll 节点」
 * 接手剩余速度继续滚）。父链第一环就是消息 LazyColumn，于是：
 *   在编辑区划到底再继续划 → 整个会话列表跟着划（本 bug）。
 *
 * 实测（HA20629S / Android 15，12 行内容的就地编辑框）：
 *   未拦截：字段吸满后每帧仍漏出 available=-51px，另有 fling 残余 -20.3/-19.9/-19.1…
 *           列表位移 (0,0) → (1,509)。
 *   拦截后：列表位移 (0,0) → (0,0)，编辑框内部滚动照常。
 *
 * 必须挂在编辑框的「真 LayoutNode 祖先」上（这里是 Card）：BasicTextField 把用户传入的
 * modifier 与内部 `.scrollable(...)` 拼进同一条链、同一个 LayoutNode，而 nested-scroll 只沿
 * LayoutNode 祖先链找响应者（NestedScrollNode.findNearestAncestor）——挂在
 * OutlinedTextField 自己的 modifier 上拦不住（实测无效）。
 *
 * 手指位移仍由编辑框自己的 scrollable 认领（它 enabled，且 canDrag 只按指针类型过滤，
 * Scrollable.kt:685），列表拿不到手势、只吃到泄漏量；把泄漏量全部标记为已消费，列表就静止。
 * 编辑框内部滚动、光标/选区、bringIntoView 均不受影响：被吞掉的只是它自己用不了的余量。
 *
 * 副作用（组合使用必读）：NestedScrollNode 上报给父级的是「父级消耗 + 自身消耗」之和，
 * 所以这里吞掉的量仍会作为 consumed 进入消息列表自己的划动观察器。列表**位置**没变，
 * 但观察器若把它当成真实位移记账，就会在列表一动不动时把「回到底部」按钮凭空切换掉。
 * 搭配本修饰符的观察器必须只认「列表真的在滚」的事件 —— 见 ChatScreen 的
 * scrollIntentObserver 里的 isScrollInProgress 闸门。
 * 本修饰符无差别吞两个轴：编辑框子树内目前没有横向滚动子级（只有垂直的
 * OutlinedTextField + 两个按钮），所以 x 始终为 0；将来若往编辑区里加横向滚动，
 * 需重新评估是否只吞 y。
 * 上述行号对应 androidx.compose.foundation / .ui 1.10.4，升级后请以函数名而不是行号定位。
 */
fun Modifier.blockScrollLeak(): Modifier = nestedScroll(BlockScrollLeakConnection)

private object BlockScrollLeakConnection : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset = available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}