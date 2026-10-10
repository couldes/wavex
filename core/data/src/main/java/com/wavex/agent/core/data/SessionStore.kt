package com.wavex.agent.core.data

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.util.UUID

/**
 * 逐会话 JSONL 文件 IO 壳（spec §4.1 文件布局）：每个会话一个 <epochMs>_<uuid8>.jsonl，
 * root 即 sessions 目录本身（构造注入，由调用方决定公共目录还是应用私有目录）。
 * 纯 java.io，无 Android 依赖，JVM 可测。
 * 单写者：所有写路径（append / delete / move / 索引重建）都经 lock 串行。
 * id→文件索引为内存态，由 loadAll() 扫描重建；append / delete / move 同步维护。
 */
internal class SessionStore(private var root: File) {

    /** 当前生效的 sessions 目录（moveAllTo 成功后切换为目标目录） */
    val dir: File get() = root

    private val lock = Any()
    private val index = HashMap<String, File>()

    /**
     * 扫描 root 重建 id→文件索引，并返回全部可解析会话（文件名降序排列）。
     * *.jsonl 按完整文件名降序（同 epoch 前缀 → 全名比较，uuid 后缀定序）；
     * 解析失败（无 header / 折叠后无节点 / 内容损坏）的文件跳过，不进索引；
     * 同 header id 出现在多个文件（迁移重试边缘）→ 保留文件名较大者。
     * 列目录也在锁内：与 appendLines 的建文件+登记索引互斥，避免 clear 抹掉并发新文件。
     */
    fun loadAll(): List<SessionCodec.Parsed> {
        val result = mutableListOf<SessionCodec.Parsed>()
        synchronized(lock) {
            val files = root.listFiles { f -> f.isFile && f.name.endsWith(JSONL_EXT) }
                ?.sortedByDescending { it.name }.orEmpty()
            index.clear()
            for (f in files) {
                val parsed = runCatching { SessionCodec.parse(f.readText()) }.getOrNull() ?: continue
                if (index.containsKey(parsed.id)) continue  // 降序在前者文件名更大，保留之
                index[parsed.id] = f
                result += parsed
            }
        }
        return result
    }

    /**
     * 追加行到 sessionId 的文件；文件不存在 → 先写 header（timestamp=now()）再追加并登记索引。
     * 文件尾非 \n（上次写入被截断）→ 先补 \n 再追加，避免残行吞掉新行。
     * lines 为空 → no-op（文件不存在时绝不因空草稿落盘）。整段持锁，单写者。
     */
    fun appendLines(sessionId: String, lines: List<String>, now: () -> String) {
        if (lines.isEmpty()) return
        synchronized(lock) {
            val file = resolveForAppend(sessionId, now())
            appendRaw(file, lines)
        }
    }

    /**
     * 追加写原语（appendLines 与 moveAllTo 的碰撞合并共用）：文件尾非 \n（上次写入被截断）
     * → 先补 \n 再追加，避免残行吞掉新行。显式 UTF-8 编码（与 loadAll/parse 端的解码假设
     * 对齐，不依赖平台默认字符集）。调用方负责持锁与空列表拦截。
     */
    private fun appendRaw(file: File, lines: List<String>) {
        if (lines.isEmpty()) return
        val last = lastByte(file)
        OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8).use { w ->
            if (last != -1 && last != '\n'.code) w.write("\n")
            for (line in lines) {
                w.write(line)
                w.write("\n")
            }
            w.flush()
        }
    }

    /**
     * 追加目标文件：索引命中且文件在盘 → 直接用；索引命中但文件被外部删除 → 原路径重建
     * header；索引未命中 → 新文件名 + header。仅 appendLines（已挡空 lines）会走到这里。
     * 重建分支只可能由「文件被外部删除」触发：崩溃安全改造后（final review）索引为逐条目
     * 绝对路径、moveAllTo 每搬移一个文件即重映射对应条目，搬移期间条目始终与文件实际位置
     * 一致，不会因半程搬移在过期路径误建 header 壳。即便真的走到重建（外部删除），随后
     * moveAllTo 的碰撞合并也会把壳内新行并回完整历史文件，不丢数据。
     */
    private fun resolveForAppend(sessionId: String, timestamp: String): File {
        val known = index[sessionId]
        if (known != null && known.exists()) return known
        val file = known ?: File(root, newSessionFileName())
        file.parentFile?.mkdirs()
        file.writeText(SessionCodec.buildSessionHeader(sessionId, timestamp) + "\n")
        index[sessionId] = file
        return file
    }

    /**
     * 删除会话文件及同名 .tmp（上次原子写残留），索引同步移除；未加载过的 id → 无文件可删，返回 false。
     * 返回两个删除是否全部成功（主文件与 .tmp 都删掉）；任一失败（如 Windows 文件被占用）返回 false ——
     * 此时索引已移除但文件仍在，下一次 loadAll 会话会复现，调用方据此可感知异常。
     */
    fun deleteSession(sessionId: String): Boolean {
        synchronized(lock) {
            val f = index.remove(sessionId) ?: return false
            val main = f.delete()
            val tmp = File(f.parentFile, f.name + TMP_EXT)
            val tmpDeleted = !tmp.exists() || tmp.delete()
            return main && tmpDeleted
        }
    }

    /**
     * 把 root 下全部文件搬移（rename，跨卷退化为复制+删除，净效果仍是 move 非复制）
     * 到 targetDir；全部文件搬移成功后本 store 才切换到 targetDir：后续 append 落新目录。
     * 崩溃安全（final review）：逐文件 try/catch、首个 IO 失败即停（fail-fast）且不外抛 ——
     * 只记日志静默返回（refreshSessionStorage 的 catch 仍是兜底）。索引为逐条目绝对路径：
     * 每成功搬移一个文件立即把对应条目重映射到目标路径；失败时 root 保持指向源目录——
     * 已搬移条目指目标、未搬移条目指源，两处会话的 append 都照常工作（已搬会话追加到
     * 目标文件，未搬会话追加到源文件），只有全部搬完 root 才切换（此后新文件落目标）。
     * targetDir 与当前 root 为同一目录（重复调用 / 路径别名）→ no-op：文件原样保留。
     * 若无此守卫，逐文件会先删后 rename 失败再 copyTo 自毁（review round 1：重试-after-成功是洞）。
     */
    fun moveAllTo(targetDir: File) {
        synchronized(lock) {
            if (isSameDir(targetDir, root)) return
            targetDir.mkdirs()
            var allMoved = true
            for (f in root.listFiles()?.filter { it.isFile }.orEmpty()) {
                val to = File(targetDir, f.name)
                try {
                    if (to.exists()) {
                        mergeOnCollision(to, f)
                    } else if (!f.renameTo(to)) {
                        f.copyTo(to, overwrite = true)
                        if (!f.delete()) throw java.io.IOException("source delete failed after copy: ${f.name}")
                    }
                    // 该文件搬移成功：对应索引条目立即重映射到目标路径（其余条目不动）
                    for ((id, known) in index) if (known == f) index[id] = to
                } catch (e: Exception) {
                    // 首个失败即停：root 不切换，剩余条目留在源目录（两类路径的 append 均可用）。
                    // 失败文件最多留下「目标半份 + 源完整」——重试经碰撞合并收敛，不丢数据
                    android.util.Log.w("SessionStore", "moveAllTo: ${f.name} failed; keeping source dir", e)
                    allMoved = false
                    break
                }
            }
            if (allMoved) root = targetDir
        }
    }

    /**
     * 目标同名文件碰撞合并（final review：绝不 to.delete() —— 那会用壳替换完整历史）：
     * 把源文件除首行 header 外的行按 appendRaw 原语追加进目标（只追加流回放收敛：同内容
     * 重复 upsert 后到者胜、delete 未知 id 为 no-op、active 后到者胜 —— 合并幂等，重试安全），
     * 成功后删源文件（失败则抛给 moveAllTo 记日志停搬，索引仍指源，重试再并一次仍收敛）。
     * 源为 header-only 壳（半程搬移后误建的场景）→ 除 header 外无行，合并即纯 no-op。
     * 目标为 0 字节（上次复制在写出任何字节前失败）→ 整文件（含 header）原样并入，
     * 等价纯 move，避免产出无 header 的不可加载文件。
     */
    private fun mergeOnCollision(to: File, from: File) {
        val allLines = from.readLines()
        appendRaw(to, if (to.length() == 0L) allLines else allLines.drop(1))
        if (!from.delete()) throw java.io.IOException("source delete failed after merge: ${from.name}")
    }

    /** 同一目录判定：含路径别名（符号链接 / 大小写 / 相对绝对拼写）——按 canonical 路径比对 */
    private fun isSameDir(a: File, b: File): Boolean =
        a == b || runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(false)

    /** 读文件末字节（真实读盘而非缓存 —— 上次写入可能被进程截断）；空文件返回 -1 */
    private fun lastByte(file: File): Int =
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() == 0L) -1
            else {
                raf.seek(raf.length() - 1)
                raf.read()
            }
        }

    /** <epochMs>_<uuid8>.jsonl */
    private fun newSessionFileName(): String =
        "${System.currentTimeMillis()}_${UUID.randomUUID().toString().replace("-", "").take(8)}$JSONL_EXT"

    private companion object {
        const val JSONL_EXT = ".jsonl"
        const val TMP_EXT = ".tmp"
    }
}
