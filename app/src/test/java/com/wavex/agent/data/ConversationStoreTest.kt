package com.wavex.agent.data

import com.wavex.agent.model.TREE_ROOT
import com.wavex.agent.model.StoredMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 行为钉子：对话树持久化 round-trip、旧格式（平铺列表）自动迁移、损坏数据静默降级。
 * 附件 uri/name 以 Pair 序列化，升级覆盖后历史必须完整。
 */
class ConversationStoreTest {

    private fun tempStore(): Pair<ConversationStore, File> {
        val f = File.createTempFile("conversations", ".json")
        f.deleteOnExit()
        return ConversationStore(f) to f
    }

    private fun sampleTreeJson(): String {
        // 树：root → m1(用户) → {m2(助手,带reasoning), m3(助手,分支)}；activeChild 走 m2
        val nodes = JSONArray()
            .put(JSONObject()
                .put("id", "m1").put("text", "你好").put("fromUser", true).put("isError", false)
                .put("attachments", JSONArray().put(
                    JSONObject().put("uri", "content://media/1").put("name", "photo.jpg"))))
            .put(JSONObject()
                .put("id", "m2").put("text", "回复A").put("fromUser", false)
                .put("reasoning", "想了想"))
            .put(JSONObject()
                .put("id", "m3").put("text", "回复B").put("fromUser", false))
        return JSONArray().put(JSONObject()
            .put("id", "c1").put("title", "测试对话")
            .put("nodes", nodes)
            .put("children", JSONObject().put(TREE_ROOT, JSONArray().put("m1")).put("m1", JSONArray().put("m2").put("m3")))
            .put("activeChild", JSONObject().put(TREE_ROOT, "m1").put("m1", "m2"))
        ).toString()
    }

    @Test
    fun `tree round-trip preserves nodes children activeChild reasoning attachments`() {
        val (store, f) = tempStore()
        f.writeText(sampleTreeJson())

        val loaded = store.load()
        assertEquals(1, loaded.size)
        val snap = loaded[0]
        assertEquals("c1", snap.id)
        assertEquals("测试对话", snap.title)
        val tree = snap.tree
        assertEquals(setOf("m1", "m2", "m3"), tree.nodes.keys)
        assertEquals(listOf("m1"), tree.children[TREE_ROOT])
        assertEquals(listOf("m2", "m3"), tree.children["m1"])
        assertEquals("m1", tree.activeChild[TREE_ROOT])
        assertEquals("m2", tree.activeChild["m1"])
        val m1 = tree.nodes["m1"]!!
        assertTrue(m1.fromUser)
        assertEquals(listOf("content://media/1" to "photo.jpg"), m1.attachments)
        assertEquals("想了想", tree.nodes["m2"]!!.reasoning)
        assertEquals("回复B", tree.nodes["m3"]!!.text)

        // save → load 再 round-trip：AgentConversationData 形态全字段还原
        val convData = com.wavex.agent.model.AgentConversationData(
            id = snap.id, title = snap.title,
            nodes = tree.nodes, children = tree.children, activeChild = tree.activeChild
        )
        store.save(listOf(convData))
        val reloaded = store.load()
        assertEquals(1, reloaded.size)
        assertEquals(tree.nodes.keys, reloaded[0].tree.nodes.keys)
        assertEquals(tree.children, reloaded[0].tree.children)
        assertEquals(tree.activeChild, reloaded[0].tree.activeChild)
        assertEquals("想了想", reloaded[0].tree.nodes["m2"]!!.reasoning)
    }

    @Test
    fun `legacy flat messages format migrates to linear tree`() {
        val (store, f) = tempStore()
        val legacy = JSONArray().put(JSONObject()
            .put("id", "old1").put("title", "旧版对话")
            .put("messages", JSONArray()
                .put(JSONObject().put("id", "a").put("text", "第一句").put("fromUser", true))
                .put(JSONObject().put("id", "b").put("text", "第二句").put("fromUser", false))
                .put(JSONObject().put("id", "c").put("text", "第三句").put("fromUser", true))
            )
        ).toString()
        f.writeText(legacy)

        val loaded = store.load()
        assertEquals(1, loaded.size)
        val tree = loaded[0].tree
        assertEquals(setOf("a", "b", "c"), tree.nodes.keys)
        // TREE_ROOT 单链：root→a→b→c
        assertEquals(listOf("a"), tree.children[TREE_ROOT])
        assertEquals(listOf("b"), tree.children["a"])
        assertEquals(listOf("c"), tree.children["b"])
        // activeChild 逐级指向
        assertEquals("a", tree.activeChild[TREE_ROOT])
        assertEquals("b", tree.activeChild["a"])
        assertEquals("c", tree.activeChild["b"])
        assertEquals("第三句", tree.nodes["c"]!!.text)
    }

    @Test
    fun `corrupted json loads as empty list without throwing`() {
        val (store, f) = tempStore()
        f.writeText("{ 这不是合法 JSON !!!")
        assertEquals(0, store.load().size)
    }

    @Test
    fun `标题元数据 round-trip 保留`() {
        val (store, f) = tempStore()
        val data = com.wavex.agent.model.AgentConversationData(
            id = "c1", title = "自动起的标题",
            titleUserDefined = true, titleUserCount = 8,
            nodes = mapOf("m1" to StoredMessage(id = "m1", text = "你好", fromUser = true)),
            children = mapOf(TREE_ROOT to listOf("m1")),
            activeChild = mapOf(TREE_ROOT to "m1")
        )
        f.writeText(store.serialize(listOf(data)))

        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertTrue(loaded[0].titleUserDefined)
        assertEquals(8, loaded[0].titleUserCount)
    }

    @Test
    fun `旧数据无标题元字段时用默认值`() {
        val (store, f) = tempStore()
        f.writeText(sampleTreeJson())   // 无 titleUserDefined/titleUserCount 字段

        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertFalse(loaded[0].titleUserDefined)
        assertEquals(0, loaded[0].titleUserCount)
    }

    @Test
    fun `missing file loads as empty list`() {
        val f = File.createTempFile("nonexistent", ".json")
        f.delete()
        assertEquals(0, ConversationStore(f).load().size)
    }

    @Test
    fun `save is atomic enough to survive retry`() {
        val (store, f) = tempStore()
        val conv = com.wavex.agent.model.AgentConversationData(
            id = "x", title = "t",
            nodes = mapOf("n1" to StoredMessage(id = "n1", text = "hi", fromUser = true)),
            children = mapOf(TREE_ROOT to listOf("n1")),
            activeChild = mapOf(TREE_ROOT to "n1")
        )
        store.save(listOf(conv))
        assertTrue(f.exists())
        // .tmp 残留不应存在（rename 成功后 tmp 已被挪走）
        assertEquals(false, File(f.parentFile, f.name + ".tmp").exists())
        assertEquals(1, store.load().size)
    }

    // ---------- linearToTree ----------

    @Test
    fun `linearToTree empty list gives empty tree`() {
        val tree = ConversationStore(File.createTempFile("conv", ".json")).let {
            // linearToTree 是 internal 方法，经由 load 之外直接单测
            it.linearToTree(emptyList())
        }
        assertTrue(tree.nodes.isEmpty())
        assertTrue(tree.children.isEmpty())
        assertTrue(tree.activeChild.isEmpty())
    }

    @Test
    fun `linearToTree chains three messages from root`() {
        val msgs = listOf(
            StoredMessage(id = "a", text = "1", fromUser = true),
            StoredMessage(id = "b", text = "2", fromUser = false),
            StoredMessage(id = "c", text = "3", fromUser = true)
        )
        val tree = ConversationStore(File.createTempFile("conv", ".json")).linearToTree(msgs)
        assertEquals(setOf("a", "b", "c"), tree.nodes.keys)
        assertEquals(listOf("a"), tree.children[TREE_ROOT])
        assertEquals(listOf("b"), tree.children["a"])
        assertEquals(listOf("c"), tree.children["b"])
        assertEquals("a", tree.activeChild[TREE_ROOT])
        assertEquals("b", tree.activeChild["a"])
        assertEquals("c", tree.activeChild["b"])
    }

    // ---------- 模型返回附件（assistant 消息带附件）的兼容回归钉 ----------

    /** 旧版本 app 读取含 assistant 附件的新 JSON：不崩、uri/name 原样读回（file:// 与 https:// 均可） */
    @Test
    fun `assistant message attachments parse forward-compatibly`() {
        val json = JSONArray().put(
            JSONObject()
                .put("id", "c1").put("title", "T")
                .put("nodes", JSONArray()
                    .put(JSONObject()
                        .put("id", "m1").put("text", "画一张").put("fromUser", true))
                    .put(JSONObject()
                        .put("id", "m2").put("text", "[图片]").put("fromUser", false)
                        .put("attachments", JSONArray()
                            .put(JSONObject().put("uri", "file:///data/user/0/x/files/generated/a.png").put("name", "生成图片-1"))
                            .put(JSONObject().put("uri", "https://img.example/b.jpg").put("name", "生成图片-2"))))
                    .put(JSONObject()
                        .put("id", "m3").put("text", "[附件 报告.xlsx]").put("fromUser", false)
                        .put("attachments", JSONArray()
                            .put(JSONObject().put("uri", "file:///data/user/0/x/files/generated/c.xlsx").put("name", "报告.xlsx")))))
                .put("children", JSONObject().put(TREE_ROOT, JSONArray().put("m1")).put("m1", JSONArray().put("m2").put("m3")))
                .put("activeChild", JSONObject().put(TREE_ROOT, "m1").put("m1", "m2"))
        ).toString()
        val snaps = ConversationStore(File.createTempFile("conv", ".json").apply { deleteOnExit() }).parse(json)
        assertEquals(1, snaps.size)
        val nodes = snaps[0].tree.nodes
        assertEquals(2, nodes["m2"]!!.attachments.size)
        assertEquals("file:///data/user/0/x/files/generated/a.png" to "生成图片-1", nodes["m2"]!!.attachments[0])
        assertEquals("https://img.example/b.jpg" to "生成图片-2", nodes["m2"]!!.attachments[1])
        assertEquals("file:///data/user/0/x/files/generated/c.xlsx" to "报告.xlsx", nodes["m3"]!!.attachments[0])
    }

    /** round-trip：assistant 附件序列化后重读不丢（备份/导出链路同一格式） */
    @Test
    fun `assistant attachments survive serialize round trip`() {
        val (store, f) = tempStore()
        val data = com.wavex.agent.model.AgentConversationData(
            id = "c1", title = "T", titleUserDefined = false, titleUserCount = 0,
            nodes = mapOf(
                "m1" to StoredMessage(id = "m1", text = "[图片]", fromUser = false, attachments = listOf("file:///g/x.png" to "生成图片-1")),
                "m2" to StoredMessage(id = "m2", text = "后续", fromUser = true)
            ),
            children = mapOf(TREE_ROOT to listOf("m1"), "m1" to listOf("m2")),
            activeChild = mapOf(TREE_ROOT to "m1", "m1" to "m2")
        )
        f.writeText(store.serialize(listOf(data)))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        // 全树节点（nodes 含所有分支消息）的附件都应保留（孤儿清扫依赖全树引用集）
        val m1 = loaded[0].tree.nodes.values.first { it.id == "m1" }
        assertEquals(listOf("file:///g/x.png" to "生成图片-1"), m1.attachments)
    }

    // ---------- 旧成功态占位迁移（占位已从正文删除，存量消息加载时清理） ----------

    private fun treeJsonOf(vararg msgs: JSONObject): String {
        val nodes = JSONArray()
        msgs.forEach { nodes.put(it) }
        return JSONArray().put(
            JSONObject()
                .put("id", "c1").put("title", "T")
                .put("nodes", nodes)
                .put("children", JSONObject())
                .put("activeChild", JSONObject())
        ).toString()
    }

    private fun msgOf(id: String, text: String, withAtt: Boolean, fromUser: Boolean = false): JSONObject {
        val m = JSONObject().put("id", id).put("text", text).put("fromUser", fromUser)
        if (withAtt) m.put(
            "attachments",
            JSONArray().put(JSONObject().put("uri", "file:///g/x.png").put("name", "图.png"))
        )
        return m
    }

    /** 独占一行的成功态占位（[图片]/[附件 名]）且有附件：加载时删行清理，下次保存即持久化 */
    @Test
    fun `legacy standalone success placeholders cleaned on load`() {
        val json = treeJsonOf(
            msgOf("m1", "看图\n[图片]\n\n请查收", withAtt = true),
            msgOf("m2", "[附件 报告.xlsx]", withAtt = true),
            msgOf("m3", "[图片]\n[图片]", withAtt = true)
        )
        val nodes = ConversationStore(File.createTempFile("conv", ".json").apply { deleteOnExit() })
            .parse(json)[0].tree.nodes
        assertEquals("看图\n\n请查收", nodes["m1"]!!.text)
        assertEquals("", nodes["m2"]!!.text)
        assertEquals("", nodes["m3"]!!.text)
    }

    /** 失败态占位、行中同形文本、无附件消息：可能是真实正文/唯一状态信息，一律不动 */
    @Test
    fun `failure inline and attachment-less placeholders kept`() {
        val json = treeJsonOf(
            msgOf("m1", "[图片：保存失败]", withAtt = true),
            msgOf("m2", "正文中提到 [图片] 字样的讨论", withAtt = true),
            msgOf("m3", "[图片]", withAtt = false),
            msgOf("m4", "[附件：过大未保存]", withAtt = true)
        )
        val nodes = ConversationStore(File.createTempFile("conv", ".json").apply { deleteOnExit() })
            .parse(json)[0].tree.nodes
        assertEquals("[图片：保存失败]", nodes["m1"]!!.text)
        assertEquals("正文中提到 [图片] 字样的讨论", nodes["m2"]!!.text)
        assertEquals("[图片]", nodes["m3"]!!.text)
        assertEquals("[附件：过大未保存]", nodes["m4"]!!.text)
    }
}