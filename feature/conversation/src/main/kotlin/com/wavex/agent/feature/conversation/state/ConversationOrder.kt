package com.wavex.agent.feature.conversation.state

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.wavex.agent.feature.conversation.model.AgentConversation

/**
 * 会话列表排序不变量（纯逻辑，可单测）：列表恒按 lastActiveAt 降序——
 * 「最近对话」名副其实：选中旧会话 / 发消息 / 新建都把该会话顶到最上。
 * 恢复时旧数据等值（lastActiveAt=0）由稳定排序保持装载序（文件序 = 创建序降序）。
 */

/** 列表排序比较器：lastActiveAt 降序（等值保持相对顺序，稳定） */
val conversationOrder: Comparator<AgentConversation> =
    compareByDescending { it.lastActiveAt }

/** 整表恢复不变量：稳定重排（等值保持原相对顺序） */
fun SnapshotStateList<AgentConversation>.reorderByLastActive() {
    val sorted = sortedWith(conversationOrder)
    clear()
    sorted.forEach { add(it) }
}

/**
 * 活跃刷新：刷新 [conversation] 的时间戳并置顶（已在列表中的先按 identity 移除）。
 * 恒置顶而非按时间找位：同毫秒平局时「刚活跃者在上」优先于时间序——
 * 时间戳只为恢复加载后的排序服务（等值平局由恢复端稳定排序处理）。
 */
fun SnapshotStateList<AgentConversation>.bumpByActivity(conversation: AgentConversation, now: Long) {
    conversation.lastActiveAt = now
    remove(conversation)
    add(0, conversation)
}
