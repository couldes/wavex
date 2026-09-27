package com.wavex.agent

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.relocation.BringIntoViewModifierNode

/**
 * 吞掉子级发出的 bringIntoView 请求（no-op 拦截，请求不再向 LazyColumn 传播）。
 *
 * 背景：长按选中消息文本时，SelectionManager 会 focusRequester.requestFocus()
 * 让选区容器获得焦点；而 Compose 的 focusable() 在获得焦点的瞬间会自动
 * bringIntoView(整个焦点节点)（见 foundation Focusable.kt：
 * `if (isFocused) coroutineScope.launch { bringIntoView() }`）。焦点节点是
 * 整个气泡的 SelectionContainer，于是：
 *   - 长按靠上的消息（气泡顶部被视口裁掉）→ 列表被突然往下拉；
 *   - 长按靠下的消息（气泡底部被裁）→ 列表被突然往上拉。
 *
 * 在气泡卡片上放一个 no-op 拦截节点后，请求到它为止（Compose 的
 * DelegatableNode.bringIntoView 只找最近祖先响应者），列表保持静止；
 * 焦点照常获得，选区/手柄/工具条功能不受影响。
 */
fun Modifier.blockBringIntoView(): Modifier = then(BlockBringIntoViewElement)

private data object BlockBringIntoViewElement : ModifierNodeElement<BlockBringIntoViewNode>() {
    override fun create(): BlockBringIntoViewNode = BlockBringIntoViewNode()

    override fun update(node: BlockBringIntoViewNode) {
        // 无状态，无需更新
    }
}

private class BlockBringIntoViewNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(
        childCoordinates: LayoutCoordinates,
        boundsProvider: () -> Rect?
    ) {
        // no-op：拦截请求，不滚动
    }
}
