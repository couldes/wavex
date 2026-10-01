package com.wavex.agent.data

import com.wavex.agent.model.TREE_ROOT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 行为钉子：备份文件夹恢复的两个关键判定。
 * - chooseRestore：候选按优先级排序传入，取第一份能解析出对话的（新坏旧好要回退到旧的）。
 * - hasMeaningfulContent：空快照不算有效备份，不能写进文件夹把好备份盖掉。
 */
class SafBackupStoreTest {

    private val store = ConversationStore(File.createTempFile("saf", ".json").apply { deleteOnExit() })

    /** 与持久化同格式的最小树 JSON（root → m1） */
    private fun treeJson(text: String): String =
        org.json.JSONArray().put(
            org.json.JSONObject()
                .put("id", "c1").put("title", "t")
                .put("nodes", org.json.JSONArray().put(
                    org.json.JSONObject().put("id", "m1").put("text", text).put("fromUser", true)))
                .put("children", org.json.JSONObject().put(TREE_ROOT, org.json.JSONArray().put("m1")))
                .put("activeChild", org.json.JSONObject().put(TREE_ROOT, "m1"))
        ).toString()

    // ---------- chooseRestore：传入顺序即优先级 ----------

    @Test
    fun `chooseRestore picks the first parseable candidate`() {
        val chosen = SafBackupStore.chooseRestore(
            listOf(treeJson("新内容"), treeJson("旧内容"))
        ) { store.parse(it) }

        val snap = store.parse(chosen!!).single()
        assertEquals("新内容", snap.tree.nodes[snap.tree.children[TREE_ROOT]!!.single()]!!.text)
    }

    @Test
    fun `chooseRestore skips invalid candidates and falls back`() {
        val chosen = SafBackupStore.chooseRestore(
            listOf("{ 这不是合法 JSON !!!", "[]", treeJson("旧内容"))
        ) { store.parse(it) }

        val snap = store.parse(chosen!!).single()
        assertEquals("旧内容", snap.tree.nodes[snap.tree.children[TREE_ROOT]!!.single()]!!.text)
    }

    @Test
    fun `chooseRestore returns null when nothing is parseable`() {
        assertNull(SafBackupStore.chooseRestore(emptyList()) { store.parse(it) })
        assertNull(SafBackupStore.chooseRestore(listOf("not json", "[]")) { store.parse(it) })
    }

    // ---------- sortRestoreCandidates：按文件修改时间降序，auto 只在同时间平级时优先 ----------

    @Test
    fun `newer file sorts first regardless of auto status`() {
        val ordered = SafBackupStore.sortRestoreCandidates(
            listOf(SafBackupStore.AUTO_BACKUP_NAME to 1000L, "wavex-backup-20251001.json" to 2000L)
        )
        assertEquals("wavex-backup-20251001.json", ordered.first())
        assertEquals(SafBackupStore.AUTO_BACKUP_NAME, ordered.last())
    }

    @Test
    fun `equal timestamps auto backup wins then name desc`() {
        val ordered = SafBackupStore.sortRestoreCandidates(
            listOf(
                "wavex-backup-20250928.json" to 1000L,
                "wavex-backup-20251001.json" to 1000L,
                SafBackupStore.AUTO_BACKUP_NAME to 1000L
            )
        )
        assertEquals(
            listOf(SafBackupStore.AUTO_BACKUP_NAME, "wavex-backup-20251001.json", "wavex-backup-20250928.json"),
            ordered
        )
    }

    @Test
    fun `missing timestamps fall back to auto first then name desc`() {
        val ordered = SafBackupStore.sortRestoreCandidates(
            listOf(
                "wavex-backup-20250928.json" to 0L,
                "conversations-pre-import-20250901-120000.json" to 0L,
                SafBackupStore.AUTO_BACKUP_NAME to 0L,
                "wavex-backup-20251001.json" to 0L
            )
        )
        assertEquals(
            listOf(
                SafBackupStore.AUTO_BACKUP_NAME,
                "wavex-backup-20251001.json",
                "wavex-backup-20250928.json",
                "conversations-pre-import-20250901-120000.json"
            ),
            ordered
        )
    }

    // ---------- 安全快照：命名可按时间排序 ----------

    @Test
    fun `snapshot file name is timestamped and sorts chronologically`() {
        val name1 = SafBackupStore.snapshotFileName(1_700_000_000_000L)
        val name2 = SafBackupStore.snapshotFileName(1_700_000_060_000L)
        assertTrue(name1.matches(Regex("conversations-pre-import-\\d{8}-\\d{6}\\.json")))
        assertTrue("同一时间线上的后写快照必须排在前面（名字序即时间序）", name2 > name1)
    }

    // ---------- 安全快照：只保留最新 N 份 ----------

    @Test
    fun `prune keeps newest snapshots and flags older for deletion`() {
        val names = listOf(
            "conversations-pre-import-20251001-120000.json",
            "conversations-pre-import-20251002-120000.json",
            "conversations-pre-import-20251003-120000.json",
            "conversations-pre-import-20251004-120000.json",
            "conversations-pre-import-20251005-120000.json"
        )
        assertEquals(
            listOf("conversations-pre-import-20251001-120000.json", "conversations-pre-import-20251002-120000.json").sorted(),
            SafBackupStore.pruneSnapshots(names, keep = 3).sorted()
        )
    }

    @Test
    fun `prune ignores non snapshot names`() {
        val names = listOf(
            "conversations-pre-import-20251005-120000.json",
            "conversations-auto.json",
            "wavex-backup-20251005.json"
        )
        assertTrue(SafBackupStore.pruneSnapshots(names, keep = 3).isEmpty())
    }

    @Test
    fun `prune does nothing when at or under keep limit`() {
        val names = listOf(
            "conversations-pre-import-20251004-120000.json",
            "conversations-pre-import-20251005-120000.json",
            "conversations-pre-import-20251006-120000.json"
        )
        assertTrue(SafBackupStore.pruneSnapshots(names, keep = 3).isEmpty())
    }

    // ---------- 空快照不算有效备份 ----------

    @Test
    fun `empty conversation list is not meaningful`() {
        assertFalse(store.hasMeaningfulContent("[]"))
        assertFalse(store.hasMeaningfulContent("{ 这不是合法 JSON !!!"))
        // 全新安装/清除数据后落盘的状态：只有一个空 welcome 对话，无消息节点
        val onlyEmptyConv = """[{"id":"welcome","title":"新对话","nodes":[],"children":{},"activeChild":{}}]"""
        assertFalse(store.hasMeaningfulContent(onlyEmptyConv))
    }

    @Test
    fun `snapshot with at least one message node is meaningful`() {
        assertTrue(store.hasMeaningfulContent(treeJson("有内容")))
    }
}
