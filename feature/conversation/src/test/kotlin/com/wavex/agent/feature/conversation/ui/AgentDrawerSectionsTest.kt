package com.wavex.agent.feature.conversation.ui

import com.wavex.agent.core.domain.model.ChatMessage
import com.wavex.agent.feature.conversation.model.AgentConversation
import com.wavex.agent.feature.conversation.state.DateGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 抽屉分区纯函数（AgentDrawer.buildDrawerSections）的语义钉子：
 * - 无搜索：置顶区/未置顶未归档区/归档排除，日期分组按桶首次出现序、组内保持活跃序；
 * - 搜索：标题与消息文本命中（含归档、忽略大小写），平铺结果，无分区。
 */
class AgentDrawerSectionsTest {

    private val today: LocalDate = LocalDate.of(2026, 1, 15)

    private fun ms(daysAgo: Long): Long =
        today.minusDays(daysAgo).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun conv(
        id: String,
        title: String = "会话$id",
        lastActive: Long = 0,
        pinned: Boolean = false,
        archived: Boolean = false,
        messageText: String? = null,
    ): AgentConversation = AgentConversation(id, title, initialLastActiveAt = lastActive).apply {
        this.pinned = pinned
        this.archived = archived
        if (messageText != null) appendMessage(ChatMessage(text = messageText, fromUser = true))
    }

    @Test
    fun `sections split pinned recent and exclude archived without query`() {
        val a = conv("a", pinned = true, lastActive = ms(0))
        val b = conv("b", lastActive = ms(1))
        val d = conv("d", lastActive = ms(3))
        val c = conv("c", archived = true, lastActive = ms(40))
        val sections = buildDrawerSections(listOf(a, b, d, c), "", today)
        assertEquals(listOf(a, b, d, c), sections.displayed)
        assertEquals(listOf(a), sections.pinned)
        assertEquals(listOf(b, d), sections.recent)
        assertEquals(listOf(a, b, d), sections.visible)
    }

    @Test
    fun `date groups keep first appearance order and in-group activity order`() {
        val a = conv("a", pinned = true, lastActive = ms(0))
        val b = conv("b", lastActive = ms(1))          // 昨天
        val d1 = conv("d1", lastActive = ms(5))        // 7 天内
        val d2 = conv("d2", lastActive = ms(2))        // 7 天内（更晚活跃）
        val e = conv("e", lastActive = ms(40))         // 更早
        // 输入列表已按活跃降序（pinned 头部），日期桶按首次出现序
        val sections = buildDrawerSections(listOf(a, b, d2, d1, e), "", today)
        assertEquals(
            listOf(
                DateGroup.YESTERDAY to listOf(b),
                DateGroup.WITHIN_WEEK to listOf(d2, d1),
                DateGroup.OLDER to listOf(e),
            ),
            sections.dateGroups.map { it.group to it.items },
        )
    }

    @Test
    fun `query matches title case-insensitively and flattens sections`() {
        val hit = conv("a", title = "Beta 方案", lastActive = ms(1))
        val other = conv("b", title = "别的", lastActive = ms(0))
        val sections = buildDrawerSections(listOf(other, hit), "bet", today)
        assertEquals(listOf(hit), sections.displayed)
        assertEquals(emptyList<AgentConversation>(), sections.pinned)
        assertEquals(listOf(hit), sections.recent)
        assertEquals(emptyList<Any>(), sections.dateGroups)
    }

    @Test
    fun `query matches message text including archived conversations`() {
        val archivedHit = conv("a", title = "归档的", archived = true, lastActive = ms(40), messageText = "含 needle 的消息")
        val miss = conv("b", title = "不含", lastActive = ms(1))
        val sections = buildDrawerSections(listOf(miss, archivedHit), "NEEDLE", today)
        assertEquals(listOf(archivedHit), sections.displayed)
    }

    @Test
    fun `empty query keeps every conversation including archived in displayed`() {
        val archived = conv("a", archived = true, lastActive = ms(2))
        val normal = conv("b", lastActive = ms(1))
        val sections = buildDrawerSections(listOf(normal, archived), "  ", today)
        // 空白查询 = 无搜索：全列表展示（归档条目的排除只发生在分区，不在过滤）
        assertEquals(listOf(normal, archived), sections.displayed)
        assertEquals(listOf(normal), sections.recent)
    }
}
