package com.wavex.agent.core.data

import com.wavex.agent.core.data.repository.DefaultConversationRepository
import com.wavex.agent.core.domain.model.AgentConversationData
import com.wavex.agent.core.domain.model.StoredMessage
import com.wavex.agent.core.domain.model.TREE_ROOT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * DefaultConversationRepository 钉子（内部测试构造器装配，JVM 直测）：
 * 目录升档搬移失败（目标路径被普通文件占据）→ 源目录不切换、文件原样保留，
 * 随后的 sync 照常追加 —— 「失败可重试、不丢数据」（plan Review Focus）。
 */
class ConversationRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val NOW = "2026-10-08T10:00:00.000Z"

    private fun msgLine(entryId: String, parentId: String? = null): String =
        SessionCodec.buildMessageEntry(
            entryId, parentId, NOW,
            StoredMessage(id = entryId, text = "text-$entryId", fromUser = true)
        )

    /** 线性链会话数据（root → e1 → e2 → …），与生产 snapshotOf 产物同构 */
    private fun dataOf(id: String, vararg msgs: StoredMessage): AgentConversationData {
        val nodes = LinkedHashMap<String, StoredMessage>()
        val children = LinkedHashMap<String, MutableList<String>>()
        val activeChild = LinkedHashMap<String, String>()
        var parent = TREE_ROOT
        msgs.forEach { m ->
            nodes[m.id] = m
            children.getOrPut(parent) { mutableListOf() }.add(m.id)
            activeChild[parent] = m.id
            parent = m.id
        }
        return AgentConversationData(
            id = id, title = "会话", titleUserDefined = false, titleUserCount = 0,
            nodes = nodes, children = children, activeChild = activeChild,
        )
    }

    /**
     * Review Focus（plan line 29）：moveAllTo 失败（目标无效）→ 源文件完好、目录不切换，
     * 后续 sync 仍可用（追加正常落源目录）。
     */
    @Test
    fun `failedMoveKeepsSourceUsable`() = kotlinx.coroutines.runBlocking {
        val source = tmp.newFolder("sessions")
        val store = SessionStore(source)
        store.appendLines("sid", listOf(msgLine("e1"))) { NOW }

        val repo = DefaultConversationRepository(
            store,
            generatedFileStore = null,
            legacyJsonFile = null,
            resolveDir = { _ -> tmp.newFile("blocked") },   // 目标被同名普通文件占据 → 搬移必然失败
            now = { NOW },
        )
        // 基线登记（真实加载序）：sync 的 diff 以此为 prev
        val loaded = repo.loadAll()
        assertEquals(listOf("sid"), loaded.map { it.id })

        // 升档搬移：目标无效 → moveAllTo fail-fast 停搬，root 仍指源目录
        val status = repo.refreshStorage()

        assertEquals(source, File(status.path))   // 目录未切换
        val jsonl = source.listFiles()!!.filter { it.name.endsWith(".jsonl") }
        assertEquals(1, jsonl.size)
        assertTrue(jsonl[0].readText().contains("text-e1"))   // 源文件内容原样

        // 后续 sync 照常追加进源文件（store 未失能）
        repo.sync(
            listOf(
                dataOf(
                    "sid",
                    StoredMessage(id = "e1", text = "text-e1", fromUser = true),
                    StoredMessage(id = "e2", text = "text-e2", fromUser = true),
                )
            ),
            emptyMap(),
        )

        val parsed = store.loadAll()
        assertEquals(1, parsed.size)
        assertEquals("sid", parsed[0].id)
        assertEquals(setOf("e1", "e2"), parsed[0].tree.nodes.keys)
        assertTrue(jsonl[0].readText().contains("text-e2"))   // 追加真的落了盘
        Unit
    }

    /**
     * 评审 Major #1 防复发钉子：经 importSessions 落盘但从未进 VM 内存列表的 id，
     * 会在下一次 sync 被孤儿清扫删掉（「内存消失 → 删文件」规则）。
     * 这正是为什么旧备份导入必须走 ConversationViewModel.importSnapshots
     * （同一主线程续体内「落盘登记 → 进列表」），而绝不能只调 repository。
     */
    @Test
    fun `importedButNeverInMemoryIdIsSweptByNextSync`() = kotlinx.coroutines.runBlocking {
        val source = tmp.newFolder("sessions")
        val store = SessionStore(source)
        val repo = DefaultConversationRepository(
            store,
            generatedFileStore = null,
            legacyJsonFile = null,
            resolveDir = { _ -> source },
            now = { NOW },
        )
        repo.loadAll()

        // 模拟「只写盘不进列表」的导入（错误用法）：文件存在、knownIds 登记
        val imported = dataOf("ghost-id", StoredMessage(id = "g1", text = "ghost", fromUser = true))
        assertEquals(1, repo.importSessions(listOf(imported)))
        assertEquals(1, store.loadAll().size)

        // 下一次 sync 的引用集不含 ghost-id → 文件被清扫
        repo.sync(emptyList(), emptyMap())

        assertEquals(0, store.loadAll().size)
        Unit
    }

}
