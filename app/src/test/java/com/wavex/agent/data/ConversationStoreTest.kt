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
}
