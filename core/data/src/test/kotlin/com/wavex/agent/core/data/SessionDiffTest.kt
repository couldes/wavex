package com.wavex.agent.core.data

import com.wavex.agent.core.domain.model.AgentConversationData
import com.wavex.agent.core.domain.model.StoredMessage
import com.wavex.agent.core.domain.model.TREE_ROOT
import com.wavex.agent.core.domain.model.TreeData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionDiff 语义钉子（spec §5.2 diff 动作表 + §5.3 流式抑制）：
 * 每个用例除断言产出行外，凡涉及树结构的都做「header + 既有行 + 产出行」回灌
 * SessionCodec.parse 的往返校验 —— 保证 diff 产出的追加序列能被加载端无损还原
 * （含 active 行在 message 行之后的顺序约束）。
 */
class SessionDiffTest {

    private fun msg(id: String, text: String, fromUser: Boolean = true) =
        StoredMessage(id = id, text = text, fromUser = fromUser)

    /** 造 AgentConversationData 辅助 */
    private fun conv(
        title: String = "会话",
        titleUserDefined: Boolean = false,
        titleUserCount: Int = 0,
        nodes: List<StoredMessage>,
        children: Map<String, List<String>>,
        activeChild: Map<String, String> = emptyMap()
    ) = AgentConversationData(
        id = "c1", title = title, titleUserDefined = titleUserDefined,
        titleUserCount = titleUserCount,
        nodes = nodes.associateBy { it.id },
        children = children, activeChild = activeChild
    )

    private fun flushedOf(c: AgentConversationData): SessionDiff.Flushed =
        SessionDiff.flushedOf(c.title, c.titleUserDefined, c.titleUserCount, c.lastActiveAt,
            c.pinned, c.pinnedAt, c.archived, TreeData(c.nodes, c.children, c.activeChild))

    /** header + 各组行拼成文件后折叠解析（无损还原校验） */
    private fun parseFile(vararg groups: List<String>): SessionCodec.Parsed =
        SessionCodec.parse(
            (listOf(SessionCodec.buildSessionHeader("c1", "t0")) + groups.flatMap { it })
                .joinToString("\n")
        )!!

    private fun types(lines: List<String>) = lines.map { JSONObject(it).getString("type") }

    /** 沿 activeChild 从根走出活跃路径（与 SessionCodecTest 同款纯数据走法） */
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
    fun `prev 为空时新节点全部成行`() {
        val c = conv(
            nodes = listOf(msg("m1", "问"), msg("m2", "答", fromUser = false), msg("m1b", "答B", fromUser = false)),
            children = mapOf(TREE_ROOT to listOf("m1"), "m1" to listOf("m2", "m1b")),
            activeChild = mapOf(TREE_ROOT to "m1", "m1" to "m2"),
        )
        val a = SessionDiff.diff(null, c, null, { "T" })
        // 标题非空（折叠基线为空标题）→ 1 条 session_info；3 个节点全成 message 行；
        // prev 无 activeChild → 全量成 active 行；无 delete
        assertEquals(listOf("session_info", "message", "message", "message", "active", "active"), types(a.lines))
        val msgs = a.lines.map { JSONObject(it) }.filter { it.getString("type") == "message" }
        assertEquals(setOf("m1", "m2", "m1b"), msgs.map { it.getString("id") }.toSet())
        // parentId 从 conv.children 反查
        assertEquals("m1", msgs.first { it.getString("id") == "m2" }.getString("parentId"))
        assertEquals(JSONObject.NULL, msgs.first { it.getString("id") == "m1" }.get("parentId"))
        // 回灌：树无损还原 —— active 行矫正 fold 默认（文件序最后的 m1b 不抢活跃位）
        val p = parseFile(a.lines)
        assertEquals(c.nodes, p.tree.nodes)
        assertEquals(c.children, p.tree.children)
        assertEquals(c.activeChild, p.tree.activeChild)
        assertEquals(listOf("m1", "m2"), activePath(p))
        assertEquals("会话", p.title)
        // next 恒为当前状态
        assertEquals(flushedOf(c), a.next)
    }

    @Test
    fun `无变化产出空 lines`() {
        val c = conv(
            nodes = listOf(msg("m1", "问")),
            children = mapOf(TREE_ROOT to listOf("m1")),
            activeChild = mapOf(TREE_ROOT to "m1"),
        )
        val prev = flushedOf(c)
        val a = SessionDiff.diff(prev, c, null, { "T" })
        assertTrue(a.lines.isEmpty())
        assertEquals(prev, a.next)
        // 空会话（无节点、空标题）同理零行 —— 配合 appendLines 空草稿不落盘（spec §6.2）
        val empty = conv(title = "", nodes = emptyList(), children = emptyMap())
        assertTrue(SessionDiff.diff(null, empty, null, { "T" }).lines.isEmpty())
    }

    @Test
    fun `内容变化产生 upsert 行`() {
        val before = conv(
            nodes = listOf(msg("m1", "问"), msg("m2", "答v1", fromUser = false)),
            children = mapOf(TREE_ROOT to listOf("m1"), "m1" to listOf("m2")),
            activeChild = mapOf(TREE_ROOT to "m1", "m1" to "m2"),
        )
        val after = before.copy(nodes = mapOf(
            "m1" to msg("m1", "问"),
            "m2" to msg("m2", "答v2", fromUser = false),  // 同 id 内容指纹变化
        ))
        val a = SessionDiff.diff(flushedOf(before), after, null, { "T" })
        // 只有 m2 一条 upsert；标题/结构/活跃分支无变化 → 无其他行
        assertEquals(listOf("message"), types(a.lines))
        val o = JSONObject(a.lines[0])
        assertEquals("m2", o.getString("id"))
        assertEquals("m1", o.getString("parentId"))
        assertEquals("答v2", o.getJSONObject("message").getString("text"))
        // 回灌：upsert 整行覆盖内容，树形状不变
        val p = parseFile(
            listOf(
                SessionCodec.buildMessageEntry("m1", null, "t1", msg("m1", "问")),
                SessionCodec.buildMessageEntry("m2", "m1", "t2", msg("m2", "答v1", fromUser = false)),
            ),
            a.lines,
        )
        assertEquals("答v2", p.tree.nodes.getValue("m2").text)
        assertEquals(listOf("m2"), p.tree.children["m1"])
        assertEquals(flushedOf(after), a.next)
    }

    @Test
    fun `节点消失产生 delete 行`() {
        val before = conv(
            nodes = listOf(msg("m1", "问"), msg("m2", "答", fromUser = false)),
            children = mapOf(TREE_ROOT to listOf("m1"), "m1" to listOf("m2")),
            activeChild = mapOf(TREE_ROOT to "m1", "m1" to "m2"),
        )
        val after = conv(
            nodes = listOf(msg("m1", "问")),
            children = mapOf(TREE_ROOT to listOf("m1")),
            activeChild = mapOf(TREE_ROOT to "m1"),
        )
        val a = SessionDiff.diff(flushedOf(before), after, null, { "T" })
        // 叶子 m2 消失：active[root] 未变、active[m1] 键随节点一起消失 → 仅一条 delete
        assertEquals(listOf("delete"), types(a.lines))
        assertEquals("m2", JSONObject(a.lines[0]).getString("targetId"))
        // 回灌：叶子删除后活跃链切回（parse 的 delete 折叠对齐 AgentConversation.removeMessageAt）
        val p = parseFile(
            listOf(
                SessionCodec.buildMessageEntry("m1", null, "t1", msg("m1", "问")),
                SessionCodec.buildMessageEntry("m2", "m1", "t2", msg("m2", "答", fromUser = false)),
                SessionCodec.buildActive("t3", "m2"),
            ),
            a.lines,
        )
        assertEquals(setOf("m1"), p.tree.nodes.keys)
        assertEquals(listOf("m1"), p.tree.children[TREE_ROOT])
        assertEquals(listOf("m1"), activePath(p))
        assertEquals(flushedOf(after), a.next)
    }

    @Test
    fun `activeChild 变化产生 active 行`() {
        val before = conv(
            nodes = listOf(msg("m1", "问"), msg("m2", "答A", fromUser = false), msg("m3", "答B", fromUser = false)),
            children = mapOf(TREE_ROOT to listOf("m1"), "m1" to listOf("m2", "m3")),
            activeChild = mapOf(TREE_ROOT to "m1", "m1" to "m2"),
        )
        val after = before.copy(activeChild = mapOf(TREE_ROOT to "m1", "m1" to "m3"))
        val a = SessionDiff.diff(flushedOf(before), after, null, { "T" })
        // 仅切换活跃分支：一条 active，无 message/delete
        assertEquals(listOf("active"), types(a.lines))
        assertEquals("m3", JSONObject(a.lines[0]).getString("targetId"))
        // 回灌：活跃分支切到 m3（兄弟都留在树上）
        val p = parseFile(
            listOf(
                SessionCodec.buildMessageEntry("m1", null, "t1", msg("m1", "问")),
                SessionCodec.buildMessageEntry("m2", "m1", "t2", msg("m2", "答A", fromUser = false)),
                SessionCodec.buildMessageEntry("m3", "m1", "t3", msg("m3", "答B", fromUser = false)),
                SessionCodec.buildActive("t4", "m2"),
            ),
            a.lines,
        )
        assertEquals(listOf("m2", "m3"), p.tree.children["m1"])
        assertEquals(listOf("m1", "m3"), activePath(p))
        assertEquals(flushedOf(after), a.next)
    }

    @Test
    fun `标题变化产生 session_info 行`() {
        val before = conv(
            title = "旧标题",
            nodes = listOf(msg("m1", "问")),
            children = mapOf(TREE_ROOT to listOf("m1")),
            activeChild = mapOf(TREE_ROOT to "m1"),
        )
        val after = before.copy(title = "新名字", titleUserDefined = true, titleUserCount = 2)
        val a = SessionDiff.diff(flushedOf(before), after, null, { "T" })
        assertEquals(listOf("session_info"), types(a.lines))
        val o = JSONObject(a.lines[0])
        assertEquals("新名字", o.getString("name"))
        assertTrue(o.getBoolean("userDefined"))
        assertEquals(2, o.getInt("userCount"))
        assertEquals("T", o.getString("timestamp"))
        // 回灌：标题三元组被覆盖
        val p = parseFile(
            listOf(SessionCodec.buildMessageEntry("m1", null, "t1", msg("m1", "问"))),
            a.lines,
        )
        assertEquals("新名字", p.title)
        assertTrue(p.titleUserDefined)
        assertEquals(2, p.titleUserCount)
        assertEquals(flushedOf(after), a.next)
    }

    @Test
    fun `suppress id 的增改删全部跳过`() {
        val base = conv(
            nodes = listOf(msg("m1", "问")),
            children = mapOf(TREE_ROOT to listOf("m1")),
            activeChild = mapOf(TREE_ROOT to "m1"),
        )
        // 增：流式占位 s 是新节点（且因 appendMessage 成为活跃孩子）→ 不产生任何行（spec §5.3 半截回复不落盘）
        val withPlaceholder = conv(
            nodes = listOf(msg("m1", "问"), msg("s", "半截", fromUser = false)),
            children = mapOf(TREE_ROOT to listOf("m1", "s")),
            activeChild = mapOf(TREE_ROOT to "s"),
        )
        val a1 = SessionDiff.diff(flushedOf(base), withPlaceholder, "s", { "T" })
        assertTrue(a1.lines.isEmpty())
        // next 为当前落盘簿记：被抑制的占位不收录（未持久化）——收尾后占位仍被视作「新节点」，
        // 凭此一次性补落最终内容（spec §5.3）；其余字段恒为当前状态
        val expectedNext = flushedOf(withPlaceholder)
        assertEquals(expectedNext.copy(nodes = expectedNext.nodes - "s"), a1.next)

        // 改：占位内容增长 → 仍零行
        val grown = withPlaceholder.copy(nodes = mapOf(
            "m1" to msg("m1", "问"),
            "s" to msg("s", "半截回复", fromUser = false),
        ))
        val a2 = SessionDiff.diff(a1.next, grown, "s", { "T" })
        assertTrue(a2.lines.isEmpty())

        // 收尾（suppress 清除）→ 一次性补落最终内容 upsert；其余节点不受影响
        val a3 = SessionDiff.diff(a2.next, grown, null, { "T" })
        assertEquals(listOf("message"), types(a3.lines))
        assertEquals("s", JSONObject(a3.lines[0]).getString("id"))

        // 删：占位被取消移除 → suppress 期间不产 delete 行；其余变化（活跃孩子切回 m1）正常成行
        val cancelled = base
        val a4 = SessionDiff.diff(flushedOf(withPlaceholder), cancelled, "s", { "T" })
        assertEquals(listOf("active"), types(a4.lines))
        assertEquals("m1", JSONObject(a4.lines[0]).getString("targetId"))
        // suppress 清除后同一变化 → delete 行正常产出（占位此前已落盘的情形）
        val a5 = SessionDiff.diff(flushedOf(withPlaceholder), cancelled, null, { "T" })
        assertEquals(listOf("delete", "active"), types(a5.lines))
        assertEquals("s", JSONObject(a5.lines.first { JSONObject(it).getString("type") == "delete" }).getString("targetId"))
    }

    @Test
    fun `矫正 active 行 新挂节点未成活跃孩子时补一行`() {
        val before = conv(
            nodes = listOf(msg("m1", "问"), msg("m2", "答A", fromUser = false)),
            children = mapOf(TREE_ROOT to listOf("m1"), "m1" to listOf("m2")),
            activeChild = mapOf(TREE_ROOT to "m1", "m1" to "m2"),
        )
        // 流式中用户切回旧分支后收尾：新挂 m2b 落盘，但活跃孩子仍是 m2 ——
        // parse 折叠会把 m1 的默认活跃切到「文件序最后挂上的 m2b」，需补一条 active(m2) 矫正
        val after = conv(
            nodes = listOf(msg("m1", "问"), msg("m2", "答A", fromUser = false), msg("m2b", "答B", fromUser = false)),
            children = mapOf(TREE_ROOT to listOf("m1"), "m1" to listOf("m2", "m2b")),
            activeChild = mapOf(TREE_ROOT to "m1", "m1" to "m2"),
        )
        val a = SessionDiff.diff(flushedOf(before), after, null, { "T" })
        assertEquals(listOf("message", "active"), types(a.lines))
        val o1 = JSONObject(a.lines[0])
        assertEquals("m2b", o1.getString("id"))
        assertEquals("m1", o1.getString("parentId"))
        assertEquals("m2", JSONObject(a.lines[1]).getString("targetId"))
        // 回灌：若无矫正行，重载后活跃分支漂移到 m2b；补行后无损还原 before 的活跃路径
        val p = parseFile(
            listOf(
                SessionCodec.buildMessageEntry("m1", null, "t1", msg("m1", "问")),
                SessionCodec.buildMessageEntry("m2", "m1", "t2", msg("m2", "答A", fromUser = false)),
            ),
            a.lines,
        )
        assertEquals(setOf("m1", "m2", "m2b"), p.tree.nodes.keys)
        assertEquals(listOf("m2", "m2b"), p.tree.children["m1"])
        assertEquals(after.activeChild, p.tree.activeChild)
        assertEquals(listOf("m1", "m2"), activePath(p))
        assertEquals(flushedOf(after), a.next)
    }

    @Test
    fun `矫正 active 行 该父已发 active 行时不重复`() {
        val before = conv(
            nodes = listOf(msg("m1", "问"), msg("m2", "答A", fromUser = false), msg("m3", "答B", fromUser = false)),
            children = mapOf(TREE_ROOT to listOf("m1"), "m1" to listOf("m2", "m3")),
            activeChild = mapOf(TREE_ROOT to "m1", "m1" to "m2"),
        )
        // 新挂 m2b 的同一 diff 里活跃孩子又切到 m3：主循环 active 行已覆盖 m1，不补矫正行
        val after = conv(
            nodes = listOf(
                msg("m1", "问"), msg("m2", "答A", fromUser = false),
                msg("m3", "答B", fromUser = false), msg("m2b", "答C", fromUser = false),
            ),
            children = mapOf(TREE_ROOT to listOf("m1"), "m1" to listOf("m2", "m3", "m2b")),
            activeChild = mapOf(TREE_ROOT to "m1", "m1" to "m3"),
        )
        val a = SessionDiff.diff(flushedOf(before), after, null, { "T" })
        // 恰好一条 active（主循环的 m3），矫正行不得重复发
        assertEquals(listOf("message", "active"), types(a.lines))
        assertEquals("m2b", JSONObject(a.lines[0]).getString("id"))
        assertEquals("m3", JSONObject(a.lines[1]).getString("targetId"))
        // 回灌
        val p = parseFile(
            listOf(
                SessionCodec.buildMessageEntry("m1", null, "t1", msg("m1", "问")),
                SessionCodec.buildMessageEntry("m2", "m1", "t2", msg("m2", "答A", fromUser = false)),
                SessionCodec.buildMessageEntry("m3", "m1", "t3", msg("m3", "答B", fromUser = false)),
                SessionCodec.buildActive("t4", "m2"),
            ),
            a.lines,
        )
        assertEquals(listOf("m2", "m3", "m2b"), p.tree.children["m1"])
        assertEquals(after.activeChild, p.tree.activeChild)
        assertEquals(listOf("m1", "m3"), activePath(p))
        assertEquals(flushedOf(after), a.next)
    }

    @Test
    fun `矫正 active 行 目标为 suppress 占位时跳过`() {
        val before = conv(
            nodes = listOf(msg("m1", "问"), msg("m2", "答A", fromUser = false)),
            children = mapOf(TREE_ROOT to listOf("m1"), "m1" to listOf("m2")),
            activeChild = mapOf(TREE_ROOT to "m1", "m1" to "m2"),
        )
        // 新挂 m2b 未落盘、流式占位 s 为活跃孩子：矫正行目标为占位 id → 跳过
        // （active 行指向未落盘 id 必被 parse 丢弃，纯垃圾行）
        val after = conv(
            nodes = listOf(
                msg("m1", "问"), msg("m2", "答A", fromUser = false),
                msg("m2b", "答B", fromUser = false), msg("s", "半截", fromUser = false),
            ),
            children = mapOf(TREE_ROOT to listOf("m1"), "m1" to listOf("m2", "m2b", "s")),
            activeChild = mapOf(TREE_ROOT to "m1", "m1" to "s"),
        )
        val a = SessionDiff.diff(flushedOf(before), after, "s", { "T" })
        assertEquals(listOf("message"), types(a.lines))
        assertEquals("m2b", JSONObject(a.lines[0]).getString("id"))
        // 占位不进落盘簿记（与 suppress 测试同规约）
        val expectedNext = flushedOf(after)
        assertEquals(expectedNext.copy(nodes = expectedNext.nodes - "s"), a.next)
    }

    // ---- 活跃元数据：lastActiveAt 变化随 session_info 行落盘 ----

    @Test
    fun `lastActiveAt 变化补发 session_info 行且带 lastActive 字段`() {
        val base = conv(nodes = listOf(msg("m1", "问")), children = mapOf(TREE_ROOT to listOf("m1")))
        val prev = flushedOf(base).copy(lastActiveAt = 100L)
        val a = SessionDiff.diff(prev, base.copy(lastActiveAt = 200L), null, { "T" })
        val info = a.lines.map { JSONObject(it) }.filter { it.getString("type") == "session_info" }
        assertEquals(1, info.size)
        assertEquals(200L, info[0].getLong("lastActive"))
        assertEquals(200L, a.next.lastActiveAt)   // 基线推进携带新活跃时间
    }

    @Test
    fun `lastActiveAt 未变不发 session_info 行`() {
        val base = conv(nodes = listOf(msg("m1", "问")), children = mapOf(TREE_ROOT to listOf("m1")))
        val prev = flushedOf(base).copy(lastActiveAt = 100L)
        val a = SessionDiff.diff(prev, base.copy(lastActiveAt = 100L), null, { "T" })
        assertEquals(emptyList<String>(), types(a.lines))
    }

    @Test
    fun `lastActiveAt 产出行回灌 parse 可还原`() {
        val base = conv(nodes = listOf(msg("m1", "问")), children = mapOf(TREE_ROOT to listOf("m1")))
        val prev = flushedOf(base).copy(lastActiveAt = 100L)
        val a = SessionDiff.diff(prev, base.copy(lastActiveAt = 200L), null, { "T" })
        assertEquals(200L, parseFile(a.lines).lastActiveAt)
    }

    @Test
    fun `pinned 变化补发 session_info 行且带置顶字段`() {
        val base = conv(nodes = listOf(msg("m1", "问")), children = mapOf(TREE_ROOT to listOf("m1")))
        val prev = flushedOf(base).copy(lastActiveAt = 100L)
        val a = SessionDiff.diff(prev, base.copy(pinned = true, pinnedAt = 300L), null, { "T" })
        val info = a.lines.map { JSONObject(it) }.filter { it.getString("type") == "session_info" }
        assertEquals(1, info.size)
        assertTrue(info[0].getBoolean("pinned"))
        assertEquals(300L, info[0].getLong("pinnedAt"))
        assertEquals(300L, a.next.pinnedAt)   // 基线推进携带置顶状态
    }

    @Test
    fun `archived 变化补发 session_info 行且带归档字段`() {
        val base = conv(nodes = listOf(msg("m1", "问")), children = mapOf(TREE_ROOT to listOf("m1")))
        val prev = flushedOf(base).copy(lastActiveAt = 100L)
        val a = SessionDiff.diff(prev, base.copy(archived = true), null, { "T" })
        val info = a.lines.map { JSONObject(it) }.filter { it.getString("type") == "session_info" }
        assertEquals(1, info.size)
        assertTrue(info[0].getBoolean("archived"))
        assertTrue(a.next.archived)           // 基线推进携带归档状态
    }
}
