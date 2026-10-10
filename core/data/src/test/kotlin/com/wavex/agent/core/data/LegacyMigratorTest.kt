package com.wavex.agent.core.data

import com.wavex.agent.core.domain.model.ConversationSnapshot
import com.wavex.agent.core.domain.model.StoredMessage
import com.wavex.agent.core.domain.model.TREE_ROOT
import com.wavex.agent.core.domain.model.TreeData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 旧 conversations.json → 逐会话 JSONL 迁移钉子（spec §7.1）+ SessionCodec.initialLines 语义钉：
 * 逐会话文件、倒序赋值保序（文件名降序 = 旧列表顺序）、.migrated 原子改名、失败不动 json、
 * 重跑幂等、已写文件在失败路径保留；initialLines：header → messages（entry id = 消息 id）→
 * active（仅补非折叠默认）→ session_info，round-trip 树逐字段等价。
 */
class LegacyMigratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val NOW = "2026-10-08T10:00:00.000Z"

    private fun sessionsDir(): File = tmp.newFolder("sessions")
    private fun jsonFile(): File = File(tmp.root, "conversations.json")

    private fun migrate(
        f: File,
        store: SessionStore,
        epoch: Long = 1760000000000L,
        now: () -> String = { NOW }
    ): Boolean = LegacyMigrator.migrateIfNeeded(f, store, epoch, now)

    /** 旧格式树会话（旧 conversations.json 分叉树格式）：root→m1→{m2,m3}，活跃分支 m2 */
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

    /** 旧格式平铺会话：messages 线性链 a→b→c */
    private fun flatConv(id: String, title: String): JSONObject =
        JSONObject()
            .put("id", id).put("title", title)
            .put("messages", JSONArray()
                .put(JSONObject().put("id", "a").put("text", "第一句").put("fromUser", true))
                .put(JSONObject().put("id", "b").put("text", "第二句").put("fromUser", false))
                .put(JSONObject().put("id", "c").put("text", "第三句").put("fromUser", true)))

    private fun writeJson(f: File, vararg convs: JSONObject) {
        val arr = JSONArray()
        convs.forEach { arr.put(it) }
        f.writeText(arr.toString())
    }

    private fun jsonlFiles(dir: File): List<File> =
        dir.listFiles()?.filter { it.name.endsWith(".jsonl") }.orEmpty().sortedByDescending { it.name }

    /** 与 sampleTreeJson 同树形的内存快照（initialLines 单测用） */
    private fun treeSnapshot(): ConversationSnapshot = ConversationSnapshot(
        id = "c1", title = "测试对话", titleUserDefined = true, titleUserCount = 3,
        tree = TreeData(
            nodes = linkedMapOf(
                "m1" to StoredMessage(id = "m1", text = "你好", fromUser = true,
                    attachments = listOf("content://media/1" to "photo.jpg")),
                "m2" to StoredMessage(id = "m2", text = "回复A", fromUser = false, reasoning = "想了想"),
                "m3" to StoredMessage(id = "m3", text = "回复B", fromUser = false),
            ),
            children = mapOf(TREE_ROOT to listOf("m1"), "m1" to listOf("m2", "m3")),
            activeChild = mapOf(TREE_ROOT to "m1", "m1" to "m2"),
        ),
    )

    // ---------- LegacyConversationJson.parse：旧格式直接解析钉 ----------

    @Test
    fun `LegacyConversationJson parse 树格式全字段还原`() {
        val raw = JSONArray().put(treeConv("c1", "测试对话")).toString()
        val snaps = LegacyConversationJson.parse(raw)
        assertEquals(1, snaps.size)
        val snap = snaps[0]
        assertEquals("c1", snap.id)
        assertEquals("测试对话", snap.title)
        assertTrue(snap.titleUserDefined)
        assertEquals(3, snap.titleUserCount)
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
    }

    @Test
    fun `LegacyConversationJson parse 平铺线性链还原`() {
        val raw = JSONArray().put(flatConv("old1", "旧版对话")).toString()
        val tree = LegacyConversationJson.parse(raw)[0].tree
        assertEquals(setOf("a", "b", "c"), tree.nodes.keys)
        // TREE_ROOT 单链：root→a→b→c，activeChild 逐级指向
        assertEquals(listOf("a"), tree.children[TREE_ROOT])
        assertEquals(listOf("b"), tree.children["a"])
        assertEquals(listOf("c"), tree.children["b"])
        assertEquals("a", tree.activeChild[TREE_ROOT])
        assertEquals("b", tree.activeChild["a"])
        assertEquals("c", tree.activeChild["b"])
        assertEquals("第三句", tree.nodes["c"]!!.text)
    }

    @Test
    fun `LegacyConversationJson parse 空 messages 得空树`() {
        val raw = JSONArray().put(
            JSONObject().put("id", "empty").put("title", "t").put("messages", JSONArray())
        ).toString()
        val tree = LegacyConversationJson.parse(raw)[0].tree
        assertTrue(tree.nodes.isEmpty())
        assertTrue(tree.children.isEmpty())
        assertTrue(tree.activeChild.isEmpty())
    }

    @Test
    fun `LegacyConversationJson parse 无标题元字段用默认值`() {
        // flatConv 不含 titleUserDefined/titleUserCount 字段
        val snap = LegacyConversationJson.parse(
            JSONArray().put(flatConv("old1", "旧版对话")).toString()
        )[0]
        assertFalse(snap.titleUserDefined)
        assertEquals(0, snap.titleUserCount)
    }

    // ---------- 迁移（spec §7.1） ----------

    @Test
    fun `迁移产出逐会话文件且顺序保序`() {
        val f = jsonFile()
        // 会话 A（树格式）在前、B（平铺格式）在后：迁移后文件名降序首个仍是 A
        writeJson(f, treeConv("c1", "测试对话"), flatConv("old1", "旧版对话"))
        val dir = sessionsDir()
        val store = SessionStore(dir)

        assertTrue(migrate(f, store))

        // 文件名 <迁移时刻+1000-序号>_<uuid8>：倒序赋值，列表首位 epoch 最大
        val files = jsonlFiles(dir)
        assertEquals(2, files.size)
        assertTrue(files.all { it.name.matches(Regex("\\d+_[0-9a-f]{8}\\.jsonl")) })
        assertEquals(
            listOf(1760000001000L, 1760000000999L),
            files.map { it.name.substringBefore('_').toLong() }
        )

        val parsed = store.loadAll()
        assertEquals(listOf("c1", "old1"), parsed.map { it.id })

        // 会话 A：title 三元组 / nodes 数 / 树形状 / 附件 / reasoning 与源一致；
        // 非默认活跃分支（m2，非文件序最后的 m3）经 active 行保住
        val a = parsed[0]
        assertEquals("测试对话", a.title)
        assertTrue(a.titleUserDefined)
        assertEquals(3, a.titleUserCount)
        assertEquals(3, a.tree.nodes.size)
        assertEquals(listOf("m1"), a.tree.children[TREE_ROOT])
        assertEquals(listOf("m2", "m3"), a.tree.children["m1"])
        assertEquals("m1", a.tree.activeChild[TREE_ROOT])
        assertEquals("m2", a.tree.activeChild["m1"])
        assertEquals(listOf("content://media/1" to "photo.jpg"), a.tree.nodes["m1"]!!.attachments)
        assertEquals("想了想", a.tree.nodes["m2"]!!.reasoning)

        // 会话 B（平铺）：linearToTree 线性链逐级保留
        val b = parsed[1]
        assertEquals("旧版对话", b.title)
        assertEquals(setOf("a", "b", "c"), b.tree.nodes.keys)
        assertEquals(listOf("a"), b.tree.children[TREE_ROOT])
        assertEquals(listOf("b"), b.tree.children["a"])
        assertEquals(listOf("c"), b.tree.children["b"])
        assertEquals("a", b.tree.activeChild[TREE_ROOT])
        assertEquals("b", b.tree.activeChild["a"])
        assertEquals("c", b.tree.activeChild["b"])
    }

    @Test
    fun `迁移后 json 改名为 migrated`() {
        val f = jsonFile()
        writeJson(f, treeConv("c1", "测试对话"))
        val raw = f.readText()

        assertTrue(migrate(f, SessionStore(sessionsDir())))

        assertFalse(f.exists())   // 原文件不存在
        val migrated = File(tmp.root, "conversations.json.migrated")
        assertTrue(migrated.exists())
        assertEquals(raw, migrated.readText())   // 内容不变（手工救回通道）
    }

    @Test
    fun `解析空或文件缺失返回 false 且不动文件`() {
        val dir = sessionsDir()
        val store = SessionStore(dir)

        // 文件缺失
        assertFalse(migrate(jsonFile(), store))

        // 损坏 json（parse 静默空列表）
        val bad = jsonFile()
        bad.writeText("{ 这不是合法 JSON !!!")
        assertFalse(migrate(bad, store))
        assertEquals("{ 这不是合法 JSON !!!", bad.readText())

        // 空数组（旧格式零会话）同样视为解析空
        bad.writeText("[]")
        assertFalse(migrate(bad, store))
        assertEquals("[]", bad.readText())

        assertTrue(jsonlFiles(dir).isEmpty())
        assertFalse(File(tmp.root, "conversations.json.migrated").exists())
    }

    @Test
    fun `重跑幂等：json 已改名则跳过`() {
        val f = jsonFile()
        writeJson(f, treeConv("c1", "测试对话"))
        val dir = sessionsDir()
        val store = SessionStore(dir)
        assertTrue(migrate(f, store))
        val files = jsonlFiles(dir).map { it.name to it.readText() }

        // jsonFile 已改名不存在 → 二次调用直接 false，已迁移文件原样
        assertFalse(migrate(f, store))
        assertEquals(files, jsonlFiles(dir).map { it.name to it.readText() })
        assertTrue(File(tmp.root, "conversations.json.migrated").exists())
    }

    @Test
    fun `部分写失败不产生 migrated`() {
        // sessions 目录路径被同名普通文件占据 → 建目录失败（Windows 上只读目录拦不住建文件，
        // 用「路径被文件占用」这一跨平台等价触发方式）→ 首个文件都写不出
        val blocker = tmp.newFile("sessions")
        val f = jsonFile()
        writeJson(f, treeConv("c1", "测试对话"), flatConv("old1", "旧版对话"))
        val raw = f.readText()

        assertFalse(migrate(f, SessionStore(blocker)))

        assertEquals(raw, f.readText())   // json 原名仍在、内容未动
        assertFalse(File(tmp.root, "conversations.json.migrated").exists())
    }

    @Test
    fun `中途写失败已写文件保留且不产生 migrated`() {
        val f = jsonFile()
        writeJson(f, treeConv("c1", "测试对话"), flatConv("old1", "旧版对话"))
        val raw = f.readText()
        val dir = sessionsDir()
        // initialLines 每会话取一次时间戳：第 2 个会话生成行时抛异常 → 迁移中止
        var calls = 0
        val now: () -> String = { if (++calls <= 1) NOW else error("boom") }

        assertFalse(LegacyMigrator.migrateIfNeeded(f, SessionStore(dir), 1760000000000L, now))

        assertEquals(raw, f.readText())   // json 不改名、内容未动
        assertFalse(File(tmp.root, "conversations.json.migrated").exists())
        // 已写的会话 A 文件保留且完整（下次启动重试时同 id 由 loadAll 按文件名较大者去重）
        val written = jsonlFiles(dir)
        assertEquals(1, written.size)
        val parsed = SessionStore(dir).loadAll()
        assertEquals(listOf("c1"), parsed.map { it.id })
        assertEquals(3, parsed[0].tree.nodes.size)
    }

    /**
     * Review Focus（plan line 27）：malformed legacy JSON 必须原样留在盘上（原名、原内容），
     * 绝不改名/删除 —— .migrated 是手工救回通道，只有全部会话写成功后 json 才改名。
     */
    @Test
    fun `failedLegacyParseLeavesSourceUnchanged`() {
        val f = jsonFile()
        val raw = "{ 这不是合法 JSON !!!"
        f.writeText(raw)
        val dir = sessionsDir()
        val store = SessionStore(dir)

        assertFalse(migrate(f, store))

        // 源文件原样保留：名字没变、内容没动、没有 .migrated 副本、一个 jsonl 都没写
        assertTrue(f.exists())
        assertEquals(raw, f.readText())
        assertFalse(File(tmp.root, "conversations.json.migrated").exists())
        assertTrue(jsonlFiles(dir).isEmpty())
    }

    // ---------- SessionCodec.initialLines 语义 ----------

    @Test
    fun `initialLines 行序与 active 补发规则`() {
        val snap = treeSnapshot()
        var calls = 0
        val lines = SessionCodec.initialLines(snap, now = { ++calls; NOW })

        // 迁移时刻取一次，全行共用
        assertEquals(1, calls)
        // 行序：header → messages → active → session_info；恰一行 active（root 的活跃=折叠默认不补，
        // m1 的活跃 m2 ≠ 文件序最后的 m3 需补）
        assertEquals(
            listOf("session", "message", "message", "message", "active", "session_info"),
            lines.map { JSONObject(it).getString("type") }
        )
        // entry id = 消息 id 本身（parse 折叠以行 id 为节点 id，spec §4.3 upsert 同 id 键）
        assertEquals(listOf("m1", "m2", "m3"), lines.drop(1).take(3).map { JSONObject(it).getString("id") })
        assertTrue(JSONObject(lines[1]).isNull("parentId"))   // 根消息 parentId 原生 null
        assertEquals("m1", JSONObject(lines[2]).getString("parentId"))
        assertEquals("m1", JSONObject(lines[3]).getString("parentId"))
        assertEquals("m2", JSONObject(lines[4]).getString("targetId"))
        val info = JSONObject(lines[5])
        assertEquals("测试对话", info.getString("name"))
        assertTrue(info.getBoolean("userDefined"))
        assertEquals(3, info.getInt("userCount"))
        // 全行时间戳一致
        assertEquals(List(6) { NOW }, lines.map { JSONObject(it).optString("timestamp") })
    }

    @Test
    fun `initialLines round-trip 树逐字段等价`() {
        val snap = treeSnapshot()
        val lines = SessionCodec.initialLines(snap, now = { NOW })
        val parsed = SessionCodec.parse(lines.joinToString("\n"))!!
        assertEquals("c1", parsed.id)
        assertEquals("测试对话", parsed.title)
        assertTrue(parsed.titleUserDefined)
        assertEquals(3, parsed.titleUserCount)
        assertEquals(snap.tree, parsed.tree)   // nodes/children/activeChild 逐字段还原
    }

    @Test
    fun `initialLines round-trip 平铺线性链零 active 行`() {
        // linearToTree 的活跃链 = 每父唯一孩子 = 折叠默认 → 全程无需 active 行
        val snap = ConversationSnapshot(
            id = "old1", title = "旧版对话",
            tree = TreeData(
                nodes = linkedMapOf(
                    "a" to StoredMessage(id = "a", text = "第一句", fromUser = true),
                    "b" to StoredMessage(id = "b", text = "第二句", fromUser = false),
                    "c" to StoredMessage(id = "c", text = "第三句", fromUser = true),
                ),
                children = mapOf(TREE_ROOT to listOf("a"), "a" to listOf("b"), "b" to listOf("c")),
                activeChild = mapOf(TREE_ROOT to "a", "a" to "b", "b" to "c"),
            ),
        )
        val lines = SessionCodec.initialLines(snap, now = { NOW })
        assertEquals(
            listOf("session", "message", "message", "message", "session_info"),
            lines.map { JSONObject(it).getString("type") }
        )
        assertEquals(snap.tree, SessionCodec.parse(lines.joinToString("\n"))!!.tree)
    }

    @Test
    fun `initialLines idOverride 覆盖会话 id`() {
        val snap = ConversationSnapshot(
            id = "old1", title = "t",
            tree = TreeData(
                nodes = linkedMapOf("n1" to StoredMessage(id = "n1", text = "hi", fromUser = true)),
                children = mapOf(TREE_ROOT to listOf("n1")),
                activeChild = mapOf(TREE_ROOT to "n1"),
            ),
        )
        val lines = SessionCodec.initialLines(snap, idOverride = "fresh-id", now = { NOW })
        assertEquals("fresh-id", JSONObject(lines[0]).getString("id"))
        assertEquals("fresh-id", SessionCodec.parse(lines.joinToString("\n"))!!.id)
    }

    @Test
    fun `initialLines 跳过未父化孤儿节点`() {
        // 节点在 nodes 但不在任何 children 桶：旧 UI 不进任何路径（不可见），迁移同样不落行保持不可见
        val snap = ConversationSnapshot(
            id = "c1", title = "t",
            tree = TreeData(
                nodes = linkedMapOf(
                    "m1" to StoredMessage(id = "m1", text = "可见", fromUser = true),
                    "ghost" to StoredMessage(id = "ghost", text = "孤儿", fromUser = true),
                ),
                children = mapOf(TREE_ROOT to listOf("m1")),
                activeChild = emptyMap(),
            ),
        )
        val lines = SessionCodec.initialLines(snap, now = { NOW })
        assertEquals(
            listOf("session", "message", "session_info"),
            lines.map { JSONObject(it).getString("type") }
        )
        assertEquals("m1", JSONObject(lines[1]).getString("id"))
    }

    @Test
    fun `空树会话产出 header 加 session_info 两行`() {
        val snap = ConversationSnapshot(
            id = "c1", title = "空会话",
            tree = TreeData(emptyMap(), emptyMap(), emptyMap())
        )
        val lines = SessionCodec.initialLines(snap, now = { NOW })
        assertEquals(listOf("session", "session_info"), lines.map { JSONObject(it).getString("type") })
    }

    // ---------- 模型返回附件（assistant 消息带附件）的兼容回归钉 ----------

    /** 旧格式读取含 assistant 附件的 JSON：不崩、uri/name 原样读回（file:// 与 https:// 均可） */
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
        val snaps = LegacyConversationJson.parse(json)
        assertEquals(1, snaps.size)
        val nodes = snaps[0].tree.nodes
        assertEquals(2, nodes["m2"]!!.attachments.size)
        assertEquals("file:///data/user/0/x/files/generated/a.png" to "生成图片-1", nodes["m2"]!!.attachments[0])
        assertEquals("https://img.example/b.jpg" to "生成图片-2", nodes["m2"]!!.attachments[1])
        assertEquals("file:///data/user/0/x/files/generated/c.xlsx" to "报告.xlsx", nodes["m3"]!!.attachments[0])
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
        val nodes = LegacyConversationJson.parse(json)[0].tree.nodes
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
        val nodes = LegacyConversationJson.parse(json)[0].tree.nodes
        assertEquals("[图片：保存失败]", nodes["m1"]!!.text)
        assertEquals("正文中提到 [图片] 字样的讨论", nodes["m2"]!!.text)
        assertEquals("[图片]", nodes["m3"]!!.text)
        assertEquals("[附件：过大未保存]", nodes["m4"]!!.text)
    }
}
