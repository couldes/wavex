package com.wavex.agent.feature.conversation.state

import androidx.compose.runtime.mutableStateListOf
import com.wavex.agent.feature.conversation.model.AgentConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 会话列表排序不变量钉子：列表恒按 lastActiveAt 降序——
 * 「最近对话」= 最近用过（选中/新建/发送即置顶）。
 * 等值（旧数据 lastActiveAt=0、同毫秒新建）保持稳定相对顺序。
 */
class ConversationOrderTest {

    private fun conv(id: String, lastActiveAt: Long) =
        AgentConversation(id, "会话$id", initialLastActiveAt = lastActiveAt)

    // ---- reorderByLastActive：恢复不变量 ----

    @Test
    fun `restore sorts by lastActiveAt descending`() {
        val list = mutableStateListOf(conv("a", 100), conv("b", 300), conv("c", 200))
        list.reorderByLastActive()
        assertEquals(listOf("b", "c", "a"), list.map { it.id })
    }

    @Test
    fun `restore keeps load order for equal timestamps`() {
        // 旧数据全部 lastActiveAt=0：稳定排序保持装载序（文件序 = 创建序降序）
        val list = mutableStateListOf(conv("a", 0), conv("b", 0), conv("c", 0))
        list.reorderByLastActive()
        assertEquals(listOf("a", "b", "c"), list.map { it.id })
    }

    // ---- bumpByActivity：活跃刷新 ----

    @Test
    fun `bump moves existing conversation to front`() {
        val list = mutableStateListOf(conv("a", 300), conv("b", 200), conv("c", 100))
        list.bumpByActivity(list.first { it.id == "c" }, 400)
        assertEquals(listOf("c", "a", "b"), list.map { it.id })
        assertEquals(400, list.first { it.id == "c" }.lastActiveAt)
    }

    @Test
    fun `bump inserts new conversation before older ones`() {
        val list = mutableStateListOf(conv("a", 300), conv("b", 100))
        list.bumpByActivity(conv("n", 0), 400)
        assertEquals(listOf("n", "a", "b"), list.map { it.id })
    }

    @Test
    fun `bump on tied timestamp still lands on top`() {
        // 同毫秒平局：刚活跃者必须在上（时间序平局只由恢复端稳定排序处理）
        val list = mutableStateListOf(conv("a", 300))
        list.bumpByActivity(conv("n", 0), 300)
        assertEquals(listOf("n", "a"), list.map { it.id })
    }

    @Test
    fun `bump on absent conversation still inserts`() {
        // 复用路径先 remove 再 bump：bump 对不在列表的会话按纯插入处理
        val list = mutableStateListOf(conv("a", 300))
        val offline = conv("n", 0)
        list.bumpByActivity(offline, 400)
        assertSameList(listOf("n", "a"), list)
    }

    // ---- setPinned：置顶区固定在头部，区内按置顶时刻降序 ----

    @Test
    fun `setPinned moves conversation to pinned section head`() {
        val list = mutableStateListOf(conv("a", 300), conv("b", 200), conv("c", 100))
        list.setPinned(list.first { it.id == "c" }, true, 400)
        assertEquals(listOf("c", "a", "b"), list.map { it.id })
        assertTrue(list.first { it.id == "c" }.pinned)
        assertEquals(400, list.first { it.id == "c" }.pinnedAt)
    }

    @Test
    fun `pinned section orders by pin recency`() {
        val list = mutableStateListOf(conv("a", 300), conv("b", 200))
        list.setPinned(list.first { it.id == "a" }, true, 100)
        list.setPinned(list.first { it.id == "b" }, true, 200)
        // 后钉的 c 在前：置顶区按 pinnedAt 降序
        assertEquals(listOf("b", "a"), list.map { it.id })
    }

    @Test
    fun `activity bump does not move pinned conversations`() {
        val list = mutableStateListOf(conv("a", 300), conv("b", 200))
        list.setPinned(list.first { it.id == "b" }, true, 50)
        assertEquals(listOf("b", "a"), list.map { it.id })
        // 未置顶的 a 活跃后顶到未置顶区头，但不得越过置顶区
        list.bumpByActivity(list.first { it.id == "a" }, 400)
        assertEquals(listOf("b", "a"), list.map { it.id })
        assertEquals(400, list.first { it.id == "a" }.lastActiveAt)
        // 置顶会话自己活跃：只刷时间戳，位置不动
        list.bumpByActivity(list.first { it.id == "b" }, 500)
        assertEquals(listOf("b", "a"), list.map { it.id })
    }

    @Test
    fun `unpin returns conversation to head of unpinned section`() {
        val list = mutableStateListOf(conv("a", 300), conv("b", 200), conv("c", 100))
        list.setPinned(list.first { it.id == "c" }, true, 400)
        list.setPinned(list.first { it.id == "c" }, false, 500)
        assertEquals(listOf("c", "a", "b"), list.map { it.id })
        assertFalse(list.first { it.id == "c" }.pinned)
        assertEquals(0, list.first { it.id == "c" }.pinnedAt)
    }

    @Test
    fun `restore keeps pinned before unpinned regardless of activity`() {
        val pinned = conv("a", 0).also { it.pinned = true }
        val list = mutableStateListOf(pinned, conv("b", 999))
        list.reorderByLastActive()
        assertEquals(listOf("a", "b"), list.map { it.id })
    }

    // ---- DateGroup：未置顶区日期分组（按本地日历日切桶，today 注入） ----

    @Test
    fun `date buckets follow calendar days`() {
        val today = LocalDate.of(2026, 10, 13)
        val zone = ZoneId.systemDefault()
        fun at(daysAgo: Long): Long =
            today.minusDays(daysAgo).atStartOfDay(zone).toInstant().toEpochMilli() + 1_000
        assertEquals(DateGroup.TODAY, DateGroup.of(at(0), today))
        assertEquals(DateGroup.YESTERDAY, DateGroup.of(at(1), today))
        assertEquals(DateGroup.WITHIN_WEEK, DateGroup.of(at(2), today))
        assertEquals(DateGroup.WITHIN_WEEK, DateGroup.of(at(6), today))
        assertEquals(DateGroup.WITHIN_MONTH, DateGroup.of(at(7), today))
        assertEquals(DateGroup.WITHIN_MONTH, DateGroup.of(at(29), today))
        assertEquals(DateGroup.OLDER, DateGroup.of(at(30), today))
        // 旧数据（0 = 未知时间）落「更早」
        assertEquals(DateGroup.OLDER, DateGroup.of(0, today))
    }

    private fun assertSameList(ids: List<String>, list: List<AgentConversation>) {
        assertEquals(ids, list.map { it.id })
    }
}
