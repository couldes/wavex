package com.wavex.agent.feature.conversation.state

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.wavex.agent.feature.conversation.model.AgentConversation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 会话列表排序不变量（纯逻辑，可单测）：
 * 列表 = 置顶区（pinnedAt 降序，位置固定不随活跃跳动）+ 未置顶区（lastActiveAt 降序）
 * + 归档区（lastActiveAt 降序，列表尾部）。归档与置顶互斥（归档即取消置顶）。
 * 恢复时旧数据等值（lastActiveAt=0 / pinnedAt=0）由稳定排序保持装载序（文件序 = 创建序降序）。
 */

/** 列表排序比较器：置顶在前（pinnedAt 降序），未置顶按 lastActiveAt 降序，归档垫底 */
val conversationOrder: Comparator<AgentConversation> =
    compareBy<AgentConversation> { it.archived }
        .thenBy { !it.pinned }
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
 * 已置顶/已归档的会话不动位置（置顶区固定、归档区垫底），只刷时间戳。
 */
fun SnapshotStateList<AgentConversation>.bumpByActivity(conversation: AgentConversation, now: Long) {
    conversation.lastActiveAt = now
    if (conversation.pinned || conversation.archived) return
    remove(conversation)
    // 未置顶区头部 = 置顶区之后；无未置顶项时落到归档区之前（或列表尾）
    val at = indexOfFirst { !it.pinned }
    if (at < 0) add(conversation) else add(at, conversation)
}

/**
 * 置顶/取消置顶：置顶的会话移入置顶区头部（最新钉的在前）；
 * 取消置顶的会话按活跃序回到未置顶区头部。伴随的列表快照写同时触发落盘观察。
 * 已归档的会话不可置顶（归档与置顶互斥），置顶动作对其为无操作。
 */
fun SnapshotStateList<AgentConversation>.setPinned(conversation: AgentConversation, pinned: Boolean, now: Long) {
    if (conversation.archived) return
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

/**
 * 归档/取消归档：归档的会话移入列表尾部的归档区头部（区内按活跃降序），并强制取消置顶；
 * 取消归档视为一次活跃，回到未置顶区头部。
 */
fun SnapshotStateList<AgentConversation>.setArchived(conversation: AgentConversation, archived: Boolean, now: Long) {
    conversation.archived = archived
    remove(conversation)
    if (archived) {
        conversation.pinned = false
        conversation.pinnedAt = 0
        conversation.lastActiveAt = now
        val at = indexOfFirst { it.archived }
        if (at < 0) add(conversation) else add(at, conversation)
    } else {
        conversation.lastActiveAt = now
        val at = indexOfFirst { !it.pinned && !it.archived }
        if (at < 0) add(conversation) else add(at, conversation)
    }
}

/**
 * 未置顶区的日期分组（ChatGPT 式扫读）：按本地日历日切桶——
 * 今天 / 昨天 / 7 天内 / 30 天内 / 更早。列表已按 lastActiveAt 降序，
 * 分组标题按桶首次出现顺序渲染即天然有序；组内保持活跃序。
 */
enum class DateGroup(val label: String) {
    TODAY("今天"),
    YESTERDAY("昨天"),
    WITHIN_WEEK("7 天内"),
    WITHIN_MONTH("30 天内"),
    OLDER("更早");

    companion object {
        /** [epochMs] → 所属分组；[today] 注入以便单测（时钟不进纯函数）。0（旧数据未知）落「更早」。 */
        fun of(epochMs: Long, today: LocalDate): DateGroup {
            if (epochMs <= 0) return OLDER
            val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
            val days = ChronoUnit.DAYS.between(date, today)
            return when {
                days <= 0L -> TODAY        // 当天（时钟回拨容错归今天）
                days == 1L -> YESTERDAY
                days < 7L -> WITHIN_WEEK
                days < 30L -> WITHIN_MONTH
                else -> OLDER
            }
        }
    }
}
