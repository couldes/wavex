package com.wavex.agent.feature.conversation.state

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.wavex.agent.feature.conversation.model.AgentConversation

/**
 * 会话列表排序不变量（纯逻辑，可单测）：
 * 列表 = 置顶区（pinnedAt 降序，位置固定不随活跃跳动）+ 未置顶区（lastActiveAt 降序）——
 * 「最近对话」名副其实：选中旧会话 / 发消息 / 新建都把该会话顶到未置顶区最上。
 * 恢复时旧数据等值（lastActiveAt=0 / pinnedAt=0）由稳定排序保持装载序（文件序 = 创建序降序）。
 */

/** 列表排序比较器：置顶在前（pinnedAt 降序），未置顶按 lastActiveAt 降序 */
val conversationOrder: Comparator<AgentConversation> =
    compareBy<AgentConversation> { !it.pinned }
        .thenByDescending { if (it.pinned) it.pinnedAt else it.lastActiveAt }

/** 整表恢复不变量：稳定重排（等值保持原相对顺序） */
fun SnapshotStateList<AgentConversation>.reorderByLastActive() {
    val sorted = sortedWith(conversationOrder)
    clear()
    sorted.forEach { add(it) }
}

/**
 * 活跃刷新：刷新 [conversation] 的时间戳并移到未置顶区头部（已在列表中的先按 identity 移除）。
 * 恒置顶而非按时间找位：同毫秒平局时「刚活跃者在上」优先于时间序——
 * 时间戳只为恢复加载后的排序服务（等值平局由恢复端稳定排序处理）。
 * 已置顶的会话不动位置（置顶区固定，不随活跃跳动），只刷时间戳。
 */
fun SnapshotStateList<AgentConversation>.bumpByActivity(conversation: AgentConversation, now: Long) {
    conversation.lastActiveAt = now
    if (conversation.pinned) return
    remove(conversation)
    val at = indexOfFirst { !it.pinned }
    if (at < 0) add(conversation) else add(at, conversation)
}

/**
 * 置顶/取消置顶：置顶的会话移入置顶区头部（最新钉的在前）；
 * 取消置顶的会话按活跃序回到未置顶区头部。伴随的列表快照写同时触发落盘观察。
 */
fun SnapshotStateList<AgentConversation>.setPinned(conversation: AgentConversation, pinned: Boolean, now: Long) {
    conversation.pinned = pinned
    if (pinned) conversation.pinnedAt = now
    remove(conversation)
    if (pinned) {
        add(0, conversation)
    } else {
        conversation.pinnedAt = 0
        val at = indexOfFirst { !it.pinned }
        if (at < 0) add(conversation) else add(at, conversation)
    }
}
