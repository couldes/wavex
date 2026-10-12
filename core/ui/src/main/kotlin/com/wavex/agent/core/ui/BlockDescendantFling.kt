package com.wavex.agent.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * 吞掉子级 scrollable 惯性滑动（fling）的启动速度，使其只随手指拖拽滚动、松手即停。
 *
 * 背景（设备实测 TB331FC / Android 15，foundation 1.10.4）：Compose 文本字段的内部惯性
 * 滑动**不会**在新的点按按下时冻结——与 View 时代 EditText 在 ACTION_DOWN 强停 scroller
 * 的行为不同——而点按→光标的映射发生在**抬起瞬间**（getOffsetForPosition at UP）。于是
 * 惯性在 down→up 期间继续移动内容，光标落在「内容已滑到的位置」而非手指所指行：
 * 朝顶部甩动后快速点按，光标直接落到文本第一行（就地编辑框与主输入框旧路径均复现）。
 *
 * 判别数据（EditorFlingTapBareHarnessInstrumentedTest）：深滚动后静止点按 = 第 15 行
 * （映射折叠滚动正确）；甩向顶部后按住 250ms 点按 = 第 0 行（新/旧架构一致）；
 * 挂本修饰符后 = 甩动拖拽段结束时的所见行（惯性不再移动内容，点按所见即所得）。
 *
 * 语义：NestedScrollConnection.onPreFling 全量返回 available —— 子级 fling 以 0 速度启动，
 * 直接结束，不产生任何惯性位移。拖拽滚动、光标/选区、bringIntoView 均不受影响；
 * 祖先（如消息列表）自身的滚动与惯性也不经过本连接（嵌套滚动只向祖先方向上报，
 * 本节点是子级的祖先、列表的后代，列表自己的 fling 不经此处）。
 *
 * 必须挂在字段内部 scrollable 的「真 LayoutNode 祖先」上（编辑气泡 = Card，输入条 =
 * 外层 Surface）：BasicTextField 把用户 modifier 与内部 .scrollable(...) 拼进同一条链、
 * 同一个 LayoutNode，而 nested-scroll 只沿祖先链查找响应者——挂在字段自己的 modifier
 * 上无效。与 blockScrollLeak 同理，见其 KDoc。
 *
 * 与 blockScrollLeak 的分工：blockScrollLeak 吞的是子级**用不掉的余量**（post pass，
 * 拖拽饱和后的外溢 + 撞边惯性残余）；本修饰符吞的是子级**自己的惯性启动速度**
 * （pre pass）。两者可同节点叠加，互不替代。
 *
 * 副作用：子级失去 glide（松手即停）。对可见高度受 maxLines/heightIn 限制、内容最多
 * 数屏的编辑框可接受；若将来挂在长列表上需重新评估手感。上述行为对应
 * androidx.compose.ui 1.10.4，升级后请以 onPreFling 语义定位。
 */
fun Modifier.blockDescendantFling(): Modifier = nestedScroll(BlockDescendantFlingConnection)

private object BlockDescendantFlingConnection : NestedScrollConnection {
    override suspend fun onPreFling(available: Velocity): Velocity = available
}
