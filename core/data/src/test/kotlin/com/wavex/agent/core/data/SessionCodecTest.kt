package com.wavex.agent.core.data

import com.wavex.agent.core.domain.model.TREE_ROOT
import com.wavex.agent.core.domain.model.StoredMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionCodec 格式与语义钉子：五类行构造器（spec §4.2 行格式）+ 折叠解析 parse（spec §4.3）。
 * 每行一个 JSON 对象 —— session / message / session_info / active / delete。
 * 追加写入端（Task 3）依赖这里的字段名，加载端依赖折叠语义。
 */
class SessionCodecTest {

    /** 沿 activeChild 从根走出活跃路径（与 AgentConversation.rebuildFrom 同构的纯数据走法） */
    private fun activePath(p: SessionCodec.Parsed): List<String> {
        val ids = mutableListOf<String>()
        var parent = TREE_ROOT
        while (true) {
            val next = p.tree.activeChild[parent] ?: break
            ids += next
            parent = next
        }
        return ids
    }

    @Test
    fun `header 行含 type version id timestamp`() {
        val line = SessionCodec.buildSessionHeader("abc", "2026-10-08T10:00:00.000Z")
        val o = JSONObject(line)
        assertEquals("session", o.getString("type"))
        assertEquals(SessionCodec.FORMAT_VERSION, o.getInt("version"))
        assertEquals(1, o.getInt("version"))
        assertEquals("abc", o.getString("id"))
        assertEquals("2026-10-08T10:00:00.000Z", o.getString("timestamp"))
    }

    @Test
    fun `message 行含 parentId 与消息字段`() {
        val m = StoredMessage(id = "m1", text = "你好", fromUser = true,
            reasoning = "思考", attachments = listOf("content://x" to "a.png"))
        val o = JSONObject(SessionCodec.buildMessageEntry("e1", null, "t", m))
        assertEquals("message", o.getString("type"))
        assertEquals("e1", o.getString("id"))
        assertEquals("t", o.getString("timestamp"))
        assertTrue(o.isNull("parentId"))
        val msg = o.getJSONObject("message")
        assertEquals("你好", msg.getString("text"))
        assertTrue(msg.getBoolean("fromUser"))
        assertFalse(msg.getBoolean("isError"))
        assertEquals("思考", msg.getString("reasoning"))
        assertEquals("content://x", msg.getJSONArray("attachments").getJSONObject(0).getString("uri"))
        assertEquals("a.png", msg.getJSONArray("attachments").getJSONObject(0).getString("name"))
        // 非根消息：parentId 为具体父 id
        val child = JSONObject(SessionCodec.buildMessageEntry("e2", "e1", "t", m))
        assertEquals("e1", child.getString("parentId"))
    }

    @Test
    fun `message 行 reason 为空时不写 reasoning 字段`() {
        val m = StoredMessage(id = "m1", text = "你好", fromUser = true)
        val msg = JSONObject(SessionCodec.buildMessageEntry("e1", null, "t", m)).getJSONObject("message")
        assertFalse(msg.has("reasoning"))
        // 空 attachments 同理不写字段（省行宽）
        assertFalse(msg.has("attachments"))
    }

    @Test
    fun `session_info active delete 行字段正确`() {
        val info = JSONObject(SessionCodec.buildSessionInfo("t", "新会话", true, 3))
        assertEquals("session_info", info.getString("type"))
        assertEquals("t", info.getString("timestamp"))
        assertEquals("新会话", info.getString("name"))
        assertTrue(info.getBoolean("userDefined"))
        assertEquals(3, info.getInt("userCount"))

        val active = JSONObject(SessionCodec.buildActive("t", "m9"))
        assertEquals("active", active.getString("type"))
        assertEquals("t", active.getString("timestamp"))
        assertEquals("m9", active.getString("targetId"))

        val del = JSONObject(SessionCodec.buildDelete("t", "m9"))
        assertEquals("delete", del.getString("type"))
        assertEquals("t", del.getString("timestamp"))
        assertEquals("m9", del.getString("targetId"))
    }

    // ---- parse：折叠解析（spec §4.3） ----

    @Test
    fun `线性会话折叠`() {
        val raw = listOf(
            SessionCodec.buildSessionHeader("s1", "t0"),
            SessionCodec.buildMessageEntry("m1", null, "t1",
                StoredMessage(id = "m1", text = "你好", fromUser = true)),
            SessionCodec.buildMessageEntry("m2", "m1", "t2",
                StoredMessage(id = "m2", text = "回复", fromUser = false)),
        ).joinToString("\n")
        val p = SessionCodec.parse(raw)!!
        assertEquals("s1", p.id)
        // 沿 activeChild 走出 [m1, m2]（无 active 行 → 活跃孩子 = 文件序最后一个孩子）
        assertEquals(listOf("m1", "m2"), activePath(p))
        assertEquals(listOf("m1"), p.tree.children[TREE_ROOT])
        assertEquals(listOf("m2"), p.tree.children["m1"])
        assertEquals("你好", p.tree.nodes.getValue("m1").text)
        assertFalse(p.tree.nodes.getValue("m2").fromUser)
        // toSnapshot 透传（无 session_info 行 → 标题三元组取默认值）
        val snap = p.toSnapshot()
        assertEquals("s1", snap.id)
        assertEquals("", snap.title)
        assertFalse(snap.titleUserDefined)
        assertEquals(0, snap.titleUserCount)
        assertEquals(p.tree, snap.tree)
    }

    @Test
    fun `upsert 同 id 整行覆盖且可改签 parentId`() {
        val raw = listOf(
            SessionCodec.buildSessionHeader("s1", "t0"),
            SessionCodec.buildMessageEntry("m1", null, "t1",
                StoredMessage(id = "m1", text = "根", fromUser = true)),
            SessionCodec.buildMessageEntry("m2", "m1", "t2",
                StoredMessage(id = "m2", text = "v1", fromUser = false)),
            // 同 id 再落一行：整行覆盖 + parentId 改签为根（孤儿改签场景）
            SessionCodec.buildMessageEntry("m2", null, "t3",
                StoredMessage(id = "m2", text = "v2", fromUser = true, reasoning = "更新")),
        ).joinToString("\n")
        val p = SessionCodec.parse(raw)!!
        // nodes[m2] 为新内容
        assertEquals("v2", p.tree.nodes.getValue("m2").text)
        assertTrue(p.tree.nodes.getValue("m2").fromUser)
        assertEquals("更新", p.tree.nodes.getValue("m2").reasoning)
        // children 更新：m1 不再有孩子，m2 挂到根末尾
        assertTrue(p.tree.children["m1"].isNullOrEmpty())
        assertEquals(listOf("m1", "m2"), p.tree.children[TREE_ROOT])
        // 改签后 m2 是根的文件序最后一个孩子 → 默认活跃
        assertEquals("m2", p.tree.activeChild[TREE_ROOT])
    }

    @Test
    fun `delete 改签孤儿并重接活跃链`() {
        // 场景一（brief）：root→a→b（b 为默认活跃）→ delete a → b 改签根，活跃链接上 b
        val raw1 = listOf(
            SessionCodec.buildSessionHeader("s1", "t0"),
            SessionCodec.buildMessageEntry("a", null, "t1",
                StoredMessage(id = "a", text = "a", fromUser = true)),
            SessionCodec.buildMessageEntry("b", "a", "t2",
                StoredMessage(id = "b", text = "b", fromUser = false)),
            SessionCodec.buildDelete("t3", "a"),
        ).joinToString("\n")
        val p1 = SessionCodec.parse(raw1)!!
        assertNull(p1.tree.nodes["a"])
        assertEquals(listOf("b"), p1.tree.children[TREE_ROOT])
        assertEquals("b", p1.tree.activeChild[TREE_ROOT])
        assertEquals(listOf("b"), activePath(p1))

        // 场景二：root→x→(b2, c2)，active→b2 → delete x：孤儿保持相对顺序改签根，
        // 活跃链取被删节点原本的活跃孩子 b2（对齐 AgentConversation.removeMessageById），而非最后一个孤儿
        val raw2 = listOf(
            SessionCodec.buildSessionHeader("s2", "t0"),
            SessionCodec.buildMessageEntry("x", null, "t1",
                StoredMessage(id = "x", text = "x", fromUser = true)),
            SessionCodec.buildMessageEntry("b2", "x", "t2",
                StoredMessage(id = "b2", text = "b2", fromUser = true)),
            SessionCodec.buildMessageEntry("c2", "x", "t3",
                StoredMessage(id = "c2", text = "c2", fromUser = false)),
            SessionCodec.buildActive("t4", "b2"),
            SessionCodec.buildDelete("t5", "x"),
        ).joinToString("\n")
        val p2 = SessionCodec.parse(raw2)!!
        assertNull(p2.tree.nodes["x"])
        assertEquals(listOf("b2", "c2"), p2.tree.children[TREE_ROOT])
        assertEquals("b2", p2.tree.activeChild[TREE_ROOT])
        assertEquals(listOf("b2"), activePath(p2))
    }

    @Test
    fun `active 行切换活跃分支`() {
        val raw = listOf(
            SessionCodec.buildSessionHeader("s1", "t0"),
            SessionCodec.buildMessageEntry("m1", null, "t1",
                StoredMessage(id = "m1", text = "问", fromUser = true)),
            SessionCodec.buildMessageEntry("m2", "m1", "t2",
                StoredMessage(id = "m2", text = "答A", fromUser = false)),
            SessionCodec.buildMessageEntry("m3", "m1", "t3",
                StoredMessage(id = "m3", text = "答B", fromUser = false)),
            SessionCodec.buildActive("t4", "m2"),  // 切回第一个版本
            SessionCodec.buildActive("t5", "m3"),  // 再切到 m3：最后一次 active 生效
        ).joinToString("\n")
        val p = SessionCodec.parse(raw)!!
        // 兄弟都留在树上，路径按 activeChild 走
        assertEquals(listOf("m2", "m3"), p.tree.children["m1"])
        assertEquals(listOf("m1", "m2"), activePath(SessionCodec.parse(raw.lines().take(5).joinToString("\n"))!!))
        assertEquals(listOf("m1", "m3"), activePath(p))
        assertEquals("m3", p.tree.activeChild["m1"])
    }

    @Test
    fun `残行与未知 type 跳过`() {
        val raw = listOf(
            SessionCodec.buildSessionHeader("s1", "t0"),
            SessionCodec.buildMessageEntry("m1", null, "t1",
                StoredMessage(id = "m1", text = "你好", fromUser = true)),
            "{\"type\":\"mess",  // 进程被杀写一半的残行（spec §4.4）
            "{\"type\":\"xx\"}",  // 未知 type
        ).joinToString("\n")
        val p = SessionCodec.parse(raw)!!
        assertEquals("s1", p.id)
        assertEquals(1, p.tree.nodes.size)
        assertEquals(listOf("m1"), activePath(p))
    }

    @Test
    fun `无 header 返回 null`() {
        // 只有 message 行（无 header）
        val noHeader = SessionCodec.buildMessageEntry("m1", null, "t1",
            StoredMessage(id = "m1", text = "你好", fromUser = true))
        assertNull(SessionCodec.parse(noHeader))
    }

    @Test
    fun `header 空树解析为零节点会话`() {
        // 只有 header（零节点）：改名空草稿 / 消息删空的会话仍可加载（spec §6.2，fix round 1）
        val p = SessionCodec.parse(SessionCodec.buildSessionHeader("s1", "t0"))!!
        assertEquals("s1", p.id)
        assertEquals("", p.title)   // 无 session_info 行：标题折叠基线为空串
        assertTrue(p.titleUserDefined.not())
        assertTrue(p.tree.nodes.isEmpty())
        assertTrue(p.tree.children.isEmpty())
        assertTrue(p.tree.activeChild.isEmpty())
        assertEquals("s1", p.toSnapshot().id)
    }

    @Test
    fun `session_info 行覆盖标题三元组`() {
        val raw = listOf(
            SessionCodec.buildSessionHeader("s1", "t0"),
            SessionCodec.buildMessageEntry("m1", null, "t1",
                StoredMessage(id = "m1", text = "你好", fromUser = true)),
            SessionCodec.buildSessionInfo("t2", "自动标题", false, 1),
            SessionCodec.buildSessionInfo("t3", "手动改名", true, 2),  // 后行覆盖
        ).joinToString("\n")
        val p = SessionCodec.parse(raw)!!
        assertEquals("手动改名", p.title)
        assertTrue(p.titleUserDefined)
        assertEquals(2, p.titleUserCount)
        assertEquals("手动改名", p.toSnapshot().title)
        assertEquals("s1", p.toSnapshot().id)
    }

    @Test
    fun `session_info 行携带活跃时间且旧文件缺省为零`() {
        // 新格式：session_info 带 lastActive → 折叠透传（恢复排序的持久化依据）
        val fresh = listOf(
            SessionCodec.buildSessionHeader("s1", "t0"),
            SessionCodec.buildSessionInfo("t1", "会话", false, 0, 1728800000000),
        ).joinToString("\n")
        val p = SessionCodec.parse(fresh)!!
        assertEquals(1728800000000L, p.lastActiveAt)
        assertEquals(1728800000000L, p.toSnapshot().lastActiveAt)

        // 旧文件无该字段：缺省 0（加载端保持文件序稳定排序）
        val legacy = listOf(
            SessionCodec.buildSessionHeader("s1", "t0"),
            SessionCodec.buildSessionInfo("t1", "会话", false, 0),
        ).joinToString("\n")
        assertEquals(0L, SessionCodec.parse(legacy)!!.lastActiveAt)

        // 0 值不写字段（省行宽；旧版本读到 session_info 行照常折叠）
        assertFalse(JSONObject(SessionCodec.buildSessionInfo("t1", "会话", false, 0, 0)).has("lastActive"))
        assertTrue(JSONObject(SessionCodec.buildSessionInfo("t1", "会话", false, 0, 5)).has("lastActive"))
    }

    @Test
    fun `initialLines 与 parse 往返保留活跃时间`() {
        val snap = com.wavex.agent.core.domain.model.ConversationSnapshot(
            id = "s1", title = "会话", titleUserDefined = true, titleUserCount = 1,
            lastActiveAt = 1728800000000,
            tree = com.wavex.agent.core.domain.model.TreeData(
                nodes = mapOf("m1" to StoredMessage(id = "m1", text = "你好", fromUser = true)),
                children = mapOf(TREE_ROOT to listOf("m1")),
                activeChild = mapOf(TREE_ROOT to "m1"),
            ),
        )
        val p = SessionCodec.parse(SessionCodec.initialLines(snap) { "t1" }.joinToString("\n"))!!
        assertEquals(1728800000000L, p.lastActiveAt)
        assertEquals("会话", p.title)
        assertEquals(listOf("m1"), activePath(p))
    }

    @Test
    fun `session_info 行携带置顶元数据且旧文件缺省未置顶`() {
        // 新格式：session_info 带 pinned/pinnedAt → 折叠透传（置顶区恢复的持久化依据）
        val fresh = listOf(
            SessionCodec.buildSessionHeader("s1", "t0"),
            SessionCodec.buildSessionInfo("t1", "会话", false, 0, 100, true, 200),
        ).joinToString("\n")
        val p = SessionCodec.parse(fresh)!!
        assertTrue(p.pinned)
        assertEquals(200L, p.pinnedAt)
        val snap = p.toSnapshot()
        assertTrue(snap.pinned)
        assertEquals(200L, snap.pinnedAt)

        // 旧文件无该字段：缺省未置顶
        val legacy = listOf(
            SessionCodec.buildSessionHeader("s1", "t0"),
            SessionCodec.buildSessionInfo("t1", "会话", false, 0),
        ).joinToString("\n")
        val lp = SessionCodec.parse(legacy)!!
        assertFalse(lp.pinned)
        assertEquals(0L, lp.pinnedAt)

        // 未置顶不写字段（省行宽）
        val raw = JSONObject(SessionCodec.buildSessionInfo("t1", "会话", false, 0, 5, false, 0))
        assertFalse(raw.has("pinned"))
        assertFalse(raw.has("pinnedAt"))
    }

    @Test
    fun `session_info 行携带归档元数据且旧文件缺省未归档`() {
        // 新格式：session_info 带 archived → 折叠透传（归档区恢复的持久化依据）
        val fresh = listOf(
            SessionCodec.buildSessionHeader("s1", "t0"),
            SessionCodec.buildSessionInfo("t1", "会话", false, 0, 100, false, 0, true),
        ).joinToString("\n")
        val p = SessionCodec.parse(fresh)!!
        assertTrue(p.archived)
        val snap = p.toSnapshot()
        assertTrue(snap.archived)

        // 旧文件无该字段：缺省未归档
        val legacy = listOf(
            SessionCodec.buildSessionHeader("s1", "t0"),
            SessionCodec.buildSessionInfo("t1", "会话", false, 0),
        ).joinToString("\n")
        assertFalse(SessionCodec.parse(legacy)!!.archived)

        // 未归档不写字段（省行宽）
        val raw = JSONObject(SessionCodec.buildSessionInfo("t1", "会话", false, 0, 5, false, 0, false))
        assertFalse(raw.has("archived"))
        val marked = JSONObject(SessionCodec.buildSessionInfo("t1", "会话", false, 0, 5, false, 0, true))
        assertTrue(marked.has("archived"))
    }
}
