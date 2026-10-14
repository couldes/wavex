package com.wavex.agent.core.data

import com.wavex.agent.core.domain.model.TREE_ROOT
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 旧备份一次性导入（spec §7.3）核心纯逻辑钉：旧 json/bak → LegacyConversationJson.parse →
 * SessionCodec.initialLines(idOverride = 新 id) → SessionStore.appendLines → loadAll。
 * 断言：导入会话以覆盖的新 id 落盘（≠ 旧 id，永不与现有会话撞 id）；标题三元组 / 节点数 /
 * 树形状 / 活跃分支逐字段保真；文件首行 header 的 id = 新 id。
 */
class LegacyImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val NOW = "2026-10-08T10:00:00.000Z"

    /** 旧 .bak 样例（树格式，fixture 风格同 LegacyMigratorTest）：含 reasoning + 附件 + 手动命名标题，非默认活跃分支 */
    private fun treeConv(id: String, title: String): JSONObject =
        JSONObject()
            .put("id", id).put("title", title)
            .put("titleUserDefined", true).put("titleUserCount", 3)
            .put("nodes", JSONArray()
                .put(JSONObject()
                    .put("id", "m1").put("text", "你好").put("fromUser", true).put("isError", false)
                    .put("attachments", JSONArray().put(
                        JSONObject().put("uri", "content://media/1").put("name", "photo.jpg"))))
                .put(JSONObject()
                    .put("id", "m2").put("text", "回复A").put("fromUser", false)
                    .put("reasoning", "想了想"))
                .put(JSONObject()
                    .put("id", "m3").put("text", "回复B").put("fromUser", false)))
            .put("children", JSONObject()
                .put(TREE_ROOT, JSONArray().put("m1"))
                .put("m1", JSONArray().put("m2").put("m3")))
            .put("activeChild", JSONObject().put(TREE_ROOT, "m1").put("m1", "m2"))

    /** 旧 .bak 样例（平铺格式）：messages 线性链 a→b */
    private fun flatConv(id: String, title: String): JSONObject =
        JSONObject()
            .put("id", id).put("title", title)
            .put("messages", JSONArray()
                .put(JSONObject().put("id", "a").put("text", "第一句").put("fromUser", true))
                .put(JSONObject().put("id", "b").put("text", "第二句").put("fromUser", false)))

    @Test
    fun `旧 bak 样例导入为新 id 会话文件`() {
        val snap = LegacyConversationJson.parse(
            JSONArray().put(treeConv("old-conv-1", "面试复盘")).toString()
        ).single()
        assertEquals("old-conv-1", snap.id)

        val lines = SessionCodec.initialLines(snap, idOverride = "newid", now = { NOW })
        val dir = tmp.newFolder("sessions")
        val store = SessionStore(dir)
        // 与生产 importFromLegacy 同构：initialLines 契约 = 首行 header，appendLines 自写
        // header —— 调用方剥掉首行，否则文件出现双 session 行（final review #8）
        store.appendLines("newid", lines.drop(1)) { NOW }

        val sessions = store.loadAll()
        assertEquals(1, sessions.size)
        val s = sessions.single()
        // 导入成为新会话：header id = 新 id，与旧 id 无关（永不与现有会话 id 冲突）
        assertEquals("newid", s.id)
        assertNotEquals("old-conv-1", s.id)
        // 标题三元组保真
        assertEquals("面试复盘", s.title)
        assertTrue(s.titleUserDefined)
        assertEquals(3, s.titleUserCount)
        // 节点数 / 树形状（nodes/children）/ 活跃分支逐字段保真
        assertEquals(3, s.tree.nodes.size)
        assertEquals(snap.tree, s.tree)
        assertEquals("m1", s.tree.activeChild[TREE_ROOT])
        assertEquals("m2", s.tree.activeChild["m1"])
        // 文件名 = <epochMs>_<uuid8>.jsonl（Task 7 命名规则，导入路径同样成立）
        val file = dir.listFiles()!!.single { it.name.endsWith(".jsonl") }
        assertTrue(Regex("^\\d+_[0-9a-f]{8}\\.jsonl$").matches(file.name))
        // 文件首行 header 的 id = 新 id
        assertEquals("newid", JSONObject(file.readLines().first()).getString("id"))
        // 恰好一条 session header（appendLines 自写 header，导入侧已剥掉 initialLines 首行）
        val headerCount = file.readLines().count {
            runCatching { JSONObject(it).getString("type") == "session" }.getOrDefault(false)
        }
        assertEquals(1, headerCount)
    }

    @Test
    fun `一次导入多个对话各得独立新 id`() {
        val snaps = LegacyConversationJson.parse(
            JSONArray().put(treeConv("old-a", "旧会话A")).put(flatConv("old-b", "旧会话B")).toString()
        )
        assertEquals(2, snaps.size)

        val dir = tmp.newFolder("sessions")
        val store = SessionStore(dir)
        // 逐会话独立 idOverride（真实导入用 UUID，此处用可读占位同构）
        val newIds = listOf("newid-a", "newid-b")
        snaps.forEachIndexed { i, snap ->
            store.appendLines(newIds[i], SessionCodec.initialLines(snap, idOverride = newIds[i]) { NOW }.drop(1)) { NOW }
        }

        val sessions = store.loadAll()
        assertEquals(2, sessions.size)
        assertEquals(newIds.toSet(), sessions.map { it.id }.toSet())
        assertTrue(sessions.none { it.id == "old-a" || it.id == "old-b" })
        // 两种旧格式各自保真：树格式标题 / 平铺线性链节点
        val byId = sessions.associateBy { it.id }
        assertEquals("旧会话A", byId["newid-a"]!!.title)
        assertEquals(setOf("m1", "m2", "m3"), byId["newid-a"]!!.tree.nodes.keys)
        assertEquals("旧会话B", byId["newid-b"]!!.title)
        assertEquals(setOf("a", "b"), byId["newid-b"]!!.tree.nodes.keys)
    }
}
