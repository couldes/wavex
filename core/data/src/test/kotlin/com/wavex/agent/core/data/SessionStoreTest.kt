package com.wavex.agent.core.data

import com.wavex.agent.core.domain.model.StoredMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SessionStore 文件 IO 壳钉子（spec §4.1 文件布局）：逐会话 <epochMs>_<uuid8>.jsonl，
 * 自动建 header / 空草稿不落盘 / 残行补 \n / 文件名降序 / 删除连 .tmp / 搬移切目录。
 * 全部断言真实文件状态（磁盘字节），不只看内存索引；临时目录由 TemporaryFolder 统一清理。
 */
class SessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 每个用例内多次调用各得一个独立目录（用例结束后 TemporaryFolder 统一递归删除） */
    private fun tempDir(): File = tmp.newFolder()

    private fun msgLine(entryId: String, parentId: String? = null): String =
        SessionCodec.buildMessageEntry(
            entryId, parentId, "2026-10-08T10:00:00.000Z",
            StoredMessage(id = entryId, text = "text-$entryId", fromUser = true)
        )

    /** 手工落一个 jsonl：lines 以 \n 连接，末尾是否带 \n 由 trailingNewline 控制 */
    private fun writeFile(dir: File, name: String, sessionId: String, entries: List<String>, trailingNewline: Boolean = true) {
        val lines = mutableListOf(SessionCodec.buildSessionHeader(sessionId, "2026-10-08T10:00:00.000Z"))
        lines += entries
        val text = lines.joinToString("\n") + if (trailingNewline) "\n" else ""
        File(dir, name).writeText(text)
    }

    private fun jsonlFiles(dir: File): List<File> =
        dir.listFiles()?.filter { it.name.endsWith(".jsonl") }.orEmpty()

    @Test
    fun `appendLines 自动建 header 并追加`() {
        val dir = tempDir()
        val store = SessionStore(dir)
        store.appendLines("sid", listOf(msgLine("e1"), msgLine("e2", "e1"))) { "2026-10-08T10:00:00.000Z" }

        // 真实落盘：恰好一个 jsonl，文件名匹配 <epochMs>_<uuid8>.jsonl
        val files = jsonlFiles(dir)
        assertEquals(1, files.size)
        assertTrue(files[0].name.matches(Regex("\\d+_[0-9a-f]{8}\\.jsonl")))
        // 首行是 session header，id=sessionId
        val header = JSONObject(files[0].readLines().first())
        assertEquals("session", header.getString("type"))
        assertEquals("sid", header.getString("id"))

        val parsed = store.loadAll()
        assertEquals(1, parsed.size)
        assertEquals("sid", parsed[0].id)
        assertEquals(setOf("e1", "e2"), parsed[0].tree.nodes.keys)
        // 尾字节为 \n，e2 行独立成行可解析
        val lastLine = files[0].readText().trimEnd().lineSequence().last()
        assertEquals("e2", JSONObject(lastLine).getString("id"))
    }

    @Test
    fun `空 lines 且无文件不建文件`() {
        val dir = tempDir()
        val store = SessionStore(dir)
        store.appendLines("sid", emptyList()) { "2026-10-08T10:00:00.000Z" }
        assertTrue(jsonlFiles(dir).isEmpty())
    }

    @Test
    fun `残行后追加先补换行`() {
        val dir = tempDir()
        val store = SessionStore(dir)
        // 手工造 torn 文件：header + 完整消息 + 半行残行（无 \n 结尾）
        val torn = File(dir, "1000_torn.jsonl")
        torn.writeText(
            SessionCodec.buildSessionHeader("sid", "2026-10-08T10:00:00.000Z") + "\n" +
                msgLine("e1") + "\n" +
                "{\"type\":\"mess"
        )
        store.loadAll()  // 残行被折叠跳过，文件进索引
        store.appendLines("sid", listOf(msgLine("e2"))) { "2026-10-08T10:00:00.000Z" }

        // e2 未被残行吞掉：折叠出两个节点
        val parsed = store.loadAll()
        assertEquals(1, parsed.size)
        assertEquals(setOf("e1", "e2"), parsed[0].tree.nodes.keys)
        // 尾字节确为 \n，且 e2 是独立完整行
        val text = torn.readText()
        assertTrue(text.endsWith("\n"))
        assertEquals("e2", JSONObject(text.trimEnd().lineSequence().last()).getString("id"))
    }

    @Test
    fun `文件名降序与同前缀 uuid 排序`() {
        val dir = tempDir()
        val store = SessionStore(dir)
        // 同 epoch 前缀 1000_：全名降序 zz > aa；跨前缀：1000 > 0900
        writeFile(dir, "1000_zz.jsonl", "zz", listOf(msgLine("z1")))
        writeFile(dir, "1000_aa.jsonl", "aa", listOf(msgLine("a1")))
        writeFile(dir, "0900_mm.jsonl", "mm", listOf(msgLine("m1")))
        assertEquals(listOf("zz", "aa", "mm"), store.loadAll().map { it.id })
    }

    @Test
    fun `deleteSession 删文件与索引`() {
        val dir = tempDir()
        val store = SessionStore(dir)
        store.appendLines("sid", listOf(msgLine("e1"))) { "2026-10-08T10:00:00.000Z" }
        val f = jsonlFiles(dir).single()
        // 同名 .tmp（上次原子写残留）一并删除
        File(dir, f.name + ".tmp").writeText("stale")

        // 返回值：主文件与 .tmp 均删掉 → true；未加载过的 id（含重复删除）无文件可删 → false
        assertTrue(store.deleteSession("sid"))
        assertFalse(store.deleteSession("ghost"))  // 不存在的 id：no-op 不抛

        assertFalse(f.exists())
        assertFalse(File(dir, f.name + ".tmp").exists())
        assertTrue(store.loadAll().isEmpty())
    }

    @Test
    fun `moveAllTo 搬移并切换目录`() {
        val old = tempDir()
        val target = tempDir()
        val store = SessionStore(old)
        store.appendLines("sid", listOf(msgLine("e1"), msgLine("e2", "e1"))) { "2026-10-08T10:00:00.000Z" }

        store.moveAllTo(target)

        // 搬移非复制：旧目录空、新目录有文件、store 切到目标目录
        assertEquals(target, store.dir)
        assertTrue(jsonlFiles(old).isEmpty())
        val moved = jsonlFiles(target)
        assertEquals(1, moved.size)
        // 后续 append 落新目录，且不重建 header（4 行 = header + e1 + e2 + e3）
        store.appendLines("sid", listOf(msgLine("e3", "e2"))) { "2026-10-08T10:00:00.000Z" }
        assertTrue(jsonlFiles(old).isEmpty())
        assertEquals(1, jsonlFiles(target).size)
        assertEquals(4, moved[0].readLines().size)
        val parsed = store.loadAll()
        assertEquals(1, parsed.size)
        assertEquals(setOf("e1", "e2", "e3"), parsed[0].tree.nodes.keys)
    }

    @Test
    fun `moveAllTo 碰撞合并 收敛不丢数据`() {
        val old = tempDir()
        val target = tempDir()
        val store = SessionStore(old)
        store.appendLines("sid", listOf(msgLine("e1"), msgLine("e2", "e1"))) { "2026-10-08T10:00:00.000Z" }
        store.loadAll()
        val full = jsonlFiles(old).single()

        // 模拟半程搬移：文件已落到目标目录（如跨卷复制后中断），索引仍指源路径；
        // 此后对 sid 的 append 按外部删除语义在源路径重建 header 壳并写入新行（e3）
        val movedTo = File(target, full.name)
        assertTrue(full.renameTo(movedTo))
        store.appendLines("sid", listOf(msgLine("e3", "e2"))) { "2026-10-08T10:00:00.000Z" }
        assertEquals(1, jsonlFiles(old).size)   // 源路径：壳（header + e3）
        assertEquals(1, jsonlFiles(target).size) // 目标路径：完整历史（e1+e2）

        // 再次搬移：碰撞合并（绝不 to.delete()）——源壳除 header 外的行（e3）追加进目标完整
        // 文件，删源、逐条目重映射；全部成功才切 root
        store.moveAllTo(target)

        assertEquals(target, store.dir)
        assertTrue(jsonlFiles(old).isEmpty())
        // 目标文件是原完整历史文件本体（追加合并，未被删除替换）
        assertEquals(setOf(movedTo), jsonlFiles(target).toSet())
        val parsed = store.loadAll()
        assertEquals(1, parsed.size)
        assertEquals("sid", parsed[0].id)
        // 并集：e1/e2（原目标）+ e3（壳内新行）都在，无数据丢失
        assertEquals(setOf("e1", "e2", "e3"), parsed[0].tree.nodes.keys)
        // 合并后追加照常落目标文件（索引已重映射）
        store.appendLines("sid", listOf(msgLine("e4", "e3"))) { "2026-10-08T10:00:00.000Z" }
        assertTrue(jsonlFiles(old).isEmpty())
        assertEquals(setOf("e1", "e2", "e3", "e4"), store.loadAll().single().tree.nodes.keys)
    }

    @Test
    fun `moveAllTo 自搬移两次 文件完好`() {
        val dir = tempDir()
        val store = SessionStore(dir)
        store.appendLines("sid", listOf(msgLine("e1"), msgLine("e2", "e1"))) { "2026-10-08T10:00:00.000Z" }
        val before = jsonlFiles(dir).single().readText()

        // 自搬移（targetDir == 当前 root）连调两次：无守卫时逐文件先删后 rename 失败再 copyTo 自毁
        store.moveAllTo(store.dir)
        store.moveAllTo(store.dir)

        // 文件原样保留、目录未切换、无异常
        assertEquals(dir, store.dir)
        assertEquals(before, jsonlFiles(dir).single().readText())
        val parsed = store.loadAll()
        assertEquals(1, parsed.size)
        assertEquals("sid", parsed[0].id)
        assertEquals(setOf("e1", "e2"), parsed[0].tree.nodes.keys)
    }

    @Test
    fun `损坏文件跳过不阻塞其余`() {
        val dir = tempDir()
        val store = SessionStore(dir)
        File(dir, "1000_bad.jsonl").writeText("not a jsonl at all\n{{{\n")
        // header-only（零节点）照常加载为空会话（spec §6.2，fix round 1），只有坏文件被跳过
        File(dir, "0900_empty.jsonl").writeText(SessionCodec.buildSessionHeader("empty", "t") + "\n")
        writeFile(dir, "2000_good.jsonl", "good", listOf(msgLine("g1")))

        val parsed = store.loadAll()
        assertEquals(listOf("good", "empty"), parsed.map { it.id })
        assertTrue(parsed[1].tree.nodes.isEmpty())
    }

    @Test
    fun `同 header id 多文件保留文件名较大者`() {
        val dir = tempDir()
        val store = SessionStore(dir)
        writeFile(dir, "1000_old.jsonl", "dup", listOf(msgLine("o1")))
        writeFile(dir, "2000_new.jsonl", "dup", listOf(msgLine("n1"), msgLine("n2", "n1")))

        val parsed = store.loadAll()
        assertEquals(1, parsed.size)
        assertEquals("dup", parsed[0].id)
        assertEquals(setOf("n1", "n2"), parsed[0].tree.nodes.keys)
    }

    /**
     * Review Focus（plan line 28）：JSONL 尾部截断/损坏的残行不得吞掉同文件前面的有效会话 ——
     * loadAll 对整文件 runCatching + parse 对逐行 runCatching，残行静默跳过，前面行照常折叠。
     */
    @Test
    fun `truncatedTailKeepsEarlierSessions`() {
        val dir = tempDir()
        val store = SessionStore(dir)
        writeFile(dir, "1000_a.jsonl", "a", listOf(msgLine("a1")))
        writeFile(dir, "0900_b.jsonl", "b", listOf(msgLine("b1")))
        // 第三个文件：有效 header + 完整消息行 + 被截断的半行残行（无 \n 结尾、JSON 不完整）
        File(dir, "0800_c.jsonl").writeText(
            SessionCodec.buildSessionHeader("c", "2026-10-08T10:00:00.000Z") + "\n" +
                msgLine("c1") + "\n" +
                "{\"type\":\"mess"
        )

        val parsed = store.loadAll()
        // 三个会话全部还原：截断尾巴只影响自身行，不吞前面的行、不阻塞整个文件
        assertEquals(listOf("a", "b", "c"), parsed.map { it.id })
        assertEquals(setOf("a1"), parsed[0].tree.nodes.keys)
        assertEquals(setOf("b1"), parsed[1].tree.nodes.keys)
        assertEquals(setOf("c1"), parsed[2].tree.nodes.keys)
    }
}
