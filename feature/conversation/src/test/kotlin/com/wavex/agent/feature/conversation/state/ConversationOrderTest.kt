package com.wavex.agent.feature.conversation.state

import androidx.compose.runtime.mutableStateListOf
import com.wavex.agent.feature.conversation.model.AgentConversation
import org.junit.Assert.assertEquals
import org.junit.Test

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

    private fun assertSameList(ids: List<String>, list: List<AgentConversation>) {
        assertEquals(ids, list.map { it.id })
    }
}
