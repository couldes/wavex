package com.wavex.agent.data

import com.wavex.agent.model.TREE_ROOT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 行为钉子：备份文件夹恢复的关键判定。
 * - buildRestorePreviews：选择列表的文件级预览（有效性判定、对话数、样例标题截断）。
 * - sortRestoreCandidates：恢复候选按修改时间降序（最新优先）。
 * - hasMeaningfulContent/parseIfMeaningful：空快照不算有效备份，不能写进文件夹把好备份盖掉。
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

    /** 多对话 JSON：第 i 个对话标题 = titles[i]，各含一条消息 */
    private fun multiConvJson(vararg titles: String): String =
        org.json.JSONArray().apply {
            titles.forEachIndexed { i, t ->
                put(
                    org.json.JSONObject()
                        .put("id", "c$i").put("title", t)
                        .put("nodes", org.json.JSONArray().put(
                            org.json.JSONObject().put("id", "m$i").put("text", "msg$i").put("fromUser", true)))
                        .put("children", org.json.JSONObject().put(TREE_ROOT, org.json.JSONArray().put("m$i")))
                        .put("activeChild", org.json.JSONObject().put(TREE_ROOT, "m$i"))
                )
            }
        }.toString()

    // ---------- buildRestorePreviews：恢复选择列表的文件级预览 ----------

    @Test
    fun `buildRestorePreviews preserves priority order with per-file preview`() {
        val previews = SafBackupStore.buildRestorePreviews(
            listOf("auto.json" to multiConvJson("工作", "生活"), "old.json" to treeJson("旧消息")),
            parse = { store.parseIfMeaningful(it) },
            title = { it.title }
        )
        assertEquals(listOf("auto.json", "old.json"), previews.map { it.name })
        assertTrue(previews[0].valid)
        assertEquals(2, previews[0].conversationCount)
        assertEquals(listOf("工作", "生活"), previews[0].sampleTitles)
        assertTrue(previews[1].valid)
        assertEquals(1, previews[1].conversationCount)
    }

    @Test
    fun `buildRestorePreviews marks invalid files without breaking others`() {
        val previews = SafBackupStore.buildRestorePreviews(
            listOf(
                "broken.json" to "{ 这不是合法 JSON !!!",
                "empty.json" to "[]",
                "blank.json" to "   ",
                "empty-conv.json" to """[{"id":"welcome","title":"新对话","nodes":[],"children":{},"activeChild":{}}]""",
                "good.json" to multiConvJson("有效")
            ),
            // 与 ViewModel 恢复同一规则（同一入口 parseIfMeaningful）：无实际内容不算有效
            parse = { store.parseIfMeaningful(it) },
            title = { it.title }
        )
        assertEquals(listOf(false, false, false, false, true), previews.map { it.valid })
        assertEquals(listOf("有效"), previews.last().sampleTitles)
        assertTrue(previews.dropLast(1).all { it.sampleTitles.isEmpty() })
    }

    @Test
    fun `buildRestorePreviews caps sample titles at three`() {
        val previews = SafBackupStore.buildRestorePreviews(
            listOf("big.json" to multiConvJson("一", "二", "三", "四", "五")),
            parse = { store.parseIfMeaningful(it) },
            title = { it.title }
        )
        assertTrue(previews.single().valid)
        assertEquals(5, previews.single().conversationCount)
        assertEquals(listOf("一", "二", "三"), previews.single().sampleTitles)
    }

    @Test
    fun `buildRestorePreviews on empty input returns empty`() {
        assertTrue(
            SafBackupStore.buildRestorePreviews(
                emptyList(), parse = { store.parseIfMeaningful(it) }, title = { it.title }
            ).isEmpty()
        )
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
